package potal

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.tile.{RoCCCommand, RoCCResponse}

// Experimental isolated port, not a production opcode reservation or OS identity.
final class PoTalIndexRoCCAdapter(dim: Int, spRows: Int)(implicit p: Parameters) extends Module {
  import PoTalIndexWindow._
  def keyType = new PoTalIndexWindowKey(2, 8, 8, 8)
  val io = IO(new Bundle {
    val cmd = Flipped(Decoupled(new RoCCCommand))
    val resp = Decoupled(new RoCCResponse)
    val req = Decoupled(new PoTalIndexRegisterRequest(2, 8, 8, 8))
    val result = Flipped(Decoupled(new PoTalIndexRegisterResult(2, 64, 8, 8, 8)))
    val contextValid = Input(Bool())
    val issuer = Input(UInt(8.W))
    val epoch = Input(UInt(64.W))
    val contextChanged = Input(Bool())
    val feederFault = Input(Bool())
    val feederReason = Input(UInt(4.W))
    val revoke = Output(Bool())
    val fault = Output(Bool())
    val reason = Output(UInt(4.W))
    val staged = Output(Bool())
    val finiteBusy = Output(Bool())
    val filling = Output(Bool())
    val guardEligible = Input(Bool())
    val guardDrained = Input(Bool())
    val guard = Decoupled(new PoTalScaleCommand)
    val guardResp = Flipped(Decoupled(new PoTalScaleCommandResponse))
    val guardDebt = Output(Bool())
    val publishedValid = Output(Vec(2, Bool()))
    val publishedKey = Output(Vec(2, keyType))
    val publishedIssuer = Output(Vec(2, UInt(8.W)))
    val publishedEpoch = Output(Vec(2, UInt(64.W)))
  })
  require(io.cmd.bits.rs1.getWidth == 64)
  require(io.result.bits.getWidth == 115)
  val idle :: offer :: typed :: guardWait :: reply :: Nil = Enum(5)
  val state = RegInit(idle)
  val physical = Reg(new RoCCCommand)
  val issuer = Reg(UInt(8.W))
  val epoch = Reg(UInt(64.W))
  val op = Reg(UInt(3.W))
  val request = Reg(new PoTalIndexRegisterRequest(2, 8, 8, 8))
  val retained = Reg(new PoTalIndexRegisterResult(2, 64, 8, 8, 8))
  val data = Reg(UInt(64.W))
  val fromTyped = RegInit(false.B)
  val staged = RegInit(false.B)
  val header = Reg(new PoTalIndexWindowHeader(8, 8))
  val stageIssuer = Reg(UInt(8.W))
  val stageEpoch = Reg(UInt(64.W))
  val stageAge = RegInit(0.U(11.W))
  val fault = RegInit(false.B)
  val reason = RegInit(0.U(4.W))
  val localFault = WireDefault(false.B)
  val localReason = WireDefault(PROTOCOL.U(4.W))
  val live = RegInit(VecInit(Seq.fill(2)(false.B)))
  val sealedSlot = RegInit(VecInit(Seq.fill(2)(false.B)))
  val keys = Reg(Vec(2, keyType))
  val epochs = Reg(Vec(2, UInt(64.W)))
  val issuers = Reg(Vec(2, UInt(8.W)))
  val rowCounts = Reg(Vec(2, UInt(7.W)))
  val expectedGeneration = RegInit(1.U(8.W))
  val expectedTicket = RegInit(0.U(1.W))
  val generationExhausted = RegInit(false.B)
  val publicationValid = RegInit(VecInit(Seq.fill(2)(false.B)))
  val publicationKey = Reg(Vec(2, keyType))
  val publicationIssuer = Reg(Vec(2, UInt(8.W)))
  val publicationEpoch = Reg(Vec(2, UInt(64.W)))
  val active = RegInit(0.U.asTypeOf(keyType))
  val filling = RegInit(false.B)
  val activeRows = RegInit(0.U(32.W))
  val count = RegInit(0.U(7.W))
  val age = RegInit(0.U(13.W))
  val guardOffered = RegInit(false.B)
  val guardAccepted = RegInit(false.B)
  val guardDebt = guardOffered || guardAccepted
  val guardFailed = RegInit(false.B)
  def pack(s: UInt, r: UInt, k: PoTalIndexWindowKey, n: UInt): UInt =
    Cat(0.U(20.W), k.owner, k.residency, k.pair, n.pad(7), k.generation, k.ticket, op, r, s)
  val zero = 0.U.asTypeOf(keyType)
  def local(s: UInt, r: UInt, k: PoTalIndexWindowKey = zero): Unit = {
    // op is updated on this edge, so callers override the op field below.
    data := pack(s, r, k, 0.U); state := reply; fromTyped := false.B
  }
  val contextBad = (staged && (!io.contextValid || io.issuer =/= stageIssuer || io.epoch =/= stageEpoch)) ||
    (state =/= idle && (!io.contextValid || io.issuer =/= issuer || io.epoch =/= epoch))
  val stageExpired = staged && stageAge === 1023.U
  when(staged && stageAge < 1024.U) { stageAge := stageAge + 1.U }
  // Once the typed delivery deadline is reached, restoring valid on that same
  // edge must not retract the fault observed by admission/consumer logic.
  val typedExpired = state === typed && age === 4095.U
  val blocked = fault || io.feederFault || contextBad || io.contextChanged || stageExpired || typedExpired
  val blockedReason = Mux(fault, reason, Mux(io.feederFault, io.feederReason,
    Mux(typedExpired, RESPONSE.U(4.W), Mux(stageExpired, IO_TIMEOUT.U(4.W), IDENTITY.U(4.W)))))
  io.cmd.ready := state === idle && !guardDebt && !reset.asBool
  io.resp.valid := state === reply
  io.resp.bits.rd := physical.inst.rd
  io.resp.bits.data := data
  io.req.valid := state === offer && !blocked
  io.req.bits := request
  io.result.ready := state === reply && fromTyped && io.resp.fire
  io.revoke := fault || localFault || contextBad || io.contextChanged || stageExpired
  io.fault := blocked || localFault
  io.reason := Mux(fault, reason, Mux(io.feederFault, io.feederReason, localReason))
  io.staged := staged
  io.filling := filling
  io.finiteBusy := state =/= idle || guardDebt
  io.guardDebt := guardDebt
  io.publishedValid := publicationValid
  io.publishedKey := publicationKey
  io.publishedIssuer := publicationIssuer
  io.publishedEpoch := publicationEpoch
  io.guard.valid := guardOffered
  io.guard.bits := 0.U.asTypeOf(io.guard.bits)
  io.guard.bits.funct := GemminiISA.POTAL_FENCE
  io.guard.bits.xd := true.B
  io.guard.bits.rd := 1.U
  io.guardResp.ready := guardAccepted
  when(io.cmd.fire) {
    physical := io.cmd.bits; issuer := io.issuer; epoch := io.epoch
    val f = io.cmd.bits.inst.funct
    val decoded = Mux(f >= 32.U && f <= 37.U, f - 32.U, 7.U)(2, 0)
    op := decoded
    age := 0.U; fromTyped := false.B
    request := 0.U.asTypeOf(request)
    val a = io.cmd.bits.rs1
    val b = io.cmd.bits.rs2
    val framing = io.cmd.bits.inst.opcode === "h7b".U && io.cmd.bits.inst.xd &&
      io.cmd.bits.inst.xs1 && io.cmd.bits.inst.xs2 && io.cmd.bits.inst.rd =/= 0.U
    // Local replies must use this command's decoded operation, not the prior one.
    def diagnostic(s: Int, r: Int): Unit = {
      data := Cat(0.U(53.W), decoded, r.U(4.W), s.U(4.W)); state := reply
    }
    when(blocked) {
      data := Cat(0.U(53.W), decoded, blockedReason, FAULT.U(4.W))
      state := reply
    }
      .elsewhen(!io.contextValid) { diagnostic(FAULT, IDENTITY); localFault := true.B; localReason := IDENTITY.U }
      .elsewhen(!framing || decoded === 7.U) { diagnostic(FAULT, PROTOCOL); localFault := true.B }
      .elsewhen(decoded === 0.U) {
        when(staged || filling) { diagnostic(FAULT, PROTOCOL); localFault := true.B }
          .otherwise {
            header.bBase := a(31, 0); header.jTiles := a(63, 32)
            header.sourceKRows := b(31, 0); header.activeRows := b(63, 32)
            stageIssuer := io.issuer; stageEpoch := io.epoch
            staged := true.B; stageAge := 0.U
            diagnostic(LOAD_OK, NONE)
          }
      }.elsewhen(decoded === 5.U) {
        when(a.orR || b.orR) { diagnostic(FAULT, PROTOCOL); localFault := true.B }
          .elsewhen(staged || filling || !io.guardEligible) { diagnostic(BUSY, STATE) }
          .otherwise { state := guardWait; guardFailed := false.B }
      }.elsewhen(decoded === 1.U) {
        when(!staged || a(63, 17).orR || b.orR) { diagnostic(FAULT, PROTOCOL); localFault := true.B }
          .elsewhen(a(7, 0) =/= io.issuer) {
            diagnostic(FAULT, IDENTITY); localFault := true.B; localReason := IDENTITY.U
          }.otherwise {
            val h = WireDefault(header)
            h.owner := a(7, 0); h.residency := a(15, 8); h.pair := a(16)
            val start = Mux(h.pair === 0.U, (spRows / 4).U, (3 * spRows / 4).U)
            val end = Mux(h.pair === 0.U, (spRows / 2).U, spRows.U)
            val tiles = (h.sourceKRows +& (dim - 1).U) >> log2Ceil(dim)
            val extent = h.bBase +& (tiles * h.jTiles * dim.U)
            val legal = h.jTiles =/= 0.U && h.jTiles <= 65535.U &&
              h.sourceKRows =/= 0.U && h.activeRows =/= 0.U && h.activeRows <= 64.U &&
              h.bBase >= start && h.bBase < end && extent <= end
            staged := false.B
            when(!legal) { diagnostic(INVALID, HEADER) }
              .otherwise { request.header := h; request.op := 0.U; state := offer }
          }
      }.elsewhen(staged) { diagnostic(FAULT, PROTOCOL); localFault := true.B }
      .elsewhen(decoded === 2.U) {
        request.op := 1.U; request.rs1 := a; request.rs2 := b; state := offer
      }.elsewhen(decoded === 3.U) {
        when(a.orR || b.orR) { diagnostic(FAULT, PROTOCOL); localFault := true.B }
          .otherwise { request.op := 2.U; state := offer }
      }.otherwise {
        val k = Wire(keyType)
        k.ticket := a(0); k.generation := a(8, 1); k.owner := a(16, 9)
        k.residency := a(24, 17); k.pair := a(25)
        val s = k.ticket
        when(a(63, 26).orR || b.orR) { diagnostic(FAULT, PROTOCOL); localFault := true.B }
          .elsewhen(!live(s) || keys(s).asUInt =/= k.asUInt ||
            epochs(s) =/= io.epoch || issuers(s) =/= io.issuer) {
            data := Cat(0.U(20.W), k.owner, k.residency, k.pair, 0.U(7.W),
              k.generation, k.ticket, decoded, IDENTITY.U(4.W), INVALID.U(4.W))
            state := reply
          }.otherwise { request.op := 3.U; request.key := k; state := offer }
      }
  }
  when(io.req.fire) { state := typed }
  val r = io.result.bits
  val association = r.op === request.op && r.issuer === issuer && r.epoch === epoch &&
    r.count <= 64.U && r.reason <= PROTOCOL.U
  val acceptedKey = r.key.owner === request.header.owner && r.key.residency === request.header.residency &&
    r.key.pair === request.header.pair && r.key.generation === expectedGeneration &&
    r.key.ticket === expectedTicket && !generationExhausted
  val beginLegal = (r.status === ACCEPTED.U && r.reason === NONE.U && r.count === 0.U && acceptedKey) ||
    (r.status === BUSY_NOT_ACCEPTED.U && r.reason === NONE.U && r.key.asUInt === 0.U) ||
    (r.status === EXHAUSTED.U && r.reason === STATE.U && r.key.asUInt === 0.U) ||
    (r.status === INVALID.U && r.reason === HEADER.U && r.key.asUInt === 0.U)
  val transferLegal = r.status === LOAD_OK.U && r.reason === NONE.U && r.key.asUInt === active.asUInt &&
    Mux(request.op === 1.U, r.count === Mux(activeRows - count > 4.U, count + 4.U, activeRows), r.count === activeRows)
  val applyLegal = r.key.asUInt === request.key.asUInt &&
    ((r.status === APPLIED.U && r.reason === NONE.U && r.count === rowCounts(request.key.ticket)) ||
      (r.status === INVALID.U && r.reason === IDENTITY.U) ||
      ((r.status === BUSY.U || r.status === NOT_READY.U) && r.reason === STATE.U))
  // Feeder starts each result with activeKey/count. BEGIN's Queue decision
  // may replace that with zero (rejection) or the fully checked allocation.
  // PUSH can acknowledge a strict prefix before a later lane fails. APPLY
  // substitutes the supplied full key; terminal() still copies active count.
  val beginFaultIdentity =
    ((r.key.asUInt === active.asUInt || r.key.asUInt === 0.U) && r.count === count) ||
      (acceptedKey && r.count === 0.U)
  val pushLimit = Mux(filling, Mux(activeRows - count > 4.U, count +& 4.U, activeRows), count)
  val faultIdentity = Mux(request.op === 0.U, beginFaultIdentity,
    Mux(request.op === 3.U, r.key.asUInt === request.key.asUInt && r.count === count,
      r.key.asUInt === active.asUInt && Mux(request.op === 1.U,
        r.count >= count && r.count <= pushLimit, r.count === count)))
  val faultLegal = r.status === FAULT.U && r.reason =/= NONE.U && faultIdentity
  val validResult = association && (faultLegal ||
    Mux(request.op === 0.U, beginLegal, Mux(request.op === 3.U, applyLegal, transferLegal)))
  when(state === typed && io.result.valid) {
    retained := r; fromTyped := true.B
    when(!validResult) {
      data := pack(FAULT.U(4.W), RESPONSE.U(4.W), zero, 0.U)
      localFault := true.B; localReason := RESPONSE.U
    }.elsewhen(blocked) {
      // First physical presentation is checked now. Only state==reply has
      // already offered an immutable historical disposition to the CPU.
      data := pack(FAULT.U(4.W), blockedReason, zero, 0.U)
    }.otherwise {
      data := pack(r.status, r.reason, r.key, r.count)
      when(r.status === FAULT.U) { localFault := true.B; localReason := r.reason }
      when(request.op === 0.U && r.status === ACCEPTED.U) {
        val s = r.key.ticket
        keys(s) := r.key; epochs(s) := epoch; issuers(s) := issuer
        live(s) := true.B; sealedSlot(s) := false.B; active := r.key
        activeRows := request.header.activeRows; count := 0.U; filling := true.B
        rowCounts(s) := request.header.activeRows
        expectedTicket := ~expectedTicket
        when(expectedGeneration === 255.U) { generationExhausted := true.B }
          .otherwise { expectedGeneration := expectedGeneration + 1.U }
      }
      when(request.op === 1.U && r.status === LOAD_OK.U) { count := r.count }
      when(request.op === 2.U && r.status === LOAD_OK.U) { filling := false.B; sealedSlot(active.ticket) := true.B }
      when(request.op === 3.U && r.status === APPLIED.U) {
        live(r.key.ticket) := false.B
        publicationValid(r.key.pair) := true.B; publicationKey(r.key.pair) := r.key
        publicationIssuer(r.key.pair) := issuer; publicationEpoch(r.key.pair) := epoch
      }
    }
    state := reply
  }
  when(state =/= idle && age < 4096.U) { age := age + 1.U }
  // Missing typed delivery faults finitely but cannot retire its request.
  // No result is fabricated: retain state/debt until the genuine peer returns.
  when(typedExpired) {
    localFault := true.B; localReason := RESPONSE.U
  }
  when(state === guardWait && !guardDebt && !blocked && io.guardEligible && io.guardDrained) { guardOffered := true.B }
  when(io.guard.fire) { guardOffered := false.B; guardAccepted := true.B }
  when(io.guardResp.fire) {
    guardAccepted := false.B
    when(state === guardWait && !guardFailed && !blocked) {
      when(io.guardResp.bits.rd === 1.U && io.guardResp.bits.status === PoTalScaleCommandStatus.OK) {
        local(10.U(4.W), NONE.U(4.W))
      }.otherwise { local(FAULT.U(4.W), RESPONSE.U(4.W)); localFault := true.B; localReason := RESPONSE.U }
    }
  }
  when((state === guardWait || (state === reply && !fromTyped)) && age === 4095.U && !io.resp.fire) {
    localFault := true.B; localReason := IO_TIMEOUT.U
    when(state === guardWait) { local(FAULT.U(4.W), IO_TIMEOUT.U(4.W)); guardFailed := true.B }
  }
  when(contextBad || io.contextChanged || stageExpired || localFault || io.feederFault) {
    when(!fault) {
      fault := true.B
      reason := Mux(localFault, localReason, Mux(io.feederFault, io.feederReason,
        Mux(stageExpired, IO_TIMEOUT.U, IDENTITY.U)))
    }
    when(state === guardWait) {
      local(FAULT.U(4.W), Mux(localFault, localReason,
        Mux(io.feederFault, io.feederReason, IDENTITY.U(4.W))))
      guardFailed := true.B
    }
    when(state === offer) { local(FAULT.U(4.W), blockedReason) }
  }
  when(io.resp.fire) { state := idle; fromTyped := false.B }
  val held = RegNext(io.resp.valid && !io.resp.ready, false.B)
  val previous = RegNext(io.resp.bits.asUInt)
  when(held) { assert(io.resp.valid && io.resp.bits.asUInt === previous) }
  when(state === reply && fromTyped) { assert(io.result.valid && io.result.bits.asUInt === retained.asUInt) }
}
