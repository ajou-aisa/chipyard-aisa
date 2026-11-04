package potal

import chisel3._
import chisel3.util._

// Local protocol only; these values are not RoCC funct encodings.
object PoTalIndexWindow {
  val ACCEPTED = 0; val BUSY_NOT_ACCEPTED = 1; val INVALID = 2
  val NOT_READY = 3; val BUSY = 4; val APPLY_STARTED = 5
  val APPLIED = 6; val FAULT = 7; val EXHAUSTED = 8; val LOAD_OK = 9
  val NONE = 0; val HEADER = 1; val IDENTITY = 2; val STATE = 3
  val COUNT = 4; val K_BOUNDS = 5; val LOADER = 6
  val LOAD_TIMEOUT = 7; val RESPONSE = 8; val IO_TIMEOUT = 9; val PROTOCOL = 10
  val FREE = 0; val FILLING = 1; val READY = 2; val APPLYING = 3; val ERROR = 4
  val DATA = 0; val DONE = 1; val LOAD_ERROR = 2
  def width(n: Int): Int = math.max(1, log2Ceil(n))
}

final class PoTalIndexWindowKey(
  depth: Int, ownerWidth: Int, residencyWidth: Int, ticketGenerationWidth: Int
) extends Bundle {
  val ticket = UInt(PoTalIndexWindow.width(depth).W)
  val generation = UInt(ticketGenerationWidth.W)
  val owner = UInt(ownerWidth.W)
  val residency = UInt(residencyWidth.W)
  val pair = UInt(PoTalProtocol.WeightBufferIdWidth.W)
}

final class PoTalIndexWindowHeader(ownerWidth: Int, residencyWidth: Int) extends Bundle {
  val owner = UInt(ownerWidth.W)
  val residency = UInt(residencyWidth.W)
  val pair = UInt(PoTalProtocol.WeightBufferIdWidth.W)
  val bBase = UInt(32.W)
  val jTiles = UInt(32.W)
  val sourceKRows = UInt(32.W)
  val activeRows = UInt(32.W)
}

final class PoTalIndexWindowResult(
  depth: Int, ownerWidth: Int, residencyWidth: Int, ticketGenerationWidth: Int
) extends Bundle {
  val status = UInt(4.W)
  val reason = UInt(4.W)
  val key = new PoTalIndexWindowKey(depth, ownerWidth, residencyWidth, ticketGenerationWidth)
}

final class PoTalIndexWindowQueueIO(
  depth: Int, maxEntries: Int, ownerWidth: Int, residencyWidth: Int,
  ticketGenerationWidth: Int, pairCount: Int = PoTalProtocol.WeightBufferCount
) extends Bundle {
  def keyType = new PoTalIndexWindowKey(depth, ownerWidth, residencyWidth, ticketGenerationWidth)
  def resultType = new PoTalIndexWindowResult(depth, ownerWidth, residencyWidth, ticketGenerationWidth)
  val ctl = Flipped(Decoupled(new Bundle {
    val op = Bool()
    val header = new PoTalIndexWindowHeader(ownerWidth, residencyWidth)
    val key = keyType
  }))
  val ctlResp = Decoupled(resultType)
  val load = Flipped(Decoupled(new Bundle {
    val key = keyType
    val kind = UInt(2.W)
    val residentK = UInt(32.W)
  }))
  val loadAck = Valid(resultType)
  val finished = Valid(resultType)
  val cmd = Decoupled(new PoTalScaleCommand)
  val resp = Flipped(Decoupled(new PoTalScaleCommandResponse))
  val indexUpdateSafe = Input(Vec(pairCount, Bool()))
  val externalApplyPermit = Input(Vec(pairCount, Bool()))
  val installerAvailable = Input(Bool())
  val consumer = Flipped(Valid(keyType))
  val consumerPermit = Output(Bool())
  val installerLock = Output(Bool())
  val poisoned = Output(Bool())
  val fenceBusy = Output(Bool())
  val closeBlocked = Output(Bool())
  val faultReason = Output(UInt(4.W))
  val faultKey = Output(keyType)
  val occupancy = Output(UInt(PoTalIndexWindow.width(depth + 1).W))
  val entryState = Output(Vec(depth, UInt(3.W)))
  val entryCount = Output(Vec(depth, UInt(PoTalIndexWindow.width(maxEntries + 1).W)))
  val publishedValid = Output(Vec(pairCount, Bool()))
  val publishedKey = Output(Vec(pairCount, keyType))
}

// Reset is a fresh isolated lifetime: every producer/consumer must be quiesced.
// Owner and residency issuers must not recycle identities while consumers live.
final class PoTalIndexWindowQueue(
  depth: Int = 2, maxEntries: Int, dim: Int, indexEntries: Int, spRows: Int,
  ownerWidth: Int, residencyWidth: Int, ticketGenerationWidth: Int,
  loadDeadline: Int, ioDeadline: Int,
  pairCount: Int = PoTalProtocol.WeightBufferCount
) extends Module {
  import PoTalIndexWindow._
  require(depth >= 1 && maxEntries >= 1 && maxEntries <= indexEntries)
  require(Set(16, 32, 64).contains(dim) && indexEntries >= dim)
  require(spRows > 0 && spRows % 4 == 0)
  require(pairCount == PoTalProtocol.WeightBufferCount && pairCount == 2)
  require(PoTalProtocol.WeightBufferIdWidth == 1)
  require(ownerWidth >= 1 && residencyWidth >= 1 && ticketGenerationWidth >= 1)
  require(loadDeadline >= 2 && ioDeadline >= 2)

  val io = IO(new PoTalIndexWindowQueueIO(
    depth, maxEntries, ownerWidth, residencyWidth, ticketGenerationWidth, pairCount))
  def keyType = new PoTalIndexWindowKey(depth, ownerWidth, residencyWidth, ticketGenerationWidth)
  def resultType = new PoTalIndexWindowResult(depth, ownerWidth, residencyWidth, ticketGenerationWidth)
  val zeroKey = 0.U.asTypeOf(keyType)
  val headers = Reg(Vec(depth, new PoTalIndexWindowHeader(ownerWidth, residencyWidth)))
  val payload = Reg(Vec(depth, Vec(maxEntries, UInt(32.W))))
  val generations = Reg(Vec(depth, UInt(ticketGenerationWidth.W)))
  val states = RegInit(VecInit(Seq.fill(depth)(FREE.U(3.W))))
  val counts = RegInit(VecInit(Seq.fill(depth)(0.U(width(maxEntries + 1).W))))
  val ages = RegInit(VecInit(Seq.fill(depth)(0.U(width(loadDeadline + 1).W))))
  val head = RegInit(0.U(width(depth).W))
  val tail = RegInit(0.U(width(depth).W))
  val occupancy = RegInit(0.U(width(depth + 1).W))
  val nextGeneration = RegInit(1.U(ticketGenerationWidth.W))
  val exhausted = RegInit(false.B)
  val idle = 0.U(3.W); val configSend = 1.U(3.W); val configWait = 2.U(3.W)
  val writeSend = 3.U(3.W); val writeWait = 4.U(3.W)
  val phase = RegInit(idle)
  val cursor = RegInit(0.U(width(maxEntries + 1).W))
  val ioAge = RegInit(0.U(width(ioDeadline + 1).W))
  val publishedValid = RegInit(VecInit(Seq.fill(pairCount)(false.B)))
  val publishedKey = RegInit(VecInit(Seq.fill(pairCount)(zeroKey)))
  val poison = RegInit(false.B)
  val faultReason = RegInit(NONE.U(4.W))
  val faultKey = RegInit(zeroKey)
  val replyPending = RegInit(false.B)
  val reply = RegInit(0.U.asTypeOf(resultType))

  def itemKey(slot: UInt): PoTalIndexWindowKey = {
    val k = Wire(keyType)
    k.ticket := slot
    k.generation := generations(slot)
    k.owner := headers(slot).owner
    k.residency := headers(slot).residency
    k.pair := headers(slot).pair
    k
  }
  def matches(k: PoTalIndexWindowKey): Bool =
    k.ticket < depth.U && states(k.ticket) =/= FREE.U &&
      k.asUInt === itemKey(k.ticket).asUInt
  def advance(slot: UInt): UInt = Mux(slot === (depth - 1).U, 0.U, slot + 1.U)

  val activeKey = itemKey(head)
  val h = headers(head)
  val sending = phase === configSend || phase === writeSend
  val waiting = phase === configWait || phase === writeWait
  io.cmd.valid := sending && !reset.asBool
  io.cmd.bits.funct := Mux(phase === configSend,
    GemminiISA.POTAL_INDEX_CONFIG, GemminiISA.POTAL_INDEX_WRITE)
  io.cmd.bits.rd := 1.U
  io.cmd.bits.xd := true.B
  io.cmd.bits.rs1 := Mux(phase === configSend, Cat(h.jTiles, h.bBase), cursor.pad(64))
  io.cmd.bits.rs2 := Mux(phase === configSend,
    Cat(h.activeRows, h.sourceKRows), payload(head)(cursor).pad(64))
  io.resp.ready := !reset.asBool
  io.load.ready := !reset.asBool
  io.ctl.ready := !replyPending && !reset.asBool
  io.ctlResp.valid := replyPending
  io.ctlResp.bits := reply

  val loadKey = io.load.bits.key
  val loadSlot = loadKey.ticket
  val loadMatch = matches(loadKey)
  val loadReason = WireDefault(NONE.U(4.W))
  when(io.load.fire && loadMatch) {
    when(io.load.bits.kind === LOAD_ERROR.U) { loadReason := LOADER.U }
      .elsewhen(states(loadSlot) =/= FILLING.U) { loadReason := STATE.U }
      .elsewhen(io.load.bits.kind === 3.U) { loadReason := PROTOCOL.U }
      .elsewhen(io.load.bits.kind === DONE.U && counts(loadSlot) =/= headers(loadSlot).activeRows) {
        loadReason := COUNT.U
      }.elsewhen(io.load.bits.kind === DATA.U) {
        when(counts(loadSlot) >= headers(loadSlot).activeRows) { loadReason := COUNT.U }
          .elsewhen(io.load.bits.residentK >= headers(loadSlot).sourceKRows) { loadReason := K_BOUNDS.U }
      }
  }
  val expired = Wire(Vec(depth, Bool()))
  for (i <- 0 until depth) {
    val timelyDone = io.load.fire && loadMatch && loadSlot === i.U &&
      io.load.bits.kind === DONE.U && loadReason === NONE.U
    expired(i) := states(i) === FILLING.U && ages(i) === (loadDeadline - 1).U && !timelyDone
  }
  val expiredSlot = PriorityEncoder(expired.asUInt)
  val badResponse = io.resp.fire &&
    (!waiting || io.resp.bits.rd =/= 1.U || io.resp.bits.status =/= PoTalScaleCommandStatus.OK)
  val phaseFire = Mux(sending, io.cmd.fire, io.resp.fire)
  val ioExpired = phase =/= idle && ioAge === (ioDeadline - 1).U && !phaseFire
  val cause = WireDefault(NONE.U(4.W))
  val culprit = WireDefault(zeroKey)
  when(loadReason =/= NONE.U) {
    cause := loadReason; culprit := loadKey
  }.elsewhen(expired.asUInt.orR) {
    cause := LOAD_TIMEOUT.U; culprit := itemKey(expiredSlot)
  }.elsewhen(badResponse) {
    cause := RESPONSE.U
    culprit := Mux(phase =/= idle, activeKey, zeroKey)
  }.elsewhen(ioExpired) {
    cause := IO_TIMEOUT.U; culprit := activeKey
  }
  val faultNow = !poison && cause =/= NONE.U && !reset.asBool
  val blocked = poison || faultNow
  val effectiveReason = Mux(poison, faultReason, cause)

  // Widen before rounding/multiplication/addition: never validate a truncated extent.
  val incoming = io.ctl.bits.header
  val bankRows = spRows / 4
  val bankStart = Mux(incoming.pair === 0.U, bankRows.U, (3 * bankRows).U)
  val bankEnd = Mux(incoming.pair === 0.U, (2 * bankRows).U, spRows.U)
  val sourceTiles = (incoming.sourceKRows +& (dim - 1).U) >> log2Ceil(dim)
  val regionEnd = incoming.bBase +& (sourceTiles * incoming.jTiles * dim.U)
  val headerValid = incoming.pair < pairCount.U &&
    incoming.jTiles =/= 0.U && incoming.jTiles <= 65535.U &&
    incoming.sourceKRows =/= 0.U && incoming.activeRows =/= 0.U &&
    incoming.activeRows <= maxEntries.U && incoming.bBase >= bankStart &&
    incoming.bBase < bankEnd && regionEnd <= bankEnd
  val admission = WireDefault(0.U.asTypeOf(resultType))
  admission.status := ACCEPTED.U
  val requestKey = io.ctl.bits.key
  when(io.ctl.bits.op) {
    admission.key := requestKey
    when(!matches(requestKey)) {
      admission.status := INVALID.U; admission.reason := IDENTITY.U
    }.elsewhen(requestKey.ticket =/= head || states(head) === APPLYING.U) {
      admission.status := BUSY.U; admission.reason := STATE.U
    }.elsewhen(states(head) === FILLING.U) {
      admission.status := NOT_READY.U; admission.reason := STATE.U
    }.elsewhen(states(head) =/= READY.U || !io.indexUpdateSafe(h.pair) ||
      !io.externalApplyPermit(h.pair) || !io.installerAvailable) {
      admission.status := BUSY.U; admission.reason := STATE.U
    }.otherwise { admission.status := APPLY_STARTED.U }
  }.otherwise {
    when(!headerValid) {
      admission.status := INVALID.U; admission.reason := HEADER.U
    }.elsewhen(occupancy === depth.U) { admission.status := BUSY_NOT_ACCEPTED.U }
      .elsewhen(exhausted) {
        admission.status := EXHAUSTED.U; admission.reason := STATE.U
      }.otherwise {
        admission.key.ticket := tail
        admission.key.generation := nextGeneration
        admission.key.owner := incoming.owner
        admission.key.residency := incoming.residency
        admission.key.pair := incoming.pair
      }
  }
  val decision = WireDefault(admission)
  when(blocked) {
    decision.status := FAULT.U; decision.reason := effectiveReason
    when(!io.ctl.bits.op) { decision.key := zeroKey }
  }
  val allocate = io.ctl.fire && decision.status === ACCEPTED.U
  val applyStart = io.ctl.fire && decision.status === APPLY_STARTED.U
  val retire = !blocked && phase === writeWait && io.resp.fire &&
    cursor === h.activeRows - 1.U

  io.loadAck.valid := io.load.fire
  io.loadAck.bits.key := loadKey
  io.loadAck.bits.status := Mux(blocked, FAULT.U, Mux(loadMatch, LOAD_OK.U, INVALID.U))
  io.loadAck.bits.reason := Mux(blocked, effectiveReason, Mux(loadMatch, NONE.U, IDENTITY.U))
  io.finished.valid := faultNow || retire
  io.finished.bits.status := Mux(faultNow, FAULT.U, APPLIED.U)
  io.finished.bits.reason := Mux(faultNow, cause, NONE.U)
  io.finished.bits.key := Mux(faultNow, culprit, activeKey)
  io.installerLock := states.map(_ === APPLYING.U).reduce(_ || _) || poison || applyStart
  io.consumerPermit := io.consumer.valid && !blocked && !io.installerLock &&
    (0 until pairCount).map(p => publishedValid(p) &&
      io.consumer.bits.asUInt === publishedKey(p).asUInt).reduce(_ || _)
  // ioExpired implies phase!=IDLE, which already makes Fence busy. Factor that
  // term out of admission here to avoid ready -> timeout -> fenceBusy -> ready
  // when the real ingress qualifies a Fence. This is the same Boolean predicate
  // as ongoing work OR allocate OR applyStart, not an extra refusal condition.
  val admissionBusy = io.ctl.fire && !poison && loadReason === NONE.U &&
    !expired.asUInt.orR && !badResponse &&
    (admission.status === ACCEPTED.U || admission.status === APPLY_STARTED.U)
  io.fenceBusy := states.map(s => s === FILLING.U || s === APPLYING.U || s === ERROR.U)
    .reduce(_ || _) || phase =/= idle || replyPending || poison || admissionBusy
  io.closeBlocked := occupancy =/= 0.U || io.fenceBusy
  io.occupancy := occupancy
  io.entryState := states
  io.entryCount := counts
  io.publishedValid := publishedValid
  io.publishedKey := publishedKey
  io.poisoned := poison
  io.faultReason := faultReason
  io.faultKey := faultKey

  // Replies are independent of poison and installer progress, and cannot refill
  // on a consumption edge. In particular a later fault never rewrites a reply.
  when(io.ctlResp.fire) { replyPending := false.B }
  when(io.ctl.fire) { reply := decision; replyPending := true.B }
  when(blocked) {
    when(faultNow) {
      poison := true.B; faultReason := cause; faultKey := culprit
      for (i <- 0 until depth) { when(states(i) =/= FREE.U) { states(i) := ERROR.U } }
    }
    // An irrevocable stalled offer survives quarantine, including its first edge.
    when(sending && io.cmd.fire) {
      phase := Mux(phase === configSend, configWait, writeWait)
    }.elsewhen(waiting && io.resp.fire) { phase := idle }
  }.otherwise {
    for (i <- 0 until depth) {
      when(states(i) === FILLING.U) { ages(i) := ages(i) + 1.U }
    }
    when(phase =/= idle) { ioAge := ioAge + 1.U }
    when(sending && io.cmd.fire) {
      phase := Mux(phase === configSend, configWait, writeWait); ioAge := 0.U
    }
    when(waiting && io.resp.fire) {
      ioAge := 0.U
      when(phase === configWait) { phase := writeSend }
        .elsewhen(retire) {
          phase := idle
          publishedValid(h.pair) := true.B
          publishedKey(h.pair) := activeKey
          states(head) := FREE.U
          head := advance(head)
        }.otherwise { cursor := cursor + 1.U; phase := writeSend }
    }
    when(io.load.fire && loadMatch) {
      when(io.load.bits.kind === DATA.U) {
        payload(loadSlot)(counts(loadSlot)) := io.load.bits.residentK
        counts(loadSlot) := counts(loadSlot) + 1.U
      }.elsewhen(io.load.bits.kind === DONE.U) { states(loadSlot) := READY.U }
    }
    when(allocate) {
      headers(tail) := incoming
      generations(tail) := nextGeneration
      counts(tail) := 0.U; ages(tail) := 0.U; states(tail) := FILLING.U
      tail := advance(tail)
      when(nextGeneration.andR) { exhausted := true.B }
        .otherwise { nextGeneration := nextGeneration + 1.U }
    }
    when(applyStart) {
      states(head) := APPLYING.U; phase := configSend; cursor := 0.U; ioAge := 0.U
      publishedValid(h.pair) := false.B
    }
    when(allocate =/= retire) {
      occupancy := Mux(allocate, occupancy + 1.U, occupancy - 1.U)
    }
  }
  assert(occupancy <= depth.U)
  assert(PopCount(states.map(_ =/= FREE.U)) === occupancy)
  when(io.finished.valid && io.finished.bits.status === APPLIED.U) {
    assert(io.resp.fire && io.resp.bits.status === PoTalScaleCommandStatus.OK)
    assert(!blocked && phase === writeWait)
  }
  when(io.cmd.fire) { assert(io.installerLock) }
}
