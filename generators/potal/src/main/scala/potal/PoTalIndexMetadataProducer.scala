package potal

import chisel3._
import chisel3.util._

final class PoTalIndexMetadataStart(
  depth: Int, ownerWidth: Int, residencyWidth: Int, generationWidth: Int, addressWidth: Int
) extends Bundle {
  val key = new PoTalIndexWindowKey(depth, ownerWidth, residencyWidth, generationWidth)
  val sourceAddress = UInt(addressWidth.W)
  val bufferBase = UInt(addressWidth.W)
  val bufferBytes = UInt(addressWidth.W)
  val count = UInt(32.W)
  val activeRows = UInt(32.W)
  val sourceKRows = UInt(32.W)
}

final class PoTalIndexMetadataProducerIO(
  depth: Int, maxEntries: Int, ownerWidth: Int, residencyWidth: Int,
  generationWidth: Int, addressWidth: Int
) extends Bundle {
  def keyType = new PoTalIndexWindowKey(depth, ownerWidth, residencyWidth, generationWidth)
  val start = Flipped(Decoupled(new PoTalIndexMetadataStart(
    depth, ownerWidth, residencyWidth, generationWidth, addressWidth)))
  val memReq = Decoupled(new Bundle {
    val key = keyType
    val index = UInt(PoTalIndexWindow.width(maxEntries + 1).W)
    val address = UInt(addressWidth.W)
    val last = Bool()
  })
  val memResp = Flipped(Decoupled(new Bundle {
    val key = keyType
    val index = UInt(PoTalIndexWindow.width(maxEntries + 1).W)
    val address = UInt(addressWidth.W)
    val data = UInt(32.W)
    val mask = UInt(4.W)
    val complete = Bool()
    val last = Bool()
    val error = Bool()
  }))
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
}

// Independent byte-address domain, not a Gemmini DMA or coherence adapter.
// complete certifies no further accesses/responses for that exact request.
// Reset is permitted only after joint transport/Queue/consumer quiescence.
final class PoTalIndexMetadataProducer(
  depth: Int, maxEntries: Int, ownerWidth: Int, residencyWidth: Int,
  ticketGenerationWidth: Int, addressWidth: Int, deadline: Int
) extends Module {
  import PoTalIndexWindow._
  require(depth > 0 && maxEntries > 0 && addressWidth >= 2 && deadline >= 2)
  require(ownerWidth > 0 && residencyWidth > 0 && ticketGenerationWidth > 0)
  val io = IO(new PoTalIndexMetadataProducerIO(
    depth, maxEntries, ownerWidth, residencyWidth, ticketGenerationWidth, addressWidth))
  val idle :: offer :: waitResponse :: data :: done :: quarantine :: Nil = Enum(6)
  val state = RegInit(idle)
  val context = Reg(new PoTalIndexMetadataStart(
    depth, ownerWidth, residencyWidth, ticketGenerationWidth, addressWidth))
  val index = RegInit(0.U(width(maxEntries + 1).W))
  val word = Reg(UInt(32.W))
  val age = RegInit(0.U(width(deadline + 1).W))
  val reason = RegInit(0.U(4.W))
  val lease = RegInit(false.B)
  val outstanding = RegInit(false.B)
  val requestHeld = RegInit(false.B)
  val loadHeld = RegInit(false.B)
  val heldKind = Reg(UInt(2.W))
  val errorSent = RegInit(false.B)
  val failed = reason =/= 0.U
  val enabled = !reset.asBool
  // ACCEPTED must attach even when poison races its registered observation.
  io.start.ready := state === idle && !failed && enabled
  val starting = io.start.fire
  val currentKey = Mux(starting, io.start.bits.key, context.key)
  val s = io.start.bits
  val arithmeticWidth = math.max(addressWidth + 1, 35)
  val sourceEnd = s.sourceAddress.pad(arithmeticWidth) + (s.count.pad(arithmeticWidth) << 2)
  val bufferEnd = s.bufferBase.pad(arithmeticWidth) + s.bufferBytes.pad(arithmeticWidth)
  val limit = (BigInt(1) << addressWidth).U(arithmeticWidth.W)
  val sourceValid = s.count =/= 0.U && s.count <= maxEntries.U &&
    s.count === s.activeRows && s.sourceAddress(1, 0) === 0.U &&
    s.bufferBytes =/= 0.U && s.sourceAddress >= s.bufferBase &&
    sourceEnd <= bufferEnd && sourceEnd <= limit && bufferEnd <= limit
  val address = (context.sourceAddress + (index.pad(addressWidth) << 2))(addressWidth - 1, 0)
  val last = index === context.count - 1.U
  val r = io.memResp.bits
  val identity = r.key.asUInt === context.key.asUInt
  val position = r.index === index && r.address === address
  val terminal = outstanding && identity && position && r.complete
  val responseReason = WireDefault(0.U(4.W))
  when(io.memResp.valid && state =/= quarantine && !failed) {
    when(state =/= waitResponse || !outstanding) { responseReason := 3.U }
      .elsewhen(!identity) { responseReason := 2.U }
      .elsewhen(!position) { responseReason := 3.U }
      .elsewhen(r.mask =/= 15.U || !r.complete || r.last =/= last) { responseReason := 4.U }
      .elsewhen(r.error) { responseReason := 5.U }
      .elsewhen(r.data >= context.sourceKRows) { responseReason := 6.U }
  }
  // No loadAck feedback into load.valid/bits: Queue ack depends on load.fire.
  // At the deadline a final offered DONE may complete; a bad ack faults below.
  val timelyDone = state === done && io.load.ready
  val cause = WireDefault(0.U(4.W))
  when(io.queuePoisoned) { cause := 9.U }
    .elsewhen(responseReason =/= 0.U) { cause := responseReason }
    .elsewhen(starting && !sourceValid) { cause := 1.U }
    .elsewhen(state =/= idle && state =/= quarantine &&
      age === (deadline - 1).U && !timelyDone) { cause := 7.U }
  val faultNow = enabled && !failed && cause =/= 0.U
  val blocked = failed || faultNow
  io.memReq.valid := enabled && state === offer && (requestHeld || !blocked)
  io.memReq.bits.key := context.key
  io.memReq.bits.index := index
  io.memReq.bits.address := address
  io.memReq.bits.last := last
  io.memResp.ready := enabled && !loadHeld &&
    (state === waitResponse || state === quarantine || state === idle || failed)
  val sendError = blocked && !errorSent && io.contextLive && !io.queuePoisoned
  io.load.valid := enabled && (loadHeld || sendError ||
    (!blocked && (state === data || state === done)))
  io.load.bits.key := currentKey
  io.load.bits.kind := Mux(loadHeld, heldKind,
    Mux(sendError, LOAD_ERROR.U, Mux(state === done, DONE.U, DATA.U)))
  io.load.bits.residentK := Mux(io.load.bits.kind === DATA.U, word, 0.U)
  val ackOK = io.loadAck.valid && io.loadAck.bits.key.asUInt === currentKey.asUInt &&
    io.loadAck.bits.status === LOAD_OK.U
  io.busy := state =/= idle || failed
  io.sourceLease := lease
  io.transportUncertain := outstanding || requestHeld
  io.faulted := failed
  io.faultReason := reason
  io.contextKey := context.key

  when(enabled) {
    when(starting) {
      context := s; index := 0.U; age := 0.U; lease := true.B
      errorSent := false.B; state := offer
    }
    when(state =/= idle && age < deadline.U) { age := age + 1.U }
    requestHeld := io.memReq.valid && !io.memReq.ready
    loadHeld := io.load.valid && !io.load.ready
    when(io.load.valid && !io.load.ready) { heldKind := io.load.bits.kind }
    when(io.memReq.fire) { outstanding := true.B; state := waitResponse }
    when(io.memResp.fire && terminal) { outstanding := false.B }
    when(!blocked) {
      when(state === waitResponse && io.memResp.fire) { word := r.data; state := data }
      when(io.load.fire && ackOK) {
        when(state === data) {
          when(last) { state := done }
            .otherwise { index := index + 1.U; state := offer }
        }.elsewhen(state === done) { state := idle; lease := false.B }
      }
    }
    when(io.load.fire && io.load.bits.kind === LOAD_ERROR.U) { errorSent := true.B }
    when(faultNow) { reason := cause }
    val badAck = io.load.fire && io.load.bits.kind =/= LOAD_ERROR.U && !ackOK
    when(!failed && !faultNow && badAck) { reason := 8.U }
    when(blocked || badAck) {
      // Preserve only offers actually exposed in a preceding stalled cycle.
      // Their payload/context/index cannot move until their handshake finishes.
      when(requestHeld && !io.memReq.fire) { state := offer }
        .elsewhen(loadHeld && !io.load.fire) { state := state }
        .otherwise { state := quarantine }
      // Fault release is transport completion, not healthy Queue completion.
      // A newly firing irrevocable request must retain the lease.
      when(!outstanding && !requestHeld && !io.memReq.fire) { lease := false.B }
    }
  }
}
