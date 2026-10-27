package potal

import chisel3._
import chisel3.util._
import chisel3.iotesters._
import scala.collection.mutable.ArrayBuffer
import GemminiISA._
import PoTalResidentTestSupport._

// Actual ExecuteController, including its command queues, Index tables,
// reverse-row reads and Mesh tags. Ordered SRAM responses carry constant rows;
// this checks routing, not SCU arithmetic or final physical ACC completion.
class PoTalResidentConsumerHarness extends Module {
  private val c = config
  val io = IO(new Bundle {
    val cmd = Flipped(Decoupled(new PoTalScaleCommand))
    val indexed = Input(Bool())
    val pair = Input(UInt(1.W))
    val j = Input(UInt(16.W))
    val k = Input(UInt(16.W))
    val indexConfig = Flipped(Valid(new PoTalIndexConfig))
    val indexWrite = Flipped(Valid(new PoTalIndexWrite(32)))
    val readEnable = Input(Bool())
    val readValid = Output(Vec(4, Bool()))
    val readFire = Output(Vec(4, Bool()))
    val readRow = Output(Vec(4, UInt(12.W)))
    val lookup = Output(Vec(2, Bool()))
    val raw = Output(Vec(2, Bool()))
    val row = Output(Vec(2, UInt(9.W)))
    val busy = Output(Bool())
  })
  val execute = Module(new ExecuteController(64, 32, c))
  execute.io.cmd.valid := io.cmd.valid
  io.cmd.ready := execute.io.cmd.ready
  execute.io.cmd.bits := 0.U.asTypeOf(execute.io.cmd.bits)
  command(execute.io.cmd.bits.cmd, io.cmd.bits)
  execute.io.cmd.bits.rob_id.valid := true.B
  execute.io.cmd.bits.rob_id.bits := io.cmd.bits.rd
  execute.io.cmd.bits.potal.valid := io.cmd.bits.funct === PRELOAD_CMD
  execute.io.cmd.bits.potal.indexedWeight := io.indexed
  execute.io.cmd.bits.potal.weightBufferId := io.pair
  execute.io.cmd.bits.potal.indexedJTile := io.j
  execute.io.cmd.bits.potal.indexedKTile := io.k
  execute.io.potalIndexConfig := io.indexConfig
  execute.io.potalIndexWrite := io.indexWrite
  execute.io.im2col.req.ready := false.B
  execute.io.im2col.resp.valid := false.B
  execute.io.im2col.resp.bits := 0.U.asTypeOf(execute.io.im2col.resp.bits)
  execute.io.counter.external_reset := false.B
  for (bank <- 0 until 4) {
    val port = execute.io.srams.read(bank)
    val responses = Module(new Queue(new ScratchpadReadResp(128), 8))
    responses.io.enq.valid := port.req.valid && io.readEnable
    responses.io.enq.bits.data := BigInt("01" * 16, 16).U(128.W)
    responses.io.enq.bits.fromDMA := false.B
    port.req.ready := responses.io.enq.ready && io.readEnable
    port.resp <> responses.io.deq
    io.readValid(bank) := port.req.valid
    io.readFire(bank) := port.req.fire
    io.readRow(bank) := port.req.bits.addr
  }
  for (bank <- 0 until 2) {
    execute.io.acc.read_req(bank).ready := true.B
    execute.io.acc.read_resp(bank).valid := false.B
    execute.io.acc.read_resp(bank).bits := 0.U.asTypeOf(execute.io.acc.read_resp(bank).bits)
    execute.io.acc.write(bank).ready := true.B
    io.raw(bank) := execute.io.potalRawWriteValid(bank)
    io.row(bank) := execute.io.acc.write(bank).bits.addr
  }
  io.lookup := execute.io.indexedTableLookupActive
  io.busy := execute.io.busy
}

class PoTalResidentConsumerTester(dut: PoTalResidentConsumerHarness) extends PeekPokeTester(dut) {
  val reads = Array.fill(4)(ArrayBuffer.empty[Int])
  val writes = Array.fill(2)(ArrayBuffer.empty[Int])
  poke(dut.io.cmd.valid, 0)
  poke(dut.io.indexConfig.valid, 0); poke(dut.io.indexWrite.valid, 0)
  poke(dut.io.indexed, 0); poke(dut.io.pair, 0)
  poke(dut.io.j, 0); poke(dut.io.k, 0); poke(dut.io.readEnable, 1)
  reset(2)

  def tick(): Unit = {
    for (b <- 0 until 4) if (peek(dut.io.readFire(b)) == 1)
      reads(b) += peek(dut.io.readRow(b)).toInt
    for (b <- 0 until 2) if (peek(dut.io.raw(b)) == 1)
      writes(b) += peek(dut.io.row(b)).toInt
    step(1)
  }
  def until(condition: => Boolean): Unit = {
    var cycles = 0
    while (!condition && cycles < 4000) { tick(); cycles += 1 }
    assert(condition, "Execute routing handshake timed out")
  }
  def send(f: Int, a: BigInt, b: BigInt, rd: Int): Unit = {
    poke(dut.io.cmd.bits.funct, f); poke(dut.io.cmd.bits.rs1, a)
    poke(dut.io.cmd.bits.rs2, b); poke(dut.io.cmd.bits.rd, rd)
    poke(dut.io.cmd.bits.xd, 0); poke(dut.io.cmd.valid, 1)
    until(peek(dut.io.cmd.ready) == 1)
    tick(); poke(dut.io.cmd.valid, 0)
  }
  def matrix(address: BigInt, rows: Int = 16): BigInt =
    address | (BigInt(16) << 32) | (BigInt(rows) << 48)
  def source(pair: Int, slot: Int): Int = (slot * 29 + 3 + pair * 11) % 448

  for (pair <- 0 until 2) {
    val d = dut.io.indexConfig.bits
    poke(d.weightBufferId, pair); poke(d.bBase, 4160 + pair * 8192)
    poke(d.jTiles, 9); poke(d.sourceKRows, 448); poke(d.activeRows, 32)
    poke(dut.io.indexConfig.valid, 1); tick(); poke(dut.io.indexConfig.valid, 0)
    for (slot <- 0 until 32) {
      poke(dut.io.indexWrite.bits.weightBufferId, pair)
      poke(dut.io.indexWrite.bits.slot, slot)
      poke(dut.io.indexWrite.bits.sourceK, source(pair, slot))
      poke(dut.io.indexWrite.valid, 1); tick(); poke(dut.io.indexWrite.valid, 0)
    }
  }
  send(0, 0x10004, BigInt(1) << 48, 1)

  for (pair <- 0 until 2; bank <- 0 until 2; indexed <- Seq(false, true)) {
    reads.foreach(_.clear()); writes.foreach(_.clear())
    poke(dut.io.indexed, if (indexed) 1 else 0); poke(dut.io.pair, pair)
    poke(dut.io.j, 5); poke(dut.io.k, 1)
    poke(dut.io.readEnable, 0)
    val rows = if (bank == 0) 16 else 13
    val acc = 80 + bank * 512
    send(6, matrix(BigInt((if (indexed) 4160 else 4672) + pair * 8192), rows),
      matrix((BigInt(1) << 31) | BigInt(acc)), 2)
    // The unselected queued command deliberately has the wrong descriptor/J.
    poke(dut.io.indexed, 0); poke(dut.io.pair, 1 - pair)
    poke(dut.io.j, 0); poke(dut.io.k, 0)
    send(4, matrix(BigInt(pair * 8192)), matrix(BigInt("ffffffff", 16)), 3)
    val bBank = 2 * pair + 1
    until(peek(dut.io.readValid(bBank)) == 1)
    val firstSource = source(pair, 16 + rows - 1)
    val first = if (indexed) 64 + (firstSource / 16 * 9 + 5) * 16 + firstSource % 16
      else 576 + rows - 1
    for (_ <- 0 until 4) {
      expect(dut.io.lookup(pair), if (indexed) 1 else 0)
      expect(dut.io.lookup(1 - pair), 0)
      expect(dut.io.readRow(bBank), first)
      expect(dut.io.readFire(bBank), 0)
      tick()
    }
    poke(dut.io.readEnable, 1)
    until(writes(bank).size == 16)
    until(peek(dut.io.busy) == 0)
    val expected = (0 until rows).reverse.map { row =>
      val s = source(pair, 16 + row)
      if (indexed) 64 + (s / 16 * 9 + 5) * 16 + s % 16 else 576 + row
    }
    assert(reads(bBank).toSeq == expected)
    assert(reads(2 * (1 - pair) + 1).isEmpty)
    assert(writes(bank).toSeq == (80 until 96))
    assert(writes(1 - bank).isEmpty)
  }
}

// Actual RS dependencies, including issued-but-uncompleted Index readers.
class PoTalResidentHazardHarness extends Module {
  private val c = config
  val io = IO(new Bundle {
    val cmd = Flipped(Decoupled(new PoTalScaleCommand))
    val indexed = Input(Bool())
    val pair = Input(UInt(1.W))
    val complete = Flipped(Valid(UInt(c.ROB_ID_WIDTH.W)))
    val ldReady = Input(Bool())
    val exReady = Input(Bool())
    val stReady = Input(Bool())
    val ld = Output(Valid(UInt(c.ROB_ID_WIDTH.W)))
    val ex = Output(Valid(UInt(c.ROB_ID_WIDTH.W)))
    val st = Output(Valid(UInt(c.ROB_ID_WIDTH.W)))
    val readers = Output(Vec(2, Bool()))
  })
  val station = Module(new ReservationStation(c, new GemminiCmd(c.reservation_station_entries)))
  station.io.alloc.valid := io.cmd.valid
  io.cmd.ready := station.io.alloc.ready
  station.io.alloc.bits := 0.U.asTypeOf(station.io.alloc.bits)
  command(station.io.alloc.bits.cmd, io.cmd.bits)
  station.io.alloc.bits.potal.valid := io.cmd.bits.funct === PRELOAD_CMD
  station.io.alloc.bits.potal.indexedWeight := io.indexed
  station.io.alloc.bits.potal.weightBufferId := io.pair
  station.io.completed := io.complete
  station.io.counter.external_reset := false.B
  for (pair <- 0 until 2) {
    station.io.potalIndexConfiguredByBuffer(pair) := true.B
    val d = station.io.potalIndexDescriptors(pair)
    d.bBase := (4160 + pair * 8192).U
    d.jTiles := 9.U; d.sourceKRows := 448.U; d.activeRows := 32.U
    d.weightBufferId := pair.U
  }
  station.io.issue.ld.ready := io.ldReady
  station.io.issue.ex.ready := io.exReady
  station.io.issue.st.ready := io.stReady
  io.ld.valid := station.io.issue.ld.valid; io.ld.bits := station.io.issue.ld.rob_id
  io.ex.valid := station.io.issue.ex.valid; io.ex.bits := station.io.issue.ex.rob_id
  io.st.valid := station.io.issue.st.valid; io.st.bits := station.io.issue.st.rob_id
  io.readers := station.io.indexedPreloadOutstanding
}

class PoTalResidentHazardTester(dut: PoTalResidentHazardHarness) extends PeekPokeTester(dut) {
  poke(dut.io.cmd.valid, 0); poke(dut.io.complete.valid, 0)
  poke(dut.io.ldReady, 0); poke(dut.io.exReady, 0); poke(dut.io.stReady, 0)
  poke(dut.io.indexed, 0); poke(dut.io.pair, 0)

  def allocate(f: Int, a: BigInt, b: BigInt): Unit = {
    poke(dut.io.cmd.bits.funct, f); poke(dut.io.cmd.bits.rs1, a)
    poke(dut.io.cmd.bits.rs2, b); poke(dut.io.cmd.bits.rd, 0)
    poke(dut.io.cmd.bits.xd, 0); poke(dut.io.cmd.valid, 1)
    expect(dut.io.cmd.ready, 1)
    step(1); poke(dut.io.cmd.valid, 0)
  }
  def complete(id: BigInt): Unit = {
    poke(dut.io.complete.bits, id); poke(dut.io.complete.valid, 1)
    step(1); poke(dut.io.complete.valid, 0)
  }
  def setup(): Unit = {
    reset(2)
    allocate(0, 0x10004, BigInt(1) << 48)
    expect(dut.io.ex.valid, 1)
    val ex = peek(dut.io.ex.bits)
    poke(dut.io.exReady, 1); step(1); poke(dut.io.exReady, 0)
    complete(ex)
    allocate(0, 0x100101, 0)
    expect(dut.io.ld.valid, 1)
    poke(dut.io.ldReady, 1); step(1); poke(dut.io.ldReady, 0)
    allocate(0, 2, 0)
    expect(dut.io.st.valid, 1)
    poke(dut.io.stReady, 1); step(1); poke(dut.io.stReady, 0)
  }
  def matrix(address: BigInt): BigInt =
    address | (BigInt(16) << 32) | (BigInt(16) << 48)

  for (pair <- 0 until 2; indexed <- Seq(false, true); bank <- 0 until 2) {
    val base = 4672 + pair * 8192
    val acc = (BigInt(1) << 31) | BigInt(bank * 512 + 80)
    // Native hazards cover the actual tile; Index hazards cover the full panel.
    for ((address, blocked) <- Seq(
      (base, true), (8176 + pair * 8192, indexed),
      (4672 + (1 - pair) * 8192, false))) {
      setup()
      poke(dut.io.indexed, if (indexed) 1 else 0); poke(dut.io.pair, pair)
      allocate(6, matrix(BigInt(base)), matrix(acc))
      expect(dut.io.readers(pair), if (indexed) 1 else 0)
      allocate(2, BigInt("80000000", 16), matrix(BigInt(address)))
      expect(dut.io.ld.valid, if (blocked) 0 else 1)
      expect(dut.io.ex.valid, 1)
      val id = peek(dut.io.ex.bits)
      poke(dut.io.exReady, 1); step(1); poke(dut.io.exReady, 0)
      expect(dut.io.readers(pair), if (indexed) 1 else 0)
      expect(dut.io.ld.valid, if (blocked) 0 else 1)
      complete(id)
      expect(dut.io.readers(pair), 0)
      expect(dut.io.ld.valid, 1)
    }
    // Readback from the effective ACC tile waits; another tile/bank does not.
    for ((address, blocked) <- Seq(
      (acc, true), ((BigInt(1) << 31) | BigInt(bank * 512), false),
      ((BigInt(1) << 31) | BigInt((1 - bank) * 512 + 80), false))) {
      setup()
      poke(dut.io.indexed, if (indexed) 1 else 0); poke(dut.io.pair, pair)
      allocate(6, matrix(BigInt(base)), matrix(acc))
      allocate(3, BigInt("80000000", 16), matrix(address))
      expect(dut.io.st.valid, if (blocked) 0 else 1)
      val id = peek(dut.io.ex.bits)
      poke(dut.io.exReady, 1); step(1); poke(dut.io.exReady, 0)
      expect(dut.io.st.valid, if (blocked) 0 else 1)
      complete(id)
      expect(dut.io.st.valid, 1)
    }
  }
}

class PoTalResidentConsumerSpec extends ChiselFlatSpec {
  behavior of "Resident Index and ACC consumers"
  it should "read native and full-panel indexed J through actual Execute and route both ACC banks" in {
    Driver.execute(Array("--backend-name", "treadle", "--target-dir",
      "test_run_dir/potal-resident-consumer"), () => new PoTalResidentConsumerHarness) {
      dut => new PoTalResidentConsumerTester(dut)
    } should be(true)
  }
  it should "retain actual native, full-panel Index, and effective ACC dependencies in RS" in {
    // RS uses the real plusarg_reader timeout. Treadle has no implementation
    // for that black box and substitutes zero; Verilator executes its vsrc.
    Driver.execute(Array("--backend-name", "verilator", "--target-dir",
      "test_run_dir/potal-resident-hazards"), () => new PoTalResidentHazardHarness) {
      dut => new PoTalResidentHazardTester(dut)
    } should be(true)
  }
}
