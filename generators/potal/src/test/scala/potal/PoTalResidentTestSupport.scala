package potal

import chisel3._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tile.{MaxHartIdBits, RoCCCommand, RocketTileParams, TileKey, TileVisibilityNodeKey}
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters

// CoreBundle reflection evaluates physical-address metadata even for RoCC
// payloads. Negotiate a real metadata edge; no test replaces the datapath.
private[potal] class PoTalResidentVisibility(implicit p: Parameters) extends LazyModule {
  val node = TLEphemeralNode()
  private val client = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(
    TLMasterParameters.v1("resident-test", IdRange(0, 1))))))
  private val manager = TLManagerNode(Seq(TLSlavePortParameters.v1(Seq(
    TLSlaveParameters.v1(address = Seq(AddressSet(0, (BigInt(1) << 36) - 1)),
      regionType = RegionType.UNCACHED, supportsGet = TransferSizes(1, 64),
      supportsPutFull = TransferSizes(1, 64), fifoId = Some(0))), beatBytes = 8)))
  manager := node := client
  lazy val module = new LazyModuleImp(this)
}

object PoTalResidentTestSupport {
  private val base = new freechips.rocketchip.system.DefaultConfig
  private val visibility = LazyModule(new PoTalResidentVisibility()(base))
  implicit val parameters: Parameters = base.alterPartial {
    case TileKey => RocketTileParams()
    case MaxHartIdBits => 1
    case TileVisibilityNodeKey => visibility.node
  }

  val config = PoTalConfigs.a8w8D16Sat32

  def command(target: RoCCCommand, source: PoTalScaleCommand): Unit = {
    target := 0.U.asTypeOf(target)
    target.inst.funct := source.funct
    target.inst.xd := source.xd
    target.inst.xs1 := true.B
    target.inst.xs2 := true.B
    target.inst.rd := source.rd
    target.inst.opcode := "h7b".U
    target.rs1 := source.rs1
    target.rs2 := source.rs2
  }

  def view(base: Int, pitch: Int = 144, origin: Int = 0, acc: Int = 80): BigInt =
    BigInt(base) | (BigInt(pitch) << 16) | (BigInt(origin) << 32) | (BigInt(acc) << 48)

  def flags(pair: Int = 0, indexed: Boolean = false, bank: Int = 0): BigInt =
    BigInt(0x11 | (pair << 1) | (if (indexed) 4 else 0) | (bank << 3))

  def run(pair: Int = 0, indexed: Boolean = false, bank: Int = 0,
          extended: Boolean = true, accumulate: Boolean = false): BigInt =
    BigInt(if (extended) 8 else 0) | (if (accumulate) BigInt(1) else BigInt(0)) |
      (BigInt(pair + 1) << 18) | (BigInt(pair + 1) << 16) |
      (BigInt(1) << 20) | (BigInt(1) << 37) | (BigInt(1) << 45) |
      (BigInt(1) << 61) | (BigInt(bank) << 62) |
      (if (indexed) BigInt(1) << 63 else BigInt(0))
}
