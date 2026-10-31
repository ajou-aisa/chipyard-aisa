package potal

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import chisel3.iotesters._
import freechips.rocketchip.tile.RoCCResponse
import Arithmetic.SIntArithmetic
import PoTalResidentTestSupport._

class PoTalPrefetchTag extends Bundle with TagQueueTag {
  val garbage = Bool()
  def make_this_garbage(dummy: Int = 0): Unit = { garbage := true.B }
}

// Exercise the real DIM32 buffering, mesh registers, stationary PE registers,
// MAC and counter decoder. The write ports use real masked SRAM banks.
// The DMA/zero/Execute selector models Scratchpad's already-selected source;
// it does not emulate TileLink or prove DMA completion.
class PoTalPrefetchOverlapHarness(latency: Int, supported: Boolean, resident: Boolean = false)
    extends Module {
  val config = PoTalConfigs.a8w8D32Sat32
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new MeshWithDelaysReq(
      SInt(32.W), new PoTalPrefetchTag, 32, true)))
    val a = Flipped(Decoupled(Vec(32, SInt(8.W))))
    val b = Flipped(Decoupled(Vec(32, SInt(8.W))))
    val d = Flipped(Decoupled(Vec(32, SInt(8.W))))
    val aCompute = Input(Bool())
    val aGarbage = Input(Bool())
    val aCols = Input(UInt(6.W))
    val aBank = Input(UInt(2.W))
    val aFromAcc = Input(Bool())
    val im2col = Input(Bool())
    val dGarbage = Input(Bool())
    val dZero = Input(Bool())
    val dCols = Input(UInt(6.W))
    val dBank = Input(UInt(2.W))
    val dFromAcc = Input(Bool())
    val mac = Output(Valid(Bool()))
    val macStream = Output(new PoTalStreamTag)
    val macValue = Output(SInt(21.W))
    val writeSource = Input(UInt(2.W)) // 0 idle, 1 DMA, 2 zero-fill, 3 Execute
    val writeBank = Input(UInt(2.W))
    val writeMask = Input(UInt(32.W))
    val readBank = Input(UInt(2.W))
    val readRequest = Input(Bool())
    val readReady = Output(Bool())
    val readData = Output(Valid(UInt(256.W)))
    val cmd = Flipped(Decoupled(new PoTalScaleCommand))
    val resp = Decoupled(new RoCCResponse)
  })
  val mesh = Module(new MeshWithDelays(SInt(8.W), SInt(21.W), SInt(32.W),
    new PoTalPrefetchTag, Dataflow.WS, false, latency, 1,
    1, 1, 32, 32, 1, 1, observePrefetch = true))
  mesh.io.req <> io.req
  mesh.io.a.valid := io.a.valid
  mesh.io.a.bits := io.a.bits.asTypeOf(mesh.io.a.bits)
  io.a.ready := mesh.io.a.ready
  mesh.io.b.valid := io.b.valid
  mesh.io.b.bits := io.b.bits.asTypeOf(mesh.io.b.bits)
  io.b.ready := mesh.io.b.ready
  mesh.io.d.valid := io.d.valid
  mesh.io.d.bits := io.d.bits.asTypeOf(mesh.io.d.bits)
  io.d.ready := mesh.io.d.ready
  mesh.io.aObservation.get.valid := PoTalOperandObservation.aUseful(
    io.aCompute, io.aGarbage, io.aCols, io.aFromAcc, io.im2col, io.aBank)
  mesh.io.aObservation.get.bits := io.aBank(1)
  mesh.io.dObservation.get.valid := PoTalOperandObservation.dUseful(
    io.dGarbage, io.dZero, io.dCols, io.dFromAcc, io.dBank)
  mesh.io.dObservation.get.bits := io.dBank(1)
  io.mac := mesh.io.qualifiedMac.get
  io.macStream := mesh.io.qualifiedMacStream.get
  // Observe the same combinational product whose validity the monitor reports.
  io.macValue := 0.S
  BoringUtils.bore(mesh.mesh.mesh(0)(0).tile(0)(0).mac_unit.io.out_d, Seq(io.macValue))

  val banks = Seq.fill(4)(Module(new ScratchpadBank(8, 256, 1, false, false, false)))
  for ((bank, index) <- banks.zipWithIndex) {
    bank.io.read.req.valid := io.readRequest && io.readBank === index.U
    bank.io.read.req.bits := 0.U.asTypeOf(bank.io.read.req.bits)
    bank.io.read.resp.ready := true.B
    bank.io.write.en := io.writeSource =/= 0.U && io.writeBank === index.U
    bank.io.write.addr := 0.U
    bank.io.write.data := Mux(io.writeSource === 2.U, 0.U, Fill(32, 7.U(8.W)))
    bank.io.write.mask := io.writeMask.asTypeOf(bank.io.write.mask)
  }
  io.readReady := VecInit(banks.map(_.io.read.req.ready))(io.readBank)
  io.readData.valid := VecInit(banks.map(_.io.read.resp.valid))(io.readBank)
  io.readData.bits := VecInit(banks.map(_.io.read.resp.bits.data))(io.readBank)
  val counter = Module(new CounterController(8, 64, resident, supported))
  counter.io.in.valid := io.cmd.valid
  io.cmd.ready := counter.io.in.ready
  command(counter.io.in.bits, io.cmd.bits)
  io.resp <> counter.io.out
  CounterEventIO.init(counter.io.event_io)
  val bWrites = Seq(1, 3).map { index =>
    PoTalPrefetchObservation.bWrite(
      io.writeSource === 1.U && banks(index).io.write.en,
      banks(index).io.write.mask.asUInt)
  }
  PoTalPrefetchObservation.connect(counter.io.event_io,
    bWrites(0), bWrites(1), mesh.io.qualifiedMac.get)
}

class PoTalPrefetchOverlapTester(dut: PoTalPrefetchOverlapHarness, latency: Int,
                                supported: Boolean, resident: Boolean = false)
    extends PeekPokeTester(dut) {
  val allMask = (BigInt(1) << 32) - 1
  val sevens = BigInt("07" * 32, 16)

  def defaults(): Unit = {
    poke(dut.io.req.valid, 0)
    poke(dut.io.req.bits.pe_control.dataflow, 1)
    poke(dut.io.req.bits.pe_control.propagate, 0)
    poke(dut.io.req.bits.pe_control.shift, 0)
    poke(dut.io.req.bits.a_transpose, 0)
    poke(dut.io.req.bits.bd_transpose, 0)
    poke(dut.io.req.bits.total_rows, 1)
    poke(dut.io.req.bits.tag.garbage, 0)
    poke(dut.io.req.bits.flush, 0)
    poke(dut.io.req.bits.computeUseful.get, 0)
    poke(dut.io.req.bits.computeStream.get.valid, 0)
    poke(dut.io.req.bits.computeStream.get.id, 0)
    poke(dut.io.req.bits.computeStream.get.serial, 0)
    poke(dut.io.req.bits.computeStream.get.pair, 0)
    poke(dut.io.req.bits.computeStream.get.generation, 0)
    poke(dut.io.a.valid, 0); poke(dut.io.b.valid, 0); poke(dut.io.d.valid, 0)
    for (i <- 0 until 32) {
      poke(dut.io.a.bits(i), 5)
      poke(dut.io.b.bits(i), 0)
      poke(dut.io.d.bits(i), 7)
    }
    poke(dut.io.aCompute, 0); poke(dut.io.aGarbage, 0); poke(dut.io.aCols, 32)
    poke(dut.io.aBank, 0); poke(dut.io.aFromAcc, 0); poke(dut.io.im2col, 0)
    poke(dut.io.dGarbage, 0); poke(dut.io.dZero, 0); poke(dut.io.dCols, 32)
    poke(dut.io.dBank, 1); poke(dut.io.dFromAcc, 0)
    poke(dut.io.writeSource, 0); poke(dut.io.writeBank, 0); poke(dut.io.writeMask, allMask)
    poke(dut.io.readBank, 0); poke(dut.io.readRequest, 0)
    poke(dut.io.cmd.valid, 0); poke(dut.io.resp.ready, 0)
    poke(dut.io.cmd.bits.funct, 126); poke(dut.io.cmd.bits.rs1, 0)
    poke(dut.io.cmd.bits.rs2, 0); poke(dut.io.cmd.bits.rd, 13); poke(dut.io.cmd.bits.xd, 1)
  }

  def access(rs1: BigInt, rs2: BigInt = 0, funct: Int = 126, xd: Int = 1): BigInt = {
    expect(dut.io.cmd.ready, 1)
    poke(dut.io.cmd.bits.rs1, rs1); poke(dut.io.cmd.bits.rs2, rs2)
    poke(dut.io.cmd.bits.funct, funct); poke(dut.io.cmd.bits.xd, xd)
    poke(dut.io.cmd.valid, 1)
    step(1)
    poke(dut.io.cmd.valid, 0)
    expect(dut.io.resp.valid, 1); expect(dut.io.resp.bits.rd, 13)
    val result = peek(dut.io.resp.bits.data)
    // Explicit response backpressure, not a timed wait for an async result.
    poke(dut.io.cmd.bits.rs1, 1)
    step(2)
    expect(dut.io.resp.valid, 1); expect(dut.io.resp.bits.data, result)
    poke(dut.io.resp.ready, 1); step(1); poke(dut.io.resp.ready, 0)
    result
  }

  def setup(): Unit = {
    defaults()
    reset(3)
    for (i <- 0 until 7) access(BigInt((45 + i) << 12) | (i << 4) | 8)
    // SRAM contents do not reset. Initialize all banks through real zero-fill
    // writes; none may increment a DMA-source event.
    poke(dut.io.writeSource, 2)
    for (bank <- 0 until 4) { poke(dut.io.writeBank, bank); step(1) }
    poke(dut.io.writeSource, 0)
    access(1)
  }

  def readBank(bank: Int, expected: BigInt): Unit = {
    poke(dut.io.readBank, bank); poke(dut.io.readRequest, 1)
    expect(dut.io.readReady, 1)
    step(1)
    poke(dut.io.readRequest, 0)
    expect(dut.io.readData.valid, 1)
    expect(dut.io.readData.bits, expected)
    step(1)
    expect(dut.io.readData.valid, 0)
  }

  def counts(expected: Seq[Int]): Unit =
    expected.zipWithIndex.foreach { case (value, i) =>
      assert(access(i << 4) == BigInt(value), s"counter selector ${45 + i}")
    }

  // One row through the real independent operand holding registers.
  // Live request and operand metadata are deliberately changed after acceptance.
  def row(compute: Boolean, pair: Int, flip: Int, skew: Boolean = false,
          aGarbage: Boolean = false, aCols: Int = 32, flush: Boolean = false,
          replaceOnLaunch: Boolean = false): Unit = {
    poke(dut.io.req.bits.computeUseful.get, if (compute) 1 else 0)
    poke(dut.io.req.bits.computeStream.get.valid, if (compute) 1 else 0)
    poke(dut.io.req.bits.computeStream.get.id, 6 + pair)
    poke(dut.io.req.bits.computeStream.get.serial, 101 + pair)
    poke(dut.io.req.bits.computeStream.get.pair, pair)
    poke(dut.io.req.bits.computeStream.get.generation, 251 + pair)
    poke(dut.io.req.bits.pe_control.propagate, flip)
    poke(dut.io.req.bits.flush, if (flush) 1 else 0)
    poke(dut.io.req.valid, 1)
    expect(dut.io.req.ready, 1)
    step(1)
    poke(dut.io.req.valid, 0)
    poke(dut.io.req.bits.computeUseful.get, if (compute) 0 else 1)
    poke(dut.io.req.bits.computeStream.get.valid, 0)
    poke(dut.io.req.bits.computeStream.get.serial, 999)
    poke(dut.io.req.bits.computeStream.get.pair, 1 - pair)
    if (!flush) {
      poke(dut.io.aCompute, if (compute) 1 else 0)
      poke(dut.io.aBank, pair * 2)
      poke(dut.io.aGarbage, if (aGarbage) 1 else 0)
      poke(dut.io.aCols, aCols)
      poke(dut.io.a.valid, 1)
      expect(dut.io.a.ready, 1)
      step(1)
      poke(dut.io.a.valid, 0)
      // This unrelated current pair/operation must not change buffered A.
      poke(dut.io.aBank, (1 - pair) * 2)
      poke(dut.io.aCompute, 0)
      poke(dut.io.a.bits(0), 11)
      if (skew) {
        expect(dut.io.a.ready, 0)
        expect(dut.io.mac.valid, 0)
        // A second offered operand is backpressured and must not overwrite A.
        poke(dut.io.a.valid, 1)
        // A remains full while independent B and D arrive on different edges.
        poke(dut.io.b.valid, 1); step(1); poke(dut.io.b.valid, 0)
        expect(dut.io.a.ready, 0); expect(dut.io.mac.valid, 0)
      } else {
        poke(dut.io.b.valid, 1)
      }
      poke(dut.io.dBank, pair * 2 + 1)
      poke(dut.io.dGarbage, if (compute) 1 else 0)
      poke(dut.io.d.valid, 1)
      expect(dut.io.d.ready, 1)
      step(1)
      poke(dut.io.a.valid, 0); poke(dut.io.b.valid, 0); poke(dut.io.d.valid, 0)
      poke(dut.io.dBank, (1 - pair) * 2 + 1)
      poke(dut.io.dGarbage, 1)
      poke(dut.io.d.bits(0), 13)
    }
    if (replaceOnLaunch) {
      // Accept a different request and new operands on the same edge that
      // launches the old buffered row. It is a preload for the opposite pair.
      poke(dut.io.req.valid, 1)
      poke(dut.io.req.bits.computeUseful.get, 0)
      poke(dut.io.req.bits.pe_control.propagate, 0)
      poke(dut.io.a.valid, 1); poke(dut.io.b.valid, 1); poke(dut.io.d.valid, 1)
      poke(dut.io.dGarbage, 0)
      expect(dut.io.req.ready, 1); expect(dut.io.a.ready, 1)
    }
    // At this point a row is launched. The first physical PE sees it after
    // tile_latency+1 clock edges; the counter samples the following edge.
    for (cycle <- 0 until latency + 1) {
      expect(dut.io.mac.valid, 0)
      step(1)
      if (replaceOnLaunch && cycle == 0) {
        poke(dut.io.req.valid, 0)
        poke(dut.io.a.valid, 0); poke(dut.io.b.valid, 0); poke(dut.io.d.valid, 0)
      }
    }
    if (peek(dut.io.mac.valid) == 1) {
      expect(dut.io.macStream.valid, 1)
      expect(dut.io.macStream.id, 6 + pair)
      expect(dut.io.macStream.serial, 101 + pair)
      expect(dut.io.macStream.pair, pair)
      expect(dut.io.macStream.generation, 251 + pair)
    }
  }

  setup()
  row(compute = false, pair = 0, flip = 0); step(1)
  poke(dut.io.a.bits(0), 5)
  row(compute = true, pair = 0, flip = 1, replaceOnLaunch = true)
  expect(dut.io.mac.valid, 1); expect(dut.io.mac.bits, 0); expect(dut.io.macValue, 35)
  poke(dut.io.writeBank, 3); poke(dut.io.writeSource, 1); step(1)
  poke(dut.io.writeSource, 0)
  // The replacement preload reaches the PE one cycle behind the old compute.
  expect(dut.io.mac.valid, 0); step(1)
  poke(dut.io.a.bits(0), 5)
  row(compute = true, pair = 1, flip = 1)
  expect(dut.io.mac.valid, 1); expect(dut.io.mac.bits, 1); expect(dut.io.macValue, 65)
  poke(dut.io.writeBank, 1); poke(dut.io.writeSource, 1); step(1)
  poke(dut.io.writeSource, 0)
  counts(Seq(1, 1, 1, 1, 1, 1, 2))

  setup()
  assert(access(PoTalPrefetchCapability.Query) ==
    (if (supported) PoTalPrefetchCapability.Signature else BigInt(0)))
  assert(access(PoTalResidentCapability.Query) ==
    (if (resident) PoTalResidentCapability.Signature else BigInt(0)))
  assert(access(PoTalPrefetchCapability.Query, rs2 = 1) == 0)
  assert(access(PoTalPrefetchCapability.Query, funct = 125) == 0)
  assert(access(PoTalPrefetchCapability.Query, xd = 0) == 0)
  assert(access(PoTalPrefetchCapability.Query ^ (BigInt(1) << 40)) == 0)
  counts(Seq.fill(7)(0))

  for (pair <- 0 until 2) {
    setup()
    row(compute = false, pair = pair, flip = 0)
    expect(dut.io.mac.valid, 0); step(1)
    poke(dut.io.a.bits(0), 5)
    row(compute = true, pair = pair, flip = 1, skew = true)
    expect(dut.io.mac.valid, 1); expect(dut.io.mac.bits, pair)
    expect(dut.io.macValue, 35)
    poke(dut.io.writeBank, (1 - pair) * 2 + 1)
    // A high mask bit alone is still a physical write, not an empty mask.
    if (pair == 1) poke(dut.io.writeMask, BigInt(1) << 31)
    poke(dut.io.writeSource, 1)
    step(1)
    poke(dut.io.writeSource, 0)
    expect(dut.io.mac.valid, 0)
    val expected = Array.fill(7)(0)
    expected(1 - pair) = 1; expected(2 + pair) = 1
    expected(4 + pair) = 1; expected(6) = 1
    counts(expected.toSeq)
    readBank((1 - pair) * 2 + 1, if (pair == 0) sevens else BigInt(7) << 248)
    // Snapshot holds across later B writes, then exposes the live count.
    access(4)
    poke(dut.io.writeSource, 1); step(1); poke(dut.io.writeSource, 0)
    counts(expected.toSeq)
    access(2)
    expected(1 - pair) += 1
    counts(expected.toSeq)
    // Capability access is side-effect-free with nonzero live counters.
    assert(access(PoTalPrefetchCapability.Query) ==
      (if (supported) PoTalPrefetchCapability.Signature else BigInt(expected(0))))
    counts(expected.toSeq)
    access(1); counts(Seq.fill(7)(0))
  }

  // No cross-pair count for separated cycles, same-pair DMA, empty masks,
  // A-bank DMA, zero-fill, or Execute SRAM writes.
  for (kind <- Seq("separated", "same", "mask", "a-bank", "zero", "execute")) {
    setup()
    row(compute = false, pair = 0, flip = 0); step(1)
    poke(dut.io.a.bits(0), 5)
    row(compute = true, pair = 0, flip = 1)
    expect(dut.io.mac.valid, 1); expect(dut.io.macValue, 35)
    if (kind == "separated") step(1)
    poke(dut.io.writeBank, if (kind == "same") 1 else if (kind == "a-bank") 2 else 3)
    poke(dut.io.writeSource, if (kind == "zero") 2 else if (kind == "execute") 3 else 1)
    poke(dut.io.writeMask, if (kind == "mask") BigInt(0) else allMask)
    step(1); poke(dut.io.writeSource, 0)
    counts(Seq(if (kind == "same") 1 else 0, if (kind == "separated") 1 else 0, 1, 0, 0, 0, 0))
    readBank(if (kind == "same") 1 else if (kind == "a-bank") 2 else 3,
      if (kind == "mask" || kind == "zero") BigInt(0) else sevens)
  }

  for (kind <- Seq("preload", "flush", "padding", "garbage", "weight-zero",
                  "pair-mismatch", "transpose-a", "transpose-d", "acc-a", "im2col")) {
    setup()
    if (kind == "weight-zero") poke(dut.io.dZero, 1)
    row(compute = false, pair = 0, flip = 0); step(1)
    poke(dut.io.a.bits(0), 5)
    if (kind == "transpose-a") poke(dut.io.req.bits.a_transpose, 1)
    if (kind == "transpose-d") poke(dut.io.req.bits.bd_transpose, 1)
    if (kind == "acc-a") poke(dut.io.aFromAcc, 1)
    if (kind == "im2col") poke(dut.io.im2col, 1)
    row(compute = kind != "preload", pair = if (kind == "pair-mismatch") 1 else 0,
      flip = 1, aGarbage = kind == "garbage", aCols = if (kind == "padding") 0 else 32,
      flush = kind == "flush")
    expect(dut.io.mac.valid, 0)
    poke(dut.io.writeBank, 3); poke(dut.io.writeSource, 1)
    step(1); poke(dut.io.writeSource, 0)
    counts(Seq(0, 1, 0, 0, 0, 0, 0))
  }
}

class PoTalPrefetchOverlapSpec extends ChiselFlatSpec {
  behavior of "DIM32 physical prefetch observation"
  for ((latency, supported, resident) <- Seq((0, true, false), (2, true, false), (0, false, true))) {
    it should s"track buffered operands and decode counters ($latency,$supported,$resident)" in {
      Driver.execute(Array("--backend-name", "verilator", "--target-dir",
        s"test_run_dir/potal-prefetch-$latency-$supported-$resident"),
        () => new PoTalPrefetchOverlapHarness(latency, supported, resident)) {
        dut => new PoTalPrefetchOverlapTester(dut, latency, supported, resident)
      } should be(true)
    }
  }
  it should "advertise only the opt-in physical DIM32 contract" in {
    val config = PoTalConfigs.a8w8D32Sat32
    PoTalPrefetchCapability.supported(config, 64, true) should be(true)
    PoTalPrefetchCapability.supported(config, 64, false) should be(false)
    PoTalPrefetchCapability.supported(config, 32, true) should be(false)
    PoTalPrefetchCapability.supported(PoTalConfigs.a8w8D16Sat32, 64, true) should be(false)
    PoTalPrefetchCapability.supported(config.copy(clock_gate = true), 64, true) should be(false)
    PoTalResidentCapability.supported(config, 64) should be(false)
    PoTalResidentCapability.supported(PoTalConfigs.a8w8D16Sat32, 64) should be(true)
    (new PoTalSat32D32GemminiConfig)(PoTalPrefetchObservationEnabled) should be(false)
    (new PoTalSat32D32PrefetchObservationGemminiConfig)(PoTalPrefetchObservationEnabled) should be(true)
  }
}
