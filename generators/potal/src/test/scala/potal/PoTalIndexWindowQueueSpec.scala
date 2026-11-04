package potal

import chisel3._
import chisel3.util._
import chisel3.iotesters._
import java.nio.file.Files

object PoTalIndexWindowFixture {
  val config = PoTalConfigs.a8w8D32Sat32
  val dim = config.meshRows * config.tileRows
  val entries = config.potalIndexEntries
  val spRows = config.potalSpRows
  val depth = 2
  val maximum = math.min(entries, dim + 1)
  val ownerWidth = 8
  val residencyWidth = 8
  val generationWidth = 2
  val loadDeadline = 96
  val ioDeadline = 8
  val data = Seq(2, 0, 2) // Ordered repeated filler; not DIM aligned, no pad-K tail.
  case class Key(ticket: BigInt, generation: BigInt, owner: BigInt, residency: BigInt, pair: BigInt)
  case class Header(owner: Int = 5, residency: Int = 9, pair: Int = 0,
                    rows: Int = data.size, source: BigInt = dim + 1,
                    j: BigInt = 1, baseOverride: Option[BigInt] = None) {
    def base: BigInt = baseOverride.getOrElse(BigInt((2 * pair + 1) * (spRows / 4)))
  }
}

// Real ingress and its assertions remain in hierarchy. The table below observes
// only its event outputs; it neither accepts commands nor feeds the queue.
final class PoTalIndexWindowQueueHarness(
  maximum: Int = PoTalIndexWindowFixture.maximum,
  generationWidth: Int = PoTalIndexWindowFixture.generationWidth,
  loadDeadline: Int = PoTalIndexWindowFixture.loadDeadline
) extends Module {
  import PoTalIndexWindowFixture.{maximum => _, generationWidth => _, loadDeadline => _, _}
  val io = IO(new Bundle {
    val q = new PoTalIndexWindowQueueIO(depth, maximum, ownerWidth, residencyWidth, generationWidth)
    val external = Flipped(Decoupled(new PoTalScaleCommand))
    val externalResp = Decoupled(new PoTalScaleCommandResponse)
    val globalDrained = Input(Bool())
    val ownershipReleased = Input(Bool())
    val closeAllowed = Output(Bool())
    val holdResponse = Input(Bool())
    // Adapter acts only on actual registered ingress responses: 0 pass,
    // 1 replace status, 2 replace rd, 3 consume/drop.
    val responseMode = Input(UInt(2.W))
    val rawResponse = Valid(new PoTalScaleCommandResponse)
    val rawResponseReady = Output(Bool())
    val forwardedResponse = Valid(new PoTalScaleCommandResponse)
    val commandReady = Output(Bool())
    val configEvent = Valid(new PoTalIndexConfig)
    val writeEvent = Valid(new PoTalIndexWrite(entries))
    val descriptors = Output(Vec(PoTalProtocol.WeightBufferCount, new PoTalIndexConfig))
    val complete = Output(Vec(PoTalProtocol.WeightBufferCount, Bool()))
    val table = Output(Vec(PoTalProtocol.WeightBufferCount, Vec(entries, UInt(32.W))))
    val tableValid = Output(Vec(PoTalProtocol.WeightBufferCount, Vec(entries, Bool())))
    val configCount = Output(UInt(32.W))
    val writeCount = Output(UInt(32.W))
  })
  val queue = Module(new PoTalIndexWindowQueue(
    depth, maximum, dim, entries, spRows, ownerWidth, residencyWidth,
    generationWidth, loadDeadline, ioDeadline))
  val ingress = Module(new PoTalScaleCommandIngress(dim, config.potalScaleEntries, entries, spRows))
  queue.io <> io.q
  val externalPending = RegInit(false.B)
  // q.cmd.ready, q.resp.*, and q.installerAvailable are intentionally owned by
  // this wrapper rather than the tester. The remaining q ports are transparent.
  queue.io.installerAvailable := !externalPending && !io.external.valid && !ingress.io.resp.valid
  // Select bits from the registered SEND phase, not combinational admission
  // lock: ready must not feed faultNow -> APPLY admission -> ready.
  ingress.io.cmd.valid := queue.io.cmd.valid ||
    (io.external.valid && !externalPending && !queue.io.installerLock)
  ingress.io.cmd.bits := Mux(queue.io.cmd.valid, queue.io.cmd.bits, io.external.bits)
  queue.io.cmd.ready := ingress.io.cmd.ready
  io.commandReady := ingress.io.cmd.ready
  io.external.ready := !queue.io.installerLock && !externalPending && ingress.io.cmd.ready
  when(io.external.fire) { externalPending := true.B }
  when(io.externalResp.fire) { externalPending := false.B }
  io.externalResp.valid := ingress.io.resp.valid && externalPending
  io.externalResp.bits := ingress.io.resp.bits
  queue.io.resp.valid := ingress.io.resp.valid && !externalPending &&
    !io.holdResponse && io.responseMode =/= 3.U
  queue.io.resp.bits := ingress.io.resp.bits
  when(io.responseMode === 1.U) { queue.io.resp.bits.status := PoTalScaleCommandStatus.BAD_COMMAND }
  when(io.responseMode === 2.U) { queue.io.resp.bits.rd := 2.U }
  ingress.io.resp.ready := Mux(externalPending, io.externalResp.ready,
    !io.holdResponse && (io.responseMode === 3.U || queue.io.resp.ready))
  io.rawResponse.valid := ingress.io.resp.valid
  io.rawResponse.bits := ingress.io.resp.bits
  io.rawResponseReady := ingress.io.resp.ready
  io.forwardedResponse.valid := queue.io.resp.valid
  io.forwardedResponse.bits := queue.io.resp.bits
  ingress.io.indexUpdateSafe := io.q.indexUpdateSafe
  ingress.io.fenceDrained := io.globalDrained && !queue.io.fenceBusy
  ingress.io.releaseSafe := io.globalDrained
  ingress.io.scaleLoad.ready := true.B
  ingress.io.scaleRelease.ready := true.B
  ingress.io.scaleConflict := false.B
  ingress.io.releaseMiss := false.B
  io.closeAllowed := !queue.io.closeBlocked && io.globalDrained && io.ownershipReleased
  io.configEvent := ingress.io.indexConfigWrite
  io.writeEvent := ingress.io.indexWrite
  io.descriptors := ingress.io.indexDescriptors
  io.complete := ingress.io.indexComplete
  val table = RegInit(VecInit(Seq.fill(PoTalProtocol.WeightBufferCount)(
    VecInit(Seq.fill(entries)(0.U(32.W))))))
  val valid = RegInit(VecInit(Seq.fill(PoTalProtocol.WeightBufferCount)(
    VecInit(Seq.fill(entries)(false.B)))))
  val configCount = RegInit(0.U(32.W))
  val writeCount = RegInit(0.U(32.W))
  when(io.configEvent.valid) {
    valid(io.configEvent.bits.weightBufferId).foreach(_ := false.B)
    configCount := configCount + 1.U
  }
  when(io.writeEvent.valid) {
    val p = io.writeEvent.bits.weightBufferId
    val s = io.writeEvent.bits.slot
    assert(ingress.io.indexConfiguredByBuffer(p))
    assert(s < io.descriptors(p).activeRows)
    assert(io.writeEvent.bits.sourceK < io.descriptors(p).sourceKRows)
    table(p)(s) := io.writeEvent.bits.sourceK
    valid(p)(s) := true.B
    writeCount := writeCount + 1.U
  }
  io.table := table; io.tableValid := valid
  io.configCount := configCount; io.writeCount := writeCount
  assert(!(io.external.fire && queue.io.cmd.fire && queue.io.cmd.valid))
  when(queue.io.cmd.fire) { assert(queue.io.installerLock) }
  dontTouch(queue.io)
  dontTouch(ingress.io)
}

final class PoTalIndexWindowQueueTester(
  dut: PoTalIndexWindowQueueHarness, scenario: String, outputDirectory: String
)
    extends PeekPokeTester(dut) {
  import PoTalIndexWindow._
  import PoTalIndexWindowFixture._
  val q = dut.io.q
  var cycle = 0
  var configs = 0
  var writes = 0
  var applied = 0
  var faults = 0
  var appliedBeforeFault = 0
  val eventLog = new java.io.PrintWriter(new java.io.File(outputDirectory, "events.log"))
  var expectedInstall: Option[(Key, Header, Seq[Int])] = None
  var installWrites = 0
  var installConfigs = 0
  var stalledCommand: Option[Seq[BigInt]] = None
  var stalledReply: Option[Seq[BigInt]] = None
  val acceptedAt = scala.collection.mutable.Map.empty[Key, Int]
  def readKey(k: PoTalIndexWindowKey): Key =
    Key(peek(k.ticket), peek(k.generation), peek(k.owner), peek(k.residency), peek(k.pair))
  def key(k: PoTalIndexWindowKey, v: Key): Unit = {
    poke(k.ticket, v.ticket); poke(k.generation, v.generation); poke(k.owner, v.owner)
    poke(k.residency, v.residency); poke(k.pair, v.pair)
  }
  val zero = Key(0, 0, 0, 0, 0)
  def header(h: Header): Unit = {
    poke(q.ctl.bits.header.owner, h.owner); poke(q.ctl.bits.header.residency, h.residency)
    poke(q.ctl.bits.header.pair, h.pair); poke(q.ctl.bits.header.bBase, h.base)
    poke(q.ctl.bits.header.jTiles, h.j); poke(q.ctl.bits.header.sourceKRows, h.source)
    poke(q.ctl.bits.header.activeRows, h.rows)
  }
  def commandBits: Seq[BigInt] = Seq(peek(q.cmd.bits.funct), peek(q.cmd.bits.rs1),
    peek(q.cmd.bits.rs2), peek(q.cmd.bits.rd), peek(q.cmd.bits.xd))
  def replyBits: Seq[BigInt] = Seq(peek(q.ctlResp.bits.status), peek(q.ctlResp.bits.reason),
    peek(q.ctlResp.bits.key.ticket), peek(q.ctlResp.bits.key.generation),
    peek(q.ctlResp.bits.key.owner), peek(q.ctlResp.bits.key.residency), peek(q.ctlResp.bits.key.pair))
  def fire(valid: Bool, ready: Bool): Boolean = peek(valid) == 1 && peek(ready) == 1
  def tick(): Unit = {
    stalledCommand.foreach { b => expect(q.cmd.valid, 1); assert(commandBits == b) }
    stalledReply.foreach { b => expect(q.ctlResp.valid, 1); assert(replyBits == b) }
    stalledCommand = if (peek(q.cmd.valid) == 1 && peek(dut.io.commandReady) == 0)
      Some(commandBits) else None
    stalledReply = if (peek(q.ctlResp.valid) == 1 && peek(q.ctlResp.ready) == 0)
      Some(replyBits) else None
    if (peek(dut.io.configEvent.valid) == 1) {
      configs += 1
      expectedInstall.foreach { case (k, h, _) =>
        installConfigs += 1; assert(installConfigs == 1)
        expect(dut.io.configEvent.bits.weightBufferId, k.pair)
        expect(dut.io.configEvent.bits.bBase, h.base)
        expect(dut.io.configEvent.bits.jTiles, h.j)
        expect(dut.io.configEvent.bits.sourceKRows, h.source)
        expect(dut.io.configEvent.bits.activeRows, h.rows)
      }
    }
    if (peek(dut.io.writeEvent.valid) == 1) {
      writes += 1
      expectedInstall.foreach { case (k, _, values) =>
        assert(installConfigs == 1 && installWrites < values.size)
        expect(dut.io.writeEvent.bits.weightBufferId, k.pair)
        expect(dut.io.writeEvent.bits.slot, installWrites)
        expect(dut.io.writeEvent.bits.sourceK, values(installWrites))
        installWrites += 1
      }
    }
    if (peek(q.finished.valid) == 1) {
      if (peek(q.finished.bits.status) == APPLIED) {
        applied += 1
        expect(dut.io.forwardedResponse.valid, 1)
        expect(dut.io.forwardedResponse.bits.rd, 1)
        expect(dut.io.forwardedResponse.bits.status, 0)
        expectedInstall.foreach { case (k, _, values) =>
          assert(readKey(q.finished.bits.key) == k)
          assert(installConfigs == 1 && installWrites == values.size)
        }
      } else {
        faults += 1; appliedBeforeFault = applied; expect(q.finished.bits.status, FAULT)
      }
    }
    if (peek(q.installerLock) == 1) {
      expect(q.consumerPermit, 0); expect(dut.io.external.ready, 0)
    }
    if (expectedInstall.nonEmpty && peek(q.installerLock) == 1) expect(q.fenceBusy, 1)
    val slotLog = (0 until depth).map(i => s"$i:${peek(q.entryState(i))}:${peek(q.entryCount(i))}").mkString(",")
    val pairLog = (0 until PoTalProtocol.WeightBufferCount).map { p =>
      val d = dut.io.descriptors(p)
      s"$p:${peek(q.indexUpdateSafe(p))}:${peek(q.externalApplyPermit(p))}:" +
        s"${peek(q.publishedValid(p))}:${readKey(q.publishedKey(p))}:" +
        s"${peek(d.bBase)},${peek(d.jTiles)},${peek(d.sourceKRows)},${peek(d.activeRows)}:${peek(dut.io.complete(p))}"
    }.mkString(";")
    val line = s"IWQ case=$scenario cycle=$cycle " +
      s"ctl=${peek(q.ctl.valid)}/${peek(q.ctl.ready)}/${fire(q.ctl.valid,q.ctl.ready)}:${peek(q.ctl.bits.op)}:${readKey(q.ctl.bits.key)} " +
      s"ctlResp=${peek(q.ctlResp.valid)}/${peek(q.ctlResp.ready)}/${fire(q.ctlResp.valid,q.ctlResp.ready)}:${replyBits.mkString(",")} " +
      s"load=${peek(q.load.valid)}/${peek(q.load.ready)}/${fire(q.load.valid,q.load.ready)}:" +
      s"${readKey(q.load.bits.key)}:${peek(q.load.bits.kind)}:${peek(q.load.bits.residentK)} " +
      s"ack=${peek(q.loadAck.valid)}:${peek(q.loadAck.bits.status)}:${peek(q.loadAck.bits.reason)}:${readKey(q.loadAck.bits.key)} " +
      s"cmd=${peek(q.cmd.valid)}/${peek(dut.io.commandReady)}/${fire(q.cmd.valid,dut.io.commandReady)}:${commandBits.mkString(",")} " +
      s"resp=${peek(dut.io.forwardedResponse.valid)}/${peek(q.resp.ready)}/${fire(dut.io.forwardedResponse.valid,q.resp.ready)}:" +
      s"${peek(dut.io.forwardedResponse.bits.rd)}:${peek(dut.io.forwardedResponse.bits.status)} " +
      s"rawResp=${peek(dut.io.rawResponse.valid)}/${peek(dut.io.rawResponseReady)}/${fire(dut.io.rawResponse.valid,dut.io.rawResponseReady)}:${peek(dut.io.rawResponse.bits.rd)}:${peek(dut.io.rawResponse.bits.status)} " +
      s"finished=${peek(q.finished.valid)}:${peek(q.finished.bits.status)}:${peek(q.finished.bits.reason)}:${readKey(q.finished.bits.key)} " +
      s"windows=${peek(q.occupancy)} entries=$slotLog lock=${peek(q.installerLock)} " +
      s"consumer=${peek(q.consumer.valid)}/${peek(q.consumerPermit)}:${readKey(q.consumer.bits)} " +
      s"fenceBusy=${peek(q.fenceBusy)} closeBlocked=${peek(q.closeBlocked)} " +
      s"fault=${peek(q.poisoned)}:${peek(q.faultReason)}:${readKey(q.faultKey)} " +
      s"configEvent=${peek(dut.io.configEvent.valid)}:${peek(dut.io.configEvent.bits.weightBufferId)}:" +
      s"${peek(dut.io.configEvent.bits.bBase)},${peek(dut.io.configEvent.bits.jTiles)}," +
      s"${peek(dut.io.configEvent.bits.sourceKRows)},${peek(dut.io.configEvent.bits.activeRows)} " +
      s"writeEvent=${peek(dut.io.writeEvent.valid)}:${peek(dut.io.writeEvent.bits.weightBufferId)}:" +
      s"${peek(dut.io.writeEvent.bits.slot)}:${peek(dut.io.writeEvent.bits.sourceK)} " +
      s"configs=$configs writes=$writes pairs=$pairLog external=${peek(dut.io.external.valid)}/${peek(dut.io.external.ready)}"
    println(line); eventLog.println(line); eventLog.flush()
    step(1); cycle += 1
    expect(dut.io.configCount, configs); expect(dut.io.writeCount, writes)
  }
  def until(condition: => Boolean, bound: Int): Unit = {
    var edges = 0
    while (!condition && edges < bound) { tick(); edges += 1 }
    assert(condition, s"$scenario failed bounded event condition at cycle $cycle after $bound edges")
  }
  poke(q.ctl.valid, 0); poke(q.ctl.bits.op, 0); header(Header()); key(q.ctl.bits.key, zero)
  poke(q.ctlResp.ready, 0); poke(q.load.valid, 0); key(q.load.bits.key, zero)
  poke(q.load.bits.kind, DATA); poke(q.load.bits.residentK, 0)
  poke(q.consumer.valid, 0); key(q.consumer.bits, zero)
  poke(q.cmd.ready, 0); poke(q.resp.valid, 0); poke(q.resp.bits.rd, 0); poke(q.resp.bits.status, 0)
  poke(q.installerAvailable, 0)
  for (p <- 0 until PoTalProtocol.WeightBufferCount) {
    poke(q.indexUpdateSafe(p), 1); poke(q.externalApplyPermit(p), 1)
  }
  poke(dut.io.external.valid, 0); poke(dut.io.external.bits.funct, 25)
  poke(dut.io.external.bits.rs1, 0); poke(dut.io.external.bits.rs2, 0)
  poke(dut.io.external.bits.rd, 7); poke(dut.io.external.bits.xd, 1)
  poke(dut.io.externalResp.ready, 0)
  poke(dut.io.globalDrained, 1); poke(dut.io.ownershipReleased, 0)
  poke(dut.io.holdResponse, 0); poke(dut.io.responseMode, 0)
  tick()

  def consumeReply(): Unit = {
    expect(q.ctlResp.valid, 1); expect(q.ctl.ready, 0)
    poke(q.ctlResp.ready, 1); tick(); poke(q.ctlResp.ready, 0)
    expect(q.ctlResp.valid, 0); expect(q.ctl.ready, 1)
  }
  def ctl(op: Boolean, k: Key, h: Header, status: Int, reason: Int,
          consume: Boolean = true): Key = {
    expect(q.ctl.ready, 1)
    poke(q.ctl.bits.op, if (op) 1 else 0); key(q.ctl.bits.key, k); header(h)
    poke(q.ctl.valid, 1)
    val edge = cycle
    tick(); poke(q.ctl.valid, 0)
    expect(q.ctlResp.valid, 1); expect(q.ctlResp.bits.status, status); expect(q.ctlResp.bits.reason, reason)
    val result = readKey(q.ctlResp.bits.key)
    if (op) assert(result == k)
    else if (status != ACCEPTED) assert(result == zero)
    else acceptedAt(result) = edge
    if (consume) consumeReply()
    result
  }
  def submit(h: Header = Header()): Key = ctl(false, zero, h, ACCEPTED, NONE)
  def applyRequest(k: Key, status: Int = APPLY_STARTED, reason: Int = NONE): Unit =
    ctl(true, k, Header(), status, reason)
  def beat(k: Key, kind: Int, value: BigInt = 0, status: Int = LOAD_OK, reason: Int = NONE): Unit = {
    key(q.load.bits.key, k); poke(q.load.bits.kind, kind); poke(q.load.bits.residentK, value)
    poke(q.load.valid, 1); expect(q.load.ready, 1); expect(q.loadAck.valid, 1)
    expect(q.loadAck.bits.status, status); expect(q.loadAck.bits.reason, reason)
    assert(readKey(q.loadAck.bits.key) == k)
    tick(); poke(q.load.valid, 0)
  }
  def fill(k: Key, values: Seq[Int] = data): Unit = {
    values.zipWithIndex.foreach { case (v, i) =>
      beat(k, DATA, v); expect(q.entryCount(k.ticket.toInt), i + 1)
    }
    beat(k, DONE); expect(q.entryState(k.ticket.toInt), READY)
  }
  def arm(k: Key, h: Header = Header(), values: Seq[Int] = data): Unit = {
    expectedInstall = Some((k, h, values)); installConfigs = 0; installWrites = 0
  }
  def checkTable(k: Key, h: Header = Header(), values: Seq[Int] = data): Unit = {
    val p = k.pair.toInt
    expect(dut.io.descriptors(p).bBase, h.base); expect(dut.io.descriptors(p).jTiles, h.j)
    expect(dut.io.descriptors(p).sourceKRows, h.source); expect(dut.io.descriptors(p).activeRows, h.rows)
    expect(dut.io.descriptors(p).weightBufferId, p); expect(dut.io.complete(p), 1)
    for (s <- 0 until entries) {
      expect(dut.io.tableValid(p)(s), if (s < values.size) 1 else 0)
      if (s < values.size) expect(dut.io.table(p)(s), values(s))
    }
  }
  def finish(k: Key, h: Header = Header(), values: Seq[Int] = data): Unit = {
    until(peek(q.publishedValid(k.pair.toInt)) == 1 &&
      readKey(q.publishedKey(k.pair.toInt)) == k, (values.size + 1) * (2 * ioDeadline + 2))
    assert(installConfigs == 1 && installWrites == values.size)
    expect(q.entryState(k.ticket.toInt), FREE)
    expect(q.installerLock, 0); checkTable(k, h, values)
    key(q.consumer.bits, k); poke(q.consumer.valid, 1); expect(q.consumerPermit, 1)
    expectedInstall = None
  }
  def install(k: Key, h: Header = Header(), values: Seq[Int] = data): Unit = {
    arm(k, h, values); applyRequest(k); finish(k, h, values)
  }
  def snapshot: Seq[BigInt] =
    Seq(peek(q.occupancy)) ++ (0 until depth).flatMap(i => Seq(peek(q.entryState(i)), peek(q.entryCount(i)))) ++
      (0 until PoTalProtocol.WeightBufferCount).flatMap(p =>
        (0 until entries).flatMap(s => Seq(peek(dut.io.table(p)(s)), peek(dut.io.tableValid(p)(s)))))
  def sticky(k: Key, reason: Int): Unit = {
    expect(q.poisoned, 1); expect(q.faultReason, reason); assert(faults == 1)
    assert(readKey(q.faultKey) == k)
    expect(q.installerLock, 1); expect(q.fenceBusy, 1); expect(q.closeBlocked, 1)
    expect(q.consumerPermit, 0); expect(q.entryState(k.ticket.toInt), ERROR)
    for (i <- 0 until depth if peek(q.entryState(i)) != FREE) expect(q.entryState(i), ERROR)
    val frozen = snapshot
    applyRequest(k, FAULT, reason)
    ctl(false, zero, Header(), FAULT, reason)
    beat(k, DONE, status = FAULT, reason = reason)
    assert(snapshot == frozen); assert(faults == 1 && applied == appliedBeforeFault)
    expect(q.finished.valid, 0)
  }

  scenario match {
    case "busy" =>
      val first = submit(); fill(first); install(first)
      val eventCheckpoint = (configs, writes)
      val oldTable = snapshot.drop(1 + 2 * depth)
      poke(q.indexUpdateSafe(0), 0); poke(q.externalApplyPermit(0), 0)
      val next = submit(Header(owner = 6, residency = 10))
      applyRequest(next, NOT_READY, STATE)
      fill(next, Seq(1, 1, 0))
      applyRequest(next, BUSY, STATE)
      expect(q.occupancy, 1); expect(q.entryCount(next.ticket.toInt), 3)
      assert((configs, writes) == eventCheckpoint)
      assert(snapshot.drop(1 + 2 * depth) == oldTable)
      key(q.consumer.bits, first); expect(q.consumerPermit, 1)
      poke(q.indexUpdateSafe(0), 1); poke(q.externalApplyPermit(0), 1)
      arm(next, Header(owner = 6, residency = 10), Seq(1, 1, 0))
      // Lock and publication invalidation include the admission edge.
      key(q.ctl.bits.key, next); poke(q.ctl.bits.op, 1); poke(q.ctl.valid, 1)
      expect(q.installerLock, 1); expect(q.consumerPermit, 0)
      tick(); poke(q.ctl.valid, 0); expect(q.publishedValid(0), 0)
      // Real safe remains authoritative even after the grant.
      poke(q.indexUpdateSafe(0), 0)
      for (_ <- 0 until 2) { expect(q.cmd.valid, 1); expect(dut.io.commandReady, 0); tick() }
      poke(q.indexUpdateSafe(0), 1); consumeReply()
      finish(next, Header(owner = 6, residency = 10), Seq(1, 1, 0))
      assert((configs, writes) == (eventCheckpoint._1 + 1, eventCheckpoint._2 + 3))
      key(q.consumer.bits, first); expect(q.consumerPermit, 0)

    case "full" =>
      val a = submit(); fill(a)
      val b = submit(Header(pair = 1)); fill(b)
      expect(q.occupancy, 2); applyRequest(b, BUSY, STATE)
      val full = snapshot
      ctl(false, zero, Header(), BUSY_NOT_ACCEPTED, NONE)
      assert(snapshot == full)
      arm(a); applyRequest(a)
      until(installWrites == data.size, (data.size + 1) * (2 * ioDeadline + 2))
      // The final response and third SUBMIT fire together. Pre-edge full wins.
      expect(dut.io.forwardedResponse.valid, 1)
      ctl(false, zero, Header(), BUSY_NOT_ACCEPTED, NONE)
      expect(q.occupancy, 1); finish(a)
      val c = submit()
      assert(c.generation == 3 && c.ticket == a.ticket)
      expect(q.occupancy, 2); fill(c)
      install(b, Header(pair = 1)); install(c)
      expect(q.occupancy, 0)

    case "identity" =>
      val empty = snapshot
      Seq(Header(j = 0), Header(j = 65536), Header(source = 0), Header(rows = 0),
        Header(rows = maximum + 1), Header(baseOverride = Some(0)),
        Header(pair = 1, baseOverride = Some(Header().base)),
        Header(source = (BigInt(1) << 32) - 1, j = 65535),
        Header(baseOverride = Some(BigInt(spRows / 2 - 1)))).foreach { invalid =>
        ctl(false, zero, invalid, INVALID, HEADER); assert(snapshot == empty)
      }
      val h = Header()
      val a = ctl(false, zero, h, ACCEPTED, NONE, consume = false)
      header(Header(owner = 77, residency = 88, pair = 1, rows = 0, source = 0, j = 0))
      // Loader is independent of held ACCEPTED response and mutated live header.
      fill(a); expect(q.ctlResp.bits.status, ACCEPTED)
      assert(readKey(q.ctlResp.bits.key) == a); consumeReply()
      val stable = snapshot
      val bad = Seq(a.copy(ticket = 1), a.copy(owner = 7), a.copy(residency = 11),
        a.copy(pair = 1), a.copy(generation = 0))
      bad.foreach { k =>
        beat(k, DATA, 0, INVALID, IDENTITY); applyRequest(k, INVALID, IDENTITY)
        assert(snapshot == stable); assert(configs == 0 && writes == 0)
      }
      arm(a); ctl(true, a, Header(), APPLY_STARTED, NONE, consume = false)
      header(Header(owner = 77, residency = 88, pair = 1, rows = 0, source = 0, j = 0))
      consumeReply()
      // A competing CONFIG is held until ownership is released, never interleaved.
      poke(dut.io.external.bits.funct, 27)
      poke(dut.io.external.bits.rs1, (BigInt(1) << 32) | Header(pair = 1).base)
      poke(dut.io.external.bits.rs2, (BigInt(data.size) << 32) | Header().source)
      poke(dut.io.external.valid, 1)
      until(applied == 1, (data.size + 1) * (2 * ioDeadline + 2))
      finish(a)
      expect(dut.io.external.ready, 1); tick(); poke(dut.io.external.valid, 0)
      expect(dut.io.externalResp.valid, 1)
      poke(dut.io.externalResp.ready, 1); tick(); poke(dut.io.externalResp.ready, 0)
      beat(a, DATA, 0, INVALID, IDENTITY); applyRequest(a, INVALID, IDENTITY)
      key(q.consumer.bits, a); expect(q.consumerPermit, 1)
      val b = submit(Header(pair = 1)); assert(b.generation == 2)
      fill(b, Seq(0, 1, 0)); install(b, Header(pair = 1), Seq(0, 1, 0))
      checkTable(a); key(q.consumer.bits, a); expect(q.consumerPermit, 1)
      val c = submit(); assert(c.generation == 3)
      fill(c); install(c)
      ctl(false, zero, Header(), EXHAUSTED, STATE)
      beat(a, DATA, 0, INVALID, IDENTITY); applyRequest(a, INVALID, IDENTITY)
      key(q.consumer.bits, a); expect(q.consumerPermit, 0)
      key(q.consumer.bits, c); expect(q.consumerPermit, 1)
      key(q.consumer.bits, b); expect(q.consumerPermit, 1)
      checkTable(b, Header(pair = 1), Seq(0, 1, 0))

    case "fence" =>
      val a = submit()
      poke(dut.io.external.valid, 1) // Real funct25 waits, not a Queue control.
      expect(dut.io.external.ready, 0); expect(q.fenceBusy, 1)
      data.foreach { v => expect(dut.io.external.ready, 0); beat(a, DATA, v) }
      expect(dut.io.external.ready, 0); beat(a, DONE)
      expect(q.fenceBusy, 0); expect(q.closeBlocked, 1); expect(dut.io.external.ready, 1)
      tick(); poke(dut.io.external.valid, 0)
      expect(dut.io.externalResp.valid, 1); expect(dut.io.externalResp.bits.status, 0)
      applyRequest(a, BUSY, STATE) // Earlier Fence response still owns ingress.
      poke(dut.io.externalResp.ready, 1); tick(); poke(dut.io.externalResp.ready, 0)
      arm(a); applyRequest(a)
      poke(dut.io.external.valid, 1)
      until(applied == 1, (data.size + 1) * (2 * ioDeadline + 2))
      finish(a)
      expect(dut.io.external.ready, 1); tick(); poke(dut.io.external.valid, 0)
      poke(dut.io.externalResp.ready, 1); tick(); poke(dut.io.externalResp.ready, 0)
      expect(q.closeBlocked, 0); expect(dut.io.closeAllowed, 0)
      poke(dut.io.ownershipReleased, 1); poke(dut.io.globalDrained, 0)
      expect(dut.io.closeAllowed, 0); poke(dut.io.globalDrained, 1); expect(dut.io.closeAllowed, 1)

    case name =>
      // Each branch runs in a fresh Driver. No reset/retry recovery after faults.
      val a = submit()
      name match {
        case "premature" => beat(a, DATA, 0); beat(a, DONE, status = FAULT, reason = COUNT); sticky(a, COUNT)
        case "missing-data" | "missing-done" =>
          if (name == "missing-done") data.foreach(v => beat(a, DATA, v))
          val deadlineCycle = acceptedAt(a) + loadDeadline
          while (cycle < deadlineCycle) { expect(q.finished.valid, 0); tick() }
          expect(q.finished.valid, 1); expect(q.finished.bits.reason, LOAD_TIMEOUT); tick()
          sticky(a, LOAD_TIMEOUT)
        case "excess" =>
          data.foreach(v => beat(a, DATA, v))
          beat(a, DATA, 0, FAULT, COUNT); sticky(a, COUNT)
        case "late" =>
          fill(a); beat(a, DATA, 0, FAULT, STATE); sticky(a, STATE)
        case "bad-k" => beat(a, DATA, Header().source, FAULT, K_BOUNDS); sticky(a, K_BOUNDS)
        case "loader" => beat(a, LOAD_ERROR, status = FAULT, reason = LOADER); sticky(a, LOADER)
        case "reserved" => beat(a, 3, status = FAULT, reason = PROTOCOL); sticky(a, PROTOCOL)
        case "apply-late" =>
          fill(a)
          key(q.ctl.bits.key, a); poke(q.ctl.bits.op, 1); poke(q.ctl.valid, 1)
          beat(a, DATA, 0, FAULT, STATE); poke(q.ctl.valid, 0)
          expect(q.ctlResp.bits.status, FAULT); consumeReply()
          assert(configs == 0 && writes == 0); sticky(a, STATE)
        case "final-error" | "final-late" =>
          fill(a); arm(a); applyRequest(a)
          until(installWrites == data.size, (data.size + 1) * (2 * ioDeadline + 2))
          expect(dut.io.forwardedResponse.valid, 1)
          val reason = if (name == "final-error") LOADER else STATE
          beat(a, if (name == "final-error") LOAD_ERROR else DATA, status = FAULT, reason = reason)
          expect(q.publishedValid(0), 0); expectedInstall = None; sticky(a, reason)
        case "partial-error" | "fault-priority" =>
          fill(a); arm(a); applyRequest(a)
          until(installWrites == 1, (2 * ioDeadline + 2) * 2)
          expect(dut.io.rawResponse.valid, 1); assert(configs == 1 && writes == 1)
          poke(dut.io.responseMode, 1)
          if (name == "fault-priority") {
            beat(a, LOAD_ERROR, status = FAULT, reason = LOADER)
          } else {
            expect(q.finished.valid, 1); expect(q.finished.bits.reason, RESPONSE); tick()
          }
          expect(q.publishedValid(0), 0); expectedInstall = None
          sticky(a, if (name == "fault-priority") LOADER else RESPONSE)
        case "fault-submit" =>
          fill(a)
          poke(q.ctl.bits.op, 0); header(Header(pair = 1)); poke(q.ctl.valid, 1)
          beat(a, DATA, status = FAULT, reason = STATE); poke(q.ctl.valid, 0)
          expect(q.ctlResp.bits.status, FAULT); assert(readKey(q.ctlResp.bits.key) == zero)
          consumeReply(); expect(q.occupancy, 1); sticky(a, STATE)
        case "published-poison" =>
          fill(a); install(a)
          val b = submit(Header(pair = 1))
          key(q.consumer.bits, a); poke(q.consumer.valid, 1); expect(q.consumerPermit, 1)
          beat(b, LOAD_ERROR, status = FAULT, reason = LOADER)
          expect(q.publishedValid(0), 1); expect(q.consumerPermit, 0)
          sticky(b, LOADER)
        case "two-slot-error" =>
          val b = submit(Header(pair = 1)); beat(b, DATA, 1)
          beat(a, LOAD_ERROR, status = FAULT, reason = LOADER)
          expect(q.occupancy, 2); expect(q.entryCount(b.ticket.toInt), 1)
          expect(q.entryState(b.ticket.toInt), ERROR); sticky(a, LOADER)
        case "bad-status" | "wrong-rd" | "lost" =>
          fill(a); arm(a); poke(dut.io.holdResponse, 1); applyRequest(a)
          until(peek(dut.io.rawResponse.valid) == 1, ioDeadline)
          assert(configs == 1 && writes == 0)
          for (_ <- 0 until 2) {
            expect(dut.io.commandReady, 0); expect(dut.io.forwardedResponse.valid, 0); tick()
          }
          poke(dut.io.responseMode, if (name == "bad-status") 1 else if (name == "wrong-rd") 2 else 3)
          poke(dut.io.holdResponse, 0)
          if (name == "lost") {
            // CONFIG fired during applyRequest's consumeReply edge; WAIT age
            // is now 2. Drop the actual response, without forwarding a handshake.
            val remaining = ioDeadline - 2
            for (i <- 0 until remaining) {
              expect(q.finished.valid, if (i == remaining - 1) 1 else 0)
              if (i == remaining - 1) expect(q.finished.bits.reason, IO_TIMEOUT)
              tick()
            }
            expectedInstall = None; sticky(a, IO_TIMEOUT)
          } else {
            expect(q.finished.valid, 1); tick()
            expectedInstall = None; sticky(a, RESPONSE)
          }
        case "stalled-poison" =>
          fill(a); arm(a)
          ctl(true, a, Header(), APPLY_STARTED, NONE, consume = false)
          poke(q.indexUpdateSafe(0), 0)
          val offered = commandBits
          beat(a, LOAD_ERROR, status = FAULT, reason = LOADER)
          expect(q.ctlResp.bits.status, APPLY_STARTED) // Pending reply is immutable.
          consumeReply(); expect(q.cmd.valid, 1); assert(commandBits == offered)
          expect(q.poisoned, 1)
          poke(q.indexUpdateSafe(0), 1); tick() // Irrevocable CONFIG may still take effect.
          expect(dut.io.rawResponse.valid, 1); tick() // Quarantine drains, creates no WRITE.
          expect(q.cmd.valid, 0); assert(configs == 1 && writes == 0)
          expectedInstall = None; sticky(a, LOADER)
        case "send-timeout" | "late-response" =>
          fill(a); arm(a)
          ctl(true, a, Header(), APPLY_STARTED, NONE, consume = false)
          if (name == "send-timeout") poke(q.indexUpdateSafe(0), 0)
          else poke(dut.io.holdResponse, 1)
          // SEND starts at age zero. For WAIT, consumeReply fires CONFIG and
          // starts its age zero; the same bounded loop therefore covers both.
          if (name == "late-response") consumeReply()
          for (i <- 0 until ioDeadline) {
            expect(q.finished.valid, if (i == ioDeadline - 1) 1 else 0)
            if (i == ioDeadline - 1) expect(q.finished.bits.reason, IO_TIMEOUT)
            tick()
          }
          expect(q.poisoned, 1)
          if (name == "send-timeout") {
            expect(q.cmd.valid, 1); assert(configs == 0)
            consumeReply(); poke(q.indexUpdateSafe(0), 1); tick()
          } else assert(configs == 1)
          poke(dut.io.holdResponse, 0)
          expect(dut.io.rawResponse.valid, 1); tick()
          expect(q.cmd.valid, 0); assert(writes == 0)
          expectedInstall = None; sticky(a, IO_TIMEOUT)
        case "deadline-done" =>
          data.foreach(v => beat(a, DATA, v))
          val deadlineCycle = acceptedAt(a) + loadDeadline
          while (cycle < deadlineCycle) { tick() }
          beat(a, DONE); expect(q.poisoned, 0); install(a)
        case "done-apply" =>
          data.foreach(v => beat(a, DATA, v))
          key(q.ctl.bits.key, a); poke(q.ctl.bits.op, 1); poke(q.ctl.valid, 1)
          beat(a, DONE); poke(q.ctl.valid, 0)
          expect(q.ctlResp.bits.status, NOT_READY); consumeReply(); install(a)
        case "parallel-load" =>
          fill(a)
          val b = submit(Header(pair = 1))
          arm(a)
          key(q.ctl.bits.key, a); poke(q.ctl.bits.op, 1); poke(q.ctl.valid, 1)
          beat(b, DATA, data.head); poke(q.ctl.valid, 0)
          expect(q.ctlResp.bits.status, APPLY_STARTED); consumeReply()
          data.tail.foreach(v => beat(b, DATA, v)); beat(b, DONE)
          finish(a); install(b, Header(pair = 1))
        case "final-apply" | "reclaim-submit" =>
          fill(a); arm(a); applyRequest(a)
          until(installWrites == data.size, (data.size + 1) * (2 * ioDeadline + 2))
          expect(dut.io.forwardedResponse.valid, 1)
          if (name == "final-apply") {
            applyRequest(a, BUSY, STATE); finish(a)
          } else {
            val b = submit(Header(pair = 1))
            expect(q.occupancy, 1); finish(a)
            fill(b); install(b, Header(pair = 1))
          }
        case "repeated-small-source" =>
          // No activeRows<=sourceKRows or alignment/uniqueness requirement.
          // The initial ordinary allocation is filled/retired first.
          fill(a); install(a)
          val h = Header(source = 1, baseOverride = Some(Header().base + 1))
          val b = submit(h); fill(b, Seq(0, 0, 0)); install(b, h, Seq(0, 0, 0))
        case other => throw new IllegalArgumentException(other)
      }
  }
  eventLog.close()
}

class PoTalIndexWindowQueueSpec extends ChiselFlatSpec {
  behavior of "Isolated Index Window Queue through real Scale command ingress"
  private def run(name: String): Unit = {
    val root = java.nio.file.Paths.get(sys.env.getOrElse(
      "POTAL_INDEX_WINDOW_TEST_DIR", "test_run_dir/potal-index-window"))
    Files.createDirectories(root)
    val dir = Files.createTempDirectory(root, name + "-")
    Driver.execute(Array("--backend-name", "treadle", "--generate-vcd-output", "on",
      "--target-dir", dir.toString), () => new PoTalIndexWindowQueueHarness) {
      dut => new PoTalIndexWindowQueueTester(dut, name, dir.toString)
    } should be(true)
  }
  it should "fill the next window without touching the busy current table" in { run("busy") }
  it should "refuse full admission without mutation and reclaim without head of line blocking" in { run("full") }
  it should "snapshot headers and enforce tickets generations owners residencies and both pairs" in { run("identity") }
  it should "fail closed on incomplete malformed late and uncertain transactions" in {
    Seq("premature", "missing-data", "missing-done", "excess", "late", "bad-k", "loader",
      "reserved", "apply-late", "final-error", "final-late", "partial-error", "fault-priority",
      "fault-submit", "published-poison", "two-slot-error", "bad-status", "wrong-rd", "lost",
      "stalled-poison", "send-timeout",
      "late-response", "deadline-done", "done-apply", "parallel-load", "final-apply",
      "reclaim-submit", "repeated-small-source").foreach(run)
  }
  it should "let READY pass a real Fence while retaining Close and installer exclusion" in { run("fence") }
}
