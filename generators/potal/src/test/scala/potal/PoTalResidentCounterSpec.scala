package potal

import chisel3._
import chisel3.util._
import chisel3.iotesters._
import freechips.rocketchip.tile.RoCCResponse
import PoTalResidentTestSupport._

class PoTalResidentCounterHarness(supported: Boolean) extends Module {
  val io = IO(new Bundle {
    val cmd = Flipped(Decoupled(new PoTalScaleCommand))
    val resp = Decoupled(new RoCCResponse)
    val event = Input(Bool())
    val external = Input(UInt(32.W))
    val externalReset = Output(Bool())
  })
  val counter = Module(new CounterController(8, 64, supported))
  counter.io.in.valid := io.cmd.valid
  io.cmd.ready := counter.io.in.ready
  command(counter.io.in.bits, io.cmd.bits)
  io.resp <> counter.io.out
  counter.io.event_io.event_signal := VecInit(Seq.fill(CounterEvent.n)(false.B))
  counter.io.event_io.event_signal(1) := io.event
  counter.io.event_io.external_values := VecInit(Seq.fill(CounterExternal.n)(0.U(32.W)))
  counter.io.event_io.external_values(1) := io.external
  io.externalReset := counter.io.event_io.external_reset
}

class PoTalResidentCounterTester(dut: PoTalResidentCounterHarness, supported: Boolean)
  extends PeekPokeTester(dut) {
  poke(dut.io.cmd.valid, 0)
  poke(dut.io.resp.ready, 0)
  poke(dut.io.event, 0)
  poke(dut.io.external, 123)
  reset(2)

  def access(rs1: BigInt, rs2: BigInt = 0, xd: Int = 1, funct: Int = 126,
             expected: Option[BigInt] = None): BigInt = {
    expect(dut.io.cmd.ready, 1)
    poke(dut.io.cmd.bits.funct, funct)
    poke(dut.io.cmd.bits.rs1, rs1); poke(dut.io.cmd.bits.rs2, rs2)
    poke(dut.io.cmd.bits.xd, xd); poke(dut.io.cmd.bits.rd, 13)
    poke(dut.io.cmd.valid, 1)
    expect(dut.io.externalReset, 0)
    step(1)
    expect(dut.io.cmd.ready, 0)
    expect(dut.io.resp.valid, 1)
    expect(dut.io.resp.bits.rd, 13)
    val data = peek(dut.io.resp.bits.data)
    expected.foreach(value => expect(dut.io.resp.bits.data, value))
    // Hold a different live request while the response is blocked.
    poke(dut.io.cmd.bits.rs1, 1)
    poke(dut.io.cmd.bits.rd, 29)
    for (_ <- 0 until 4) {
      expect(dut.io.externalReset, 0)
      step(1)
      expect(dut.io.resp.valid, 1)
      expect(dut.io.resp.bits.rd, 13)
      expect(dut.io.resp.bits.data, data)
      expect(dut.io.cmd.ready, 0)
    }
    poke(dut.io.cmd.valid, 0)
    poke(dut.io.resp.ready, 1); step(1); poke(dut.io.resp.ready, 0)
    expect(dut.io.resp.valid, 0)
    data
  }

  // Counter1 counts real events and must survive the capability setup.
  access(0x1018)
  poke(dut.io.event, 1); step(7); poke(dut.io.event, 0)
  access(0x10, expected = Some(7))
  // Counter0 initially external, and all reads use a stale snapshot.
  access((BigInt(1) << 31) | 0x1008)
  access(4)
  poke(dut.io.external, 456)
  access(0, expected = Some(123))
  access(0xA) // Discard the old sampled value, exactly as the CPU must.
  access(PoTalResidentCapability.Query,
    expected = Some(if (supported) PoTalResidentCapability.Signature else BigInt(0)))
  access(0, expected = Some(0))
  access(0x10, expected = Some(7))
  // A later event is visible: setup cleared global snapshot-read mode.
  poke(dut.io.event, 1); step(1); poke(dut.io.event, 0)
  access(0x10, expected = Some(8))
  access(PoTalResidentCapability.Query ^ (BigInt(1) << 40), expected = Some(0))
  access(PoTalResidentCapability.Query, rs2 = 1, expected = Some(0))
  access(PoTalResidentCapability.Query, xd = 0, expected = Some(0))
  access(PoTalResidentCapability.Query, funct = 125, expected = Some(0))
}

class PoTalResidentCounterSpec extends ChiselFlatSpec {
  behavior of "Resident capability counter"
  for (supported <- Seq(false, true)) {
    it should s"preserve canonical counters and recognize only the exact supported query ($supported)" in {
      Driver.execute(Array("--backend-name", "treadle", "--target-dir",
        s"test_run_dir/potal-resident-counter-$supported"),
        () => new PoTalResidentCounterHarness(supported)) {
        dut => new PoTalResidentCounterTester(dut, supported)
      } should be(true)
    }
  }

  it should "advertise only the fixed complete geometry" in {
    PoTalResidentCapability.supported(config, 64) should be(true)
    PoTalResidentCapability.supported(config, 32) should be(false)
    PoTalResidentCapability.supported(config.copy(num_counter = 0), 64) should be(false)
    PoTalResidentCapability.supported(config.copy(enablePoTalSat32Acc = false), 64) should be(false)
    PoTalResidentCapability.supported(config.copy(potalScaleEntries = 128), 64) should be(false)
    PoTalResidentCapability.supported(config.copy(potalIndexWindowKTiles = 1), 64) should be(false)
    PoTalResidentCapability.supported(config.copy(sp_capacity = CapacityInKilobytes(128)), 64) should be(false)
    PoTalResidentCapability.supported(config.copy(acc_capacity = CapacityInKilobytes(32)), 64) should be(false)
    PoTalResidentCapability.supported(config.copy(ex_read_from_spad = false), 64) should be(false)
    PoTalResidentCapability.supported(PoTalConfigs.a8w8Sat32(32), 64) should be(false)
    PoTalResidentCapability.supported(PoTalConfigs.a8w8Sat32(64), 64) should be(false)
  }
}
