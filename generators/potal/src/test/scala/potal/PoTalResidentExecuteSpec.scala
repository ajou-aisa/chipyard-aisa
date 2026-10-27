package potal

import chisel3._
import chisel3.util._
import chisel3.iotesters._
import GemminiISA._
import PoTalResidentTestSupport._

// Exercise the actual generator, including captured request and command stalls.
class PoTalResidentExecuteHarness extends Module {
  private val c = config
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new LoopMatmulExecuteReq(16, 40, 16, 16384, 1024, 2)))
    val out = Decoupled(new Bundle {
      val funct = UInt(7.W)
      val rs1 = UInt(64.W)
      val rs2 = UInt(64.W)
    })
    val loaded = Input(Bool())
    val overloaded = Input(Bool())
    val block = Output(UInt(16.W))
    val k = Output(UInt(16.W))
    val j = Output(UInt(16.W))
    val idle = Output(Bool())
  })
  val execute = Module(new LoopMatmulExecute(16, 40, 16, 16384, 1024, 2,
    new PreloadRs(c.mvin_rows_bits, c.mvin_cols_bits, c.local_addr_t),
    new PreloadRs(c.mvout_rows_bits, c.mvout_cols_bits, c.local_addr_t),
    new ComputeRs(c.mvin_rows_bits, c.mvin_cols_bits, c.local_addr_t),
    new ComputeRs(c.mvin_rows_bits, c.mvin_cols_bits, c.local_addr_t)))
  execute.io.req <> io.req
  io.out.valid := execute.io.cmd.valid
  io.out.bits.funct := execute.io.cmd.bits.inst.funct
  io.out.bits.rs1 := execute.io.cmd.bits.rs1
  io.out.bits.rs2 := execute.io.cmd.bits.rs2
  execute.io.cmd.ready := io.out.ready
  execute.io.lda_completed := io.loaded
  execute.io.ldb_completed := io.loaded
  execute.io.ldd_completed := io.loaded
  execute.io.ld_ka := 0.U
  execute.io.ld_kb := 0.U
  execute.io.ld_i := 0.U
  execute.io.ld_j := 0.U
  execute.io.rob_overloaded := io.overloaded
  io.block := execute.io.scaleBlockIndex
  io.k := execute.io.k
  io.j := execute.io.j
  io.idle := execute.io.idle
}

class PoTalResidentExecuteTester(dut: PoTalResidentExecuteHarness) extends PeekPokeTester(dut) {
  poke(dut.io.req.valid, 0)
  poke(dut.io.out.ready, 0)
  poke(dut.io.loaded, 0)
  poke(dut.io.overloaded, 0)
  reset(2)

  def exercise(pair: Int, base: Int, pitch: Int, js: Int, ks: Int,
               acc: Int, padJ: Int = 0, padK: Int = 0,
               resident: Boolean = true, accumulate: Boolean = false): Unit = {
    expect(dut.io.req.ready, 1)
    val r = dut.io.req.bits
    poke(r.max_i, 1); poke(r.max_j, js); poke(r.max_k, ks)
    poke(r.pad_i, 3); poke(r.pad_j, padJ); poke(r.pad_k, padK)
    poke(r.a_tranpose, 0); poke(r.b_tranpose, 0)
    poke(r.accumulate, if (accumulate) 1 else 0)
    poke(r.passesPerBlock, 1)
    poke(r.a_addr_start, pair * 8192 + 32)
    poke(r.b_addr_end, (pair + 1) * 8192)
    poke(r.c_addr_start, acc)
    poke(r.resident, if (resident) 1 else 0)
    poke(r.resident_b_base, base); poke(r.resident_b_pitch, pitch)
    poke(r.loop_id, pair); poke(r.skip, 0)
    poke(dut.io.loaded, 0)
    poke(dut.io.req.valid, 1); step(1); poke(dut.io.req.valid, 0)
    // Poison incoming geometry after capture. It must not affect this run.
    poke(r.resident_b_base, 8000); poke(r.resident_b_pitch, 16)
    poke(r.c_addr_start, 0); poke(r.max_j, 31)
    expect(dut.io.out.valid, 0)
    poke(dut.io.loaded, 1)
    poke(dut.io.overloaded, 1)
    expect(dut.io.out.valid, 0)
    step(1)
    poke(dut.io.overloaded, 0)

    for (k <- 0 until ks; j <- 0 until js; compute <- Seq(false, true)) {
      expect(dut.io.out.valid, 1)
      expect(dut.io.out.bits.funct, if (compute) 4 else 6)
      expect(dut.io.k, k); expect(dut.io.j, j); expect(dut.io.block, k)
      val rs1 = peek(dut.io.out.bits.rs1)
      val rs2 = peek(dut.io.out.bits.rs2)
      val address = if (compute) pair * 8192 + 32 + k * 16
        else if (resident) base + k * pitch + j * 16
        else (pair + 1) * 8192 - ks * js * 16 + (k * js + j) * 16
      assert((rs1 & 0x3fff) == address, s"k=$k j=$j address=$rs1 expected=$address")
      assert(((rs1 >> 48) & 0xffff) ==
        (if (compute) 13 else 16 - (if (k == ks - 1) padK else 0)))
      assert(((rs1 >> 32) & 0xffff) ==
        (if (compute) 16 - (if (k == ks - 1) padK else 0)
         else 16 - (if (j == js - 1) padJ else 0)))
      if (!compute) {
        assert((rs2 & 0x3ff) == acc + j * 16)
        assert(((rs2 >> 31) & 1) == 1)
        assert(((rs2 >> 30) & 1) == (if (accumulate || k != 0) 1 else 0))
        assert(((rs2 >> 48) & 0xffff) == 13)
      }
      // Explicit backpressure at every PRELOAD and COMPUTE.
      poke(dut.io.out.ready, 0)
      for (_ <- 0 until 3) {
        step(1)
        expect(dut.io.out.valid, 1)
        expect(dut.io.out.bits.rs1, rs1); expect(dut.io.out.bits.rs2, rs2)
        expect(dut.io.block, k)
      }
      poke(dut.io.out.ready, 1); step(1); poke(dut.io.out.ready, 0)
    }
    expect(dut.io.idle, 1)
    expect(dut.io.out.valid, 0)
  }

  for (pair <- 0 until 2) {
    // Full K28/J9 source: original K3/J5 begins at 4672 (+8192 on pair1).
    exercise(pair, 4672 + pair * 8192, 144, 2, 2, 80 + pair * 512)
    exercise(pair, 4160 + pair * 8192, 144, 9, 28, pair * 512, 5, 5)
    exercise(pair, 8176 + pair * 8192, 144, 1, 1, 496 + pair * 512, 5, 5)
    // Original K112 is block3/phase1. P1 emits local Scale blocks0 then1;
    // software binds those to original block3 then4, without merged dots.
    exercise(pair, 5216 + pair * 8192, 144, 1, 2, pair * 512, accumulate = true)
  }
  // A legacy request ignores nonzero resident payload.
  exercise(0, 4672, 144, 1, 1, 0, resident = false)
}

class PoTalResidentExecuteSpec extends ChiselFlatSpec {
  behavior of "Resident LoopMatmulExecute"
  it should "capture geometry and emit exact native addresses under backpressure" in {
    Driver.execute(Array("--backend-name", "treadle", "--target-dir",
      "test_run_dir/potal-resident-execute"), () => new PoTalResidentExecuteHarness) {
      dut => new PoTalResidentExecuteTester(dut)
    } should be(true)
  }
}
