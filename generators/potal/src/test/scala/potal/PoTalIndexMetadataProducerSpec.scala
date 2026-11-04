package potal

import chisel3._
import chisel3.util._
import chisel3.iotesters._
import java.nio.file.Files
import scala.collection.mutable

// Attachment only: all installation, arbitration and table observation still
// belong to the original I25A harness and real command ingress.
final class PoTalIndexMetadataHarness extends Module {
  import PoTalIndexWindowFixture._
  import PoTalIndexWindow._
  val original = Module(new PoTalIndexWindowQueueHarness(64, 32, 1024))
  val producer = Module(new PoTalIndexMetadataProducer(depth, 64, ownerWidth, residencyWidth, 32, 64, 512))
  val io = IO(new Bundle {
    val h = chiselTypeOf(original.io)
    val m = new PoTalIndexMetadataProducerIO(depth, 64, ownerWidth, residencyWidth, 32, 64)
    val lease = Output(Bool())
  })
  original.io <> io.h
  producer.io <> io.m
  val pending = Reg(new PoTalIndexMetadataStart(depth, ownerWidth, residencyWidth, 32, 64))
  val reserved = RegInit(false.B)
  val live = RegInit(false.B)
  // An unsolicited idle response must not allocate a new key on the edge that
  // locally faults the Producer, before the registered ACCEPTED can attach.
  val canSubmit = !reserved && !producer.io.busy && !io.m.memResp.valid
  original.io.q.ctl.valid := io.h.q.ctl.valid && (io.h.q.ctl.bits.op || canSubmit)
  io.h.q.ctl.ready := original.io.q.ctl.ready && (io.h.q.ctl.bits.op || canSubmit)
  when(original.io.q.ctl.fire && !original.io.q.ctl.bits.op) {
    pending := io.m.start.bits
    pending.activeRows := io.h.q.ctl.bits.header.activeRows
    pending.sourceKRows := io.h.q.ctl.bits.header.sourceKRows
    reserved := true.B
  }
  val observed = reserved && original.io.q.ctlResp.valid
  producer.io.start.valid := observed && original.io.q.ctlResp.bits.status === ACCEPTED.U
  producer.io.start.bits := pending
  producer.io.start.bits.key := original.io.q.ctlResp.bits.key
  when(observed) { reserved := false.B }
  when(original.io.q.finished.valid && original.io.q.finished.bits.status === APPLIED.U &&
    original.io.q.finished.bits.key.asUInt === producer.io.contextKey.asUInt) { live := false.B }
  when(producer.io.start.fire) { live := true.B }
  producer.io.contextLive := live || producer.io.start.fire
  producer.io.queuePoisoned := original.io.q.poisoned
  original.io.q.load <> producer.io.load
  producer.io.loadAck := original.io.q.loadAck
  // These outputs expose the actual attachment, not the unused tester inputs.
  io.m.load.valid := producer.io.load.valid
  io.m.load.bits := producer.io.load.bits
  io.m.memReq <> producer.io.memReq
  io.lease := reserved || producer.io.sourceLease ||
    (original.io.q.ctl.fire && !original.io.q.ctl.bits.op)
  original.io.globalDrained := io.h.globalDrained && !reserved &&
    !producer.io.busy && !producer.io.sourceLease && !producer.io.transportUncertain &&
    !producer.io.faulted
  when(producer.io.start.valid) { assert(producer.io.start.ready) }
}

object PoTalIndexMetadataFixture {
  // Exact orderedResidentK arrays, panel-major/window-major, from
  // /tmp/h3-8i-25b/contract/payloads.json (I23 cached graph2 plan0).
  // Internal repeated fillers are intentional. Actual lengths 38/39 are absent.
  val ffn = Seq(
    Seq(0, 8, 9, 11, 14, 16, 17, 27, 28, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 37, 39, 40, 42, 43, 48, 49, 50, 51, 56),
    Seq(65, 66, 69, 70, 71, 74, 75, 79, 81, 84, 86, 89, 93, 94, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 97, 98, 101, 107, 113, 116, 121, 123, 126),
    Seq(133, 135, 136, 142, 148, 133, 133, 133, 133, 133, 133, 133, 133, 133, 133, 133, 133, 133, 133, 133, 133, 133, 133, 133, 133, 133, 133, 133, 133, 133, 133, 133, 168, 171, 176, 180),
    Seq(196, 209, 210, 212, 214, 215, 217, 222, 196, 196, 196, 196, 196, 196, 196, 196, 196, 196, 196, 196, 196, 196, 196, 196, 196, 196, 196, 196, 196, 196, 196, 196, 225, 228, 234),
    Seq(3, 6, 16, 17, 21, 24, 30, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 33, 34, 37, 44, 58),
    Seq(69, 80, 89, 69, 69, 69, 69, 69, 69, 69, 69, 69, 69, 69, 69, 69, 69, 69, 69, 69, 69, 69, 69, 69, 69, 69, 69, 69, 69, 69, 69, 69, 99, 106, 108, 113, 114, 115, 116, 117, 122, 125, 126),
    Seq(129, 130, 131, 133, 135, 139, 141, 144, 146, 150, 154, 159, 129, 129, 129, 129, 129, 129, 129, 129, 129, 129, 129, 129, 129, 129, 129, 129, 129, 129, 129, 129, 166, 168, 170, 176, 178, 179, 184, 189),
    Seq(201, 202, 203, 204, 205, 215, 201, 201, 201, 201, 201, 201, 201, 201, 201, 201, 201, 201, 201, 201, 201, 201, 201, 201, 201, 201, 201, 201, 201, 201, 201, 201, 229, 230, 231, 233, 235, 239, 240, 241, 242, 255),
    Seq(0, 6, 8, 11, 19, 22, 23, 26, 27, 28, 31, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 34, 35, 37, 38, 50, 54, 60, 61),
    Seq(66, 67, 77, 78, 82, 85, 89, 93, 66, 66, 66, 66, 66, 66, 66, 66, 66, 66, 66, 66, 66, 66, 66, 66, 66, 66, 66, 66, 66, 66, 66, 66, 102, 105, 114, 118, 124),
    Seq(131, 136, 143, 144, 146, 149, 152, 153, 131, 131, 131, 131, 131, 131, 131, 131, 131, 131, 131, 131, 131, 131, 131, 131, 131, 131, 131, 131, 131, 131, 131, 131, 160, 168, 169, 171, 175, 183, 187, 188, 189, 190, 191),
    Seq(194, 202, 205, 207, 210, 211, 214, 218, 219, 222, 194, 194, 194, 194, 194, 194, 194, 194, 194, 194, 194, 194, 194, 194, 194, 194, 194, 194, 194, 194, 194, 194, 226, 227, 231, 232, 240, 241, 245, 247, 248, 250, 255))
}

final class PoTalIndexMetadataTester(dut: PoTalIndexMetadataHarness, scenario: String, directory: String)
    extends PeekPokeTester(dut) {
  import PoTalIndexWindow._
  import PoTalIndexWindowFixture.{Key, Header, entries, depth}
  val h = dut.io.h
  val q = h.q
  val m = dut.io.m
  val zero = Key(0, 0, 0, 0, 0)
  def getKey(k: PoTalIndexWindowKey): Key =
    Key(peek(k.ticket), peek(k.generation), peek(k.owner), peek(k.residency), peek(k.pair))
  def putKey(k: PoTalIndexWindowKey, v: Key): Unit = {
    poke(k.ticket, v.ticket); poke(k.generation, v.generation); poke(k.owner, v.owner)
    poke(k.residency, v.residency); poke(k.pair, v.pair)
  }
  def fire(v: Bool, r: Bool): Boolean = peek(v) == 1 && peek(r) == 1
  case class Response(k: Key, index: BigInt, address: BigInt, value: BigInt,
                      last: BigInt, mask: Int = 15, complete: Int = 1, error: Int = 0)
  case class Window(k: Key, header: Header, address: BigInt, values: Seq[Int], submitted: Int)
  val windows = mutable.Map.empty[Key, Window]
  val bytes = mutable.Map.empty[BigInt, Int]
  val requests = mutable.ArrayBuffer.empty[Response]
  val delivered = mutable.Map.empty[Key, Int].withDefaultValue(0)
  val requested = mutable.Map.empty[Key, Int].withDefaultValue(0)
  val marks = mutable.Map.empty[(Key, String), Int]
  var cycle = 0
  var applied = 0
  var configs = 0
  var writes = 0
  var auto = true
  var delay = 0
  var pending: Option[(Int, Response)] = None
  var injecting: Option[Response] = None
  var corrupt: Response => Response = r => r
  var heldRequest: Option[Seq[BigInt]] = None
  var heldResponse: Option[Response] = None
  val log = new java.io.PrintWriter(new java.io.File(directory, "events.log"))
  def source(address: BigInt, count: BigInt, base: BigInt, size: BigInt): Unit = {
    poke(m.start.bits.sourceAddress, address); poke(m.start.bits.bufferBase, base)
    poke(m.start.bits.bufferBytes, size); poke(m.start.bits.count, count)
  }
  def header(v: Header): Unit = {
    poke(q.ctl.bits.header.owner, v.owner); poke(q.ctl.bits.header.residency, v.residency)
    poke(q.ctl.bits.header.pair, v.pair); poke(q.ctl.bits.header.bBase, v.base)
    poke(q.ctl.bits.header.jTiles, v.j); poke(q.ctl.bits.header.sourceKRows, v.source)
    poke(q.ctl.bits.header.activeRows, v.rows)
  }
  def memoryWrite(address: BigInt, values: Seq[Int]): Unit = {
    assert(peek(dut.io.lease) == 0, "source mutation/reuse requires released lease")
    values.zipWithIndex.foreach { case (value, i) =>
      for (j <- 0 until 4) bytes(address + 4 * i + j) = (value >>> (8 * j)) & 255
    }
  }
  def response(r: Response): Unit = {
    putKey(m.memResp.bits.key, r.k); poke(m.memResp.bits.index, r.index)
    poke(m.memResp.bits.address, r.address); poke(m.memResp.bits.data, r.value)
    poke(m.memResp.bits.last, r.last); poke(m.memResp.bits.mask, r.mask)
    poke(m.memResp.bits.complete, r.complete); poke(m.memResp.bits.error, r.error)
  }
  def reqBits: Seq[BigInt] = {
    val k = getKey(m.memReq.bits.key)
    Seq(k.ticket, k.generation, k.owner, k.residency, k.pair,
      peek(m.memReq.bits.index), peek(m.memReq.bits.address), peek(m.memReq.bits.last))
  }
  def mark(k: Key, name: String): Unit = { marks.getOrElseUpdate((k, name), cycle); () }
  def tick(): Unit = {
    val offered = injecting.orElse(if (auto) pending.filter(_._1 <= cycle).map(_._2) else None)
    poke(m.memResp.valid, if (offered.nonEmpty) 1 else 0)
    offered.foreach(response)
    heldRequest.foreach(b => { expect(m.memReq.valid, 1); assert(reqBits == b) })
    heldResponse.foreach(r => assert(offered.contains(r), "response changed while stalled"))
    heldRequest = if (peek(m.memReq.valid) == 1 && peek(m.memReq.ready) == 0) Some(reqBits) else None
    heldResponse = if (offered.nonEmpty && peek(m.memResp.ready) == 0) offered else None
    if (fire(m.memReq.valid, m.memReq.ready)) {
      val k = getKey(m.memReq.bits.key)
      val a = peek(m.memReq.bits.address)
      val i = peek(m.memReq.bits.index)
      val w = windows(k)
      assert(i == requested(k) && a == w.address + 4 * requested(k))
      assert(peek(m.memReq.bits.last) == (if (i == w.values.size - 1) 1 else 0))
      assert(pending.isEmpty, "more than one outstanding read")
      // Four independent bytes at the requested address; never index into the
      // expected Queue payload to manufacture a response.
      val value = (0 until 4).map(j => BigInt(bytes(a + j)) << (8 * j)).reduce(_ | _)
      val r = Response(k, i, a, value, peek(m.memReq.bits.last))
      requests += r; requested(k) += 1; mark(k, "readFirst")
      pending = Some((cycle + 1 + delay, corrupt(r)))
      expect(dut.io.lease, 1)
    }
    val responseFired = fire(m.memResp.valid, m.memResp.ready)
    if (responseFired) {
      val r = offered.get
      mark(r.k, "responseFirst")
      if (r.complete == 1) marks((r.k, "readLast")) = cycle
      if (injecting.isEmpty) pending = None
      injecting = None
    }
    if (peek(m.load.valid) == 1) {
      expect(q.load.ready, 1) // Real Queue never backpressures.
      val k = getKey(m.load.bits.key)
      expect(q.loadAck.valid, 1)
      assert(getKey(q.loadAck.bits.key) == k)
      if (peek(m.load.bits.kind) == DATA && peek(q.loadAck.bits.status) == LOAD_OK) {
        val w = windows(k)
        assert(delivered(k) < w.values.size)
        expect(m.load.bits.residentK, w.values(delivered(k)))
        delivered(k) += 1; mark(k, "fillFirst"); expect(dut.io.lease, 1)
      }
      if (peek(m.load.bits.kind) == DONE && peek(q.loadAck.bits.status) == LOAD_OK) {
        assert(delivered(k) == windows(k).values.size && pending.isEmpty)
        marks((k, "done")) = cycle; expect(dut.io.lease, 1)
      }
      if (peek(m.load.bits.kind) == LOAD_ERROR) {
        assert(k == getKey(m.contextKey) || peek(m.start.ready) == 1)
        mark(k, "error")
      }
    }
    for ((k, w) <- windows if peek(q.entryState(k.ticket.toInt)) == READY &&
      getKey(m.contextKey) == k) {
      assert(marks.contains((k, "done"))); mark(k, "ready")
    }
    if (peek(h.configEvent.valid) == 1) {
      configs += 1
      val k = windows.keys.filter(k => peek(q.entryState(k.ticket.toInt)) == APPLYING).maxBy(_.generation)
      val w = windows(k)
      expect(h.configEvent.bits.weightBufferId, k.pair); expect(h.configEvent.bits.bBase, w.header.base)
      expect(h.configEvent.bits.jTiles, w.header.j); expect(h.configEvent.bits.sourceKRows, w.header.source)
      expect(h.configEvent.bits.activeRows, w.values.size); mark(k, "config")
    }
    if (peek(h.writeEvent.valid) == 1) {
      writes += 1
      val k = windows.keys.filter(k => peek(q.entryState(k.ticket.toInt)) == APPLYING).maxBy(_.generation)
      val slot = peek(h.writeEvent.bits.slot).toInt
      expect(h.writeEvent.bits.sourceK, windows(k).values(slot))
      expect(h.writeEvent.bits.weightBufferId, k.pair)
      marks((k, "writeLast")) = cycle
    }
    if (peek(q.finished.valid) == 1 && peek(q.finished.bits.status) == APPLIED) {
      val k = getKey(q.finished.bits.key)
      expect(h.forwardedResponse.valid, 1); expect(h.forwardedResponse.bits.rd, 1)
      expect(h.forwardedResponse.bits.status, 0)
      assert(marks((k, "writeLast")) < cycle)
      mark(k, "applied"); applied += 1
    }
    if (peek(q.installerLock) == 1) { expect(q.consumerPermit, 0); expect(h.external.ready, 0) }
    val line = s"cycle=$cycle case=$scenario req=${peek(m.memReq.valid)}/${peek(m.memReq.ready)}:${reqBits.mkString(",")} " +
      s"resp=${peek(m.memResp.valid)}/${peek(m.memResp.ready)}:$offered " +
      s"load=${peek(m.load.valid)}/${peek(q.load.ready)}:${getKey(m.load.bits.key)}:${peek(m.load.bits.kind)}:${peek(m.load.bits.residentK)} " +
      s"ack=${peek(q.loadAck.valid)}:${peek(q.loadAck.bits.status)}:${peek(q.loadAck.bits.reason)} " +
      s"lease=${peek(dut.io.lease)} uncertain=${peek(m.transportUncertain)} fault=${peek(m.faulted)}:${peek(m.faultReason)} " +
      s"QUEUE=${(0 until depth).map(i => s"$i:${peek(q.entryState(i))}:${peek(q.entryCount(i))}").mkString(",")} " +
      s"ctl=${peek(q.ctl.valid)}/${peek(q.ctl.ready)}:${peek(q.ctl.bits.op)} reply=${peek(q.ctlResp.valid)}:${peek(q.ctlResp.bits.status)}:${getKey(q.ctlResp.bits.key)} " +
      s"config=${peek(h.configEvent.valid)} write=${peek(h.writeEvent.valid)}:${peek(h.writeEvent.bits.slot)} " +
      s"checked=${peek(h.forwardedResponse.valid)}:${peek(h.forwardedResponse.bits.rd)}:${peek(h.forwardedResponse.bits.status)} " +
      s"finished=${peek(q.finished.valid)}:${peek(q.finished.bits.status)}:${getKey(q.finished.bits.key)}"
    log.println(line); log.flush()
    step(1); cycle += 1
    // End the consumed offer before callers inspect the next cycle. Otherwise
    // an until(DATA) observer would see a fictitious duplicate response.
    if (responseFired) poke(m.memResp.valid, 0)
    expect(h.configCount, configs); expect(h.writeCount, writes)
    val current = getKey(m.contextKey)
    if (windows.contains(current) && peek(m.faulted) == 0) {
      val state = peek(q.entryState(current.ticket.toInt))
      if (state == FILLING || state == READY) expect(q.entryCount(current.ticket.toInt), delivered(current))
      if (state == READY) {
        assert(marks.contains((current, "done"))); mark(current, "ready")
      } else if (state == FILLING) expect(dut.io.lease, 1)
    }
  }
  def until(condition: => Boolean, bound: Int = 1024): Unit = {
    var n = 0
    while (!condition && n < bound) { tick(); n += 1 }
    assert(condition, s"$scenario bounded condition failed at $cycle")
  }
  poke(q.ctl.valid, 0); poke(q.ctl.bits.op, 0); putKey(q.ctl.bits.key, zero); header(Header())
  poke(q.ctlResp.ready, 0); poke(q.load.valid, 0); putKey(q.load.bits.key, zero)
  poke(q.load.bits.kind, DATA); poke(q.load.bits.residentK, 0)
  poke(q.consumer.valid, 0); putKey(q.consumer.bits, zero)
  poke(q.cmd.ready, 0); poke(q.resp.valid, 0); poke(q.resp.bits.rd, 0); poke(q.resp.bits.status, 0)
  poke(q.installerAvailable, 0)
  for (p <- 0 until 2) { poke(q.indexUpdateSafe(p), 1); poke(q.externalApplyPermit(p), 1) }
  poke(h.external.valid, 0); poke(h.external.bits.funct, 25); poke(h.external.bits.rs1, 0)
  poke(h.external.bits.rs2, 0); poke(h.external.bits.rd, 1); poke(h.external.bits.xd, 1)
  poke(h.externalResp.ready, 1); poke(h.globalDrained, 1); poke(h.ownershipReleased, 1)
  poke(h.holdResponse, 0); poke(h.responseMode, 0)
  poke(m.start.valid, 0); putKey(m.start.bits.key, zero); source(4096, 1, 4096, 4)
  poke(m.start.bits.activeRows, 0); poke(m.start.bits.sourceKRows, 0)
  poke(m.memReq.ready, 1); poke(m.memResp.valid, 0); response(Response(zero, 0, 0, 0, 0))
  poke(m.load.ready, 0); poke(m.loadAck.valid, 0); putKey(m.loadAck.bits.key, zero)
  poke(m.loadAck.bits.status, 0); poke(m.loadAck.bits.reason, 0)
  poke(m.contextLive, 0); poke(m.queuePoisoned, 0)
  tick()

  def consume(): Unit = { poke(q.ctlResp.ready, 1); tick(); poke(q.ctlResp.ready, 0) }
  def submit(values: Seq[Int], address: BigInt = 4096, pair: Int = 0, count: Option[BigInt] = None,
             base: Option[BigInt] = None, size: Option[BigInt] = None, status: Int = ACCEPTED,
             hold: Boolean = false, actual: Boolean = false, residency: Int = 9): Key = {
    memoryWrite(address, values)
    source(address, count.getOrElse(BigInt(values.size)), base.getOrElse(address),
      size.getOrElse(BigInt(values.size * 4)))
    val hd = Header(owner = 5, residency = residency, pair = pair, rows = values.size,
      source = 256, j = if (actual) 8 else 1)
    header(hd); poke(q.ctl.bits.op, 0); poke(q.ctl.valid, 1); expect(q.ctl.ready, 1)
    val edge = cycle
    tick(); poke(q.ctl.valid, 0)
    expect(q.ctlResp.valid, 1); expect(q.ctlResp.bits.status, status)
    val k = getKey(q.ctlResp.bits.key)
    if (status == ACCEPTED) {
      windows(k) = Window(k, hd, address, values, edge)
      log.println(s"SUBMIT key=$k edge=$edge")
    }
    // Caller changes all source/header inputs immediately after QueueSubmit.
    source(123, 0, 0, 0); header(Header(owner = 77, residency = 88, pair = 1, rows = 0, source = 0, j = 0))
    if (!hold) consume()
    k
  }
  def ready(k: Key): Unit = {
    until(peek(q.entryState(k.ticket.toInt)) == READY)
    assert(delivered(k) == windows(k).values.size)
    mark(k, "ready"); expect(dut.io.lease, 0); expect(m.busy, 0)
  }
  def applyKey(k: Key, status: Int = APPLY_STARTED): Unit = {
    poke(q.ctl.bits.op, 1); putKey(q.ctl.bits.key, k); poke(q.ctl.valid, 1)
    expect(q.ctl.ready, 1)
    if (status == APPLY_STARTED) { mark(k, "apply"); expect(q.consumerPermit, 0) }
    tick(); poke(q.ctl.valid, 0); expect(q.ctlResp.bits.status, status); consume()
  }
  def finish(k: Key): Unit = {
    until(marks.contains((k, "applied")))
    val w = windows(k); val p = k.pair.toInt
    expect(q.entryState(k.ticket.toInt), FREE); expect(q.publishedValid(p), 1)
    assert(getKey(q.publishedKey(p)) == k)
    expect(h.descriptors(p).bBase, w.header.base); expect(h.descriptors(p).jTiles, w.header.j)
    expect(h.descriptors(p).activeRows, w.values.size); expect(h.descriptors(p).sourceKRows, w.header.source)
    expect(h.complete(p), 1)
    for (i <- 0 until entries) {
      expect(h.tableValid(p)(i), if (i < w.values.size) 1 else 0)
      if (i < w.values.size) expect(h.table(p)(i), w.values(i))
    }
    putKey(q.consumer.bits, k); poke(q.consumer.valid, 1); expect(q.consumerPermit, 1)
    def interval(a: String, b: String): String = s"${marks((k, a))}..${marks((k, b))}"
    log.println(s"INTERVAL key=$k Read=${interval("readFirst", "readLast")} FILL=${interval("fillFirst", "done")} " +
      s"READYwait=${interval("ready", "apply")} APPLY=${interval("apply", "applied")} SUBMITtoREADY=${w.submitted}..${marks((k, "ready"))}; overlapping RTL edges, not hardware time")
  }
  def install(k: Key): Unit = { ready(k); applyKey(k); finish(k) }
  def tableSnapshot: Seq[BigInt] = (0 until 2).flatMap { p =>
    Seq(peek(h.descriptors(p).bBase), peek(h.descriptors(p).jTiles),
      peek(h.descriptors(p).sourceKRows), peek(h.descriptors(p).activeRows), peek(h.complete(p))) ++
      (0 until entries).flatMap(i => Seq(peek(h.table(p)(i)), peek(h.tableValid(p)(i))))
  } ++ Seq(peek(h.configCount), peek(h.writeCount))
  def sticky(k: Key, expectedReason: Int, uncertain: Boolean, priorApplied: Int = 0): Unit = {
    until(peek(m.faulted) == 1, 520)
    expect(m.faultReason, expectedReason); expect(q.poisoned, 1)
    assert(getKey(q.faultKey) == k); expect(q.entryState(k.ticket.toInt), ERROR)
    expect(m.transportUncertain, if (uncertain) 1 else 0)
    if (uncertain) expect(dut.io.lease, 1)
    assert(applied == priorApplied); expect(h.closeAllowed, 0)
    val before = requests.size
    for (_ <- 0 until 3) { tick(); expect(m.faultReason, expectedReason); expect(q.poisoned, 1) }
    assert(requests.size == before)
  }

  scenario match {
    case "A" =>
      val sets = Seq(Seq(1), Seq.tabulate(33)(i => (i * 7) % 251)) ++
        PoTalIndexMetadataFixture.ffn ++ Seq(Seq.tabulate(64)(i => if (i % 3 == 0) 0 else i))
      sets.zipWithIndex.foreach { case (values, i) =>
        val actual = i >= 2 && i < 14
        val k = submit(values, 4096 + i * 1024, pair = if (actual) ((i - 2) / 4) % 2 else 0, hold = true,
          actual = actual, residency = if (actual) (i - 2) / 4 else 9)
        ready(k) // Held ACCEPTED must not postpone/restart loading.
        expect(q.ctlResp.valid, 1); assert(getKey(q.ctlResp.bits.key) == k)
        assert(requested(k) == values.size)
        // Reuse after DONE, before APPLY: Queue must own the original bytes.
        memoryWrite(windows(k).address, values.map(_ => 255))
        consume(); applyKey(k); finish(k)
      }
      // Aligned subrange of an unaligned allocation and legal exclusive 2^64 end.
      val edge = (BigInt(1) << 64) - 4
      install(submit(Seq(7), edge, base = Some(edge - 1), size = Some(5)))
    case "B" =>
      val first = submit(Seq(2, 0, 2)); install(first)
      val snapshot = tableSnapshot
      poke(q.indexUpdateSafe(0), 0); poke(q.externalApplyPermit(0), 0)
      val next = submit(Seq(1, 1, 0), 8192)
      ready(next); assert(tableSnapshot == snapshot); assert(requested(next) == 3)
      putKey(q.consumer.bits, first); expect(q.consumerPermit, 1)
      applyKey(next, BUSY)
      poke(q.indexUpdateSafe(0), 1); applyKey(next, BUSY)
      poke(q.indexUpdateSafe(0), 0); poke(q.externalApplyPermit(0), 1); applyKey(next, BUSY)
      assert(tableSnapshot == snapshot)
      poke(q.indexUpdateSafe(0), 1); applyKey(next); finish(next)
      putKey(q.consumer.bits, first); expect(q.consumerPermit, 0)
    case "D" =>
      val values = Seq.tabulate(64)(i => if (i % 4 == 0) 0 else i % 7)
      val a = submit(values); ready(a)
      val b = submit(values.reverse, 8192, pair = 1); ready(b)
      val before = requests.size; val snapshot = tableSnapshot
      submit(Seq(1), 12288, status = BUSY_NOT_ACCEPTED)
      assert(requests.size == before && tableSnapshot == snapshot); expect(q.occupancy, 2)
      applyKey(a)
      until(marks.get((a, "writeLast")).exists(_ < cycle) && peek(h.writeEvent.valid) == 0 &&
        peek(h.forwardedResponse.valid) == 1 && peek(h.tableValid(0)(63)) == 1)
      // Refusal uses pre-edge occupancy even when final checked response retires.
      submit(Seq(1), 12288, status = BUSY_NOT_ACCEPTED)
      finish(a); expect(q.occupancy, 1); assert(requests.size == before)
      val c = submit(values, 12288)
      assert(c.ticket == a.ticket && c.generation == b.generation + 1)
      ready(c); applyKey(b); finish(b); applyKey(c); finish(c)
    case name if name.startsWith("invalid-") =>
      val top = BigInt(1) << 64
      val (address, count, base, size) = name.stripPrefix("invalid-") match {
        case "zero" => (BigInt(4096), BigInt(0), BigInt(4096), BigInt(8))
        case "65" => (BigInt(4096), BigInt(65), BigInt(4096), BigInt(260))
        case "mismatch" => (BigInt(4096), BigInt(1), BigInt(4096), BigInt(8))
        case "alignment" => (BigInt(4097), BigInt(2), BigInt(4096), BigInt(12))
        case "short" => (BigInt(4096), BigInt(2), BigInt(4096), BigInt(7))
        case "empty" => (BigInt(4096), BigInt(2), BigInt(4096), BigInt(0))
        case "below" => (BigInt(4096), BigInt(2), BigInt(4100), BigInt(16))
        case "source-overflow" => (top - 4, BigInt(2), top - 4, BigInt(4))
        case "buffer-overflow" => (top - 8, BigInt(2), top - 8, BigInt(9))
        case "wide-count" => (BigInt(4096), top.min((BigInt(1) << 32) - 1), BigInt(4096), BigInt(8))
      }
      val k = submit(Seq(0, 1), address, count = Some(count), base = Some(base), size = Some(size))
      sticky(k, 1, false); assert(requests.isEmpty && delivered(k) == 0)
    case "delay" =>
      delay = 2
      poke(m.memReq.ready, 0)
      val k = submit(Seq(1, 2, 0))
      for (_ <- 0 until 4) { tick(); expect(dut.io.lease, 1) }
      poke(m.memReq.ready, 1); install(k)
    case "replacement" =>
      val old = submit(Seq(1)); ready(old)
      val k = submit(Seq(2), 8192, hold = true)
      // Accepted replacement, old response and first observation share an edge.
      injecting = Some(requests.last); tick()
      sticky(k, 3, false)
      expect(q.entryState(old.ticket.toInt), ERROR); assert(requested(k) == 0)
    case "zero-cycle" =>
      val k = submit(Seq(1))
      // Earliest legal completion is AFTER request.fire, not its own edge.
      injecting = Some(Response(k, 0, 4096, 1, 1)); tick()
      sticky(k, 3, false); assert(requests.isEmpty && delivered(k) == 0)
    case "poison-start" =>
      val old = submit(Seq(1)); ready(old)
      poke(h.holdResponse, 1); applyKey(old)
      val k = submit(Seq(2), 8192, hold = true)
      poke(h.responseMode, 1); poke(h.holdResponse, 0); tick()
      // Queue's earlier installer fault is authoritative; no invented ERROR key.
      until(peek(m.faulted) == 1, 4)
      expect(m.faultReason, 9); expect(q.poisoned, 1); expect(q.entryState(k.ticket.toInt), ERROR)
      assert(getKey(m.contextKey) == k && requested(k) == 0)
      expect(m.memReq.valid, 0); expect(h.closeAllowed, 0)
    case "request-timeout" | "response-timeout" =>
      if (scenario == "request-timeout") poke(m.memReq.ready, 0) else auto = false
      val k = submit(Seq(1, 2))
      until(peek(m.faulted) == 1, 520)
      sticky(k, 7, true)
      if (scenario == "request-timeout") {
        poke(m.memReq.ready, 1); auto = false; tick()
      }
      injecting = Some(requests.last); tick(); tick()
      expect(m.transportUncertain, 0); expect(dut.io.lease, 0); expect(m.faultReason, 7)
    case "final-data" | "done" | "ready" | "applying" | "final-response" | "retired" | "retired-submit" =>
      val k = submit(Seq(1))
      if (scenario == "final-data") until(peek(m.load.valid) == 1 && peek(m.load.bits.kind) == DATA)
      else if (scenario == "done") until(peek(m.load.valid) == 1 && peek(m.load.bits.kind) == DONE)
      else {
        ready(k)
        if (Set("applying", "final-response", "retired", "retired-submit").contains(scenario)) applyKey(k)
        if (scenario == "final-response") until(peek(h.tableValid(0)(0)) == 1 && peek(h.forwardedResponse.valid) == 1)
        if (scenario.startsWith("retired")) finish(k)
      }
      if (scenario == "retired-submit") {
        header(Header(rows = 1, source = 256)); source(8192, 1, 8192, 4)
        poke(q.ctl.bits.op, 0); poke(q.ctl.valid, 1)
        response(requests.last); poke(m.memResp.valid, 1); expect(q.ctl.ready, 0)
      }
      injecting = Some(requests.last); tick()
      if (scenario.startsWith("retired")) {
        poke(q.ctl.valid, 0); expect(q.occupancy, 0); expect(q.ctlResp.valid, 0)
        expect(m.faulted, 1); expect(m.faultReason, 3); expect(q.poisoned, 0)
        assert(applied == 1); expect(q.publishedValid(0), 1); expect(h.closeAllowed, 0)
      } else sticky(k, 3, false)
    case name =>
      var once = false
      corrupt = r => {
        val target = if (name == "past" || name == "duplicate") 1 else 0
        if (once || r.index != target) r else {
          once = true
          name match {
            case "ticket" => r.copy(k = r.k.copy(ticket = 1))
            case "generation" => r.copy(k = r.k.copy(generation = 0))
            case "owner" => r.copy(k = r.k.copy(owner = 6))
            case "residency" => r.copy(k = r.k.copy(residency = 10))
            case "pair" => r.copy(k = r.k.copy(pair = 1))
            case "future" => r.copy(index = 1)
            case "past" => r.copy(index = 0)
            case "duplicate" => requests.head
            case "address" => r.copy(address = r.address + 4)
            case "mask" => r.copy(mask = 7)
            case "incomplete" => r.copy(complete = 0)
            case "early-last" => r.copy(last = 1)
            case "missing-last" => r.copy(last = 0)
            case "read-error" => r.copy(error = 1)
            case "bad-k" => r.copy(value = 256)
          }
        }
      }
      val k = submit(if (name == "missing-last") Seq(1) else Seq(1, 2))
      val identityFault = Set("ticket", "generation", "owner", "residency", "pair").contains(name)
      val uncertain = identityFault || Set("future", "past", "duplicate", "address", "incomplete").contains(name)
      val reason = if (identityFault) 2 else if (Set("future", "past", "duplicate", "address").contains(name)) 3
        else if (name == "read-error") 5 else if (name == "bad-k") 6 else 4
      sticky(k, reason, uncertain)
      assert(!marks.contains((k, "done")) && !marks.contains((k, "ready")))
      if (uncertain) {
        injecting = Some(requests.last); tick(); tick()
        expect(m.transportUncertain, 0); expect(dut.io.lease, 0); expect(m.faultReason, reason)
      }
  }
  log.close()
}

// Controlled-ready sink is deliberately a Producer UNIT test, not Queue
// backpressure. It also checks full 32-bit little-endian words above byte 0.
final class PoTalIndexMetadataSinkTester(dut: PoTalIndexMetadataProducer, scenario: String, directory: String)
    extends PeekPokeTester(dut) {
  import PoTalIndexWindow._
  val m = dut.io
  val log = new java.io.PrintWriter(new java.io.File(directory, "events.log"))
  var cycle = 0
  def key(k: PoTalIndexWindowKey, owner: Int = 5): Unit = {
    poke(k.ticket, 0); poke(k.generation, 1); poke(k.owner, owner); poke(k.residency, 9); poke(k.pair, 0)
  }
  def tick(): Unit = {
    log.println(s"unit=$scenario cycle=$cycle req=${peek(m.memReq.valid)}/${peek(m.memReq.ready)} " +
      s"resp=${peek(m.memResp.valid)}/${peek(m.memResp.ready)} load=${peek(m.load.valid)}/${peek(m.load.ready)}:" +
      s"${peek(m.load.bits.kind)}:${peek(m.load.bits.residentK)} lease=${peek(m.sourceLease)} " +
      s"uncertain=${peek(m.transportUncertain)} reason=${peek(m.faultReason)}")
    log.flush(); step(1); cycle += 1
  }
  poke(m.start.valid, 0); key(m.start.bits.key)
  poke(m.start.bits.sourceAddress, 4096); poke(m.start.bits.bufferBase, 4095); poke(m.start.bits.bufferBytes, 5)
  poke(m.start.bits.count, 1); poke(m.start.bits.activeRows, 1); poke(m.start.bits.sourceKRows, BigInt("ffffffff", 16))
  poke(m.memReq.ready, 1); poke(m.memResp.valid, 0); key(m.memResp.bits.key)
  poke(m.memResp.bits.index, 0); poke(m.memResp.bits.address, 4096)
  val bytes = Map[BigInt, Int](BigInt(4096) -> 0x04, BigInt(4097) -> 0x03,
    BigInt(4098) -> 0x02, BigInt(4099) -> 0x01)
  val value = (0 until 4).map(j => BigInt(bytes(BigInt(4096 + j))) << (8 * j)).reduce(_ | _)
  poke(m.memResp.bits.data, value); poke(m.memResp.bits.mask, 15)
  poke(m.memResp.bits.complete, 1); poke(m.memResp.bits.last, 1); poke(m.memResp.bits.error, 0)
  poke(m.load.ready, 0); poke(m.loadAck.valid, 1); key(m.loadAck.bits.key)
  poke(m.loadAck.bits.status, LOAD_OK); poke(m.loadAck.bits.reason, 0)
  poke(m.queuePoisoned, 0); poke(m.contextLive, 1)
  poke(m.start.valid, 1); tick(); poke(m.start.valid, 0)
  expect(m.memReq.valid, 1); expect(m.memReq.bits.address, 4096); tick()
  poke(m.memResp.valid, 1); expect(m.memResp.ready, 1); tick(); poke(m.memResp.valid, 0)
  expect(m.load.valid, 1); expect(m.load.bits.kind, DATA); expect(m.load.bits.residentK, value)
  tick() // Establish irrevocable stalled DATA.
  if (scenario == "sink-data-fault") poke(m.memResp.valid, 1)
  for (_ <- 0 until 3) {
    expect(m.load.valid, 1); expect(m.load.bits.kind, DATA); expect(m.load.bits.residentK, value); tick()
  }
  poke(m.load.ready, 1)
  if (scenario == "ack-missing") poke(m.loadAck.valid, 0)
  if (scenario == "ack-key") key(m.loadAck.bits.key, 6)
  if (scenario == "ack-status") poke(m.loadAck.bits.status, INVALID)
  tick()
  if (scenario.startsWith("ack-")) {
    expect(m.faultReason, 8); expect(m.load.bits.kind, LOAD_ERROR); tick()
  } else if (scenario == "sink-data-fault") {
    expect(m.faultReason, 3); expect(m.load.bits.kind, LOAD_ERROR)
    expect(m.memResp.ready, 1); tick(); poke(m.memResp.valid, 0)
  } else {
    expect(m.load.bits.kind, DONE); expect(m.sourceLease, 1)
    poke(m.load.ready, 0); tick()
    if (scenario == "sink-done-fault") poke(m.memResp.valid, 1)
    for (_ <- 0 until 3) { expect(m.load.valid, 1); expect(m.load.bits.kind, DONE); tick() }
    poke(m.load.ready, 1); tick()
    if (scenario == "sink-done-fault") {
      expect(m.faultReason, 3); expect(m.load.bits.kind, LOAD_ERROR); tick()
    } else { expect(m.sourceLease, 0); expect(m.faulted, 0); expect(m.busy, 0) }
  }
  log.close()
}

class PoTalIndexMetadataProducerSpec extends ChiselFlatSpec {
  behavior of "Addressed Index metadata producer"
  private def run(name: String, unit: Boolean = false): Unit = {
    val root = java.nio.file.Paths.get(sys.env.getOrElse(
      "POTAL_INDEX_METADATA_TEST_DIR", "test_run_dir/potal-index-metadata"))
    Files.createDirectories(root)
    val dir = Files.createTempDirectory(root, name + "-")
    val args = Array("--backend-name", "treadle", "--generate-vcd-output", "on", "--target-dir", dir.toString)
    if (unit) {
      Driver.execute(args, () => new PoTalIndexMetadataProducer(2, 64, 8, 8, 32, 64, 512)) {
        dut => new PoTalIndexMetadataSinkTester(dut, name, dir.toString)
      } should be(true)
    } else {
      Driver.execute(args, () => new PoTalIndexMetadataHarness) {
        dut => new PoTalIndexMetadataTester(dut, name, dir.toString)
      } should be(true)
    }
  }
  it should "A fetch exact addressed arrays with snapshots and Queue owned completion" in { run("A") }
  it should "B fetch while the installed table is busy and apply only after both gates" in { run("B") }
  it should "C preserve offers and quarantine tagged framing transport and late faults" in {
    Seq("delay", "ticket", "generation", "owner", "residency", "pair", "future", "past", "duplicate",
      "address", "mask", "incomplete", "early-last", "missing-last", "read-error", "bad-k",
      "request-timeout", "response-timeout", "final-data", "done", "ready", "applying",
      "final-response", "retired", "retired-submit", "replacement", "zero-cycle", "poison-start").foreach(name => run(name))
    Seq("sink", "sink-data-fault", "sink-done-fault", "ack-missing", "ack-key", "ack-status")
      .foreach(name => run(name, unit = true))
  }
  it should "D enforce capacity source extents full refusal and exact reclaim" in {
    run("D")
    Seq("zero", "65", "mismatch", "alignment", "short", "empty", "below",
      "source-overflow", "buffer-overflow", "wide-count").foreach(name => run("invalid-" + name))
  }
}
