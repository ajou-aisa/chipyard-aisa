package potal

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import org.chipsalliance.cde.config.{Config, Parameters}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket._
import freechips.rocketchip.tile.{BuildRoCC, RocketTile}
import freechips.rocketchip.tilelink._

object PoTalIndexMetadataTransportProfile {
  def parameters(output: String, enabled: Boolean, shared: Boolean): Parameters =
    new Config((site, here, up) => {
      case freechips.rocketchip.stage.phases.TargetDirKey => output
      case PoTalIndexMetadataTransportKey => enabled
      case BuildRoCC => Seq((q: Parameters) => {
        implicit val p: Parameters = q
        val gemmini = LazyModule(new Gemmini(PoTalConfigs.a8w8D32Sat32.copy(use_shared_tlb = shared)))
        gemmini
      })
    }) ++ new chipyard.PoTalSat32D32GemminiRocketConfig

  def dump(label: String, edge: TLEdgeParameters): Unit = {
    println(s"EDGE $label bundle=${edge.bundle} beatBytes=${edge.manager.beatBytes}" +
      s" minLatency=${edge.manager.minLatency} maxLgSize=${edge.maxLgSize}")
    println(s"FIELDS request=${edge.bundle.requestFields} echo=${edge.bundle.echoFields}" +
      s" response=${edge.bundle.responseFields}")
    edge.client.clients.foreach(c => println(s"CLIENT $label ${c.name} ${c.sourceId} ${c.nodePath}"))
    edge.manager.managers.foreach(m => println(s"MANAGER $label ${m.name} ${m.address}" +
      s" Get=${m.supportsGet} PutFull=${m.supportsPutFull} PutPartial=${m.supportsPutPartial}" +
      s" region=${m.regionType} executable=${m.executable} acquireB=${m.supportsAcquireB}" +
      s" acquireT=${m.supportsAcquireT} denyGet=${m.mayDenyGet} denyPut=${m.mayDenyPut}"))
  }

  // Capture at id_node, BEFORE the isolated harness reproduces the existing
  // width widget and two buffer stages. Capturing metadata.node doubles them.
  def capture(output: String, shared: Boolean): (Parameters, TLSlavePortParameters) = {
    implicit val p: Parameters = parameters(output, true, shared)
    val root = LazyModule(new chipyard.ChipTop {
      val system = lazySystem.asInstanceOf[chipyard.DigitalTop]
    })
    val tile = root.system.tiles.collect { case t: RocketTile => t }.head
    val g = tile.roccs.collect { case g: Gemmini[_, _, _] => g }.head
    val edge = g.spad.id_node.edges.out.head
    dump("captured-production-id", edge)
    (g.p, edge.manager)
  }
}

final class PoTalIndexMetadataTransportHarness(
  profile: TLSlavePortParameters, shared: Boolean, deadline: Int = 4096,
  variedWidths: Boolean = false
)(implicit p: Parameters) extends LazyModule {
  val config = PoTalConfigs.a8w8D32Sat32.copy(use_shared_tlb = shared,
    potalIndexWindowKTiles = if (variedWidths) 3 else PoTalConfigs.a8w8D32Sat32.potalIndexWindowKTiles)
  val ownerWidth = if (variedWidths) 12 else 8
  val generationWidth = if (variedWidths) 8 else 32
  val spad = LazyModule(new Scratchpad(config, metadataDeadline = deadline,
    metadataOwnerWidth = ownerWidth, metadataGenerationWidth = generationWidth))
  val manager = TLManagerNode(Seq(profile))
  manager := spad.id_node
  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val minimumResponseLatency = profile.minLatency max 1
    val readerMaskByteStride = config.aligned_to
    val (bus, edgeIn) = manager.in(0)
    implicit val edge: TLEdgeOut = spad.id_node.edges.out.head
    val oldFrontend = Module(new FrontendTLB(2, config.tlb_size, config.dma_maxbytes,
      config.use_tlb_register_filter, false, shared))
    val io = IO(new Bundle {
      val memory = new TLBundle(edgeIn.bundle)
      val control = new PoTalIndexMetadataControl(2, config.potalIndexEntries, ownerWidth, 8, generationWidth)
      val metadataPTW = new TLBPTWIO
      val legacyPTW = Vec(if (shared) 1 else 2, new TLBPTWIO)
      val read = Flipped(new ScratchpadReadMemIO(config.local_addr_t, config.mvin_scale_t_bits))
      val write = Flipped(new ScratchpadWriteMemIO(config.local_addr_t,
        config.accType.getWidth, config.acc_scale_t_bits))
      val sramRead = Flipped(Vec(config.sp_banks,
        new ScratchpadReadIO(config.sp_bank_entries, spad.spad_w)))
      val sramWrite = Flipped(Vec(config.sp_banks,
        new ScratchpadWriteIO(config.sp_bank_entries, spad.spad_w,
          (spad.spad_w / (config.aligned_to * 8)) max 1)))
      val flushLegacy = Input(Bool())
      val legacyInterrupt = Output(Bool())
      val legacyBusy = Output(Bool())
      val dmaWrites = Output(Vec(config.sp_banks, Bool()))
      val readerData = Output(chiselTypeOf(spad.reader.module.io.resp.bits))
      val readerFire = Output(Bool())
      val metadataRequest = Valid(new DecoupledTLBReq(6))
      val metadataUncertain = Output(Bool())
    })
    io.memory <> bus
    io.control <> spad.module.io.metadataControl.get
    io.metadataPTW <> spad.module.io.metadataTLB.get
    io.legacyPTW <> oldFrontend.io.ptw
    oldFrontend.io.clients <> spad.module.io.tlb
    oldFrontend.io.exp.foreach { e =>
      e.flush_retry := io.flushLegacy; e.flush_skip := false.B
    }
    oldFrontend.io.counter.external_reset := false.B
    io.legacyInterrupt := oldFrontend.io.exp.map(_.interrupt).reduce(_ || _)
    spad.module.io.flush := io.flushLegacy
    spad.module.io.counter.external_reset := false.B
    spad.module.io.dma.read <> io.read
    spad.module.io.dma.write <> io.write
    spad.module.io.srams.read <> io.sramRead
    spad.module.io.srams.write <> io.sramWrite
    spad.module.io.acc.read_req.foreach { x => x.valid := false.B; x.bits := 0.U.asTypeOf(x.bits) }
    spad.module.io.acc.write.foreach { x => x.valid := false.B; x.bits := 0.U.asTypeOf(x.bits) }
    spad.module.io.acc.read_resp.foreach(_.ready := true.B)
    io.legacyBusy := spad.module.io.busy
    io.dmaWrites := spad.module.io.dmaSpadWrite
    io.readerData := 0.U.asTypeOf(io.readerData)
    io.readerFire := false.B
    BoringUtils.addSource(spad.reader.module.io.resp.bits, "i25d_actual_reader_data")
    BoringUtils.addSink(io.readerData, "i25d_actual_reader_data")
    // Valid and ready are separately bored to avoid creating a logic node in
    // the wrong module; the conjunction is constructed in this harness.
    val readerValid = WireDefault(false.B)
    val readerReady = WireDefault(false.B)
    BoringUtils.addSource(spad.reader.module.io.resp.valid, "i25d_actual_reader_valid")
    BoringUtils.addSource(spad.reader.module.io.resp.ready, "i25d_actual_reader_ready")
    BoringUtils.addSink(readerValid, "i25d_actual_reader_valid")
    BoringUtils.addSink(readerReady, "i25d_actual_reader_ready")
    io.readerFire := readerValid && readerReady
    io.metadataRequest := 0.U.asTypeOf(io.metadataRequest)
    BoringUtils.addSource(spad.metadata.get.module.reader.io.tlb.req.valid, "i25d_metadata_request_valid")
    BoringUtils.addSource(spad.metadata.get.module.reader.io.tlb.req.bits, "i25d_metadata_request_bits")
    BoringUtils.addSink(io.metadataRequest.valid, "i25d_metadata_request_valid")
    BoringUtils.addSink(io.metadataRequest.bits, "i25d_metadata_request_bits")
    io.metadataUncertain := false.B
    BoringUtils.addSource(spad.metadata.get.module.reader.io.transportUncertain, "i25d_metadata_uncertain")
    BoringUtils.addSink(io.metadataUncertain, "i25d_metadata_uncertain")
    PoTalIndexMetadataTransportProfile.dump("isolated-id", edge)
    spad.metadata.foreach(m => PoTalIndexMetadataTransportProfile.dump("isolated-metadata", m.node.edges.out.head))
  }
}

object PoTalIndexMetadataTransportElaborate extends App {
  require(args.length == 2)
  require(Set("off-shared", "off-independent", "on-shared", "on-independent").contains(args(1)))
  val enabled = args(1).startsWith("on-")
  val shared = args(1).endsWith("-shared")
  implicit val p: Parameters = PoTalIndexMetadataTransportProfile.parameters(args(0), enabled, shared)
  val root = LazyModule(new chipyard.ChipTop {
    val system = lazySystem.asInstanceOf[chipyard.DigitalTop]
  })
  Seq(classOf[chipyard.ChipTop], classOf[chipyard.DigitalTop], classOf[RocketTile],
    classOf[TLB], classOf[FrontendTLB], classOf[PoTalIndexBurstReader],
    classOf[PoTalIndexMetadataTransport]).foreach { c =>
    println(s"ORIGIN ${c.getName} ${c.getProtectionDomain.getCodeSource}")
  }
  import PoTalIndexMetadataTransportProfile.dump
  root.system.tiles.collect { case t: RocketTile => t }.foreach { tile =>
    val expected = (if (shared) 1 else 2) + (if (enabled) 1 else 0)
    require(tile.roccs.size == 1 && tile.roccs.head.nPTWPorts == expected)
    require(tile.nPTWPorts == expected + 2)
    println(s"PTW accelerator=$expected tile=${tile.nPTWPorts}")
    tile.masterNode.edges.out.zipWithIndex.foreach { case (e, i) => dump(s"tile.master.$i", e) }
    tile.roccs.collect { case g: Gemmini[_, _, _] => g }.foreach { g =>
      require(g.config.DIM == 32 && g.config.use_shared_tlb == shared)
      require(g.spad.metadata.nonEmpty == enabled)
      g.spad.reader.core.node.edges.out.foreach(dump("reader", _))
      g.spad.writer.node.edges.out.foreach(dump("writer", _))
      g.spad.xbar_node.asInstanceOf[TLNexusNode].edges.in.zipWithIndex
        .foreach { case (e, i) => dump(s"xbar.in.$i", e) }
      g.spad.xbar_node.asInstanceOf[TLNexusNode].edges.out.foreach(dump("xbar.out", _))
      g.spad.id_node.edges.out.foreach(dump("id", _))
      g.spad.metadata.foreach(_.node.edges.out.foreach(dump("metadata", _)))
    }
  }
  dump("SBUS", root.system.sbus.busView)
  dump("MBUS_L2_CLIENT", root.system.mbus.busView)
  (new chisel3.stage.ChiselStage).emitVerilog(root.module, Array("--target-dir", args(0)))
  println("I25D_ELABORATION_PASS")
}
