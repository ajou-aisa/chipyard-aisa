package potal

import chisel3.iotesters.PeekPokeTester
import GemminiISA._

// Real RS and LoadController, with explicitly withheld returned-byte responses.
// This tests issue eligibility, not MAC execution or FPGA overlap. The overlap
// case deliberately supplies an intersecting descriptor to isolate the RAW
// hazard; the checked public API separately rejects mismatched pair layouts.
class PoTalStreamIndexedHazardTester(dut: PoTalStreamLoadHarness, overlap: Boolean)
    extends PeekPokeTester(dut) {
  poke(dut.io.cmd.valid, 0)
  poke(dut.io.allocate, 0); poke(dut.io.close, 0)
  poke(dut.io.indexed, 0); poke(dut.io.indexBase, if (overlap) 6144 else 2048)
  poke(dut.io.executeReady, 1)
  poke(dut.io.tag.valid, 0); poke(dut.io.tag.id, 0); poke(dut.io.tag.serial, 0)
  poke(dut.io.tag.pair, 0); poke(dut.io.tag.generation, 7)
  poke(dut.io.dma.req.ready, 1); poke(dut.io.dma.resp.valid, 0)
  reset(2)

  val rows = scala.collection.mutable.ArrayBuffer.empty[(BigInt, BigInt)]
  var issued = 0
  def tick(): Unit = {
    if (peek(dut.io.dma.req.valid) == 1) {
      expect(dut.io.dma.req.bits.stream.valid, 1)
      expect(dut.io.dma.req.bits.stream.id, 1)
      expect(dut.io.dma.req.bits.stream.serial, 1)
      expect(dut.io.dma.req.bits.stream.pair, 1)
      expect(dut.io.dma.req.bits.stream.generation, 8)
      rows += ((peek(dut.io.dma.req.bits.cmd_id), peek(dut.io.dma.req.bits.cols)))
    }
    if (peek(dut.io.executeValid) == 1) {
      expect(dut.io.executeTag.valid, 1)
      expect(dut.io.executeTag.id, 0); expect(dut.io.executeTag.serial, 0)
      expect(dut.io.executeTag.pair, 0); expect(dut.io.executeTag.generation, 7)
      issued += 1
    }
    step(1)
  }
  def until(condition: => Boolean): Unit = {
    var cycles = 0
    while (!condition && cycles < 200) { tick(); cycles += 1 }
    assert(condition, "RS/load pipeline did not reach the expected event")
  }
  def tag(id: Int, pair: Int, generation: Int): Unit = {
    poke(dut.io.tag.valid, 1); poke(dut.io.tag.id, id); poke(dut.io.tag.serial, id)
    poke(dut.io.tag.pair, pair); poke(dut.io.tag.generation, generation)
  }
  def send(funct: Int, rs1: BigInt, rs2: BigInt): Unit = {
    poke(dut.io.cmd.bits.funct, funct)
    poke(dut.io.cmd.bits.rs1, rs1); poke(dut.io.cmd.bits.rs2, rs2)
    poke(dut.io.cmd.bits.xd, 0); poke(dut.io.cmd.bits.rd, 0)
    poke(dut.io.cmd.valid, 1)
    until(peek(dut.io.cmd.ready) == 1)
    tick()
    poke(dut.io.cmd.valid, 0)
  }

  // Given: the older Residual epoch and an admitted future Main epoch.
  send(CONFIG_CMD.litValue.toInt, 0x200109, 32)
  tag(0, 0, 7); poke(dut.io.allocate, 1)
  expect(dut.io.allocated.id, 0); expect(dut.io.allocated.serial, 0)
  tick(); poke(dut.io.allocate, 0)
  tag(1, 1, 8); poke(dut.io.allocate, 1)
  expect(dut.io.allocated.id, 1); expect(dut.io.allocated.serial, 1)
  tick(); poke(dut.io.allocate, 0)
  val shape = (BigInt(32) << 48) | (BigInt(32) << 32)
  send(LOAD2_CMD.litValue.toInt, 0x1000, shape | 6144)
  poke(dut.io.close, 1); tick(); poke(dut.io.close, 0)
  until(rows.size == 32)
  expect(dut.io.pairBusy(1), 1)

  // When: submit the current indexed PRELOAD while all future bytes are held.
  tag(0, 0, 7); poke(dut.io.indexed, 1)
  send(PRELOAD_CMD.litValue.toInt, shape, shape | BigInt("80000000", 16))
  poke(dut.io.close, 1); tick(); poke(dut.io.close, 0)

  // Then: disjoint pair proceeds before any response. Intersecting B must wait.
  if (!overlap) {
    until(issued == 1)
    expect(dut.io.pairBusy(1), 1)
  } else {
    assert(issued == 0)
  }
  for ((id, bytes) <- rows.toVector) {
    poke(dut.io.dma.resp.valid, 1)
    poke(dut.io.dma.resp.bits.cmd_id, id); poke(dut.io.dma.resp.bits.bytesRead, bytes)
    poke(dut.io.dma.resp.bits.stream.valid, 1)
    poke(dut.io.dma.resp.bits.stream.id, 1); poke(dut.io.dma.resp.bits.stream.serial, 1)
    poke(dut.io.dma.resp.bits.stream.pair, 1); poke(dut.io.dma.resp.bits.stream.generation, 8)
    if (overlap) assert(issued == 0, "Indexed PRELOAD bypassed an unfinished overlapping load")
    tick()
  }
  poke(dut.io.dma.resp.valid, 0)
  until(peek(dut.io.pairBusy(1)) == 0 && issued == 1)
  assert(issued == 1, "PRELOAD issued more than once")
  // No fabricated EX completion: that epoch must still own its RS reference.
  expect(dut.io.pairBusy(0), 1)
  expect(dut.io.drained, 0)
}
