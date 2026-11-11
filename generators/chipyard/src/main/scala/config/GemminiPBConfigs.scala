package chipyard

import chisel3._
import org.chipsalliance.cde.config.Config

import gemmini.{Float, GemminiConfigs, LeanGemminiConfig}
import gemmini.Arithmetic.SIntArithmetic

// Plain Gemmini A8W8 / DIM32 / WS for the Gemmini-PB software baseline.
// Only generator parameters differ from GemminiConfigs.leanConfig. The RTL is
// unmodified, and K blocks are scheduled entirely in software.
object GemminiPBParams {
  val d32 = GemminiConfigs.leanConfig.copy(
    meshRows = 32,
    meshColumns = 32,
    // Full-range INT8 x INT8 over 32 rows needs 16 + log2(32) bits.
    spatialArrayOutputType = SInt(21.W),
    // Leaves the in-tree DIM16 gemmini_params.h untouched.
    headerFileName = "gemmini_pb_d32_params.h"
  )

  // d32 with only the two memory parameters PoTal uses, for the comparison against PoTal.
  val d32PoTalMatched = d32.copy(
    sp_singleported = false,
    max_in_flight_mem_reqs = 16,
    headerFileName = "gemmini_pb_d32_potal_matched_params.h"
  )
}

class LeanGemminiD32Config extends LeanGemminiConfig[SInt, Float, Float](GemminiPBParams.d32)

class LeanGemminiD32RocketConfig extends Config(
  new LeanGemminiD32Config ++
  new freechips.rocketchip.subsystem.WithNBigCores(1) ++
  new chipyard.config.WithSystemBusWidth(128) ++
  new chipyard.config.AbstractConfig)

// Five Rocket harts, one plain Gemmini on hart 0.
class FiveRocketSingleLeanGemminiD32Config extends Config(
  new chipyard.config.WithMultiRoCC ++
  new chipyard.config.WithMultiRoCCFromBuildRoCC(0) ++
  new freechips.rocketchip.subsystem.WithNBigCores(4) ++
  new LeanGemminiD32RocketConfig)

class LeanGemminiD32PoTalMatchedConfig extends LeanGemminiConfig[SInt, Float, Float](GemminiPBParams.d32PoTalMatched)

class LeanGemminiD32PoTalMatchedRocketConfig extends Config(
  new LeanGemminiD32PoTalMatchedConfig ++
  new freechips.rocketchip.subsystem.WithNBigCores(1) ++
  new chipyard.config.WithSystemBusWidth(128) ++
  new chipyard.config.AbstractConfig)

class FiveRocketSinglePoTalMatchedGemminiD32Config extends Config(
  new chipyard.config.WithMultiRoCC ++
  new chipyard.config.WithMultiRoCCFromBuildRoCC(0) ++
  new freechips.rocketchip.subsystem.WithNBigCores(4) ++
  new LeanGemminiD32PoTalMatchedRocketConfig)
