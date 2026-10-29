package potal

import chisel3._

// Captured in the configuring slot, then retained until generator retirement.
// Validity and active mode are separate: a run consumes staging, not its data.
class PoTalResidentView extends Bundle {
  val bBase = UInt(16.W)
  val bPitch = UInt(16.W)
  val jOrigin = UInt(16.W)
  val accBase = UInt(16.W)
  val pair = Bool()
  val indexed = Bool()
  val accBank = Bool()
}

object PoTalResidentCapability {
  val Query = BigInt("504f544100000000", 16)
  val Signature = BigInt("504f544102100007", 16)

  def supported[T <: Data, U <: Data, V <: Data](
    config: GemminiArrayConfig[T, U, V], xLen: Int
  ): Boolean =
    xLen == 64 && config.num_counter > 0 &&
    config.enablePoTalSat32Acc && config.dataflow == Dataflow.WS &&
    config.DIM == 16 && config.tileRows == 1 && config.tileColumns == 1 &&
    config.sp_banks == 4 && config.sp_bank_entries == 4096 &&
    config.acc_banks == 2 && config.acc_bank_entries == 512 &&
    config.potalScaleEntries == 256 && config.potalIndexEntries == 32 &&
    config.inputType.isInstanceOf[SInt] && config.inputType.getWidth == 8 &&
    config.spatialArrayOutputType.getWidth == 20 && config.accType.getWidth == 32 &&
    config.ex_read_from_spad && config.ex_write_to_acc &&
    config.mvin_scale_args.isEmpty && config.mvin_scale_acc_args.isEmpty
}

// Separate tag from the DIM16 resident API. Query only on the known COUNTER126
// ISA: funct=126, xd=1, rs1=Query, rs2=0. No counter state is changed.
// Signature: "POBS", version 1, DIM32, flags 0x0007 (B SRAM writes,
// representative physical MAC, same-target-cycle cross-pair intersection).
// Supports direct non-transposed WS operands from SP pairs (0,1) and (2,3).
// It does not certify generations, DMA completion, or allDrained.
object PoTalPrefetchCapability {
  val Query = BigInt("504f425300000000", 16)
  val Signature = BigInt("504f425301200007", 16)

  def supported[T <: Data, U <: Data, V <: Data](
    config: GemminiArrayConfig[T, U, V], xLen: Int, enabled: Boolean
  ): Boolean =
    enabled && xLen == 64 && config.num_counter >= 7 &&
    config.enablePoTalSat32Acc && config.dataflow == Dataflow.WS &&
    config.DIM == 32 && config.tileRows == 1 && config.tileColumns == 1 &&
    config.sp_banks == 4 && config.sp_bank_entries == 2048 &&
    config.acc_banks == 2 && config.acc_bank_entries == 256 &&
    config.potalScaleEntries == 256 && config.potalIndexEntries == 64 &&
    config.inputType.isInstanceOf[SInt] && config.inputType.getWidth == 8 &&
    config.spatialArrayOutputType.getWidth == 21 && config.accType.getWidth == 32 &&
    config.ex_read_from_spad && !config.ex_read_from_acc && config.ex_write_to_acc &&
    !config.clock_gate && !config.use_shared_ext_mem && !config.is_dummy &&
    config.aligned_to < config.sp_width &&
    config.mvin_scale_args.isEmpty && config.mvin_scale_acc_args.isEmpty
}
