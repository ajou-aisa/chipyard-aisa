package potal

import chisel3._
import chisel3.iotesters._

// Reproduce the nested dynamic access emitted by ExecuteController before FireSim lowering.
class PoTalIndexedLookupLoweringHarness(materializeSelector: Boolean) extends Module {
  val io = IO(new Bundle {
    val commands = Input(Vec(3, new Bundle {
      val weightBufferId = UInt(1.W)
    }))
    val place = Input(UInt(1.W))
    val slot = Input(UInt(5.W))
    val tables = Input(Vec(2, Vec(32, UInt(32.W))))
    val source = Output(UInt(32.W))
  })
  if (materializeSelector) {
    val buffer = Wire(UInt(1.W))
    buffer := io.commands(io.place).weightBufferId
    io.source := io.tables(buffer)(io.slot)
  } else {
    io.source := io.tables(io.commands(io.place).weightBufferId)(io.slot)
  }
}

class PoTalIndexedLookupLoweringTester(
  dut: PoTalIndexedLookupLoweringHarness,
  materialized: Boolean
) extends PeekPokeTester(dut) {
  for (buffer <- 0 until 2; slot <- 0 until 32) {
    poke(dut.io.tables(buffer)(slot), buffer * 64 + slot)
  }
  for (command <- 0 until 3) poke(dut.io.commands(command).weightBufferId, 0)
  if (materialized) {
    for (buffer <- 0 until 2; place <- 0 until 2; slot <- 0 until 32) {
      for (command <- 0 until 3) poke(dut.io.commands(command).weightBufferId, buffer)
      poke(dut.io.place, place)
      poke(dut.io.slot, slot)
      expect(dut.io.source, buffer * 64 + slot)
    }
  } else {
    // This expectation must fail with the archived FireSim compiler's lowering.
    poke(dut.io.place, 0)
    poke(dut.io.slot, 15)
    expect(dut.io.source, 15)
  }
}

class PoTalIndexedLookupLoweringSpec extends ChiselFlatSpec {
  behavior of "Indexed PRELOAD legacy FIRRTL lowering"

  for (materialized <- Seq(false, true)) {
    it should s"demonstrate selector materialization=$materialized" in {
      Driver.execute(
        Array("--backend-name", "treadle", "--target-dir",
          s"test_run_dir/potal-indexed-lowering-$materialized"),
        () => new PoTalIndexedLookupLoweringHarness(materialized)
      ) { dut => new PoTalIndexedLookupLoweringTester(dut, materialized) } should be(materialized)
    }
  }
}
