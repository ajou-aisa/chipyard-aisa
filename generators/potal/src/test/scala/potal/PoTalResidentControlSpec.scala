package potal

import chisel3._
import chisel3.util._
import chisel3.iotesters._

// The real ingress readiness boundary. The drained input represents completion
// supplied by Controller; this harness does not invent DMA or numeric results.
class PoTalResidentControlHarness extends Module {
  val io = IO(new Bundle {
    val cmd = Flipped(Decoupled(new PoTalScaleCommand))
    val resp = Decoupled(new PoTalScaleCommandResponse)
    val drained = Input(Bool())
    val memoryReady = Input(Bool())
    val releaseFire = Output(Bool())
    val writeFire = Output(Bool())
  })
  val ingress = Module(new PoTalScaleCommandIngress(16, 256, 32, 16384))
  ingress.io.cmd <> io.cmd
  io.resp <> ingress.io.resp
  ingress.io.releaseSafe := io.drained
  ingress.io.fenceDrained := io.drained
  ingress.io.scaleLoad.ready := io.memoryReady
  ingress.io.scaleRelease.ready := io.memoryReady
  ingress.io.scaleConflict := false.B
  ingress.io.releaseMiss := false.B
  ingress.io.indexUpdateSafe.foreach(_ := true.B)
  io.releaseFire := ingress.io.scaleRelease.fire
  io.writeFire := ingress.io.scaleLoad.fire
}

class PoTalResidentControlTester(dut: PoTalResidentControlHarness) extends PeekPokeTester(dut) {
  def control(funct: Int, rs1: BigInt = 0, rs2: BigInt = 0,
              xd: Boolean = true, rd: Int = 0): Unit = {
    poke(dut.io.cmd.bits.funct, funct)
    poke(dut.io.cmd.bits.rs1, rs1)
    poke(dut.io.cmd.bits.rs2, rs2)
    poke(dut.io.cmd.bits.xd, if (xd) 1 else 0)
    poke(dut.io.cmd.bits.rd, rd)
  }
  control(0, xd = false)
  poke(dut.io.cmd.valid, 0)
  poke(dut.io.resp.ready, 0)
  poke(dut.io.drained, 0)
  poke(dut.io.memoryReady, 1)
  step(1)

  def await(signal: Bool): Unit = {
    var cycles = 0
    while (peek(signal) == 0 && cycles < 16) { step(1); cycles += 1 }
    assert(peek(signal) != 0)
  }
  def response(rd: Int, status: Int): Unit = {
    await(dut.io.resp.valid)
    for (_ <- 0 until 4) {
      expect(dut.io.resp.bits.rd, rd)
      expect(dut.io.resp.bits.status, status)
      expect(dut.io.cmd.ready, 0)
      expect(dut.io.releaseFire, 0)
      step(1)
    }
    poke(dut.io.resp.ready, 1); step(1); poke(dut.io.resp.ready, 0)
    expect(dut.io.resp.valid, 0)
  }
  for (funct <- Seq(25, 24)) {
    control(funct, if (funct == 24) BigInt(1) << 8 else BigInt(0),
      xd = true, rd = 13)
    poke(dut.io.cmd.valid, 1)
    // Outstanding or unknown completion cannot acknowledge Fence or Release.
    for (_ <- 0 until 4) {
      expect(dut.io.cmd.ready, 0)
      expect(dut.io.resp.valid, 0)
      expect(dut.io.releaseFire, 0)
      step(1)
    }
    if (funct == 24) {
      poke(dut.io.memoryReady, 0)
      poke(dut.io.drained, 1)
      expect(dut.io.cmd.ready, 0)
      expect(dut.io.releaseFire, 0)
      step(1)
      poke(dut.io.memoryReady, 1)
    } else poke(dut.io.drained, 1)
    await(dut.io.cmd.ready)
    expect(dut.io.releaseFire, if (funct == 24) 1 else 0)
    step(1); poke(dut.io.cmd.valid, 0)
    response(13, 0)
    poke(dut.io.drained, 0)
  }
  // Malformed Release is a refusal, not a successful drain or memory effect.
  control(24, BigInt(1) << 63, xd = true, rd = 14)
  poke(dut.io.cmd.valid, 1)
  await(dut.io.cmd.ready)
  expect(dut.io.releaseFire, 0); expect(dut.io.writeFire, 0)
  step(1); poke(dut.io.cmd.valid, 0)
  response(14, 6)

  // Reset cancels a staged command without manufacturing completion.
  control(25, xd = true, rd = 15)
  poke(dut.io.cmd.valid, 1)
  expect(dut.io.cmd.ready, 0)
  reset(2)
  poke(dut.io.cmd.valid, 0)
  expect(dut.io.resp.valid, 0); expect(dut.io.releaseFire, 0)
}

class PoTalResidentControlSpec extends ChiselFlatSpec {
  behavior of "Resident completion control boundary"
  it should "withhold successful Fence and Release until qualified completion" in {
    Driver.execute(Array("--backend-name", "treadle", "--target-dir",
      "test_run_dir/potal-resident-control"), () => new PoTalResidentControlHarness) {
      dut => new PoTalResidentControlTester(dut)
    } should be(true)
  }
}
