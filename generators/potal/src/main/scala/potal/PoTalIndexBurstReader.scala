package potal

import chisel3._
import chisel3.util._
import freechips.rocketchip.rocket._
import freechips.rocketchip.tile.{CoreBundle, CoreModule}
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters

final class PoTalIndexTransportStart(
  depth: Int, ownerWidth: Int, residencyWidth: Int, generationWidth: Int
)(implicit p: Parameters) extends CoreBundle {
  val source = new PoTalIndexMetadataStart(depth, ownerWidth, residencyWidth, generationWidth, xLen)
  val status = new MStatus
  val mode = UInt(4.W)
}

// One source and a bounded immutable plan. Translation is completed for the
// entire allocation before an A offer exists. Timeout never cancels an offer.
final class PoTalIndexBurstReader(
  val edge: TLEdgeOut, maxEntries: Int, maxBytes: Int, depth: Int = 2,
  ownerWidth: Int = 8, residencyWidth: Int = 8, generationWidth: Int = 32,
  deadline: Int = 65536
)(implicit p: Parameters) extends CoreModule {
  import PoTalIndexWindow._
  require(maxEntries > 0 && maxBytes >= 4 && deadline >= 2)
  require(edge.manager.minLatency >= 1)
  require(edge.manager.beatBytes >= 4 && edge.manager.beatBytes <= 64)
  require(edge.bundle.addressBits <= xLen && edge.bundle.addressBits >= 2)
  require(edge.bundle.requestFields.isEmpty && edge.bundle.echoFields.isEmpty &&
    edge.bundle.responseFields.isEmpty)
  def startType = new PoTalIndexTransportStart(depth, ownerWidth, residencyWidth, generationWidth)
  def keyType = new PoTalIndexWindowKey(depth, ownerWidth, residencyWidth, generationWidth)
  val io = IO(new Bundle {
    val start = Flipped(Decoupled(startType))
    val tlb = new FrontendTLBIO(Some(6))
    val translationDebt = Input(Bool())
    val contextFault = Input(Bool())
    val a = Decoupled(new TLBundleA(edge.bundle))
    val d = Flipped(Decoupled(new TLBundleD(edge.bundle)))
    val load = Decoupled(new Bundle {
      val key = keyType
      val kind = UInt(2.W)
      val residentK = UInt(32.W)
    })
    val loadAck = Flipped(Valid(new PoTalIndexWindowResult(
      depth, ownerWidth, residencyWidth, generationWidth)))
    val queuePoisoned = Input(Bool())
    val contextLive = Input(Bool())
    val busy = Output(Bool())
    val sourceLease = Output(Bool())
    val transportUncertain = Output(Bool())
    val faulted = Output(Bool())
    val faultReason = Output(UInt(4.W))
    val contextKey = Output(keyType)
  })
  val idle :: discover :: discoveryWait :: plan :: certify :: certifyWait :: issue :: receive :: deliver :: done :: quarantine :: Nil = Enum(11)
  val state = RegInit(idle)
  val context = Reg(startType)
  val cursor = Reg(UInt((xLen + 1).W))
  val end = Reg(UInt((xLen + 1).W))
  val physical = Reg(UInt(paddrBits.W))
  val planPA = Reg(Vec(maxEntries, UInt(paddrBits.W)))
  val planVA = Reg(Vec(maxEntries, UInt(xLen.W)))
  val planSize = Reg(Vec(maxEntries, UInt(3.W)))
  val planOffset = Reg(Vec(maxEntries, UInt(width(maxEntries * 4 + 1).W)))
  val planCount = RegInit(0.U(width(maxEntries + 1).W))
  val slot = RegInit(0.U(width(maxEntries + 1).W))
  val words = Reg(Vec(maxEntries, UInt(32.W)))
  val index = RegInit(0.U(width(maxEntries + 1).W))
  val age = RegInit(0.U(width(deadline + 1).W))
  val reason = RegInit(0.U(4.W))
  val uncertain = RegInit(false.B)
  val lease = RegInit(false.B)
  val outstanding = RegInit(false.B)
  val aValid = RegInit(false.B)
  val request = Reg(new TLBundleA(edge.bundle))
  val expected = Reg(UInt(width(65).W))
  val beat = RegInit(0.U(width(65).W))
  val firstSink = Reg(UInt(edge.bundle.sinkBits.W))
  val firstDenied = Reg(Bool())
  val loadHeld = RegInit(false.B)
  val heldKind = Reg(UInt(2.W))
  val errorSent = RegInit(false.B)
  val failed = reason =/= 0.U
  val enabled = !reset.asBool
  io.start.ready := enabled && state === idle && !failed && !io.translationDebt
  val starting = io.start.fire
  val currentKey = Mux(starting, io.start.bits.source.key, context.source.key)
  val s = io.start.bits.source
  val arithmeticWidth = math.max(xLen + 1, 35)
  val sourceEnd = s.sourceAddress.pad(arithmeticWidth) + (s.count.pad(arithmeticWidth) << 2)
  val bufferEnd = s.bufferBase.pad(arithmeticWidth) + s.bufferBytes.pad(arithmeticWidth)
  val limit = (BigInt(1) << xLen).U(arithmeticWidth.W)
  def canonical(address: UInt, mode: UInt): Bool = {
    val modes = (minPgLevels to pgLevels).map { levels =>
      val bits = pgIdxBits + levels * pgLevelBits
      val valid = if (bits >= xLen) true.B else
        address(xLen - 1, bits - 1) === Fill(xLen - bits + 1, address(bits - 1))
      (mode === (if (xLen == 32) 1 else levels + 5).U) && valid
    }
    mode === 0.U || modes.reduce(_ || _)
  }
  val validSource = s.count =/= 0.U && s.count <= maxEntries.U &&
    s.count === s.activeRows && s.sourceAddress(1, 0) === 0.U &&
    s.bufferBytes =/= 0.U && s.sourceAddress >= s.bufferBase &&
    sourceEnd <= bufferEnd && sourceEnd <= limit && bufferEnd <= limit &&
    !io.start.bits.status.dv && io.start.bits.status.dprv =/= 2.U &&
    canonical(s.sourceAddress, io.start.bits.mode) &&
    canonical((sourceEnd - 1.U)(xLen - 1, 0), io.start.bits.mode)
  val bare = context.mode === 0.U || context.status.dprv === PRV.M.U
  val requestVA = Mux(state === certify || state === certifyWait, planVA(slot), cursor(xLen - 1, 0))
  val requestSize = Mux(state === certify || state === certifyWait, planSize(slot), 2.U)
  io.tlb.req.valid := enabled && !failed && !io.contextFault &&
    (state === discover || state === certify)
  io.tlb.req.bits := 0.U.asTypeOf(io.tlb.req.bits)
  io.tlb.req.bits.status := context.status
  io.tlb.req.bits.tlb_req.vaddr := requestVA
  io.tlb.req.bits.tlb_req.prv := context.status.dprv
  io.tlb.req.bits.tlb_req.v := context.status.dv
  io.tlb.req.bits.tlb_req.cmd := M_XRD
  io.tlb.req.bits.tlb_req.size := requestSize
  io.tlb.req.bits.tlb_req.passthrough := false.B
  val aligned = RegNext(io.tlb.req.valid, false.B)
  val response = io.tlb.resp
  val translated = aligned && !response.miss
  val responseFault = response.pf.ld || response.gf.ld || response.ae.ld || response.ma.ld
  val paLimit = (BigInt(1) << edge.bundle.addressBits).U((paddrBits + 1).W)
  val paEnd = response.paddr.pad(paddrBits + 1) + (1.U((paddrBits + 1).W) << requestSize)
  val highPA = paEnd > paLimit
  val bareHigh = bare && requestVA.pad(xLen + 1) +
    (1.U((xLen + 1).W) << requestSize) > (BigInt(1) << paddrBits).U((xLen + 1).W)

  val legalSize = WireDefault(false.B)
  val lgSize = WireDefault(2.U(3.W))
  for (lg <- 2 to math.min(6, math.min(log2Floor(maxBytes), edge.maxLgSize))) {
    val bytes = 1 << lg
    val address = physical(edge.bundle.addressBits - 1, 0)
    val covered = edge.manager.managers.map { m =>
      m.supportsGet.contains(bytes).B && m.address.map { set =>
        (0 until bytes).map(j => set.contains(address + j.U)).reduce(_ && _)
      }.reduce(_ || _)
    }.reduce(_ || _)
    when(cursor + bytes.U <= end && physical(lg - 1, 0) === 0.U &&
      cursor(pgIdxBits - 1, 0) +& bytes.U <= (1 << pgIdxBits).U &&
      physical.pad(paddrBits + 1) + bytes.U <= paLimit &&
      covered && edge.manager.supportsGetSafe(address, lg.U)) {
      legalSize := true.B; lgSize := lg.U
    }
  }
  val chunkBytes = 1.U((xLen + 1).W) << lgSize
  val (planLegal, _) = edge.Get(0.U, physical(edge.bundle.addressBits - 1, 0), lgSize)
  val (issueLegal, get) = edge.Get(0.U, planPA(slot)(edge.bundle.addressBits - 1, 0), planSize(slot))
  val beatBytes = edge.manager.beatBytes
  val beatLg = log2Ceil(beatBytes)
  val requestBytes = 1.U((xLen + 1).W) << request.size
  val (first, last, terminal, edgeBeat) = edge.count(io.d.bits, io.d.fire)
  val d = io.d.bits
  val framing = outstanding && d.source === 0.U &&
    d.opcode === TLMessages.AccessAckData && d.param === 0.U &&
    d.size === request.size && (!d.denied || d.corrupt) &&
    (beat === 0.U || (d.sink === firstSink && d.denied === firstDenied)) &&
    first === (beat === 0.U) && last === (beat === expected - 1.U) && edgeBeat === beat
  val framingFault = io.d.valid && !framing
  val cause = WireDefault(0.U(4.W))
  when(io.contextFault) { cause := 10.U }
    .elsewhen(framingFault) { cause := 3.U }
    .elsewhen(io.queuePoisoned) { cause := 9.U }
    .elsewhen(starting && !validSource) { cause := 1.U }
    .elsewhen(translated && (responseFault || highPA || bareHigh ||
      (state === certifyWait && response.paddr =/= planPA(slot)))) { cause := 2.U }
    .elsewhen(state === plan && (!legalSize || !planLegal || planCount === maxEntries.U)) { cause := 2.U }
    .elsewhen(state === issue && !issueLegal) { cause := 2.U }
    .elsewhen(io.d.fire && (d.denied || d.corrupt)) { cause := 5.U }
    .elsewhen(state =/= idle && state =/= quarantine && age === (deadline - 1).U &&
      !(state === done && io.load.ready)) { cause := 7.U }
  val faultNow = enabled && !failed && cause =/= 0.U
  val blocked = failed || faultNow
  io.a.valid := enabled && aValid
  io.a.bits := request
  io.d.ready := enabled
  val sendError = blocked && !errorSent && io.contextLive && !io.queuePoisoned
  io.load.valid := enabled && (loadHeld || sendError ||
    (!blocked && (state === deliver || state === done)))
  io.load.bits.key := currentKey
  io.load.bits.kind := Mux(loadHeld, heldKind,
    Mux(sendError, LOAD_ERROR.U, Mux(state === done, DONE.U, DATA.U)))
  io.load.bits.residentK := Mux(io.load.bits.kind === DATA.U, words(index), 0.U)
  val ackOK = io.loadAck.valid && io.loadAck.bits.key.asUInt === currentKey.asUInt &&
    io.loadAck.bits.status === LOAD_OK.U
  val badAck = io.load.fire && io.load.bits.kind =/= LOAD_ERROR.U && !ackOK
  val badWord = WireDefault(false.B)
  io.busy := state =/= idle || failed || aligned || io.translationDebt
  io.sourceLease := lease
  io.transportUncertain := uncertain || outstanding || aValid || aligned || io.translationDebt
  io.faulted := failed
  io.faultReason := reason
  io.contextKey := currentKey
  when(enabled) {
    when(starting) {
      context := io.start.bits; cursor := s.sourceAddress; end := sourceEnd
      planCount := 0.U; slot := 0.U; index := 0.U; age := 0.U
      lease := true.B; errorSent := false.B; state := discover
    }
    when(state =/= idle && age < deadline.U) { age := age + 1.U }
    when(!blocked) {
      when(io.tlb.req.valid) {
        state := Mux(state === discover, discoveryWait, certifyWait)
      }
      when(aligned) {
        when(response.miss) {
          state := Mux(state === discoveryWait, discover, certify)
        }.elsewhen(state === discoveryWait) {
          physical := response.paddr; state := plan
        }.elsewhen(state === certifyWait) {
          when(slot === planCount - 1.U) { slot := 0.U; state := issue }
            .otherwise { slot := slot + 1.U; state := certify }
        }
      }
      when(state === plan) {
        planPA(planCount) := physical
        planVA(planCount) := cursor
        planSize(planCount) := lgSize
        planOffset(planCount) := cursor - context.source.sourceAddress
        planCount := planCount + 1.U
        when(cursor + chunkBytes === end) { slot := 0.U; state := certify }
          .otherwise { cursor := cursor + chunkBytes; state := discover }
      }
      when(state === issue) {
        request := get; aValid := true.B; beat := 0.U
        expected := Mux(planSize(slot) <= beatLg.U, 1.U,
          (1.U(7.W) << planSize(slot)) >> beatLg)
        state := receive
      }
    }
    when(io.a.fire) { aValid := false.B; outstanding := true.B }
    when(framingFault) { uncertain := true.B; lease := true.B }
    when(io.d.fire && framing && !uncertain) {
      when(beat === 0.U) { firstSink := d.sink; firstDenied := d.denied }
      beat := beat + 1.U
      when(terminal) { outstanding := false.B }
      for (lane <- 0 until beatBytes / 4) {
        val laneByte = (lane * 4).U
        val subbeat = request.size < beatLg.U
        val low = request.address(beatLg - 1, 0)
        val use = !subbeat || (laneByte >= low && laneByte < low +& requestBytes)
        val offset = planOffset(slot) + Mux(subbeat, laneByte - low, (beat << beatLg) + laneByte)
        val word = d.data(32 * lane + 31, 32 * lane)
        when(use && !blocked && !d.denied && !d.corrupt) {
          words(offset >> 2) := word
          when(word >= context.source.sourceKRows) { badWord := true.B }
        }
      }
      when(terminal && !blocked) {
        when(slot === planCount - 1.U) { state := deliver }
          .otherwise { slot := slot + 1.U; state := issue }
      }
    }
    loadHeld := io.load.valid && !io.load.ready
    when(io.load.valid && !io.load.ready) { heldKind := io.load.bits.kind }
    when(io.load.fire && io.load.bits.kind === LOAD_ERROR.U) { errorSent := true.B }
    when(!blocked && !badWord && io.load.fire && ackOK) {
      when(state === deliver) {
        when(index === context.source.count - 1.U) { state := done }
          .otherwise { index := index + 1.U }
      }.elsewhen(state === done) { state := idle; lease := false.B }
    }
    when(faultNow) { reason := cause }
      .elsewhen(!failed && badWord) { reason := 6.U }
      .elsewhen(!failed && badAck) { reason := 8.U }
    when(blocked || badWord || badAck) {
      when(!loadHeld || io.load.fire) { state := quarantine }
      when(!uncertain && !framingFault && !outstanding && !aValid && !io.a.fire &&
        !loadHeld && !aligned && !io.translationDebt && !io.contextFault) { lease := false.B }
    }
    assert(!(aValid && outstanding))
    when(io.a.valid) { assert(planCount =/= 0.U) }
  }
}
