
package potal

import chisel3._


object PoTalProtocol {
  // No fixed DIM or scale BlockSize.
  // scaleBlockK = config.DIM * passesPerBlock

  val GenerationWidth = 8

  val WorkEntries = 128
  val WorkIdWidth = 7
  val WorkIdsPerLoop = 64

  // One indexed Weight buffer per Scratchpad bank pair.
  val WeightBufferCount = 2
  val WeightBufferIdWidth = 1

  // CPU-side contract:
  // passesPerBlock = scaleBlockK / DIM
  // passesPerBlock >= 1
  // scaleBlockK % DIM == 0

  def passesPerBlock(
    dim: Int,
    scaleBlockK: Int
  ): Int = {
    require(dim > 0)
    require(scaleBlockK >= dim)
    require(scaleBlockK % dim == 0)

    val passes = scaleBlockK / dim

    require(passes <= 65535)
    passes
  }
}


class PoTalCommandMetadata extends Bundle {
  val valid = Bool()

  // Scale block index within the active scale window.
  // One block contains passesPerBlock array K tiles.
  val blockIndex = UInt(16.W)

  val scaleBase = UInt(16.W)
  val scaleGeneration =
    UInt(PoTalProtocol.GenerationWidth.W)

  val outputTileCount = UInt(16.W)
  val outputTileIndex = UInt(16.W)

  val workId = UInt(PoTalProtocol.WorkIdWidth.W)

  // These fields control ACC/work semantics,
  // not scale selection.
  val firstContribution = Bool()
  val lastContribution = Bool()

  // Indexed Shared Weight
  val indexedWeight = Bool()

  // Resident Weight buffer selecting its Index Table and Descriptor.
  // 0: Scratchpad bank pair 0/1
  // 1: Scratchpad bank pair 2/3
  val weightBufferId =
    UInt(PoTalProtocol.WeightBufferIdWidth.W)

  // Residual logical K tile index
  val indexedKTile = UInt(16.W)

  // Residual output N tile index
  val indexedJTile = UInt(16.W)
}

