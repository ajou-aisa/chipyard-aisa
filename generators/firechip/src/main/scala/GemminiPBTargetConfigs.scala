package firesim.firesim

import org.chipsalliance.cde.config.Config
import firesim.configs.WithDefaultMemModel

// Plain Gemmini A8W8 / DIM32 / WS, with the same bridges, memory model and
// clocks as the PoTal DIM32 targets.
class FireSimLeanGemminiD32RocketConfig extends Config(
  new WithDefaultFireSimBridges ++
  new WithDefaultMemModel ++
  new WithFireSimConfigTweaks ++
  new chipyard.LeanGemminiD32RocketConfig)

// Five Rocket harts, one plain Gemmini on hart 0, for the Gemmini-PB baseline.
class FireSimFiveRocketSingleGemminiD32Config extends Config(
  new chipyard.config.WithMultiRoCC ++
  new chipyard.config.WithMultiRoCCFromBuildRoCC(0) ++
  new freechips.rocketchip.subsystem.WithNBigCores(4) ++
  new FireSimLeanGemminiD32RocketConfig)

class FireSimPoTalMatchedGemminiD32RocketConfig extends Config(
  new WithDefaultFireSimBridges ++
  new WithDefaultMemModel ++
  new WithFireSimConfigTweaks ++
  new chipyard.LeanGemminiD32PoTalMatchedRocketConfig)

class FireSimFiveRocketSinglePoTalMatchedGemminiD32Config extends Config(
  new chipyard.config.WithMultiRoCC ++
  new chipyard.config.WithMultiRoCCFromBuildRoCC(0) ++
  new freechips.rocketchip.subsystem.WithNBigCores(4) ++
  new FireSimPoTalMatchedGemminiD32RocketConfig)
