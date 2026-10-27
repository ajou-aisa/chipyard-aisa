package potal

import chisel3._
import chisel3.util._
import chisel3.iotesters._
import scala.collection.mutable.ArrayBuffer
import GemminiISA._
import PoTalResidentTestSupport._

// Real LoopMatmul and Index ingress. RS command completions are explicit
// one-cycle acknowledgements, not a model of DMA or physical completion.
class PoTalResidentLoopHarness(supported: Boolean = true) extends Module {
  private val c = config
  val io = IO(new Bundle {
    val cmd = Flipped(Decoupled(new PoTalScaleCommand))
    val resp = Decoupled(new PoTalScaleCommandResponse)
    val generated = Decoupled(new GemminiCmd(c.reservation_station_entries))
    val busy = Output(Bool())
    val complete = Output(Vec(2, Bool()))
    val active = Output(Vec(2, Bool()))
    val consumed = Output(Vec(2, Bool()))
    val pending = Output(Vec(2, UInt(4.W)))
  })
  val loop = Module(new LoopMatmul(16, 40, c.reservation_station_entries,
    c.reservation_station_entries_ld, c.reservation_station_entries_ex,
    c.reservation_station_entries_st, 16384, 1024, 8, 32, 64,
    new MvinRs2(c.mvin_rows_bits, c.mvin_cols_bits, c.local_addr_t),
    new PreloadRs(c.mvin_rows_bits, c.mvin_cols_bits, c.local_addr_t),
    new PreloadRs(c.mvout_rows_bits, c.mvout_cols_bits, c.local_addr_t),
    new ComputeRs(c.mvin_rows_bits, c.mvin_cols_bits, c.local_addr_t),
    new ComputeRs(c.mvin_rows_bits, c.mvin_cols_bits, c.local_addr_t),
    new MvoutRs2(c.mvout_rows_bits, c.mvout_cols_bits, c.local_addr_t), 256, supported))
  val ingress = Module(new PoTalScaleCommandIngress(16, 256, 32, 16384))
  val indexCommand = io.cmd.bits.funct === POTAL_INDEX_CONFIG ||
    io.cmd.bits.funct === POTAL_INDEX_WRITE
  ingress.io.cmd.valid := io.cmd.valid && indexCommand
  ingress.io.cmd.bits := io.cmd.bits
  loop.io.in.valid := io.cmd.valid && !indexCommand
  loop.io.in.bits := 0.U.asTypeOf(loop.io.in.bits)
  command(loop.io.in.bits.cmd, io.cmd.bits)
  io.cmd.ready := Mux(indexCommand, ingress.io.cmd.ready, loop.io.in.ready)
  loop.io.indexDescriptors := ingress.io.indexDescriptors
  loop.io.indexComplete := ingress.io.indexComplete
  ingress.io.scaleLoad.ready := true.B
  ingress.io.scaleRelease.ready := true.B
  ingress.io.scaleConflict := false.B
  ingress.io.releaseMiss := false.B
  ingress.io.releaseSafe := !loop.io.busy
  ingress.io.fenceDrained := !loop.io.busy
  io.complete := ingress.io.indexComplete
  io.active := loop.io.indexedLoopActive
  io.consumed := loop.io.indexedLoopConfigured

  // Same raw-bit accounting as Controller, including rejected runs. The
  // pending FIFO is LoopMatmul's real FIFO; no run-admission result is mocked.
  val pending = RegInit(VecInit(Seq.fill(2)(0.U(4.W))))
  for (buffer <- 0 until 2) {
    val dispatch = loop.io.in.fire && io.cmd.bits.funct === LOOP_WS &&
      io.cmd.bits.rs1(63) && io.cmd.bits.rs1(17, 16) === (buffer + 1).U
    val consumed = loop.io.indexedLoopConfigured(buffer)
    when(dispatch =/= consumed) {
      pending(buffer) := Mux(dispatch, pending(buffer) + 1.U, pending(buffer) - 1.U)
    }
    when(consumed && !dispatch) { assert(pending(buffer) =/= 0.U) }
    ingress.io.indexUpdateSafe(buffer) :=
      pending(buffer) === 0.U && !dispatch && !loop.io.indexedLoopActive(buffer)
  }
  io.pending := pending
  val responses = Module(new RRArbiter(new PoTalScaleCommandResponse, 2))
  responses.io.in(0) <> loop.io.resp
  responses.io.in(1) <> ingress.io.resp
  io.resp <> responses.io.out
  io.generated <> loop.io.out
  io.busy := loop.io.busy || ingress.io.resp.valid

  val emitted = loop.io.out.fire && loop.io.out.bits.from_matmul_fsm
  val funct = loop.io.out.bits.cmd.inst.funct
  loop.io.ld_completed := RegNext(emitted &&
    (funct === LOAD_CMD || funct === LOAD2_CMD || funct === LOAD3_CMD), false.B).asUInt
  loop.io.ex_completed := RegNext(emitted &&
    (funct === PRELOAD_CMD || funct === COMPUTE_AND_FLIP_CMD ||
      funct === COMPUTE_AND_STAY_CMD), false.B).asUInt
  loop.io.st_completed := RegNext(emitted && funct === STORE_CMD, false.B).asUInt
}

class PoTalResidentLoopTester(dut: PoTalResidentLoopHarness) extends PeekPokeTester(dut) {
  case class Emitted(funct: Int, rs1: BigInt, rs2: BigInt, indexed: Boolean,
                     j: Int, k: Int, block: Int, tile: Int, work: Int,
                     first: Boolean, last: Boolean)
  val emitted = ArrayBuffer.empty[Emitted]
  val consumed = Array(0, 0)
  poke(dut.io.cmd.valid, 0)
  poke(dut.io.resp.ready, 0)
  poke(dut.io.generated.ready, 1)
  reset(2)

  def tick(): Unit = {
    for (b <- 0 until 2) if (peek(dut.io.consumed(b)) == 1) consumed(b) += 1
    if (peek(dut.io.generated.valid) == 1 && peek(dut.io.generated.ready) == 1) {
      val g = dut.io.generated.bits
      val m = g.potal
      emitted += Emitted(peek(g.cmd.inst.funct).toInt, peek(g.cmd.rs1), peek(g.cmd.rs2),
        peek(m.indexedWeight) == 1, peek(m.indexedJTile).toInt,
        peek(m.indexedKTile).toInt, peek(m.blockIndex).toInt,
        peek(m.outputTileIndex).toInt, peek(m.workId).toInt,
        peek(m.firstContribution) == 1, peek(m.lastContribution) == 1)
    }
    step(1)
  }
  def until(condition: => Boolean): Unit = {
    var cycles = 0
    while (!condition && cycles < 2000) { tick(); cycles += 1 }
    assert(condition, s"bounded handshake timed out after $cycles cycles")
  }
  def send(f: Int, a: BigInt = 0, b: BigInt = 0, xd: Int = 0, rd: Int = 11): Unit = {
    poke(dut.io.cmd.bits.funct, f)
    poke(dut.io.cmd.bits.rs1, a); poke(dut.io.cmd.bits.rs2, b)
    poke(dut.io.cmd.bits.xd, xd); poke(dut.io.cmd.bits.rd, rd)
    poke(dut.io.cmd.valid, 1)
    until(peek(dut.io.cmd.ready) == 1)
    tick()
    poke(dut.io.cmd.valid, 0)
  }
  def response(status: Int, rd: Int = 11, stall: Int = 0): Unit = {
    until(peek(dut.io.resp.valid) == 1)
    for (_ <- 0 to stall) {
      expect(dut.io.resp.valid, 1)
      expect(dut.io.resp.bits.status, status); expect(dut.io.resp.bits.rd, rd)
      expect(dut.io.busy, 1)
      if (stall != 0) tick()
    }
    poke(dut.io.resp.ready, 1); tick(); poke(dut.io.resp.ready, 0)
  }
  def control(f: Int, a: BigInt, b: BigInt, status: Int = 0, xd: Int = 1): Unit = {
    send(f, a, b, xd)
    if (xd != 0) response(status)
  }
  def configs(j: Int = 2, k: Int = 2, i: Int = 1, padK: Int = 0,
              omit: Int = -1, a: BigInt = BigInt("80000000", 16),
              b: BigInt = 0, d: BigInt = 0, out: BigInt = 0): Unit = {
    val words = Seq(
      (9, BigInt(padK) << 32, BigInt(i) | (BigInt(j) << 16) | (BigInt(k) << 32)),
      (10, a, b), (11, d, out), (12, BigInt(k * 16), BigInt(144)),
      (13, BigInt(0), BigInt(0)))
    words.filter(_._1 != omit).foreach { case (f, x, y) => send(f, x, y) }
  }
  def drain(): Unit = {
    until(peek(dut.io.busy) == 0)
    for (b <- 0 until 2) expect(dut.io.pending(b), 0)
  }
  def reject(status: Int = 6, r: BigInt = run(), rs2: BigInt = 0): Unit = {
    val before = emitted.size
    send(8, r, rs2, xd = 1)
    response(status, stall = 3)
    drain()
    assert(emitted.size == before, "refused run emitted a load or execute command")
  }
  def check(start: Int, pair: Int, base: Int, bank: Int, indexed: Boolean,
            js: Int = 2, ks: Int = 2, origin: Int = 0, acc: Int = 80,
            legacy: Boolean = false): Unit = {
    val commands = emitted.drop(start).filter(x => x.funct != 7)
    assert(commands.forall(x => Set(2, 4, 6).contains(x.funct)), "resident B/D/C must not DMA")
    assert(commands.count(_.funct == 2) > 0, "A remains a real DMA source")
    val preloads = commands.filter(_.funct == 6)
    assert(preloads.size == js * ks)
    val workBase = preloads.head.work
    for ((p, n) <- preloads.zipWithIndex) {
      val k = n / js; val j = n % js
      val expectedB = if (legacy) (pair + 1) * 8192 - ks * js * 16 + (k * js + j) * 16
        else base + k * 144 + j * 16
      assert((p.rs1 & 0x3fff) == expectedB)
      assert((p.rs2 & 0x3ff) == bank * 512 + acc + j * 16)
      assert(p.indexed == indexed)
      assert(p.j == j + (if (legacy) 0 else origin))
      assert(p.k == k && p.block == k && p.tile == j)
      assert(p.work == workBase + j && p.first == (k == 0) && p.last == (k == ks - 1))
    }
  }

  // Missing words cannot reuse uninitialized or previously accepted operands.
  for (missing <- 9 to 13) {
    configs(omit = missing)
    control(28, view(4672), flags())
    reject()
    control(28, view(4672), flags())
    reject() // The previous run consumed every completion bit.
  }
  configs()
  reject() // No view.
  configs()
  control(28, 0, 0)
  reject() // Explicit disable is not a runnable view.
  control(28, view(4672), flags() | 0x100, status = 6)
  control(28, view(4672), BigInt(0x21), status = 7)
  control(28, view(4673), flags(), status = 5)
  control(28, view(4672, pitch = 0), flags(), status = 5)
  control(28, view(4672, pitch = 160), flags(), status = 5)
  control(28, view(4672, origin = 5), flags(), status = 6)
  control(28, view(4160, origin = 9), flags(indexed = true), status = 5)
  control(28, view(4672, acc = 512), flags(), status = 5)
  control(28, view(4672), flags(pair = 1), status = 5)
  // A failed replacement invalidates a formerly valid staged view.
  control(28, view(4672), flags())
  control(28, view(4672), flags() | 0x100, status = 6)
  configs(); reject()
  // xd=0 is consumed, invalidates staging, and produces no unsolicited reply.
  control(28, view(4672), flags())
  control(28, view(4672), flags(), xd = 0)
  configs(); reject()

  for ((js, ks, is, pk) <- Seq((2, 2, 2, 0), (0, 2, 1, 0),
                               (2, 0, 1, 0), (2, 2, 1, 16))) {
    configs(js, ks, is, pk)
    control(28, view(4672), flags())
    reject(5)
  }
  for ((b, d, out) <- Seq((1, 0, 0), (0, 1, 0), (0, 0, 1))) {
    configs(b = BigInt(b), d = BigInt(d), out = BigInt(out))
    control(28, view(4672), flags()); reject(5)
  }
  configs(a = 0); control(28, view(4672), flags()); reject(5)
  configs(); control(28, view(8176), flags()); reject(5)
  configs(); control(28, view(4672, acc = 496), flags()); reject(5)
  configs(); control(28, view(4672), flags()); reject(6, run(pair = 1))
  configs(); control(28, view(4672), flags()); reject(6, run() ^ (BigInt(1) << 61))
  configs(); control(28, view(4672), flags()); reject(6, run() | (BigInt(1) << 46))
  configs(); control(28, view(4672), flags()); reject(6, rs2 = 1)
  for (bit <- Seq(2, 4, 7, 8, 11, 15)) {
    configs(); control(28, view(4672), flags())
    reject(6, run() | (BigInt(1) << bit))
  }
  configs(); control(28, view(4672), flags()); reject(6, rs2 = BigInt(1) << 32)
  configs(j = 1, k = 29)
  control(28, view(4096, pitch = 16), flags())
  reject(5)
  configs(); control(28, view(4672), flags()); reject(5, rs2 = BigInt(255) << 3)
  configs(); control(28, view(4672), flags()); reject(5, run() | (BigInt(255) << 21))
  configs(); control(28, view(4672), flags(bank = 1)); reject(6, run(bank = 1))

  configs(); control(28, view(4672), flags())
  val noDestinationStart = emitted.size
  send(8, run(), xd = 0)
  control(28, view(4672), flags())
  reject()
  assert(emitted.size == noDestinationStart)

  // A queued run cannot mutate the slot while the view reply is blocked.
  val blockedRunStart = emitted.size
  configs(j = 1, k = 1)
  send(28, view(4672), flags(), xd = 1)
  until(peek(dut.io.resp.valid) == 1)
  send(8, run(), xd = 1, rd = 17)
  for (_ <- 0 until 4) {
    expect(dut.io.resp.valid, 1)
    expect(dut.io.resp.bits.rd, 11)
    expect(dut.io.generated.valid, 0)
    tick()
  }
  response(0)
  response(0, rd = 17)
  drain()
  check(blockedRunStart, 0, 4672, 0, indexed = false, js = 1, ks = 1)

  // Complete native runs on both pairs. Slot payload must survive command stalls.
  for (pair <- 0 until 2) {
    val start = emitted.size
    configs()
    control(28, view(4672 + pair * 8192), flags(pair))
    send(8, run(pair), xd = 1)
    response(0, stall = 4)
    drain()
    check(start, pair, 4672 + pair * 8192, 0, indexed = false)
  }

  // Two independently configured slots while the first A command is blocked.
  poke(dut.io.generated.ready, 0)
  val twoStart = emitted.size
  configs(j = 1, k = 1)
  control(28, view(4672), flags())
  send(8, run(), xd = 1); response(0)
  until(peek(dut.io.generated.valid) == 1)
  val heldA = peek(dut.io.generated.bits.cmd.rs2)
  configs(j = 1, k = 1)
  control(28, view(12864, acc = 96), flags(pair = 1))
  send(8, run(pair = 1), xd = 1); response(0)
  for (_ <- 0 until 4) {
    expect(dut.io.generated.valid, 1)
    expect(dut.io.generated.bits.cmd.rs2, heldA)
    tick()
  }
  // Third slot configuration queues, but cannot respond until a slot retires.
  send(28, view(4672), flags(), xd = 1, rd = 23)
  for (_ <- 0 until 4) { expect(dut.io.resp.valid, 0); tick() }
  poke(dut.io.generated.ready, 1)
  response(0, rd = 23)
  drain()
  val two = emitted.drop(twoStart).filter(_.funct == 6)
  assert(two.size == 2)
  assert(two.map(p => (p.rs1 & 0x3fff, p.rs2 & 0x3ff)).toSet ==
    Set((BigInt(4672), BigInt(80)), (BigInt(12864), BigInt(96))))
  assert(two.map(_.work / 64).toSet == Set(0, 1))

  // Legacy ignores a staged resident payload and consumes all completeness.
  val legacyStart = emitted.size
  configs(j = 1, k = 1)
  send(8, run(extended = false))
  drain()
  check(legacyStart, 0, 0, 0, indexed = false, js = 1, ks = 1, acc = 0, legacy = true)
  control(28, view(4672), flags()); reject()

  // FLUSH is accepted only after active generation; it cancels staging only.
  poke(dut.io.generated.ready, 0)
  val flushStart = emitted.size
  configs(j = 1, k = 1)
  control(28, view(4672), flags())
  send(8, run(), xd = 1); response(0)
  configs()
  control(28, view(12864), flags(pair = 1))
  send(7)
  for (_ <- 0 until 3) { expect(dut.io.generated.valid, 1); tick() }
  poke(dut.io.generated.ready, 1)
  drain()
  check(flushStart, 0, 4672, 0, indexed = false, js = 1, ks = 1)
  assert(emitted.last.funct == 7)
  reject()

  def indexConfig(pair: Int): Unit =
    control(27, BigInt(4160 + pair * 8192) | (BigInt(9) << 32),
      BigInt(448) | (BigInt(32) << 32))
  for (pair <- 0 until 2) {
    indexConfig(pair)
    for (slot <- 0 until 31) control(26, BigInt(slot), BigInt(48 + slot))
    expect(dut.io.complete(pair), 0)
    configs(j = 1, k = 1)
    control(28, view(4160 + pair * 8192, origin = 5), flags(pair, indexed = true))
    val beforeConsumed = consumed(pair)
    reject(5, run(pair, indexed = true))
    assert(consumed(pair) == beforeConsumed + 1)
    expect(dut.io.active(pair), 0); expect(dut.io.pending(pair), 0)
    // Refusal must release the busy guard so the missing write can proceed.
    control(26, 31, 79)
    expect(dut.io.complete(pair), 1)

    for (bank <- 0 until 2) {
      val start = emitted.size
      configs(padK = 3)
      control(28, view(4160 + pair * 8192, origin = 5), flags(pair, indexed = true, bank = bank))
      send(8, run(pair, indexed = true, bank = bank), xd = 1)
      response(0); drain()
      check(start, pair, 4160 + pair * 8192, bank, indexed = true, origin = 5)
    }
    // Full descriptor is retained; mismatched base/pitch/J extent refuse.
    for (v <- Seq(view(4176 + pair * 8192, origin = 5), view(4160 + pair * 8192, pitch = 128, origin = 5),
                  view(4160 + pair * 8192, origin = 8))) {
      configs()
      control(28, v, flags(pair, indexed = true))
      reject(5, run(pair, indexed = true))
    }
    configs(k = 3)
    control(28, view(4160 + pair * 8192, origin = 5), flags(pair, indexed = true))
    reject(5, run(pair, indexed = true))
    // Raw Index bit owns cancellation even when it disagrees with the view.
    configs(); control(28, view(4672 + pair * 8192), flags(pair))
    reject(6, run(pair, indexed = true))
    configs(); control(28, view(4160 + pair * 8192, origin = 5), flags(pair, indexed = true))
    reject(6, run(pair))
    // Reconfiguration invalidates every old Index entry.
    indexConfig(pair)
    expect(dut.io.complete(pair), 0)
    configs(); control(28, view(4160 + pair * 8192, origin = 5), flags(pair, indexed = true))
    reject(5, run(pair, indexed = true))
  }

  // Reset invalidates both slot transactions and both published Index windows.
  configs(); control(28, view(4672), flags())
  reset(2)
  expect(dut.io.complete(0), 0); expect(dut.io.complete(1), 0)
  reject()
}

class PoTalResidentLoopSpec extends ChiselFlatSpec {
  behavior of "Resident LoopMatmul admission"
  it should "consume complete slot transactions, refuse before effects, and preserve Index ownership" in {
    Driver.execute(Array("--backend-name", "treadle", "--target-dir",
      "test_run_dir/potal-resident-loop"), () => new PoTalResidentLoopHarness()) {
      dut => new PoTalResidentLoopTester(dut)
    } should be(true)
  }

  it should "refuse unsupported hardware without forwarding new opcodes or effects" in {
    Driver.execute(Array("--backend-name", "treadle", "--target-dir",
      "test_run_dir/potal-resident-unsupported"), () => new PoTalResidentLoopHarness(false)) {
      dut => new PeekPokeTester(dut) {
        poke(dut.io.cmd.valid, 0); poke(dut.io.resp.ready, 0)
        poke(dut.io.generated.ready, 1)
        reset(2)
        for ((funct, rs1, rs2) <- Seq((28, view(4672), flags()),
                                      (8, PoTalResidentTestSupport.run(), BigInt(0)))) {
          poke(dut.io.cmd.bits.funct, funct); poke(dut.io.cmd.bits.rs1, rs1)
          poke(dut.io.cmd.bits.rs2, rs2); poke(dut.io.cmd.bits.xd, 1)
          poke(dut.io.cmd.bits.rd, 9); poke(dut.io.cmd.valid, 1)
          expect(dut.io.cmd.ready, 1)
          step(1); poke(dut.io.cmd.valid, 0)
          var cycles = 0
          while (peek(dut.io.resp.valid) == 0 && cycles < 20) {
            expect(dut.io.generated.valid, 0)
            step(1); cycles += 1
          }
          expect(dut.io.resp.valid, 1)
          expect(dut.io.resp.bits.rd, 9); expect(dut.io.resp.bits.status, 7)
          expect(dut.io.generated.valid, 0)
          poke(dut.io.resp.ready, 1); step(1); poke(dut.io.resp.ready, 0)
        }
      }
    } should be(true)
  }
}
