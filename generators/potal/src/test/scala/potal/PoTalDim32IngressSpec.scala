package potal

import chisel3.iotesters._

// Actual DIM32 Scale/Index geometry and the fixed production C11 ISA fields.
// The older reduced-cache ingress fixture uses a different slot field width.
class PoTalDim32IngressTester(dut: PoTalScaleIngressHarness)
    extends PeekPokeTester(dut) {
  poke(dut.io.cmd.valid, 0)
  poke(dut.io.resp.ready, 0)
  poke(dut.io.releaseSafe, 1)
  poke(dut.io.fenceDrained, 1)
  poke(dut.io.lookupColumn, 0)
  poke(dut.io.lookupAddress, 0)
  poke(dut.io.lookupGeneration, 0)
  reset(3)

  def offer(funct: Int, rs1: BigInt = 0, rs2: BigInt = 0): Unit = {
    poke(dut.io.cmd.bits.funct, funct)
    poke(dut.io.cmd.bits.rs1, rs1)
    poke(dut.io.cmd.bits.rs2, rs2)
    poke(dut.io.cmd.bits.rd, 11)
    poke(dut.io.cmd.bits.xd, 1)
    poke(dut.io.cmd.valid, 1)
  }

  def accept(status: Int): Unit = {
    expect(dut.io.cmd.ready, 1)
    step(1)
    poke(dut.io.cmd.valid, 0)
    expect(dut.io.resp.valid, 1)
    expect(dut.io.resp.bits.rd, 11)
    expect(dut.io.resp.bits.status, status)
    // Response backpressure must preserve the exact result.
    step(2)
    expect(dut.io.resp.valid, 1)
    expect(dut.io.resp.bits.status, status)
    poke(dut.io.resp.ready, 1)
    step(1)
    poke(dut.io.resp.ready, 0)
    expect(dut.io.resp.valid, 0)
  }

  // Last legal slot, generation and DIM32 column occupy bits 0..20.
  val lastKey = BigInt("1fffff", 16)
  offer(23, lastKey, 31); accept(0)
  poke(dut.io.lookupColumn, 31)
  poke(dut.io.lookupAddress, 255)
  poke(dut.io.lookupGeneration, 255)
  expect(dut.io.lookupHit, 1)
  expect(dut.io.lookupCarrier, 31)
  offer(23, lastKey, 0); accept(3)
  offer(24, lastKey - 256); accept(4)
  offer(23, BigInt(32) << 16, 1); accept(6)
  offer(23, BigInt(1) << 22, 1); accept(6)
  offer(23, 1, BigInt("80000001", 16)); accept(2)
  poke(dut.io.releaseSafe, 0)
  offer(24, lastKey)
  for (_ <- 0 until 3) {
    expect(dut.io.cmd.ready, 0)
    expect(dut.io.resp.valid, 0)
    step(1)
  }
  poke(dut.io.releaseSafe, 1); accept(0)
  expect(dut.io.lookupHit, 0)
  offer(23, lastKey, BigInt("80000000", 16)); accept(0)
  expect(dut.io.lookupCarrier, BigInt("80000000", 16))
  offer(24, lastKey); accept(0)

  // Bank 1 is [2048,4096); Bank 3 is [6144,8192). Index64 holds two DIM32 passes.
  offer(27, (BigInt(1) << 32) | 2048, (BigInt(64) << 32) | 64); accept(0)
  expect(dut.io.indexConfigured(0), 1)
  expect(dut.io.indexComplete(0), 0)
  expect(dut.io.indexDescriptors(0).bBase, 2048)
  expect(dut.io.indexDescriptors(0).activeRows, 64)
  offer(26, 64, 0); accept(5)
  offer(26, 0, 64); accept(5)
  offer(27, (BigInt(1) << 32) | 2048, (BigInt(65) << 32) | 64); accept(5)
  expect(dut.io.indexDescriptors(0).activeRows, 64)
  for (slot <- 0 until 64) {
    offer(26, slot, 63 - slot); accept(0)
    expect(dut.io.indexComplete(0), if (slot == 63) 1 else 0)
  }
  offer(27, (BigInt(1) << 32) | 6144, (BigInt(32) << 32) | 32); accept(0)
  expect(dut.io.indexComplete(0), 1)
  expect(dut.io.indexComplete(1), 0)
  expect(dut.io.indexDescriptors(1).bBase, 6144)
  for (slot <- 0 until 32) {
    offer(26, slot, slot); accept(0)
  }
  expect(dut.io.indexComplete(1), 1)
  // A region crossing Bank 1's end must not replace either completed descriptor.
  offer(27, (BigInt(1) << 32) | 4064, (BigInt(64) << 32) | 64); accept(5)
  expect(dut.io.indexComplete(0), 1)
  expect(dut.io.indexComplete(1), 1)
  poke(dut.io.fenceDrained, 0)
  offer(25)
  for (_ <- 0 until 3) {
    expect(dut.io.cmd.ready, 0)
    expect(dut.io.resp.valid, 0)
    step(1)
  }
  poke(dut.io.fenceDrained, 1); accept(0)
  offer(28); accept(7)
}

class PoTalDim32IngressSpec extends ChiselFlatSpec {
  behavior of "PoTal DIM32 production Scale and Index fields"
  it should "preserve encoding boundaries, complete windows and drain ordering" in {
    val config = PoTalConfigs.a8w8D32Sat32
    Driver.execute(
      Array("--backend-name", "verilator", "--target-dir", "test_run_dir/h3-8-dim32-ingress"),
      () => new PoTalScaleIngressHarness(config.DIM, config.potalScaleEntries)
    ) { dut => new PoTalDim32IngressTester(dut) } should be (true)
  }
}
