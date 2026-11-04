package potal

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket._
import freechips.rocketchip.tile.HasCoreParameters
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters

final class PoTalIndexMetadataControl(
  depth: Int, maxEntries: Int, ownerWidth: Int, residencyWidth: Int, generationWidth: Int
)(implicit p: Parameters) extends Bundle {
  val submit = Flipped(Decoupled(new Bundle {
    val descriptor = new PoTalIndexTransportStart(depth, ownerWidth, residencyWidth, generationWidth)
    val header = new PoTalIndexWindowHeader(ownerWidth, residencyWidth)
  }))
  val result = Decoupled(new PoTalIndexWindowResult(depth, ownerWidth, residencyWidth, generationWidth))
  val mappingEpoch = Input(UInt(32.W))
  val flush = Input(Bool())
  val passLoad = Input(Bool())
  val transportBusy = Output(Bool())
  val metadataFenceBusy = Output(Bool())
  val metadataCloseBlocked = Output(Bool())
  val sourceLease = Output(Bool())
  val faulted = Output(Bool())
  val faultKey = Output(new PoTalIndexWindowKey(depth, ownerWidth, residencyWidth, generationWidth))
  val entryState = Output(Vec(depth, UInt(3.W)))
  val entryCount = Output(Vec(depth, UInt(PoTalIndexWindow.width(maxEntries + 1).W)))
  val load = Valid(new Bundle {
    val key = new PoTalIndexWindowKey(depth, ownerWidth, residencyWidth, generationWidth)
    val kind = UInt(2.W)
    val residentK = UInt(32.W)
  })
  val loadOffered = Output(Bool())
  val indexWrite = Output(Bool())
  val ptwPending = Output(Bool())
}

// The harness owns the roots, PMP, mapping epoch and immutable source bytes.
// Production never submits. No CPU address-space lifetime guarantee is implied.
final class PoTalIndexMetadataTransport(
  maxEntries: Int, dim: Int, indexEntries: Int, spRows: Int,
  tlbEntries: Int, maxBytes: Int, depth: Int = 2,
  ownerWidth: Int = 8, residencyWidth: Int = 8, generationWidth: Int = 32,
  deadline: Int = 65536
)(implicit p: Parameters) extends LazyModule {
  val node = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(
    TLMasterParameters.v1(name = "potal-index-metadata", sourceId = IdRange(0, 1))))))
  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) with HasCoreParameters {
    import PoTalIndexWindow._
    val io = IO(new Bundle {
      val control = new PoTalIndexMetadataControl(depth, maxEntries, ownerWidth, residencyWidth, generationWidth)
      val ptw = new TLBPTWIO
    })
    val (bus, negotiated) = node.out(0)
    implicit val edge: TLEdgeOut = negotiated
    val frontend = Module(new FrontendTLB(1, tlbEntries, 64, false, false, false, Some(6)))
    val reader = Module(new PoTalIndexBurstReader(edge, maxEntries, maxBytes,
      depth, ownerWidth, residencyWidth, generationWidth, deadline))
    val queue = Module(new PoTalIndexWindowQueue(depth, maxEntries, dim, indexEntries, spRows,
      ownerWidth, residencyWidth, generationWidth, deadline, deadline))
    frontend.io.ptw(0) <> io.ptw
    reader.io.tlb <> frontend.io.clients(0)
    frontend.io.counter.external_reset := false.B
    bus.a <> reader.io.a
    reader.io.d <> bus.d
    bus.b.ready := true.B
    bus.c.valid := false.B; bus.c.bits := 0.U.asTypeOf(bus.c.bits)
    bus.e.valid := false.B; bus.e.bits := 0.U.asTypeOf(bus.e.bits)

    val reserved = RegInit(false.B)
    val attached = RegInit(false.B)
    val lifetime = RegInit(false.B)
    val checkedDone = RegInit(false.B)
    val violation = RegInit(false.B)
    val refillPending = RegInit(false.B)
    val correlationLost = RegInit(false.B)
    val pending = Reg(new PoTalIndexTransportStart(depth, ownerWidth, residencyWidth, generationWidth))
    val envelope = Cat(io.ptw.ptbr.asUInt, io.ptw.hgatp.asUInt, io.ptw.vsatp.asUInt,
      io.ptw.status.asUInt, io.ptw.hstatus.asUInt, io.ptw.gstatus.asUInt,
      io.ptw.pmp.asUInt, io.ptw.customCSRs.asUInt, io.control.mappingEpoch)
    val frozen = Reg(UInt(envelope.getWidth.W))
    val attemptStage = RegNext(frontend.io.clients(0).req.valid, false.B)
    val refillAccepted = io.ptw.req.fire && io.ptw.req.bits.valid
    val unexpectedRefill = io.ptw.resp.valid && !refillPending
    val highRefill = io.ptw.resp.valid &&
      (io.ptw.resp.bits.pte.ppn >> (math.min(paddrBits, edge.bundle.addressBits) - pgIdxBits)).orR
    val changed = lifetime && (envelope =/= frozen || io.control.flush)
    when(changed || highRefill) { violation := true.B }
    when(unexpectedRefill || (refillAccepted && refillPending)) { correlationLost := true.B }
    when(refillAccepted) { refillPending := true.B }
    when(io.ptw.resp.valid && refillPending && !refillAccepted) { refillPending := false.B }
    val debt = refillPending || io.ptw.req.valid || attemptStage ||
      frontend.io.clients(0).req.valid || correlationLost
    reader.io.translationDebt := debt
    reader.io.contextFault := violation || changed || highRefill || unexpectedRefill || correlationLost

    val canSubmit = !reserved && !lifetime && !reader.io.busy && !debt && !violation &&
      !changed && !correlationLost && !bus.d.valid && !io.control.flush
    queue.io.ctl.valid := io.control.submit.valid && canSubmit
    queue.io.ctl.bits.op := false.B
    queue.io.ctl.bits.header := io.control.submit.bits.header
    queue.io.ctl.bits.key := 0.U.asTypeOf(queue.io.ctl.bits.key)
    io.control.submit.ready := queue.io.ctl.ready && canSubmit
    when(io.control.submit.fire) {
      pending := io.control.submit.bits.descriptor
      pending.source.activeRows := io.control.submit.bits.header.activeRows
      pending.source.sourceKRows := io.control.submit.bits.header.sourceKRows
      pending.mode := io.ptw.ptbr.mode
      reserved := true.B; attached := false.B
      checkedDone := false.B
      when(!lifetime) { frozen := envelope; lifetime := true.B }
    }
    // Invalidate only our backend before its first translation. An old flush
    // is observed, never reflected into another client's exception control.
    frontend.io.exp(0).flush_retry := io.control.flush ||
      (io.control.submit.fire && !lifetime)
    frontend.io.exp(0).flush_skip := false.B
    val accepted = queue.io.ctlResp.bits.status === ACCEPTED.U
    reader.io.start.valid := reserved && !attached && queue.io.ctlResp.valid && accepted
    reader.io.start.bits := pending
    reader.io.start.bits.source.key := queue.io.ctlResp.bits.key
    when(reader.io.start.fire) { attached := true.B }
    io.control.result.valid := queue.io.ctlResp.valid &&
      (!reserved || !accepted || attached)
    io.control.result.bits := queue.io.ctlResp.bits
    queue.io.ctlResp.ready := io.control.result.ready && io.control.result.valid
    when(queue.io.ctlResp.fire) { reserved := false.B }
    reader.io.contextLive := attached || reader.io.start.fire
    reader.io.queuePoisoned := queue.io.poisoned
    queue.io.load.valid := reader.io.load.valid && io.control.passLoad
    queue.io.load.bits := reader.io.load.bits
    reader.io.load.ready := queue.io.load.ready && io.control.passLoad
    reader.io.loadAck := queue.io.loadAck
    when(queue.io.load.fire && queue.io.load.bits.kind === DONE.U &&
      queue.io.loadAck.valid && queue.io.loadAck.bits.status === LOAD_OK.U &&
      queue.io.loadAck.bits.key.asUInt === reader.io.contextKey.asUInt) {
      checkedDone := true.B
    }
    // Retire after checked DONE or a consumed nonacceptance that never started
    // the reader, with no translation/refill/physical/output debt or fault.
    // READY remains occupied in Queue, but no longer leases the source address space.
    val rejected = queue.io.ctlResp.fire && reserved && !accepted && !attached
    val retireLease = lifetime && ((checkedDone && !reserved) || rejected) && !reader.io.busy &&
      !debt && !reader.io.load.valid && !reader.io.transportUncertain &&
      !reader.io.faulted && !queue.io.poisoned && !violation && !changed &&
      !correlationLost && !highRefill && !unexpectedRefill && !io.control.flush && !bus.d.valid
    when(retireLease) { lifetime := false.B }
    queue.io.indexUpdateSafe := VecInit(Seq.fill(PoTalProtocol.WeightBufferCount)(false.B))
    queue.io.externalApplyPermit := VecInit(Seq.fill(PoTalProtocol.WeightBufferCount)(false.B))
    queue.io.installerAvailable := false.B
    queue.io.consumer.valid := false.B
    queue.io.consumer.bits := 0.U.asTypeOf(queue.io.consumer.bits)
    queue.io.cmd.ready := false.B
    queue.io.resp.valid := false.B
    queue.io.resp.bits := 0.U.asTypeOf(queue.io.resp.bits)
    io.control.load.valid := queue.io.load.fire
    io.control.load.bits := queue.io.load.bits
    io.control.loadOffered := reader.io.load.valid
    io.control.indexWrite := queue.io.cmd.valid
    io.control.entryState := queue.io.entryState
    io.control.entryCount := queue.io.entryCount
    io.control.ptwPending := refillPending
    io.control.faulted := violation || correlationLost || reader.io.faulted || queue.io.poisoned
    io.control.faultKey := reader.io.contextKey
    io.control.sourceLease := lifetime || reserved || reader.io.sourceLease || debt
    io.control.transportBusy := reserved || reader.io.busy || debt || reader.io.load.valid
    io.control.metadataFenceBusy := io.control.transportBusy || queue.io.fenceBusy ||
      reader.io.transportUncertain || io.control.faulted
    io.control.metadataCloseBlocked := io.control.metadataFenceBusy ||
      queue.io.closeBlocked || io.control.sourceLease
    assert(!queue.io.cmd.valid && !queue.io.consumerPermit)
  }
}
