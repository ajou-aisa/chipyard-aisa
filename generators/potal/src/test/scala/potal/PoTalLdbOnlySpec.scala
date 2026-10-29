package potal

import chisel3._
import chisel3.util._
import chisel3.iotesters._
import chisel3.util.experimental.BoringUtils
import scala.collection.mutable.ArrayBuffer
import Arithmetic.SIntArithmetic
import GemminiISA._
import PoTalResidentTestSupport.{parameters, command}

// Scheduler tests expose real generator state and explicitly return RS credits.
// These credits are not a DMA, SRAM, MAC, or numerical model.
class PoTalLdbOnlyHarness(supported: Boolean = true) extends Module {
  val c = PoTalConfigs.a8w8D32Sat32
  val io = IO(new Bundle {
    val cmd = Flipped(Decoupled(new PoTalScaleCommand))
    val resp = Decoupled(new PoTalScaleCommandResponse)
    val generated = Decoupled(new GemminiCmd(c.reservation_station_entries))
    val ldComplete = Input(UInt(2.W))
    val exComplete = Input(UInt(2.W))
    val stComplete = Input(UInt(2.W))
    val busy = Output(Bool())
    val metadataZero = Output(Bool())
    val configured = Output(Vec(2, Bool()))
    val loadOnly = Output(Vec(2, Bool()))
    val completed = Output(Vec(2, Bool()))
  })
  val loop = Module(new LoopMatmul(32, 40, c.reservation_station_entries,
    2, 1, 1, 8192, 512, 8, 32, 64,
    new MvinRs2(c.mvin_rows_bits, c.mvin_cols_bits, c.local_addr_t),
    new PreloadRs(c.mvin_rows_bits, c.mvin_cols_bits, c.local_addr_t),
    new PreloadRs(c.mvout_rows_bits, c.mvout_cols_bits, c.local_addr_t),
    new ComputeRs(c.mvin_rows_bits, c.mvin_cols_bits, c.local_addr_t),
    new ComputeRs(c.mvin_rows_bits, c.mvin_cols_bits, c.local_addr_t),
    new MvoutRs2(c.mvout_rows_bits, c.mvout_cols_bits, c.local_addr_t),
    256, false, supported))
  loop.io.in.valid := io.cmd.valid
  loop.io.in.bits := 0.U.asTypeOf(loop.io.in.bits)
  command(loop.io.in.bits.cmd, io.cmd.bits)
  io.cmd.ready := loop.io.in.ready
  io.resp <> loop.io.resp
  io.generated <> loop.io.out
  loop.io.ld_completed := io.ldComplete
  loop.io.ex_completed := io.exComplete
  loop.io.st_completed := io.stComplete
  loop.io.indexDescriptors := 0.U.asTypeOf(loop.io.indexDescriptors)
  loop.io.indexComplete.foreach(_ := false.B)
  io.busy := loop.io.busy
  io.metadataZero := loop.io.out.bits.potal.asUInt === 0.U
  for (i <- 0 until 2) {
    val configured = WireInit(false.B)
    val loadOnly = WireInit(false.B)
    val completed = WireInit(false.B)
    BoringUtils.bore(loop.loops(i).configured, Seq(configured))
    BoringUtils.bore(loop.loops(i).loadOnly, Seq(loadOnly))
    BoringUtils.bore(loop.loops(i).ldb_completed, Seq(completed))
    io.configured(i) := configured
    io.loadOnly(i) := loadOnly
    io.completed(i) := completed
  }
}

class PoTalLdbOnlyTester(dut: PoTalLdbOnlyHarness, supported: Boolean)
    extends PeekPokeTester(dut) {
  case class Word(f: Int, a: BigInt, b: BigInt, fromLoop: Boolean,
                  metadata: BigInt)
  val words = ArrayBuffer.empty[Word]
  val pending = Array(0, 0, 0)
  var returnLoads = true
  var returnExecute = true
  val canonical = BigInt("200109", 16)
  poke(dut.io.cmd.valid, 0)
  poke(dut.io.resp.ready, 0)
  poke(dut.io.generated.ready, 1)
  poke(dut.io.ldComplete, 0); poke(dut.io.exComplete, 0); poke(dut.io.stComplete, 0)
  reset(2)

  def tick(): Unit = {
    val credits = Array(if (returnLoads && pending(0) > 0) 1 else 0,
      if (returnExecute && pending(1) > 0) 1 else 0,
      if (pending(2) > 0) 1 else 0)
    poke(dut.io.ldComplete, credits(0))
    poke(dut.io.exComplete, credits(1))
    poke(dut.io.stComplete, credits(2))
    for (i <- 0 until 3) pending(i) -= credits(i)
    if (peek(dut.io.generated.valid) == 1 && peek(dut.io.generated.ready) == 1) {
      val g = dut.io.generated.bits
      val f = peek(g.cmd.inst.funct).toInt
      val generated = peek(g.from_matmul_fsm) == 1
      words += Word(f, peek(g.cmd.rs1), peek(g.cmd.rs2), generated,
        if (peek(dut.io.metadataZero) == 1) BigInt(0) else BigInt(1))
      if (generated) {
        val kind = if (Set(1, 2, 14).contains(f)) 0 else if (f == 3) 2 else 1
        pending(kind) += 1
      }
    }
    step(1)
  }
  def until(condition: => Boolean): Unit = {
    var n = 0
    while (!condition && n < 3000) { tick(); n += 1 }
    assert(condition, s"handshake/state transition timed out after $n cycles")
  }
  def send(f: Int, a: BigInt = 0, b: BigInt = 0, xd: Int = 0, rd: Int = 19): Unit = {
    poke(dut.io.cmd.bits.funct, f)
    poke(dut.io.cmd.bits.rs1, a); poke(dut.io.cmd.bits.rs2, b)
    poke(dut.io.cmd.bits.xd, xd); poke(dut.io.cmd.bits.rd, rd)
    poke(dut.io.cmd.valid, 1)
    until(peek(dut.io.cmd.ready) == 1)
    tick(); poke(dut.io.cmd.valid, 0)
  }
  def response(status: Int, rd: Int = 19): Unit = {
    until(peek(dut.io.resp.valid) == 1)
    // The response itself is the state held under these four clock edges.
    for (_ <- 0 until 4) {
      expect(dut.io.resp.valid, 1); expect(dut.io.resp.bits.status, status)
      expect(dut.io.resp.bits.rd, rd); expect(dut.io.busy, 1)
      tick()
    }
    poke(dut.io.resp.ready, 1); tick(); poke(dut.io.resp.ready, 0)
  }
  def drain(): Unit = {
    until(peek(dut.io.busy) == 0 && pending.forall(_ == 0))
  }
  def loadConfig(stride: BigInt = 128, encoding: BigInt = canonical): Unit = {
    val before = words.size
    send(0, encoding, stride)
    until(words.size > before)
    assert(words.last == Word(0, encoding, stride, false, 0))
  }
  def stage(j: Int = 3, k: Int = 2, i: Int = 1, padI: Int = 0,
            padJ: Int = 5, padK: Int = 0, source: BigInt = BigInt("90000000", 16),
            stride: BigInt = 128, omit: Int = -1,
            a: BigInt = 0, d: BigInt = 0, out: BigInt = 0,
            aStride: BigInt = 0, dStride: BigInt = 0, cStride: BigInt = 0,
            reserved: BigInt = 0): Unit = {
    Seq((9, reserved | BigInt(padI) | (BigInt(padJ) << 16) | (BigInt(padK) << 32),
      BigInt(i) | (BigInt(j) << 16) | (BigInt(k) << 32)),
      (10, a, source), (11, d, out), (12, aStride, stride), (13, dStride, cStride))
      .filter(_._1 != omit).foreach { case (f, x, y) => send(f, x, y) }
  }
  def run(pair: Int = 0, flags: BigInt = 16, rs2: BigInt = 0, xd: Int = 1): Unit =
    send(29, flags | pair, rs2, xd)
  def reject(status: Int = 5, flags: BigInt = 16, rs2: BigInt = 0): Unit = {
    val before = words.size
    run(flags = flags, rs2 = rs2)
    response(status); drain()
    assert(words.size == before, "rejected load-only descriptor produced effects")
  }
  def checkLoads(start: Int, pair: Int, j: Int, k: Int, pad: Int,
                 source: BigInt, stride: BigInt): Unit = {
    val loads = words.drop(start).filter(_.fromLoop)
    val expected = for (kt <- 0 until k; jt <- 0 until j by 2) yield {
      val blocks = math.min(2, j - jt)
      val cols = blocks * 32 - (if (jt + blocks == j) pad else 0)
      val local = (pair + 1) * 4096 - k * j * 32 + (kt * j + jt) * 32
      (source + kt * 32 * stride + jt * 32,
        BigInt(local) | (BigInt(cols) << 32) | (BigInt(32) << 48))
    }
    assert(loads.map(w => (w.a, w.b)).toSeq == expected)
    assert(loads.forall(w => w.f == 1 && w.metadata == 0),
      "load-only must emit only ordinary LOAD2 without compute metadata")
  }

  if (!supported) {
    stage(); reject(7)
  } else {
    stage(); reject() // Uninitialized LOAD2 state.
    loadConfig()
    for (missing <- 9 to 13) {
      stage(omit = missing); reject(6)
      run(); response(6); drain() // Rejection consumes every other word too.
    }
    for (bad <- Seq(BigInt(0), BigInt(0x20), BigInt(0x12),
                    BigInt(1) << 20, BigInt(1) << 63)) {
      stage(); reject(6, flags = bad)
    }
    stage(); reject(6, rs2 = 1) // transpose/resadd/Index have no encoding here.
    stage(); reject(6, rs2 = 4)
    stage(); run(xd = 0); drain()
    expect(dut.io.resp.valid, 0)
    run(); response(6); drain()
    stage(reserved = BigInt(1) << 60); reject(6)
    stage(source = (BigInt(1) << 40) | BigInt("90000000", 16)); reject(6)
    stage(stride = BigInt(1) << 40); reject(6)
    stage(i = 2); reject()
    stage(j = 0); reject()
    stage(k = 0); reject()
    stage(padI = 1); reject()
    stage(padK = 1); reject()
    stage(padJ = 32); reject()
    stage(source = 0); reject()
    stage(source = BigInt("90000001", 16)); reject()
    stage(j = 9, k = 8); reject() // 2304 rows exceeds one odd bank.
    stage(a = 32); reject()
    stage(d = 32); reject()
    stage(out = 32); reject()
    stage(aStride = 32); reject()
    stage(dStride = 32); reject()
    stage(cStride = 32); reject()
    stage(stride = 64); reject()
    stage(source = (BigInt(1) << 40) - 32); reject()
    loadConfig(BigInt(1) << 33)
    stage(stride = BigInt(1) << 33); reject() // LdB tile offset masks to 32 bits.
    for (bad <- Seq(canonical ^ (BigInt(1) << 8), canonical | 4,
                    canonical | (BigInt(1) << 32), canonical ^ (BigInt(1) << 16))) {
      loadConfig(128, bad); stage(); reject()
    }
    loadConfig(160); stage(); reject()
    loadConfig()
    // Neither accepted FIFO input nor a non-handshaking output establishes state.
    send(7); until(words.last.f == 7)
    stage(); reject()
    poke(dut.io.generated.ready, 0)
    val forwarded = words.size
    send(0, canonical, 128)
    until(peek(dut.io.generated.valid) == 1)
    val heldA = peek(dut.io.generated.bits.cmd.rs1)
    val heldB = peek(dut.io.generated.bits.cmd.rs2)
    for (_ <- 0 until 4) {
      expect(dut.io.generated.bits.cmd.rs1, heldA)
      expect(dut.io.generated.bits.cmd.rs2, heldB)
      assert(words.size == forwarded)
      tick()
    }
    poke(dut.io.generated.ready, 1)
    until(words.size == forwarded + 1)

    val blockedStart = words.size
    stage()
    poke(dut.io.generated.ready, 0)
    run(); response(0)
    until(peek(dut.io.generated.valid) == 1)
    val blockedA = peek(dut.io.generated.bits.cmd.rs1)
    val blockedB = peek(dut.io.generated.bits.cmd.rs2)
    for (_ <- 0 until 4) {
      expect(dut.io.generated.valid, 1)
      expect(dut.io.generated.bits.cmd.inst.funct, 1)
      expect(dut.io.generated.bits.cmd.rs1, blockedA)
      expect(dut.io.generated.bits.cmd.rs2, blockedB)
      expect(dut.io.busy, 1)
      assert(words.size == blockedStart && pending(0) == 0)
      tick()
    }
    poke(dut.io.generated.ready, 1); drain()
    checkLoads(blockedStart, 0, 3, 2, 5, BigInt("90000000", 16), 128)

    // Both pairs, merged columns, padded J, tail columns and slot reuse.
    for ((pair, j, k, pad) <- Seq((0, 3, 2, 5), (1, 1, 3, 31), (0, 2, 1, 0))) {
      val start = words.size
      stage(j = j, k = k, padJ = pad)
      run(pair); response(0); drain()
      checkLoads(start, pair, j, k, pad, BigInt("90000000", 16), 128)
    }
    // xd=0 must not consume a response slot or start any generator.
    val noReplyStart = words.size
    stage(); run(xd = 0); drain()
    expect(dut.io.resp.valid, 0)
    assert(words.size == noReplyStart)
    run(); response(6); drain()

    for (pair <- 0 until 2; residual <- Seq(false, true)) {
      returnExecute = false
      returnLoads = false
      // Current full Main/Residual uses resident A/B. Stall its real EX budget.
      stage(j = 1, k = 8, padJ = 0, source = 0)
      val headStart = words.size
      val full = (BigInt(pair + 1) << 18) | (BigInt(pair + 1) << 16) |
        (BigInt(1) << 20) | (BigInt(1) << 37) | (BigInt(1) << 45) |
        (BigInt(1) << 61) |
        (if (residual) (BigInt(1) << 62) | (BigInt(1) << 63) else BigInt(0))
      send(8, full)
      until(pending(1) == 1)
      val activeHead = (0 until 2).find(i => peek(dut.io.configured(i)) == 1).get
      stage()
      run(pair); response(5) // Future reads of this same pair are protected.
      val tailStart = words.size
      stage(); run(1 - pair); response(0)
      expect(dut.io.configured(activeHead), 1)
      until(pending(0) == 2)
      val countAtBudget = words.count(_.f == 1)
      for (_ <- 0 until 4) {
        assert(words.count(_.f == 1) == countAtBudget)
        expect(dut.io.configured(activeHead), 1)
        tick()
      }
      returnLoads = true
      until(words.drop(tailStart).count(_.f == 1) == 4)
      until((0 until 2).exists(i => peek(dut.io.loadOnly(i)) == 1 &&
        peek(dut.io.completed(i)) == 1))
      // Tail generated B while head still has ungenerated EX commands.
      expect(dut.io.configured(activeHead), 1)
      assert(words.drop(headStart).count(_.f == 6) == 1)
      val tailLoads = words.drop(tailStart).filter(_.f == 1).toVector
      assert(tailLoads.size == 4 && tailLoads.forall(_.metadata == 0))
      val expectedTail = for (kt <- 0 until 2; jt <- Seq(0, 2)) yield {
        val cols = if (jt == 0) 64 else 27
        val local = (2 - pair) * 4096 - 192 + (kt * 3 + jt) * 32
        (BigInt("90000000", 16) + kt * 4096 + jt * 32,
          BigInt(local) | (BigInt(cols) << 32) | (BigInt(32) << 48))
      }
      assert(tailLoads.map(w => (w.a, w.b)) == expectedTail)
      // A third run can queue, but cannot allocate either occupied slot.
      send(29, 16, 0, 1, rd = 27)
      for (_ <- 0 until 4) {
        expect(dut.io.resp.valid, 0)
        expect(dut.io.configured(activeHead), 1)
        tick()
      }
      returnExecute = true
      response(6, rd = 27) // No fresh words once normal head retirement frees a slot.
      drain()
      assert(words.drop(headStart).count(_.f == 6) == 8)
      assert(words.drop(headStart).count(w => w.f == 4 || w.f == 5) == 8)
      assert(!words.drop(headStart).exists(w => Set(2, 3, 14).contains(w.f)))
    }

    // Active resadd cannot redirect a B-only SP request into ACC.
    poke(dut.io.generated.ready, 0)
    stage(j = 1, k = 1, padJ = 0, a = BigInt("80000000", 16),
      source = BigInt("81000000", 16))
    send(8, 0, 4)
    until(peek(dut.io.generated.valid) == 1)
    stage(); run(1); response(5)
    poke(dut.io.generated.ready, 1); drain()
    val afterResadd = words.size
    stage(); run(1); response(0); drain()
    checkLoads(afterResadd, 1, 3, 2, 5, BigInt("90000000", 16), 128)
  }
}

// Real RS -> LoadController -> DMA command tracker, with returned bytes
// controlled by the test. Real PoTal writeback and ACC SRAM drain structure
// comes from the existing harness. No physical overlap or numerical claim.
class PoTalLdbOnlyDrainHarness extends Module {
  val c = PoTalConfigs.a8w8D32Sat32
  val scheduler = Module(new PoTalLdbOnlyHarness())
  val rs = Module(new ReservationStation(c, new GemminiCmd(c.reservation_station_entries)))
  val ld = Module(new LoadController(c, 40, c.local_addr_t))
  val acc = Module(new PoTalDrainHandshakeHarness(productionDim32 = true))
  val io = IO(new Bundle {
    val cmd = Flipped(Decoupled(new PoTalScaleCommand))
    val resp = Decoupled(new PoTalScaleCommandResponse)
    val dma = new ScratchpadReadMemIO(c.local_addr_t, c.mvin_scale_t_bits)
    val schedulerBusy = Output(Bool())
    val rsDrained = Output(Bool())
    val loadBusy = Output(Bool())
    val allDrained = Output(Bool())
    val counterResp = Decoupled(new freechips.rocketchip.tile.RoCCResponse)
    // Busy sources absent from this LD-only harness (EX/ST/Scratchpad).
    val otherBusy = Input(Bool())
    val traceDrained = Output(Bool())
    val resetHead = Output(Bool())
    val counterFire = Output(Bool())
  })
  val control = io.cmd.bits.funct === POTAL_FENCE ||
    io.cmd.bits.funct === POTAL_SCALE_RELEASE || io.cmd.bits.funct === POTAL_SCALE_WRITE
  scheduler.io.cmd.valid := io.cmd.valid && !control
  scheduler.io.cmd.bits := io.cmd.bits
  acc.io.cmd.valid := io.cmd.valid && control
  acc.io.cmd.bits := io.cmd.bits
  io.cmd.ready := Mux(control, acc.io.cmd.ready, scheduler.io.cmd.ready)
  val responses = Module(new RRArbiter(new PoTalScaleCommandResponse, 2))
  responses.io.in(0) <> scheduler.io.resp
  responses.io.in(1) <> acc.io.resp
  io.resp <> responses.io.out
  // Same LoopMatmul -> unrolled queue -> COUNTER/RS split as Controller.
  val unrolled = Queue(scheduler.io.generated)
  val counters = Module(new CounterController(8, 64, false, true, true))
  val isCounter = unrolled.bits.cmd.inst.funct === COUNTER_OP
  counters.io.in.valid := unrolled.valid && isCounter
  counters.io.in.bits := unrolled.bits.cmd
  io.counterResp <> counters.io.out
  CounterEventIO.init(counters.io.event_io)
  rs.io.alloc.valid := unrolled.valid && !isCounter
  rs.io.alloc.bits := unrolled.bits
  unrolled.ready := Mux(isCounter, counters.io.in.ready, rs.io.alloc.ready)
  ld.io.cmd.valid := rs.io.issue.ld.valid
  ld.io.cmd.bits := rs.io.issue.ld.cmd
  ld.io.cmd.bits.rob_id.valid := true.B
  ld.io.cmd.bits.rob_id.bits := rs.io.issue.ld.rob_id
  rs.io.issue.ld.ready := ld.io.cmd.ready
  rs.io.issue.ex.ready := false.B
  rs.io.issue.st.ready := false.B
  assert(!rs.io.issue.ex.valid && !rs.io.issue.st.valid)
  rs.io.completed.valid := ld.io.completed.valid
  rs.io.completed.bits := ld.io.completed.bits
  ld.io.completed.ready := true.B
  scheduler.io.ldComplete := rs.io.matmul_ld_completed
  scheduler.io.exComplete := rs.io.matmul_ex_completed
  scheduler.io.stComplete := rs.io.matmul_st_completed
  rs.io.potalIndexDescriptors := 0.U.asTypeOf(rs.io.potalIndexDescriptors)
  rs.io.potalIndexConfiguredByBuffer.foreach(_ := false.B)
  rs.io.counter.external_reset := false.B
  ld.io.counter.external_reset := false.B
  io.dma <> ld.io.dma
  // There are no convolution, store, or execute commands in this harness.
  // RS and LD predicates are real; neither is a permanently true mock.
  val otherDrained = !scheduler.io.busy && !scheduler.io.generated.valid &&
    rs.io.drained && !ld.io.busy && !io.otherBusy
  acc.io.olderGemminiDrained := otherDrained && !unrolled.valid
  val traceDrained = PoTalTraceReset.drained(unrolled,
    otherDrained && acc.io.drained, supported = true)
  val events = counters.io.earlyEvents.get
  events := 0.U.asTypeOf(events)
  events.drained := traceDrained
  events.fenceRequest := acc.io.cmd.valid && acc.io.cmd.bits.funct === POTAL_FENCE
  events.fenceAccepted := acc.io.cmd.fire && acc.io.cmd.bits.funct === POTAL_FENCE &&
    acc.io.cmd.bits.xd && acc.io.cmd.bits.rs1 === 0.U &&
    acc.io.cmd.bits.rs2 === 0.U && acc.io.allDrained
  io.traceDrained := traceDrained
  io.resetHead := unrolled.valid && PoTalTraceReset.canonical(unrolled.bits.cmd)
  io.counterFire := counters.io.in.fire
  acc.io.holdAccWrites := false.B
  acc.io.contextIssue.valid := false.B
  acc.io.contextIssue.bits := 0.U.asTypeOf(acc.io.contextIssue.bits)
  acc.io.rawWrite.foreach { w => w.valid := false.B; w.bits := 0.U.asTypeOf(w.bits) }
  acc.io.rawCompletion := 0.U.asTypeOf(acc.io.rawCompletion)
  acc.io.robCompletion.ready := true.B
  acc.io.workDone.ready := true.B
  acc.io.readReq.foreach { r => r.valid := false.B; r.bits := 0.U }
  acc.io.readResp.foreach(_.ready := true.B)
  io.schedulerBusy := scheduler.io.busy
  io.rsDrained := rs.io.drained
  io.loadBusy := ld.io.busy
  io.allDrained := acc.io.allDrained
}

class PoTalLdbOnlyDrainTester(dut: PoTalLdbOnlyDrainHarness) extends PeekPokeTester(dut) {
  val rows = ArrayBuffer.empty[(BigInt, BigInt)]
  poke(dut.io.cmd.valid, 0); poke(dut.io.resp.ready, 0)
  poke(dut.io.counterResp.ready, 0); poke(dut.io.otherBusy, 0)
  poke(dut.io.dma.req.ready, 1); poke(dut.io.dma.resp.valid, 0)
  poke(dut.io.dma.resp.bits.cmd_id, 0); poke(dut.io.dma.resp.bits.bytesRead, 0)
  reset(2)
  def tick(): Unit = {
    if (peek(dut.io.dma.req.valid) == 1 && peek(dut.io.dma.req.ready) == 1)
      rows += ((peek(dut.io.dma.req.bits.cmd_id), peek(dut.io.dma.req.bits.cols)))
    step(1)
  }
  def until(condition: => Boolean): Unit = {
    var n = 0
    while (!condition && n < 3000) { tick(); n += 1 }
    assert(condition, "real RS/LD drain handshake timed out")
  }
  def send(f: Int, a: BigInt = 0, b: BigInt = 0, xd: Int = 0): Unit = {
    poke(dut.io.cmd.bits.funct, f); poke(dut.io.cmd.bits.rs1, a)
    poke(dut.io.cmd.bits.rs2, b); poke(dut.io.cmd.bits.xd, xd)
    poke(dut.io.cmd.bits.rd, 23); poke(dut.io.cmd.valid, 1)
    until(peek(dut.io.cmd.ready) == 1)
    tick(); poke(dut.io.cmd.valid, 0)
  }
  def response(): Unit = {
    until(peek(dut.io.resp.valid) == 1)
    expect(dut.io.resp.bits.status, 0); expect(dut.io.resp.bits.rd, 23)
    poke(dut.io.resp.ready, 1); tick(); poke(dut.io.resp.ready, 0)
  }
  for (control <- Seq(25, 24)) {
    send(0, BigInt("200109", 16), 32)
    if (control == 24) {
      // One valid Scale entry to release after the real DMA drain.
      send(23, BigInt(1) << 8, 0, 1); response()
    }
    send(9, 0, BigInt(1) | (BigInt(1) << 16) | (BigInt(1) << 32))
    send(10, 0, BigInt("90000000", 16))
    send(11); send(12, 0, 32); send(13)
    val before = rows.size
    send(29, 16, 0, 1); response() // Admission ACK while bytes remain outstanding.
    until(rows.size == before + 32 && peek(dut.io.schedulerBusy) == 0)
    expect(dut.io.rsDrained, 0); expect(dut.io.loadBusy, 1)
    expect(dut.io.allDrained, 0)
    // Fence/Release may keep cmd.ready low until drain. Offer before returning
    // bytes, then retain the exact command through the eventual handshake.
    poke(dut.io.cmd.bits.funct, control)
    poke(dut.io.cmd.bits.rs1, if (control == 24) BigInt(1) << 8 else BigInt(0))
    poke(dut.io.cmd.bits.rs2, 0); poke(dut.io.cmd.bits.xd, 1)
    poke(dut.io.cmd.bits.rd, 23); poke(dut.io.cmd.valid, 1)
    for (_ <- 0 until 4) {
      expect(dut.io.resp.valid, 0); expect(dut.io.allDrained, 0); tick()
    }
    val returned = rows.drop(before).toVector
    for ((id, bytes) <- returned) {
      poke(dut.io.dma.resp.valid, 1)
      poke(dut.io.dma.resp.bits.cmd_id, id)
      poke(dut.io.dma.resp.bits.bytesRead, bytes)
      tick()
    }
    poke(dut.io.dma.resp.valid, 0)
    until(peek(dut.io.cmd.ready) == 1)
    tick(); poke(dut.io.cmd.valid, 0)
    response()
    until(peek(dut.io.allDrained) == 1)
  }
}

// Includes the neighboring real DMA Fence/Release scenarios above, then sends
// COUNTER through LoopMatmul's input FIFO and the production-sized output FIFO.
class PoTalLdbOnlyTracePathTester(dut: PoTalLdbOnlyDrainHarness)
    extends PoTalLdbOnlyDrainTester(dut) {
  def counter(a: BigInt, b: BigInt = 0, xd: Int = 1): Unit =
    send(126, a, b, xd)

  def counterResponse(expected: Option[BigInt]): BigInt = {
    until(peek(dut.io.counterResp.valid) == 1)
    val data = peek(dut.io.counterResp.bits.data)
    expected.foreach(value => assert(data == value))
    for (_ <- 0 until 4) {
      expect(dut.io.counterResp.valid, 1)
      expect(dut.io.counterResp.bits.rd, 23)
      expect(dut.io.counterResp.bits.data, data)
      tick()
    }
    poke(dut.io.counterResp.ready, 1); tick(); poke(dut.io.counterResp.ready, 0)
    data
  }
  def trace(field: Int): BigInt = {
    counter(PoTalLdbOnlyCapability.TraceRead + field)
    counterResponse(None)
  }

  // The real command, not a manually asserted events.drained, must arm tracing.
  counter(PoTalLdbOnlyCapability.TraceReset)
  until(peek(dut.io.resetHead) == 1 && peek(dut.io.counterFire) == 1)
  expect(dut.io.traceDrained, 1)
  expect(dut.io.allDrained, 0) // Fence must still count this queue head.
  counterResponse(Some(1))
  assert((trace(0) & 1) == 1)
  val start = trace(22)
  assert(start > 0)

  // A held response blocks the following reset at the real CounterController.
  counter(PoTalLdbOnlyCapability.Query)
  until(peek(dut.io.counterResp.valid) == 1)
  counter(PoTalLdbOnlyCapability.TraceReset)
  until(peek(dut.io.resetHead) == 1)
  for (_ <- 0 until 4) {
    expect(dut.io.counterFire, 0)
    expect(dut.io.counterResp.valid, 1)
    expect(dut.io.counterResp.bits.data, PoTalLdbOnlyCapability.Signature)
    tick()
  }
  counterResponse(Some(PoTalLdbOnlyCapability.Signature))
  counterResponse(Some(1))
  assert(trace(22) > start)

  // Noncanonical reset encodings take the ordinary counter path, not the
  // own-command drain exception, and cannot replace the trace epoch.
  val canonicalStart = trace(22)
  for ((a, b, xd) <- Seq(
      (PoTalLdbOnlyCapability.TraceReset, BigInt(1), 1),
      (PoTalLdbOnlyCapability.TraceReset, BigInt(0), 0),
      (PoTalLdbOnlyCapability.TraceReset ^ (BigInt(1) << 32), BigInt(0), 1),
      (PoTalLdbOnlyCapability.TraceRead, BigInt(0), 1))) {
    counter(a, b, xd)
    until(peek(dut.io.counterFire) == 1)
    expect(dut.io.resetHead, 0); expect(dut.io.traceDrained, 0)
    counterResponse(None)
    assert(trace(22) == canonicalStart)
  }

  // The next real LoopMatmul FIFO occupant is still a required drain term.
  // Offer it immediately so it is present when reset reaches unrolled head.
  counter(PoTalLdbOnlyCapability.TraceReset)
  counter(PoTalLdbOnlyCapability.Query)
  expect(dut.io.resetHead, 1); expect(dut.io.counterFire, 1)
  expect(dut.io.schedulerBusy, 1); expect(dut.io.traceDrained, 0)
  counterResponse(Some(0))
  counterResponse(Some(PoTalLdbOnlyCapability.Signature))
  assert(trace(22) == canonicalStart)

  // Absent downstream busy sources cannot be bypassed by a canonical head.
  poke(dut.io.otherBusy, 1)
  counter(PoTalLdbOnlyCapability.TraceReset)
  counterResponse(Some(0))
  poke(dut.io.otherBusy, 0)
  assert(trace(22) == canonicalStart)

  // Retire an actual load-only scheduler slot without returning DMA bytes.
  send(0, BigInt("200109", 16), 32)
  send(9, 0, BigInt(1) | (BigInt(1) << 16) | (BigInt(1) << 32))
  send(10, 0, BigInt("90000000", 16))
  send(11); send(12, 0, 32); send(13)
  val before = rows.size
  send(29, 16, 0, 1); response()
  until(rows.size == before + 32 && peek(dut.io.schedulerBusy) == 0)
  expect(dut.io.rsDrained, 0); expect(dut.io.loadBusy, 1)
  counter(PoTalLdbOnlyCapability.TraceReset)
  until(peek(dut.io.resetHead) == 1 && peek(dut.io.counterFire) == 1)
  expect(dut.io.traceDrained, 0)
  // Hold the refused reply while old DMA work really drains. No retry and no
  // change from a registered refusal into a successful acknowledgement.
  tick()
  for ((id, bytes) <- rows.drop(before).toVector) {
    poke(dut.io.dma.resp.valid, 1)
    poke(dut.io.dma.resp.bits.cmd_id, id)
    poke(dut.io.dma.resp.bits.bytesRead, bytes)
    expect(dut.io.counterResp.valid, 1)
    expect(dut.io.counterResp.bits.data, 0)
    tick()
  }
  poke(dut.io.dma.resp.valid, 0)
  until(peek(dut.io.allDrained) == 1)
  counterResponse(Some(0))
  assert(trace(22) == canonicalStart)

  // A real qualified Fence freezes the unchanged epoch. Then a fresh idle
  // reset succeeds, proving the earlier rejection did not poison the path.
  send(25, 0, 0, 1); response()
  assert((trace(0) & 1) == 0)
  val end = trace(23)
  assert(end > canonicalStart && trace(22) == canonicalStart)
  counter(PoTalLdbOnlyCapability.TraceReset)
  counterResponse(Some(1))
  assert((trace(0) & 1) == 1 && trace(22) > end)
}

class PoTalLdbOnlyCounterHarness(supported: Boolean) extends Module {
  val io = IO(new Bundle {
    val cmd = Flipped(Decoupled(new PoTalScaleCommand))
    val resp = Decoupled(new freechips.rocketchip.tile.RoCCResponse)
    val externalValue = Input(UInt(32.W))
    val externalReset = Output(Bool())
    val events = Input(new PoTalLdbOnlyEvents)
  })
  val counters = Module(new CounterController(8, 64, false, true, supported))
  counters.io.in.valid := io.cmd.valid
  command(counters.io.in.bits, io.cmd.bits)
  io.cmd.ready := counters.io.in.ready
  io.resp <> counters.io.out
  CounterEventIO.init(counters.io.event_io)
  counters.io.earlyEvents.foreach(_ := io.events)
  counters.io.event_io.external_values(0) := io.externalValue
  io.externalReset := counters.io.event_io.external_reset
}

class PoTalLdbOnlyCounterTester(dut: PoTalLdbOnlyCounterHarness, supported: Boolean)
    extends PeekPokeTester(dut) {
  poke(dut.io.cmd.valid, 0); poke(dut.io.resp.ready, 0)
  poke(dut.io.externalValue, 37)
  poke(dut.io.events.admission.valid, 0); poke(dut.io.events.admission.bits, 0)
  dut.io.events.bWrite.foreach(poke(_, 0)); dut.io.events.mac.foreach(poke(_, 0))
  dut.io.events.dmaRequest.foreach(poke(_, 0))
  poke(dut.io.events.drained, 1)
  poke(dut.io.events.fenceRequest, 0); poke(dut.io.events.fenceAccepted, 0)
  reset(2)
  def read(a: BigInt, expected: Option[BigInt], b: BigInt = 0,
           xd: Int = 1, rd: Int = 17): Unit = {
    poke(dut.io.cmd.bits.funct, 126); poke(dut.io.cmd.bits.rs1, a)
    poke(dut.io.cmd.bits.rs2, b); poke(dut.io.cmd.bits.xd, xd)
    poke(dut.io.cmd.bits.rd, rd); poke(dut.io.cmd.valid, 1)
    expect(dut.io.cmd.ready, 1)
    step(1); poke(dut.io.cmd.valid, 0)
    expect(dut.io.resp.valid, 1); expect(dut.io.resp.bits.rd, rd)
    expected.foreach(v => expect(dut.io.resp.bits.data, v))
    val held = peek(dut.io.resp.bits.data)
    for (_ <- 0 until 4) {
      expect(dut.io.resp.valid, 1); expect(dut.io.cmd.ready, 0)
      expect(dut.io.resp.bits.data, held); expect(dut.io.resp.bits.rd, rd)
      expect(dut.io.externalReset, 0)
      step(1)
    }
    poke(dut.io.resp.ready, 1); step(1); poke(dut.io.resp.ready, 0)
  }
  read((BigInt(1) << 31) | 8, None) // Configure counter 0 as external source 0.
  read(0, Some(37))
  read(4, Some(37)) // Snapshot source 0.
  poke(dut.io.externalValue, 91)
  read(PoTalLdbOnlyCapability.Query,
    Some(if (supported) PoTalLdbOnlyCapability.Signature else BigInt(37)))
  read(0, Some(37)) // Capability read preserved snapshot state.
  read(2, Some(37)) // Snapshot reset affects subsequent reads.
  read(0, Some(91)) // Capability read preserved external configuration.
  read(PoTalLdbOnlyCapability.Query, Some(91), b = 1)
  read(PoTalLdbOnlyCapability.Query, Some(91), xd = 0)
}

class PoTalLdbOnlyTraceTester(dut: PoTalLdbOnlyCounterHarness) extends PeekPokeTester(dut) {
  poke(dut.io.cmd.valid, 0); poke(dut.io.resp.ready, 0); poke(dut.io.externalValue, 0)
  poke(dut.io.events.admission.valid, 0); poke(dut.io.events.admission.bits, 1)
  dut.io.events.bWrite.foreach(poke(_, 0)); dut.io.events.mac.foreach(poke(_, 0))
  dut.io.events.dmaRequest.foreach(poke(_, 0))
  poke(dut.io.events.drained, 1)
  poke(dut.io.events.fenceRequest, 0); poke(dut.io.events.fenceAccepted, 0)
  reset(2)
  def read(address: BigInt): BigInt = {
    poke(dut.io.cmd.bits.funct, 126); poke(dut.io.cmd.bits.rs1, address)
    poke(dut.io.cmd.bits.rs2, 0); poke(dut.io.cmd.bits.xd, 1); poke(dut.io.cmd.bits.rd, 19)
    poke(dut.io.cmd.valid, 1); expect(dut.io.cmd.ready, 1)
    step(1); poke(dut.io.cmd.valid, 0)
    expect(dut.io.resp.valid, 1); expect(dut.io.resp.bits.rd, 19)
    val value = peek(dut.io.resp.bits.data)
    poke(dut.io.resp.ready, 1); step(1); poke(dut.io.resp.ready, 0)
    value
  }
  assert(read(PoTalLdbOnlyCapability.TraceReset) == 1)
  poke(dut.io.events.drained, 0)
  poke(dut.io.events.admission.valid, 1); poke(dut.io.events.mac(0), 1)
  poke(dut.io.events.dmaRequest(1), 1)
  step(1)
  poke(dut.io.events.dmaRequest(1), 0)
  poke(dut.io.events.admission.valid, 0); poke(dut.io.events.bWrite(1), 1)
  step(2)
  poke(dut.io.events.mac(0), 0); poke(dut.io.events.bWrite(1), 0)
  step(1)
  poke(dut.io.events.fenceRequest, 1); step(1)
  poke(dut.io.events.drained, 1); poke(dut.io.events.fenceAccepted, 1); step(1)
  poke(dut.io.events.fenceRequest, 0); poke(dut.io.events.fenceAccepted, 0)
  val values = (0 until PoTalLdbOnlyCapability.TraceFields).map { i =>
    read(PoTalLdbOnlyCapability.TraceRead + i)
  }
  assert((values(0) & 3) == 0) // Frozen, no overflow.
  assert(((values(0) >> 20) & 1) == 1)
  assert(((values(0) >> 8) & 1023) == 749) // No B0/MAC1/DMA0.
  assert(values(11) == 0 && values(12) == 2)
  assert(values(13) == 3 && values(14) == 0)
  assert(values(15) == 2 && values(16) == 0 && values(17) == 2)
  assert(values(2) == values(7))
  assert(values(5) - values(7) == 1 && values(6) - values(5) == 1)
  assert(values(18) - values(6) == 2 && values(19) - values(18) == 1)
  assert(values(20) == values(2) && values(21) == values(18))
  assert(values(23) == values(19) && values(1) == values(23) - values(22))
  assert(values(26) == values(2) && values(27) == values(26))
  assert(values(5) - values(26) == 1) // DMA acceptance is not SRAM write.
  // Later reads and a refused busy reset cannot change the captured interval.
  poke(dut.io.events.drained, 0)
  assert(read(PoTalLdbOnlyCapability.TraceReset) == 0)
  for (i <- values.indices)
    assert(read(PoTalLdbOnlyCapability.TraceRead + i) == values(i))
  poke(dut.io.events.drained, 1)
  assert(read(PoTalLdbOnlyCapability.TraceReset) == 1)
  assert(read(PoTalLdbOnlyCapability.TraceRead + 12) == 0)
}

class PoTalLdbOnlySpec extends ChiselFlatSpec {
  behavior of "DIM32 scheduler-owned LdB-only admission"
  it should "exclude only canonical trace reset occupancy on the real COUNTER queue path" in {
    Driver.execute(Array("--backend-name", "verilator", "--target-dir",
      "/tmp/h3-8fg/rtl-run/ldb-only-trace-path"), () => new PoTalLdbOnlyDrainHarness()) {
      dut => new PoTalLdbOnlyTracePathTester(dut)
    } should be(true)
  }
  it should "latch physical event intervals only until qualified Fence and refuse a busy reset" in {
    Driver.execute(Array("--backend-name", "verilator", "--target-dir",
      "/tmp/h3-8fg/rtl-run/ldb-only-trace"), () => new PoTalLdbOnlyCounterHarness(true)) {
      dut => new PoTalLdbOnlyTraceTester(dut)
    } should be(true)
  }
  it should "validate staging and overlap generation without compute effects" in {
    Driver.execute(Array("--backend-name", "verilator", "--target-dir",
      "/tmp/h3-8fg/rtl-run/ldb-only"), () => new PoTalLdbOnlyHarness()) {
      dut => new PoTalLdbOnlyTester(dut, true)
    } should be(true)
  }
  it should "reject unsupported admission without forwarding funct29" in {
    Driver.execute(Array("--backend-name", "verilator", "--target-dir",
      "/tmp/h3-8fg/rtl-run/ldb-only-unsupported"), () => new PoTalLdbOnlyHarness(false)) {
      dut => new PoTalLdbOnlyTester(dut, false)
    } should be(true)
  }
  it should "keep Fence and Release blocked after slot retirement until returned DMA bytes" in {
    Driver.execute(Array("--backend-name", "verilator", "--target-dir",
      "/tmp/h3-8fg/rtl-run/ldb-only-drain"), () => new PoTalLdbOnlyDrainHarness()) {
      dut => new PoTalLdbOnlyDrainTester(dut)
    } should be(true)
  }
  it should "hold exact capability replies without changing counter configuration or snapshots" in {
    for (enabled <- Seq(false, true)) {
      Driver.execute(Array("--backend-name", "verilator", "--target-dir",
        s"/tmp/h3-8fg/rtl-run/ldb-only-counter-$enabled"),
        () => new PoTalLdbOnlyCounterHarness(enabled)) {
        dut => new PoTalLdbOnlyCounterTester(dut, enabled)
      } should be(true)
    }
  }
  it should "advertise only the opt-in observation geometry with a distinct tag" in {
    val c = PoTalConfigs.a8w8D32Sat32
    assert(PoTalLdbOnlyCapability.supported(c, 64, true, true))
    assert(!PoTalLdbOnlyCapability.supported(c, 64, false, true))
    assert(!PoTalLdbOnlyCapability.supported(c, 64, true, false))
    assert(!PoTalLdbOnlyCapability.supported(c, 32, true, true))
    assert(!PoTalLdbOnlyCapability.supported(PoTalConfigs.a8w8D16Sat32, 64, true, true))
    assert(!PoTalLdbOnlyCapability.supported(c.copy(sp_banks = 2), 64, true, true))
    assert(PoTalLdbOnlyCapability.Query != PoTalPrefetchCapability.Query)
    assert(PoTalLdbOnlyCapability.Query != PoTalResidentCapability.Query)
    assert(PoTalLdbOnlyCapability.Signature == BigInt("504f4c4401200001", 16))
  }
}
