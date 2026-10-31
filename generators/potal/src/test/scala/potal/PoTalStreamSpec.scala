package potal

import chisel3._
import chisel3.util._
import chisel3.iotesters._
import GemminiISA._
import PoTalResidentTestSupport.parameters
import Arithmetic.SIntArithmetic

// Reuse the production mode decoder, epoch table and scheduler. The surrounding
// drain harness supplies real RS terminals, never synthetic work references.
class PoTalStreamLdbHarness(enabled: Boolean = true,
                           serialWidth: Int = PoTalStream.SerialWidth)
    extends PoTalLdbOnlyHarness {
  val streamIO = IO(new Bundle {
    val terminal = Input(Vec(4, Valid(new PoTalStreamTag)))
    val pairBusy = Output(Vec(2, Bool()))
    val drained = Output(Bool())
    val ready = Output(Bool())
    val generationBusy = Output(Bool())
    val closed = Output(UInt(32.W))
    val rejected = Output(UInt(32.W))
  })
  streamIO.pairBusy.foreach(_ := false.B)
  streamIO.drained := true.B
  streamIO.ready := true.B
  streamIO.generationBusy := false.B
  streamIO.closed := 0.U
  streamIO.rejected := 0.U
  if (enabled) {
    val mode = Module(new PoTalStreamMode(true))
    val epochs = Module(new PoTalStreamEpochTable(c.reservation_station_entries + 4,
      serialWidth = serialWidth))
    val control = io.cmd.bits.funct === POTAL_STREAM
    val forbidden = mode.io.active && io.cmd.bits.funct === POTAL_LDB_ONLY && !mode.io.bOnly
    val pending = RegInit(false.B)
    val response = Reg(new PoTalScaleCommandResponse)
    val responses = Module(new RRArbiter(new PoTalScaleCommandResponse, 2))
    responses.io.in(0) <> loop.io.resp
    responses.io.in(1).valid := pending
    responses.io.in(1).bits := response
    io.resp <> responses.io.out
    when(responses.io.in(1).fire) { pending := false.B }
    mode.io.op := io.cmd.bits.rs1(3, 0)
    mode.io.funct := io.cmd.bits.funct
    mode.io.rs1 := io.cmd.bits.rs1
    val encoding = io.cmd.bits.xd && io.cmd.bits.rs2 === 0.U &&
      Mux(mode.io.op === 4.U, !io.cmd.bits.rs1(63, 5).orR,
        !io.cmd.bits.rs1(63, 4).orR)
    val legal = encoding && mode.io.legal
    val drained = epochs.io.drained && !loop.io.busy
    val controlReady = !pending && (forbidden || !legal ||
      Mux(mode.io.op === 4.U, !epochs.io.pairBusy(io.cmd.bits.rs1(4)), drained))
    val producerReady = !mode.io.producer || epochs.io.allocate.ready
    loop.io.in.valid := io.cmd.valid && !control && !forbidden && producerReady
    io.cmd.ready := Mux(control || forbidden, controlReady, loop.io.in.ready && producerReady)
    mode.io.commit := io.cmd.fire && control && legal
    when(io.cmd.fire && (control || forbidden) && io.cmd.bits.xd) {
      pending := true.B
      response.rd := io.cmd.bits.rd
      response.status := Mux(forbidden || !mode.io.legal, PoTalScaleCommandStatus.BAD_COMMAND,
        Mux(!encoding, PoTalScaleCommandStatus.INVALID_ENCODING, PoTalScaleCommandStatus.OK))
    }
    when(mode.io.producer) { loop.io.in.bits.potal.stream := epochs.io.allocated }
    epochs.io.continuous := mode.io.continuous
    epochs.io.allocate.valid := loop.io.in.fire && mode.io.producer
    epochs.io.allocate.bits.pair := mode.io.pair
    epochs.io.allocate.bits.generation := mode.io.generation
    epochs.io.close := loop.io.streamClose
    epochs.io.generated.valid := loop.io.out.fire && loop.io.out.bits.potal.stream.valid
    epochs.io.generated.bits := loop.io.out.bits.potal.stream
    epochs.io.terminal := streamIO.terminal
    epochs.io.workStart := 0.U.asTypeOf(epochs.io.workStart)
    epochs.io.workDone := 0.U.asTypeOf(epochs.io.workDone)
    epochs.io.generation := 0.U
    streamIO.pairBusy := epochs.io.pairBusy
    streamIO.drained := epochs.io.drained
    streamIO.ready := epochs.io.allocate.ready
    streamIO.generationBusy := epochs.io.generationBusy
    val closed = RegInit(0.U(32.W))
    val rejected = RegInit(0.U(32.W))
    when(loop.io.streamClose(0).valid) { closed := closed + 1.U }
    when(loop.io.streamClose(1).valid) { rejected := rejected + 1.U }
    streamIO.closed := closed
    streamIO.rejected := rejected
    when(loop.io.out.fire && loop.io.out.bits.potal.stream.valid) {
      assert(loop.io.out.bits.cmd.inst.funct === LOAD2_CMD)
      val metadata = WireInit(loop.io.out.bits.potal)
      metadata.stream := 0.U.asTypeOf(new PoTalStreamTag)
      assert(metadata.asUInt === 0.U, "B-only generated compute/Scale/Index/ACC metadata")
    }
  }
}

class PoTalStreamLdbTester(dut: PoTalLdbOnlyDrainHarness) extends PeekPokeTester(dut) {
  case class Row(id: BigInt, bytes: BigInt, epoch: BigInt, serial: BigInt, pair: BigInt)
  val rows = scala.collection.mutable.ArrayBuffer.empty[Row]
  poke(dut.io.cmd.valid, 0); poke(dut.io.resp.ready, 0)
  poke(dut.io.counterResp.ready, 1); poke(dut.io.otherBusy, 0)
  poke(dut.io.dma.req.ready, 1); poke(dut.io.dma.resp.valid, 0)
  reset(2)
  def tick(): Unit = {
    if (peek(dut.io.dma.req.valid) == 1 && peek(dut.io.dma.req.ready) == 1) {
      val r = dut.io.dma.req.bits
      expect(r.stream.valid, 1); expect(r.stream.generation, 0)
      rows += Row(peek(r.cmd_id), peek(r.cols), peek(r.stream.id),
        peek(r.stream.serial), peek(r.stream.pair))
    }
    step(1)
  }
  def until(condition: => Boolean): Unit = {
    var n = 0
    while (!condition && n < 3000) { tick(); n += 1 }
    assert(condition, s"Early B-only real scheduler/RS/LD path failed to progress: " +
      s"rows=${rows.groupBy(_.pair).map { case (pair, values) => pair -> values.size }} " +
      s"closed=${peek(dut.io.closed)} rejected=${peek(dut.io.rejected)} " +
      s"schedulerBusy=${peek(dut.io.schedulerBusy)} rsDrained=${peek(dut.io.rsDrained)} " +
      s"loadBusy=${peek(dut.io.loadBusy)} epochsDrained=${peek(dut.io.epochsDrained)} " +
      s"pairBusy=${peek(dut.io.pairBusy(0))},${peek(dut.io.pairBusy(1))}")
  }
  def present(f: Int, a: BigInt, b: BigInt = 0, xd: Int = 0): Unit = {
    poke(dut.io.cmd.bits.funct, f); poke(dut.io.cmd.bits.rs1, a)
    poke(dut.io.cmd.bits.rs2, b); poke(dut.io.cmd.bits.xd, xd)
    poke(dut.io.cmd.bits.rd, 19); poke(dut.io.cmd.valid, 1)
  }
  def send(f: Int, a: BigInt, b: BigInt = 0, xd: Int = 0): Unit = {
    present(f, a, b, xd)
    until(peek(dut.io.cmd.ready) == 1); tick(); poke(dut.io.cmd.valid, 0)
  }
  def response(status: Int): Unit = {
    until(peek(dut.io.resp.valid) == 1)
    expect(dut.io.resp.bits.status, status); expect(dut.io.resp.bits.rd, 19)
    tick()
    expect(dut.io.resp.valid, 1); expect(dut.io.resp.bits.status, status)
    poke(dut.io.resp.ready, 1); tick(); poke(dut.io.resp.ready, 0)
  }
  def control(op: Int): Unit = { send(30, op, xd = 1); response(0) }
  def stage(source: BigInt = BigInt("90000000", 16)): Unit = {
    send(9, 0, (BigInt(1) << 32) | (BigInt(1) << 16) | 1)
    send(10, 0, source); send(11, 0, 0)
    send(12, 0, 32); send(13, 0, 0)
  }
  def complete(selected: Seq[Row]): Unit = {
    for (r <- selected) {
      expect(dut.io.pairBusy(r.pair.toInt), 1)
      expect(dut.io.generationBusy, 1)
      poke(dut.io.dma.resp.valid, 1)
      poke(dut.io.dma.resp.bits.cmd_id, r.id)
      poke(dut.io.dma.resp.bits.bytesRead, r.bytes)
      poke(dut.io.dma.resp.bits.stream.valid, 1)
      poke(dut.io.dma.resp.bits.stream.id, r.epoch)
      poke(dut.io.dma.resp.bits.stream.serial, r.serial)
      poke(dut.io.dma.resp.bits.stream.pair, r.pair)
      poke(dut.io.dma.resp.bits.stream.generation, 0)
      tick()
    }
    poke(dut.io.dma.resp.valid, 0)
  }
  send(0, 0x200109, 32)
  until(peek(dut.io.allDrained) == 1)
  for (mode <- Seq(1, 2)) {
    control(mode)
    send(29, 16, xd = 1); response(7)
    expect(dut.io.epochsDrained, 1)
    assert(rows.isEmpty)
    control(3)
  }
  control(6)
  // An actual raw tagged rejection consumes one epoch, with no generated load
  // or work references. A held response cannot close that epoch again.
  send(9, 0, (BigInt(1) << 32) | (BigInt(1) << 16) | 1)
  send(29, 17, xd = 1); response(6)
  until(peek(dut.io.epochsDrained) == 1)
  expect(dut.io.rejected, 1); expect(dut.io.closed, 0)
  assert(rows.isEmpty)
  for (pair <- 0 until 2) {
    stage()
    send(29, 16 | pair, xd = 1); response(0)
    until(peek(dut.io.closed) == pair + 1)
    if (pair == 0) until(rows.count(_.pair == pair) == 32)
    expect(dut.io.pairBusy(pair), 1)
    expect(dut.io.generationBusy, 1)
  }
  until(peek(dut.io.schedulerBusy) == 0)
  expect(dut.io.epochsDrained, 0); expect(dut.io.allDrained, 0)
  // DIM32 has one DMA command credit: 16 / 32 + 1. B1 is generated
  // and owned, but cannot issue DMA until B0 returns its bytes.
  assert(rows.count(_.pair == 0) == 32 && !rows.exists(_.pair == 1))
  assert(rows.forall(_.serial == 1))
  // Serial 3 cannot be allocated while any older identity survives, even
  // though six epoch slots are free. Pair wait cannot acknowledge generation.
  expect(dut.io.epochReady, 0)
  present(30, 4, xd = 1)
  expect(dut.io.cmd.ready, 0); expect(dut.io.resp.valid, 0)
  tick()
  expect(dut.io.cmd.ready, 0); expect(dut.io.pairBusy(1), 1)
  complete(rows.filter(_.pair == 0).toVector)
  until(peek(dut.io.cmd.ready) == 1)
  expect(dut.io.pairBusy(1), 1); expect(dut.io.allDrained, 0)
  tick(); poke(dut.io.cmd.valid, 0); response(0)
  expect(dut.io.pairBusy(0), 0); expect(dut.io.epochReady, 0)
  expect(dut.io.rejected, 1); expect(dut.io.closed, 2)
  until(rows.count(_.pair == 1) == 32)
  assert(rows.filter(_.pair == 1).forall(_.serial == 2))
  complete(rows.filter(_.pair == 1).toVector)
  until(peek(dut.io.allDrained) == 1)
  expect(dut.io.generationBusy, 0); expect(dut.io.epochReady, 1)
  // More than eight producers reuse scheduler/epoch slots and wrap the reduced
  // test serial only after complete retirement, with both actual pair encodings.
  for (n <- 0 until 10) {
    val before = rows.size
    val pair = n & 1
    stage(); send(29, 16 | pair, xd = 1); response(0)
    until(rows.size == before + 32 && peek(dut.io.closed) == n + 3)
    val produced = rows.drop(before).toVector
    assert(produced.forall(r => r.pair == pair && r.serial == ((n + 3) & 3)))
    assert(produced.map(_.epoch).distinct.size == 1)
    expect(dut.io.pairBusy(pair), 1); expect(dut.io.allDrained, 0)
    complete(produced)
    until(peek(dut.io.allDrained) == 1)
  }
  // A fully staged address refusal also closes exactly once, without a LOAD2.
  val before = rows.size
  stage(source = 0); send(29, 16, xd = 1); response(5)
  until(peek(dut.io.epochsDrained) == 1)
  assert(rows.size == before)
  expect(dut.io.rejected, 2); expect(dut.io.closed, 12)
  control(3)
}

class PoTalStreamLoadHarness extends Module {
  val c = PoTalConfigs.a8w8D32Sat32
  val io = IO(new Bundle {
    val cmd = Flipped(Decoupled(new PoTalScaleCommand))
    val tag = Input(new PoTalStreamTag)
    val allocate = Input(Bool())
    val allocated = Output(new PoTalStreamTag)
    val close = Input(Bool())
    val indexed = Input(Bool())
    val indexBase = Input(UInt(32.W))
    val executeReady = Input(Bool())
    val executeValid = Output(Bool())
    val executeTag = Output(new PoTalStreamTag)
    val dma = new ScratchpadReadMemIO(c.local_addr_t, c.mvin_scale_t_bits)
    val pairBusy = Output(Vec(2, Bool()))
    val drained = Output(Bool())
  })
  val epochs = Module(new PoTalStreamEpochTable(c.reservation_station_entries + 4))
  val rs = Module(new ReservationStation(c, new GemminiCmd(c.reservation_station_entries)))
  val ld = Module(new LoadController(c, 40, c.local_addr_t))
  epochs.io.continuous := true.B
  epochs.io.allocate.valid := io.allocate
  epochs.io.allocate.bits.pair := io.tag.pair
  epochs.io.allocate.bits.generation := io.tag.generation
  io.allocated := epochs.io.allocated
  epochs.io.generation := io.tag.generation
  epochs.io.close := 0.U.asTypeOf(epochs.io.close)
  epochs.io.close(0).valid := io.close
  epochs.io.close(0).bits := io.tag
  epochs.io.generated.valid := io.cmd.fire && io.tag.valid
  epochs.io.generated.bits := io.tag
  epochs.io.workStart := 0.U.asTypeOf(epochs.io.workStart)
  epochs.io.workDone := 0.U.asTypeOf(epochs.io.workDone)
  epochs.io.terminal := rs.io.streamTerminal
  rs.io.alloc.valid := io.cmd.valid
  rs.io.alloc.bits := 0.U.asTypeOf(rs.io.alloc.bits)
  PoTalResidentTestSupport.command(rs.io.alloc.bits.cmd, io.cmd.bits)
  rs.io.alloc.bits.potal.stream := io.tag
  rs.io.alloc.bits.potal.valid := io.indexed
  rs.io.alloc.bits.potal.indexedWeight := io.indexed
  rs.io.alloc.bits.potal.weightBufferId := io.tag.pair
  io.cmd.ready := rs.io.alloc.ready
  rs.io.potalIndexDescriptors := 0.U.asTypeOf(rs.io.potalIndexDescriptors)
  for (pair <- 0 until 2) {
    rs.io.potalIndexDescriptors(pair).bBase := io.indexBase
    rs.io.potalIndexDescriptors(pair).sourceKRows := 32.U
    rs.io.potalIndexDescriptors(pair).jTiles := 1.U
    rs.io.potalIndexDescriptors(pair).activeRows := 32.U
    rs.io.potalIndexDescriptors(pair).weightBufferId := pair.U
    rs.io.potalIndexConfiguredByBuffer(pair) := io.indexed
  }
  rs.io.counter.external_reset := false.B
  ld.io.counter.external_reset := false.B
  ld.io.cmd.valid := rs.io.issue.ld.valid
  ld.io.cmd.bits := rs.io.issue.ld.cmd
  ld.io.cmd.bits.rob_id.valid := true.B
  ld.io.cmd.bits.rob_id.bits := rs.io.issue.ld.rob_id
  rs.io.issue.ld.ready := ld.io.cmd.ready
  rs.io.issue.ex.ready := io.executeReady
  io.executeValid := rs.io.issue.ex.valid
  io.executeTag := rs.io.issue.ex.cmd.potal.stream
  rs.io.issue.st.ready := false.B
  rs.io.completed.valid := ld.io.completed.valid
  rs.io.completed.bits := ld.io.completed.bits
  ld.io.completed.ready := true.B
  io.dma <> ld.io.dma
  io.pairBusy := epochs.io.pairBusy
  io.drained := epochs.io.drained && rs.io.drained && !ld.io.busy
}

class PoTalStreamLoadTester(dut: PoTalStreamLoadHarness) extends PeekPokeTester(dut) {
  poke(dut.io.cmd.valid, 0); poke(dut.io.allocate, 0); poke(dut.io.close, 0)
  poke(dut.io.indexed, 0); poke(dut.io.indexBase, 0); poke(dut.io.executeReady, 0)
  poke(dut.io.tag.valid, 0); poke(dut.io.tag.id, 0); poke(dut.io.tag.serial, 0)
  poke(dut.io.tag.pair, 0); poke(dut.io.tag.generation, 1)
  poke(dut.io.dma.req.ready, 1); poke(dut.io.dma.resp.valid, 0)
  reset(2)
  val rows = scala.collection.mutable.ArrayBuffer.empty[(BigInt, BigInt)]
  def tick(): Unit = {
    if (peek(dut.io.dma.req.valid) == 1) {
      expect(dut.io.dma.req.bits.stream.valid, 1)
      expect(dut.io.dma.req.bits.stream.id, 0)
      expect(dut.io.dma.req.bits.stream.serial, 0)
      expect(dut.io.dma.req.bits.stream.pair, 0)
      expect(dut.io.dma.req.bits.stream.generation, 1)
      rows += ((peek(dut.io.dma.req.bits.cmd_id), peek(dut.io.dma.req.bits.cols)))
    }
    step(1)
  }
  def until(condition: => Boolean): Unit = {
    var cycles = 0
    while (!condition && cycles < 200) { tick(); cycles += 1 }
    assert(condition, "Tagged real RS/load pipeline failed to progress")
  }
  def send(f: Int, a: BigInt, b: BigInt): Unit = {
    poke(dut.io.cmd.bits.funct, f); poke(dut.io.cmd.bits.rs1, a)
    poke(dut.io.cmd.bits.rs2, b); poke(dut.io.cmd.bits.xd, 0)
    poke(dut.io.cmd.bits.rd, 0); poke(dut.io.cmd.valid, 1)
    until(peek(dut.io.cmd.ready) == 1); tick(); poke(dut.io.cmd.valid, 0)
  }
  send(0, 0x200101, 32)
  poke(dut.io.allocate, 1); tick(); poke(dut.io.allocate, 0)
  poke(dut.io.tag.valid, 1)
  send(2, 0x1000, (BigInt(32) << 48) | (BigInt(32) << 32))
  poke(dut.io.close, 1); tick(); poke(dut.io.close, 0)
  until(rows.size == 32)
  expect(dut.io.pairBusy(0), 1)
  // Independent complete-on-issue command can retire while real DMA bytes
  // of the older epoch are withheld. It must never retire twice.
  poke(dut.io.tag.id, 1); poke(dut.io.tag.serial, 1)
  poke(dut.io.tag.pair, 1); poke(dut.io.tag.generation, 2)
  poke(dut.io.allocate, 1); tick(); poke(dut.io.allocate, 0)
  send(0, 0x200109, 32)
  poke(dut.io.close, 1); tick(); poke(dut.io.close, 0)
  until(peek(dut.io.pairBusy(1)) == 0)
  expect(dut.io.pairBusy(0), 1)
  for ((id, bytes) <- rows.toVector) {
    poke(dut.io.dma.resp.valid, 1)
    poke(dut.io.dma.resp.bits.cmd_id, id); poke(dut.io.dma.resp.bits.bytesRead, bytes)
    poke(dut.io.dma.resp.bits.stream.valid, 1)
    poke(dut.io.dma.resp.bits.stream.id, 0); poke(dut.io.dma.resp.bits.stream.serial, 0)
    poke(dut.io.dma.resp.bits.stream.pair, 0); poke(dut.io.dma.resp.bits.stream.generation, 1)
    expect(dut.io.pairBusy(0), 1); tick()
  }
  poke(dut.io.dma.resp.valid, 0)
  until(peek(dut.io.drained) == 1)
}

class PoTalStreamLoopHarness extends PoTalLdbOnlyHarness {
  val epoch = RegInit(0.U(32.W))
  for (pair <- 0 until 2) {
    loop.io.indexComplete(pair) := true.B
    loop.io.indexDescriptors(pair).bBase := (2048 + pair * 4096).U
    loop.io.indexDescriptors(pair).jTiles := 1.U
    loop.io.indexDescriptors(pair).sourceKRows := 32.U
    loop.io.indexDescriptors(pair).activeRows := 32.U
    loop.io.indexDescriptors(pair).weightBufferId := pair.U
  }
  when(loop.io.in.bits.cmd.inst.funct === LOOP_WS) {
    loop.io.in.bits.potal.stream.valid := true.B
    loop.io.in.bits.potal.stream.id := epoch(2, 0)
    loop.io.in.bits.potal.stream.serial := epoch
    loop.io.in.bits.potal.stream.pair := loop.io.in.bits.cmd.rs1(17, 16) === 2.U
    loop.io.in.bits.potal.stream.generation := loop.io.in.bits.cmd.rs1(44, 37)
    when(loop.io.in.fire) { epoch := epoch + 1.U }
  }
}

class PoTalStreamLoopTester(dut: PoTalStreamLoopHarness) extends PeekPokeTester(dut) {
  poke(dut.io.cmd.valid, 0); poke(dut.io.resp.ready, 1)
  poke(dut.io.generated.ready, 1)
  poke(dut.io.ldComplete, 0); poke(dut.io.exComplete, 0); poke(dut.io.stComplete, 0)
  reset(2)
  val pending = Array(0, 0, 0)
  val generated = scala.collection.mutable.ArrayBuffer.empty[(Int, Int)]
  val statuses = scala.collection.mutable.ArrayBuffer.empty[Int]
  def tick(): Unit = {
    poke(dut.io.ldComplete, pending(0)); poke(dut.io.exComplete, pending(1))
    poke(dut.io.stComplete, pending(2))
    for (i <- 0 until 3) pending(i) = 0
    if (peek(dut.io.resp.valid) == 1 && peek(dut.io.resp.ready) == 1)
      statuses += peek(dut.io.resp.bits.status).toInt
    if (peek(dut.io.generated.valid) == 1) {
      val g = dut.io.generated.bits
      if (peek(g.from_matmul_fsm) == 1) {
        expect(g.potal.stream.valid, 1)
        val serial = peek(g.potal.stream.serial).toInt
        val f = peek(g.cmd.inst.funct).toInt
        generated += ((serial, f))
        val q = if (Set(1, 2, 14).contains(f)) 0 else if (f == 3) 2 else 1
        pending(q) = 1
        if (f == 6) {
          expect(g.potal.startsWork, 1)
          expect(g.potal.firstContribution, 0)
          expect(g.potal.indexedWeight, if (serial == 1) 1 else 0)
        }
      }
    }
    step(1)
  }
  def until(condition: => Boolean): Unit = {
    var cycles = 0
    while (!condition && cycles < 300) { tick(); cycles += 1 }
    assert(condition, "Streaming scheduler did not make progress")
  }
  def send(f: Int, a: BigInt, b: BigInt, xd: Int = 0): Unit = {
    poke(dut.io.cmd.bits.funct, f); poke(dut.io.cmd.bits.rs1, a)
    poke(dut.io.cmd.bits.rs2, b); poke(dut.io.cmd.bits.xd, xd)
    poke(dut.io.cmd.bits.rd, 1); poke(dut.io.cmd.valid, 1)
    until(peek(dut.io.cmd.ready) == 1); tick(); poke(dut.io.cmd.valid, 0)
  }
  for (state <- 0 until 3) send(0, BigInt(0x200101 | (state << 3)), 32)
  until(peek(dut.io.busy) == 0)
  for (serial <- 0 until 3) {
    val pair = if (serial == 2) 2 else 1
    send(9, 0, (BigInt(1) << 32) | (BigInt(1) << 16) | 1)
    send(10, 0x1000 + serial * 1024, 0)
    send(11, 0, 0); send(12, 32, 32); send(13, 0, 0)
    val run = BigInt(3) | (BigInt(pair) << 16) | (BigInt(pair) << 18) |
      (BigInt(1) << 20) | (BigInt(1) << 37) | (BigInt(1) << 45) |
      (BigInt(1) << 61) | (if (serial == 1) BigInt(3) << 62 else BigInt(0))
    send(8, run, 0, xd=1)
  }
  until(statuses.size == 3 && peek(dut.io.busy) == 0)
  assert(statuses.toSeq == Seq(0, 0, 0))
  for (serial <- 0 until 3) {
    val words = generated.filter(_._1 == serial).map(_._2)
    assert(words.count(_ == (if (serial == 1) 14 else 2)) == 1)
    assert(words.count(_ == 6) == 1)
    assert(words.count(f => f == 4 || f == 5) == 1)
  }
  // Partially staged run is rejected once; holding the response cannot turn
  // that refusal into an accepted run or change rd/status.
  poke(dut.io.resp.ready, 0)
  send(9, 0, (BigInt(1) << 32) | (BigInt(1) << 16) | 1)
  send(8, 0, 0, xd=1)
  until(peek(dut.io.resp.valid) == 1)
  for (_ <- 0 until 3) {
    expect(dut.io.resp.bits.status, 6); expect(dut.io.resp.bits.rd, 1)
    tick()
  }
  poke(dut.io.resp.ready, 1); tick()
  assert(statuses.toSeq == Seq(0, 0, 0, 6))
  // A refused xd=0 run produces no commands or unsolicited response.
  send(8, 0, 0)
  until(peek(dut.io.busy) == 0)
  assert(statuses.size == 4 && !generated.exists(_._1 >= 3))
}

class PoTalStreamEpochTester(dut: PoTalStreamEpochTable, continuous: Boolean)
    extends PeekPokeTester(dut) {
  def tag(port: PoTalStreamTag, id: Int, serial: Int): Unit = {
    poke(port.valid, 1); poke(port.id, id); poke(port.serial, serial)
    poke(port.pair, id & 1); poke(port.generation, id + 1)
  }
  poke(dut.io.allocate.valid, 0)
  poke(dut.io.continuous, if (continuous) 1 else 0)
  poke(dut.io.generation, 1)
  dut.io.close.foreach(p => poke(p.valid, 0))
  dut.io.terminal.foreach(p => poke(p.valid, 0))
  poke(dut.io.generated.valid, 0)
  poke(dut.io.workStart.valid, 0)
  poke(dut.io.workDone.valid, 0)
  reset(2)
  val n = if (continuous) 8 else 2
  for (i <- 0 until n) {
    poke(dut.io.allocate.bits.pair, i & 1)
    poke(dut.io.allocate.bits.generation, i + 1)
    poke(dut.io.allocate.valid, 1)
    expect(dut.io.allocate.ready, 1)
    expect(dut.io.allocated.id, i)
    expect(dut.io.allocated.serial, i)
    step(1)
    poke(dut.io.allocate.valid, 0)
    // Close can precede physical completion by an arbitrary number of clocks.
    tag(dut.io.generated.bits, i, i); poke(dut.io.generated.valid, 1)
    tag(dut.io.workStart.bits, i, i); poke(dut.io.workStart.valid, 1)
    tag(dut.io.close(0).bits, i, i); poke(dut.io.close(0).valid, 1)
    step(1)
    poke(dut.io.generated.valid, 0); poke(dut.io.workStart.valid, 0)
    poke(dut.io.close(0).valid, 0)
  }
  expect(dut.io.allocate.ready, 0)
  expect(dut.io.drained, 0)
  expect(dut.io.pairBusy(0), 1); expect(dut.io.pairBusy(1), 1)
  expect(dut.io.generationBusy, 1)
  // A response terminal alone cannot recycle an epoch with delayed ACC work.
  tag(dut.io.terminal(3).bits, 0, 0); poke(dut.io.terminal(3).valid, 1)
  step(1); poke(dut.io.terminal(3).valid, 0)
  expect(dut.io.allocate.ready, 0)
  expect(dut.io.generationBusy, 1)
  tag(dut.io.workDone.bits, 0, 0); poke(dut.io.workDone.valid, 1)
  step(1); poke(dut.io.workDone.valid, 0)
  step(1)
  expect(dut.io.allocate.ready, 1)
  expect(dut.io.generationBusy, 0)
  // Reuse a physical table entry with a fresh serial, not the scheduler ID.
  poke(dut.io.allocate.bits.pair, 0); poke(dut.io.allocate.bits.generation, 1)
  poke(dut.io.allocate.valid, 1)
  expect(dut.io.allocated.id, 0); expect(dut.io.allocated.serial, n)
  step(1); poke(dut.io.allocate.valid, 0)
  // Rejected run: no generated/work refs, one close, exactly one retirement.
  tag(dut.io.close(1).bits, 0, n); poke(dut.io.close(1).valid, 1)
  step(1); poke(dut.io.close(1).valid, 0); step(1)
  for (i <- 1 until n) {
    tag(dut.io.terminal(i % 3).bits, i, i)
    poke(dut.io.terminal(i % 3).valid, 1)
    tag(dut.io.workDone.bits, i, i); poke(dut.io.workDone.valid, 1)
    step(1)
    poke(dut.io.terminal(i % 3).valid, 0); poke(dut.io.workDone.valid, 0)
  }
  step(1)
  expect(dut.io.drained, 1)
  expect(dut.io.pairBusy(0), 0); expect(dut.io.pairBusy(1), 0)
}

class PoTalStreamSpec extends ChiselFlatSpec {
  behavior of "PoTal raw-dispatch execution epochs"
  it should "close raw B-only producers only after real tagged RS DMA terminals" in {
    val directory = java.nio.file.Files.createTempDirectory("h3-8h-early-stream-ldb-")
    Driver.execute(Array("--backend-name", "verilator", "--target-dir",
      directory.toString),
      () => new PoTalLdbOnlyDrainHarness(earlyStream = true, serialWidth = 2)) {
      dut => new PoTalStreamLdbTester(dut)
    } should be(true)
  }
  for (supported <- Seq(false, true)) {
    it should s"negotiate only mode six for B-only producers supported=$supported" in {
      val directory = java.nio.file.Files.createTempDirectory(s"h3-8h-early-mode-$supported-")
      Driver.execute(Array("--backend-name", "treadle", "--target-dir",
        directory.toString), () => new PoTalStreamMode(supported)) {
        dut => new PeekPokeTester(dut) {
          poke(dut.io.commit, 0); poke(dut.io.op, 0)
          poke(dut.io.funct, 29); poke(dut.io.rs1, 17)
          reset(2)
          for (op <- Seq(0, 3, 4, 5, 7)) {
            poke(dut.io.op, op); poke(dut.io.commit, 1)
            expect(dut.io.legal, 0); step(1)
            expect(dut.io.active, 0); expect(dut.io.early, 0)
          }
          for (op <- Seq(6, 1, 2)) {
            poke(dut.io.op, op); poke(dut.io.commit, 0)
            expect(dut.io.legal, if (supported) 1 else 0)
            expect(dut.io.active, 0)
            poke(dut.io.commit, 1); step(1); poke(dut.io.commit, 0)
            expect(dut.io.active, if (supported) 1 else 0)
            expect(dut.io.early, if (supported && op == 6) 1 else 0)
            expect(dut.io.continuous, if (supported && op != 1) 1 else 0)
            expect(dut.io.bOnly, if (supported && op == 6) 1 else 0)
            expect(dut.io.producer, if (supported && op == 6) 1 else 0)
            if (supported) {
              // Main selector/generation bits must not decode B-only tags.
              for (pair <- 0 until 2) {
                poke(dut.io.rs1, BigInt(16 | pair) | (BigInt(2 - pair) << 16) |
                  (BigInt(91) << 37))
                if (op == 6) {
                  expect(dut.io.pair, pair); expect(dut.io.generation, 0)
                }
              }
              poke(dut.io.funct, 8)
              expect(dut.io.producer, 1); expect(dut.io.pair, 0)
              expect(dut.io.generation, 91)
              poke(dut.io.funct, 29)
              for (begin <- Seq(1, 2, 6)) {
                poke(dut.io.op, begin); poke(dut.io.commit, 1)
                expect(dut.io.legal, 0); step(1)
                expect(dut.io.early, if (op == 6) 1 else 0)
              }
              for (scoped <- Seq(4, 5)) {
                poke(dut.io.op, scoped); poke(dut.io.commit, 1)
                expect(dut.io.legal, 1); step(1)
                expect(dut.io.active, 1); expect(dut.io.early, if (op == 6) 1 else 0)
              }
            }
            poke(dut.io.op, 3); poke(dut.io.commit, 1); step(1)
            poke(dut.io.commit, 0)
            expect(dut.io.active, 0); expect(dut.io.early, 0)
            expect(dut.io.bOnly, 0); expect(dut.io.producer, 0)
          }
        }
      } should be(true)
    }
  }
  it should "hold all eight zero-work producers under terminal pressure and reuse safely" in {
    val directory = java.nio.file.Files.createTempDirectory("h3-8h-zero-work-pressure-")
    Driver.execute(Array("--backend-name", "treadle", "--target-dir",
      directory.toString), () => new PoTalStreamEpochTable(8, 4)) {
      dut => new PeekPokeTester(dut) {
        def tag(t: PoTalStreamTag, id: Int, serial: Int): Unit = {
          poke(t.valid, 1); poke(t.id, id); poke(t.serial, serial)
          poke(t.pair, id & 1); poke(t.generation, 0)
        }
        poke(dut.io.continuous, 1); poke(dut.io.allocate.valid, 0)
        poke(dut.io.allocate.bits.generation, 0); poke(dut.io.generation, 0)
        dut.io.close.foreach(p => poke(p.valid, 0))
        dut.io.terminal.foreach(p => poke(p.valid, 0))
        poke(dut.io.generated.valid, 0)
        poke(dut.io.workStart.valid, 0); poke(dut.io.workDone.valid, 0)
        reset(2)
        for (id <- 0 until 8) {
          poke(dut.io.allocate.bits.pair, id & 1); poke(dut.io.allocate.valid, 1)
          expect(dut.io.allocate.ready, 1); expect(dut.io.allocated.id, id)
          step(1); poke(dut.io.allocate.valid, 0)
          tag(dut.io.generated.bits, id, id); poke(dut.io.generated.valid, 1)
          tag(dut.io.close(0).bits, id, id); poke(dut.io.close(0).valid, 1)
          step(1); poke(dut.io.generated.valid, 0); poke(dut.io.close(0).valid, 0)
        }
        expect(dut.io.allocate.ready, 0); expect(dut.io.drained, 0)
        expect(dut.io.pairBusy(0), 1); expect(dut.io.pairBusy(1), 1)
        expect(dut.io.generationBusy, 1)
        for (id <- 0 until 8) {
          tag(dut.io.terminal(3).bits, id, id); poke(dut.io.terminal(3).valid, 1)
          step(1); poke(dut.io.terminal(3).valid, 0); step(1)
          expect(dut.io.allocate.ready, 1)
          if (id < 6) {
            expect(dut.io.pairBusy(0), 1); expect(dut.io.pairBusy(1), 1)
          }
        }
        expect(dut.io.drained, 1); expect(dut.io.generationBusy, 0)
        poke(dut.io.allocate.bits.pair, 0); poke(dut.io.allocate.valid, 1)
        expect(dut.io.allocated.id, 0); expect(dut.io.allocated.serial, 8)
        step(1); poke(dut.io.allocate.valid, 0)
        tag(dut.io.close(1).bits, 0, 8); poke(dut.io.close(1).valid, 1)
        step(1); poke(dut.io.close(1).valid, 0); step(1)
        expect(dut.io.drained, 1)
      }
    } should be(true)
  }
  for (work <- Seq(false, true); overflow <- Seq(false, true)) {
    it should s"assert counter bounds work=$work overflow=$overflow" in {
      var injected = false
      intercept[treadle.executable.StopException] {
        Driver.execute(Array("--backend-name", "treadle", "--target-dir",
        s"/tmp/h3-8h-rtl/bounds-$work-$overflow"), () => new PoTalStreamEpochTable(2, 2)) {
        dut => new PeekPokeTester(dut) {
          poke(dut.io.allocate.valid, 0); poke(dut.io.continuous, 1)
          poke(dut.io.generation, 1)
          dut.io.close.foreach(p => poke(p.valid, 0))
          dut.io.terminal.foreach(p => poke(p.valid, 0))
          poke(dut.io.generated.valid, 0); poke(dut.io.workStart.valid, 0); poke(dut.io.workDone.valid, 0)
          reset(2)
          assert(peek(dut.io.allocate.ready) == 1)
          poke(dut.io.allocate.bits.pair, 0); poke(dut.io.allocate.bits.generation, 1)
          poke(dut.io.allocate.valid, 1); step(1); poke(dut.io.allocate.valid, 0)
          assert(peek(dut.io.pairBusy(0)) == 1)
          val event = if (work) { if (overflow) dut.io.workStart else dut.io.workDone }
                      else { if (overflow) dut.io.generated else dut.io.terminal(0) }
          poke(event.bits.valid, 1); poke(event.bits.id, 0); poke(event.bits.serial, 0)
          poke(event.bits.pair, 0); poke(event.bits.generation, 1)
          poke(event.valid, 1)
          if (overflow) step(2) // Reach the exact bound without wrapping.
          injected = true
          step(1) // Third increment, or a decrement at zero, must assert.
        }
      }
      }
      injected should be(true)
    }
  }
  it should "retain real RS load epochs until all tagged DMA bytes return" in {
    Driver.execute(Array("--backend-name", "verilator", "--target-dir",
      "/tmp/h3-8h-rtl/load-epochs"), () => new PoTalStreamLoadHarness) {
      dut => new PoTalStreamLoadTester(dut)
    } should be(true)
  }
  for (overlap <- Seq(false, true)) {
    it should s"gate indexed Residual issue on its actual B range overlap=$overlap" in {
      val directory = java.nio.file.Files.createTempDirectory(s"h3-8h-indexed-$overlap-")
      Driver.execute(Array("--backend-name", "verilator", "--target-dir", directory.toString),
        () => new PoTalStreamLoadHarness) {
        dut => new PoTalStreamIndexedHazardTester(dut, overlap)
      } should be(true)
    }
  }
  for ((stream, ldb, width) <- Seq((false, true, 64), (true, false, 64),
      (true, true, 32), (true, true, 64))) {
    it should s"probe COUNTER safely with stream=$stream B-only=$ldb width=$width" in {
      Driver.execute(Array("--backend-name", "verilator", "--target-dir",
        s"/tmp/h3-8h-rtl/capability-$stream-$ldb-$width"),
        () => new PoTalLdbOnlyCounterHarness(ldb, stream, width)) {
        dut => new PeekPokeTester(dut) {
          poke(dut.io.cmd.valid, 0); poke(dut.io.resp.ready, 0)
          poke(dut.io.externalValue, 37)
          poke(dut.io.events.admission.valid, 0); poke(dut.io.events.admission.bits, 0)
          dut.io.events.bWrite.foreach(p => poke(p, 0))
          dut.io.events.mac.foreach(p => poke(p, 0))
          dut.io.events.dmaRequest.foreach(p => poke(p, 0))
          poke(dut.io.events.drained, 1)
          poke(dut.io.events.fenceRequest, 0); poke(dut.io.events.fenceAccepted, 0)
          reset(2)
          def query(rs1: BigInt, rs2: BigInt, expected: BigInt, checkData: Boolean = true,
                    xd: Int = 1): Unit = {
            poke(dut.io.cmd.bits.funct, 126); poke(dut.io.cmd.bits.rs1, rs1)
            poke(dut.io.cmd.bits.rs2, rs2); poke(dut.io.cmd.bits.xd, xd)
            poke(dut.io.cmd.bits.rd, 9); poke(dut.io.cmd.valid, 1)
            expect(dut.io.cmd.ready, 1); step(1); poke(dut.io.cmd.valid, 0)
            expect(dut.io.resp.valid, 1)
            if (checkData) expect(dut.io.resp.bits.data, expected)
            val held = peek(dut.io.resp.bits.data)
            expect(dut.io.resp.bits.rd, 9); expect(dut.io.externalReset, 0)
            step(2)
            expect(dut.io.resp.bits.data, held)
            poke(dut.io.resp.ready, 1); step(1); poke(dut.io.resp.ready, 0)
          }
          // Configure counter 0 as external, then prove probes preserve it.
          query((BigInt(1) << 31) | 8, 0, 0, checkData=false)
          query(0, 0, 37)
          query(PoTalStream.Query, 0, if (stream && width == 64) PoTalStream.Signature else BigInt(37))
          query(PoTalStream.Query, 1, 37)
          query(PoTalStream.EarlyQuery, 0,
            if (stream && ldb && width == 64) PoTalStream.EarlySignature else BigInt(37))
          query(PoTalStream.EarlyQuery, 1, 37)
          query(PoTalStream.EarlyQuery, 0, 37, xd = 0)
          query(0, 0, 37)
        }
      } should be(true)
    }
  }
  it should "drain before serial wrap even when unused table slots remain" in {
    Driver.execute(Array("--backend-name", "treadle", "--target-dir",
      "/tmp/h3-8h-rtl/serial-wrap"), () => new PoTalStreamEpochTable(8, 4, 2)) {
      dut => new PeekPokeTester(dut) {
        poke(dut.io.allocate.valid, 0); poke(dut.io.continuous, 1)
        poke(dut.io.allocate.bits.pair, 0); poke(dut.io.allocate.bits.generation, 1)
        poke(dut.io.generation, 1)
        dut.io.close.foreach(p => poke(p.valid, 0))
        dut.io.terminal.foreach(p => poke(p.valid, 0))
        poke(dut.io.generated.valid, 0); poke(dut.io.workStart.valid, 0)
        poke(dut.io.workDone.valid, 0)
        reset(2)
        for (serial <- 0 until 4) {
          poke(dut.io.allocate.valid, 1)
          expect(dut.io.allocate.ready, 1); expect(dut.io.allocated.serial, serial)
          step(1); poke(dut.io.allocate.valid, 0)
          if (serial == 2) expect(dut.io.allocate.ready, 0)
          poke(dut.io.close(0).bits.valid, 1); poke(dut.io.close(0).bits.id, 0)
          poke(dut.io.close(0).bits.serial, serial)
          poke(dut.io.close(0).bits.pair, 0); poke(dut.io.close(0).bits.generation, 1)
          poke(dut.io.close(0).valid, 1); step(1)
          poke(dut.io.close(0).valid, 0); step(1)
        }
        expect(dut.io.allocated.serial, 0)
        expect(dut.io.drained, 1)
      }
    } should be(true)
  }
  it should "emit ordinary Main Residual Main loops with distinct static load states" in {
    Driver.execute(Array("--backend-name", "verilator", "--target-dir",
      "/tmp/h3-8h-rtl/stream-loop"), () => new PoTalStreamLoopHarness) {
      dut => new PoTalStreamLoopTester(dut)
    } should be(true)
  }
  for (continuous <- Seq(false, true)) {
    it should s"preserve delayed terminal and work ownership continuous=$continuous" in {
      Driver.execute(Array("--backend-name", "treadle", "--target-dir",
        s"/tmp/h3-8h-rtl/epochs-$continuous"),
        () => new PoTalStreamEpochTable(8, 4)) {
        dut => new PoTalStreamEpochTester(dut, continuous)
      } should be(true)
    }
  }
}
