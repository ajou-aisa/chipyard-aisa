package potal

import chisel3._
import chisel3.util._

// Local typed commands, deliberately not physical RoCC encodings.
final class PoTalIndexRegisterRequest(d: Int, o: Int, r: Int, g: Int) extends Bundle {
  val op = UInt(2.W)
  val header = new PoTalIndexWindowHeader(o, r)
  val key = new PoTalIndexWindowKey(d, o, r, g)
  val rs1 = UInt(64.W)
  val rs2 = UInt(64.W)
}
final class PoTalIndexRegisterResult(d: Int, n: Int, o: Int, r: Int, g: Int) extends Bundle {
  val op = UInt(2.W)
  val issuer = UInt(o.W)
  val epoch = UInt(64.W)
  val key = new PoTalIndexWindowKey(d, o, r, g)
  val status = UInt(4.W)
  val reason = UInt(4.W)
  val count = UInt(PoTalIndexWindow.width(n + 1).W)
}

// Owns Queue control/loader transactions. The unchanged Queue may be composed
// outside this module; no other producer may drive its ctl or load.
final class PoTalIndexRegisterFeeder(
  depth: Int, capacity: Int, ownerWidth: Int, residencyWidth: Int,
  generationWidth: Int, responseDeadline: Int
) extends Module {
  import PoTalIndexWindow._
  require(responseDeadline >= 2)
  def keyType = new PoTalIndexWindowKey(depth, ownerWidth, residencyWidth, generationWidth)
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new PoTalIndexRegisterRequest(depth, ownerWidth, residencyWidth, generationWidth)))
    val result = Decoupled(new PoTalIndexRegisterResult(depth, capacity, ownerWidth, residencyWidth, generationWidth))
    val contextValid = Input(Bool())
    val issuer = Input(UInt(ownerWidth.W))
    val epoch = Input(UInt(64.W))
    val contextChanged = Input(Bool())
    val q = Flipped(new PoTalIndexWindowQueueIO(depth, capacity, ownerWidth, residencyWidth, generationWidth))
    val wrapperFault = Output(Bool())
    val wrapperReason = Output(UInt(4.W))
    val consumerBlocked = Output(Bool())
    val aggregateBusy = Output(Bool())
    val closeBlocked = Output(Bool())
  })
  val idle :: offer :: waitCtl :: data :: done :: waitApply :: reply :: quarantine :: Nil = Enum(8)
  val state = RegInit(idle)
  val request = Reg(new PoTalIndexRegisterRequest(depth, ownerWidth, residencyWidth, generationWidth))
  val result = RegInit(0.U.asTypeOf(io.result.bits))
  val pending = RegInit(false.B)
  val fault = RegInit(false.B)
  val faultReason = RegInit(0.U(4.W))
  val age = RegInit(0.U(width(responseDeadline + 1).W))
  val header = Reg(new PoTalIndexWindowHeader(ownerWidth, residencyWidth))
  val filling = RegInit(false.B)
  val activeKey = RegInit(0.U.asTypeOf(keyType))
  val count = RegInit(0.U(width(capacity + 1).W))
  val words = Reg(Vec(4, UInt(32.W)))
  val lane = RegInit(0.U(2.W))
  val lanes = Reg(UInt(3.W))
  val keys = Reg(Vec(depth, keyType))
  val epochs = Reg(Vec(depth, UInt(64.W)))
  val live = RegInit(VecInit(Seq.fill(depth)(false.B)))
  val sealedSlot = RegInit(VecInit(Seq.fill(depth)(false.B)))
  val sessionSet = RegInit(false.B)
  val sessionIssuer = Reg(UInt(ownerWidth.W))
  val sessionEpoch = Reg(UInt(64.W))
  val errorSent = RegInit(false.B)
  val owned = VecInit((0 until depth).map(i => live(i) && io.q.entryState(i) =/= FREE.U))
  val ownedKey = keys(PriorityEncoder(owned.asUInt))
  val obligation = pending || live.asUInt.orR || io.q.publishedValid.asUInt.orR
  val contextBad = io.contextChanged || (obligation && sessionSet &&
    (!io.contextValid || io.issuer =/= sessionIssuer || io.epoch =/= sessionEpoch))
  val replyTimeout = pending && age === (responseDeadline - 1).U && !io.result.fire
  val knownFault = contextBad || replyTimeout
  val blocked = fault || knownFault || io.q.poisoned
  io.req.ready := state === idle && !blocked && !reset.asBool
  io.result.valid := pending && state === reply
  io.result.bits := result
  io.wrapperFault := fault
  io.wrapperReason := faultReason
  io.aggregateBusy := io.q.fenceBusy || state =/= idle || pending || blocked
  io.closeBlocked := io.q.closeBlocked || io.aggregateBusy

  // These non-loader Queue ports are owned by the composing harness.
  io.q.cmd.ready := false.B
  io.q.resp.valid := false.B
  io.q.resp.bits := 0.U.asTypeOf(io.q.resp.bits)
  io.q.indexUpdateSafe.foreach(_ := false.B)
  io.q.externalApplyPermit.foreach(_ := false.B)
  io.q.installerAvailable := false.B
  io.q.consumer.valid := false.B
  io.q.consumer.bits := 0.U.asTypeOf(keyType)
  io.q.ctl.valid := state === offer && !blocked
  io.q.ctl.bits.op := request.op === 3.U
  io.q.ctl.bits.header := request.header
  io.q.ctl.bits.key := request.key
  io.q.ctlResp.ready := true.B
  val sendError = blocked && owned.asUInt.orR && !errorSent && !io.q.poisoned
  io.q.load.valid := !reset.asBool && (sendError || (!blocked && (state === data || state === done)))
  io.q.load.bits.key := Mux(sendError, ownedKey, activeKey)
  io.q.load.bits.kind := Mux(sendError, LOAD_ERROR.U, Mux(state === done, DONE.U, DATA.U))
  io.q.load.bits.residentK := Mux(state === data && !sendError, words(lane), 0.U)
  val ackOK = io.q.loadAck.valid && io.q.loadAck.bits.key.asUInt === activeKey.asUInt &&
    io.q.loadAck.bits.status === LOAD_OK.U && io.q.loadAck.bits.reason === NONE.U
  val badAck = (io.q.load.fire && !sendError && !ackOK) ||
    (io.q.loadAck.valid && !io.q.load.fire)
  val localFault = WireDefault(false.B)
  val localReason = WireDefault(PROTOCOL.U(4.W))
  // Consumer admission is not a Fence: unrelated filling and held replies
  // preserve the old published table. Detectable faults block immediately.
  io.consumerBlocked := blocked || localFault || badAck
  def terminal(status: UInt, reason: UInt): Unit = {
    result.status := status; result.reason := reason; result.count := count
    state := reply
  }
  when(io.req.fire) {
    request := io.req.bits
    pending := true.B; age := 0.U
    result := 0.U.asTypeOf(result)
    result.op := io.req.bits.op; result.issuer := io.issuer; result.epoch := io.epoch
    result.key := activeKey; result.count := count
    when(!sessionSet) { sessionSet := true.B; sessionIssuer := io.issuer; sessionEpoch := io.epoch }
    val op = io.req.bits.op
    val unusedHeader = io.req.bits.header.asUInt === 0.U
    val unusedKey = io.req.bits.key.asUInt === 0.U
    val noWords = !io.req.bits.rs1.orR && !io.req.bits.rs2.orR
    val selected = io.req.bits.key.ticket
    val matching = selected < depth.U && live(selected) &&
      keys(selected).asUInt === io.req.bits.key.asUInt && epochs(selected) === io.epoch
    when(!io.contextValid || (op === 0.U && io.req.bits.header.owner =/= io.issuer)) {
      localFault := true.B; localReason := IDENTITY.U
    }.elsewhen(op === 0.U) {
      when(filling || !unusedKey || !noWords) { localFault := true.B }
        .otherwise { state := offer }
    }.elsewhen(op === 1.U) {
      val remaining = header.activeRows - count
      val n = Mux(remaining > 4.U, 4.U, remaining)
      val incoming = VecInit(Seq(io.req.bits.rs1(31, 0), io.req.bits.rs1(63, 32),
        io.req.bits.rs2(31, 0), io.req.bits.rs2(63, 32)))
      val legal = (0 until 4).map(i => Mux(i.U < n,
        incoming(i) < header.sourceKRows, incoming(i) === 0.U)).reduce(_ && _)
      when(!filling || count >= header.activeRows || !unusedHeader || !unusedKey || !legal) {
        localFault := true.B; localReason := K_BOUNDS.U
      }.otherwise { words := incoming; lanes := n; lane := 0.U; state := data }
    }.elsewhen(op === 2.U) {
      when(!filling || count =/= header.activeRows || !unusedHeader || !unusedKey || !noWords) {
        localFault := true.B; localReason := COUNT.U
      }.otherwise { state := done }
    }.otherwise {
      result.key := io.req.bits.key
      result.count := Mux(matching, io.q.entryCount(selected), 0.U)
      when(!unusedHeader || !noWords) { localFault := true.B }
        .elsewhen(!matching) { terminal(INVALID.U, IDENTITY.U) }
        .elsewhen(!sealedSlot(selected)) { terminal(NOT_READY.U, STATE.U) }
        .otherwise { state := offer }
    }
  }
  when(pending && age < responseDeadline.U) { age := age + 1.U }
  when(io.q.ctl.fire) { state := waitCtl }
  when(io.q.ctlResp.fire && state === waitCtl) {
    result.key := io.q.ctlResp.bits.key
    when(request.op === 0.U && io.q.ctlResp.bits.status === ACCEPTED.U) {
      // Internal attachment precedes and is independent of CPU ready.
      activeKey := io.q.ctlResp.bits.key; header := request.header
      count := 0.U; result.count := 0.U; filling := true.B
      val s = io.q.ctlResp.bits.key.ticket
      keys(s) := io.q.ctlResp.bits.key; epochs(s) := result.epoch
      live(s) := true.B; sealedSlot(s) := false.B
    }
    when(request.op === 3.U && io.q.ctlResp.bits.status === APPLY_STARTED.U) { state := waitApply }
      .otherwise {
        terminal(io.q.ctlResp.bits.status, io.q.ctlResp.bits.reason)
        when(request.op === 0.U && io.q.ctlResp.bits.status === ACCEPTED.U) { result.count := 0.U }
        when(request.op === 3.U) { result.count := io.q.entryCount(request.key.ticket) }
      }
  }
  when(io.q.load.fire && !sendError && ackOK && !blocked) {
    when(state === data) {
      count := count + 1.U
      when(lane +& 1.U === lanes) {
        terminal(LOAD_OK.U, NONE.U); result.count := count + 1.U
      }.otherwise { lane := lane + 1.U }
    }.elsewhen(state === done) {
      filling := false.B; sealedSlot(activeKey.ticket) := true.B
      terminal(LOAD_OK.U, NONE.U)
    }
  }
  when(io.q.finished.valid && io.q.finished.bits.status === APPLIED.U) {
    live(io.q.finished.bits.key.ticket) := false.B
    when(state === waitApply) {
      result.key := io.q.finished.bits.key
      terminal(APPLIED.U, NONE.U)
      result.count := io.q.entryCount(io.q.finished.bits.key.ticket)
    }
  }
  when(io.result.fire) { pending := false.B; state := Mux(blocked, quarantine, idle) }
  when(io.q.load.fire && sendError) { errorSent := true.B }
  when(knownFault || localFault || badAck || io.q.poisoned) {
    when(!fault) {
      fault := true.B
      faultReason := Mux(localFault, localReason, Mux(badAck, RESPONSE.U, PROTOCOL.U))
    }
    // Already offered CPU results are immutable, even after late faults.
    when(state =/= reply) {
      when(pending || io.req.fire) {
        terminal(FAULT.U, Mux(localFault, localReason, Mux(badAck, RESPONSE.U, PROTOCOL.U)))
      }.otherwise { state := quarantine }
    }
  }
}
