package potal

import chisel3._
import chisel3.iotesters._
import freechips.rocketchip.diplomacy.LazyModule
import freechips.rocketchip.diplomacy.TransferSizes
import freechips.rocketchip.rocket.TLBPTWIO
import java.nio.file.{Files, Paths}
import scala.collection.mutable
import org.scalatest.flatspec.AnyFlatSpec

// PTW interface model only: no page-table memory accesses or Rocket page walk.
final class PoTalIndexMetadataTransportTester(
  dut: PoTalIndexMetadataTransportHarness#Impl, family: String,
  scenario: String, directory: String
) extends PeekPokeTester(dut) {
  import PoTalIndexWindow._
  val io = dut.io
  val ctl = io.control
  val log = new java.io.PrintWriter(Files.newBufferedWriter(Paths.get(directory, "events.log")), true)
  def zero(x: Data): Unit = x match {
    case b: Bits => poke(b, 0)
    case e: chisel3.experimental.EnumType => poke(e, 0)
    case r: Record => r.elements.values.foreach(zero)
    case v: Vec[_] => v.foreach(zero)
  }
  def fire(v: Bool, r: Bool): Boolean = peek(v) == 1 && peek(r) == 1
  def until(label: String, bound: Int = 2048)(condition: => Boolean): Unit = {
    var n = 0
    while (!condition && n < bound) { tick(); n += 1 }
    if (!condition) log.println(s"WAIT_FAILED label=$label cycle=$cycle fault=${peek(ctl.faulted)}" +
      s" lease=${peek(ctl.sourceLease)} busy=${peek(ctl.transportBusy)}" +
      s" states=${ctl.entryState.map(peek(_))} counts=${ctl.entryCount.map(peek(_))}")
    assert(condition, s"$label not observed after $bound cycles")
  }
  case class Request(address: BigInt, size: Int, source: BigInt, opcode: Int,
                     mask: BigInt, beats: Int, available: Int, var beat: Int = 0)
  val memory = mutable.Map.empty[BigInt, Int]
  val pending = mutable.ArrayBuffer.empty[Request]
  val sources = mutable.Set.empty[BigInt]
  val delivered = mutable.ArrayBuffer.empty[BigInt]
  val accepted = mutable.ArrayBuffer.empty[Request]
  val reads = mutable.Set.empty[BigInt]
  val readTags = mutable.Set.empty[BigInt]
  val aSources = mutable.Set.empty[BigInt]
  val operandGoldens = mutable.Map.empty[BigInt, Seq[Int]]
  var threeWayOverlap = false
  var metadataBackpressure = false
  val translationSizes = mutable.Set.empty[BigInt]
  var cycle = 0
  var done = false
  var error = false
  var writesDone = 0
  var ptwRequests = 0
  var suppressD = scenario == "missing" || family == "B"
  var suppressPTW = scenario == "pending-refill"
  var stallA = scenario == "held-a"
  var resultKey = Seq.empty[BigInt]
  var expectedControlStatus = ACCEPTED
  var lastControlStatus = Option.empty[BigInt]
  var heldA = Option.empty[Seq[BigInt]]
  var selected = Option.empty[Request]
  val translation = mutable.Map.empty[BigInt, BigInt]
  val ptwPending = mutable.Map.empty[Int, BigInt]
  val ports = Seq(io.metadataPTW) ++ io.legacyPTW
  def key(k: PoTalIndexWindowKey): Seq[BigInt] =
    Seq(peek(k.ticket), peek(k.generation), peek(k.owner), peek(k.residency), peek(k.pair))
  def aBits: Seq[BigInt] = Seq(peek(io.memory.a.bits.address), peek(io.memory.a.bits.size),
    peek(io.memory.a.bits.source), peek(io.memory.a.bits.opcode), peek(io.memory.a.bits.mask),
    peek(io.memory.a.bits.data), peek(io.memory.a.bits.corrupt))
  def initPTW(port: TLBPTWIO): Unit = {
    poke(port.req.ready, 1); poke(port.resp.valid, 0); zero(port.resp.bits)
    zero(port.ptbr); zero(port.hgatp); zero(port.vsatp); zero(port.status)
    zero(port.hstatus); zero(port.gstatus); zero(port.pmp); zero(port.customCSRs)
    // All-address NAPOT, unlocked RWX; U and S can access ordinary memory.
    if (port.pmp.nonEmpty) {
      poke(port.pmp(0).cfg.a, 3); poke(port.pmp(0).cfg.r, 1)
      poke(port.pmp(0).cfg.w, 1); poke(port.pmp(0).cfg.x, 1)
      poke(port.pmp(0).addr, (BigInt(1) << port.pmp(0).addr.getWidth) - 1)
      // TLBPTWIO carries decoded PMP, not only CSR address/config registers.
      // All-address NAPOT computes an all-one physical-address mask.
      poke(port.pmp(0).mask, (BigInt(1) << port.pmp(0).mask.getWidth) - 1)
    }
  }
  zero(ctl.submit.bits); poke(ctl.submit.valid, 0); poke(ctl.result.ready, 1)
  poke(ctl.mappingEpoch, 1); poke(ctl.flush, 0); poke(ctl.passLoad, 1)
  ports.foreach(initPTW)
  zero(io.read.req.bits); poke(io.read.req.valid, 0)
  zero(io.write.req.bits); poke(io.write.req.valid, 0)
  io.sramRead.foreach { x => poke(x.req.valid, 0); zero(x.req.bits); poke(x.resp.ready, 1) }
  io.sramWrite.foreach(zero)
  poke(io.flushLegacy, 0)
  poke(io.memory.a.ready, 0)
  poke(io.memory.d.valid, 0); zero(io.memory.d.bits)
  if (io.memory.params.hasBCE) {
    poke(io.memory.b.valid, 0); zero(io.memory.b.bits)
    poke(io.memory.c.ready, 1); poke(io.memory.e.ready, 1)
  }

  def tick(): Unit = {
    // Hold each D until acceptance. Responses originate only from accepted A.
    if (selected.isEmpty && !suppressD) selected = pending.find(_.available <= cycle)
    if (family == "B" && !metadataBackpressure && peek(io.memory.a.valid) == 1 &&
      peek(io.memory.a.bits.source) == 32) {
      if (heldA.nonEmpty) {
        metadataBackpressure = true
        stallA = false
        log.println(s"BACKPRESSURE_RELEASE cycle=$cycle source=32")
      } else {
        stallA = true
        log.println(s"BACKPRESSURE_HOLD cycle=$cycle source=32")
      }
    }
    poke(io.memory.a.ready, if (stallA) 0 else 1)
    poke(io.memory.d.valid, if (selected.nonEmpty && !suppressD) 1 else 0)
    selected.foreach { r =>
      val base = (r.address / 16) * 16 + r.beat * 16
      val data = (0 until 16).map(i =>
        BigInt(memory.getOrElse(base + i, 0xa5)) << (8 * i)).foldLeft(BigInt(0))(_ | _)
      poke(io.memory.d.bits.opcode, if (r.opcode == 4) 1 else 0)
      poke(io.memory.d.bits.param, 0); poke(io.memory.d.bits.size, r.size)
      poke(io.memory.d.bits.source, r.source); poke(io.memory.d.bits.sink, 0)
      if (scenario == "wrong-source") {
        poke(io.memory.d.bits.source, 63)
        log.println("INJECT_INVALID_SOURCE expected TileLink monitor failure")
        log.flush()
      }
      poke(io.memory.d.bits.data, data)
      poke(io.memory.d.bits.denied, if (scenario == "denied") 1 else 0)
      poke(io.memory.d.bits.corrupt, if (scenario == "denied" || scenario == "corrupt") 1 else 0)
    }
    ports.zipWithIndex.foreach { case (port, i) =>
      val response = ptwPending.get(i).filter(_ => !suppressPTW || i != 0)
      poke(port.resp.valid, if (response.nonEmpty) 1 else 0)
      response.foreach { vpn =>
        zero(port.resp.bits)
        val ppn = translation.getOrElse(vpn, vpn)
        poke(port.resp.bits.pte.ppn, if (scenario == "high-ppn") BigInt(1) << 40 else ppn)
        poke(port.resp.bits.pte.v, 1); poke(port.resp.bits.pte.r, 1)
        poke(port.resp.bits.pte.w, 1); poke(port.resp.bits.pte.a, 1)
        poke(port.resp.bits.pte.d, 1); poke(port.resp.bits.pte.u, 1)
        if (scenario.startsWith("mxr")) {
          poke(port.resp.bits.pte.r, 0); poke(port.resp.bits.pte.w, 0)
          poke(port.resp.bits.pte.x, 1)
        }
        poke(port.resp.bits.level, 2)
        // Nonhomogeneous forces the real special-entry permission checker.
        poke(port.resp.bits.homogeneous, 0)
        ptwPending.remove(i)
        log.println(s"PTW_RESPONSE cycle=$cycle client=$i vpn=$vpn ppn=$ppn")
      }
    }
    heldA.foreach { old => assert(peek(io.memory.a.valid) == 1 && aBits == old) }
    heldA = if (peek(io.memory.a.valid) == 1 && peek(io.memory.a.ready) == 0) Some(aBits) else None
    if (fire(io.memory.d.valid, io.memory.d.ready)) {
      val r = selected.get
      log.println(s"D cycle=$cycle source=${r.source} beat=${r.beat} size=${r.size}")
      r.beat += 1
      if (scenario == "partial" && r.source == 32 && r.beat == 1) suppressD = true
      if (r.beat == r.beats) {
        sources.remove(r.source); pending -= r; selected = None
      }
    }
    if (fire(io.memory.a.valid, io.memory.a.ready)) {
      val a = io.memory.a.bits
      val opcode = peek(a.opcode).toInt
      val size = peek(a.size).toInt
      val source = peek(a.source)
      val address = peek(a.address)
      assert(!sources(source), s"source $source reused before terminal D")
      sources += source; aSources += source
      val r = Request(address, size, source, opcode, peek(a.mask),
        if (opcode == 4) math.max(1, (1 << size) / 16) else 1,
        cycle + dut.minimumResponseLatency)
      pending += r; accepted += r
      if (opcode != 4) {
        assert(address == BigInt("80009000", 16) && peek(a.mask) == 15)
        assert((peek(a.data) & BigInt("ffffffff", 16)) == BigInt("03020100", 16))
      }
      if (family == "B" && source == 32) {
        if (sources.exists(s => s >= 16 && s < 32) && sources.exists(_ < 16)) {
          threeWayOverlap = true
          log.println(s"THREE_WAY_OVERLAP cycle=$cycle sources=${sources.toSeq.sorted.mkString(",")}")
        }
        suppressD = false
      }
      log.println(s"A cycle=$cycle source=$source address=$address size=$size opcode=$opcode mask=${r.mask}")
    }
    ports.zipWithIndex.foreach { case (port, i) =>
      if (fire(port.req.valid, port.req.ready) && peek(port.req.bits.valid) == 1) {
        assert(!ptwPending.contains(i), "more than one private refill")
        ptwPending(i) = peek(port.req.bits.bits.addr)
        ptwRequests += 1
        log.println(s"PTW_REQUEST cycle=$cycle client=$i vpn=${ptwPending(i)}")
      }
    }
    if (fire(ctl.result.valid, ctl.result.ready)) {
      val status = peek(ctl.result.bits.status)
      assert(status == expectedControlStatus)
      lastControlStatus = Some(status)
      if (status == ACCEPTED) resultKey = key(ctl.result.bits.key)
      log.println(s"CONTROL_RESULT cycle=$cycle status=$status key=${key(ctl.result.bits.key)}")
    }
    if (peek(io.metadataRequest.valid) == 1) {
      translationSizes += peek(io.metadataRequest.bits.tlb_req.size)
      assert(peek(io.metadataRequest.bits.tlb_req.passthrough) == 0)
      assert(peek(io.metadataRequest.bits.tlb_req.prv) == peek(io.metadataRequest.bits.status.dprv))
      log.println(s"TRANSLATE cycle=$cycle va=${peek(io.metadataRequest.bits.tlb_req.vaddr)}" +
        s" size=${peek(io.metadataRequest.bits.tlb_req.size)} prv=${peek(io.metadataRequest.bits.tlb_req.prv)}")
    }
    if (peek(ctl.load.valid) == 1) {
      val k = key(ctl.load.bits.key)
      if (resultKey.nonEmpty) assert(k == resultKey)
      val kind = peek(ctl.load.bits.kind).toInt
      log.println(s"LOAD cycle=$cycle key=$k kind=$kind data=${peek(ctl.load.bits.residentK)}")
      if (kind == DATA) delivered += peek(ctl.load.bits.residentK)
      if (kind == DONE) done = true
      if (kind == LOAD_ERROR) error = true
    }
    if (peek(io.read.resp.valid) == 1) {
      expect(io.read.resp.bits.cmd_id, 0)
      expect(io.read.resp.bits.stream.valid, 1)
      reads += peek(io.read.resp.bits.stream.serial) - 100
      assert(peek(io.read.resp.bits.bytesRead) == 32)
    }
    if (peek(io.readerFire) == 1) {
      expect(io.readerData.cmd_id, 0)
      val tag = peek(io.readerData.stream.serial) - 100
      readTags += tag
      val golden = operandGoldens(tag)
      assert((peek(io.readerData.data) & ((BigInt(1) << 256) - 1)) ==
        golden.zipWithIndex.map { case (byte, i) => BigInt(byte) << (8 * i) }.sum)
      io.readerData.mask.zipWithIndex.foreach { case (x, i) =>
        expect(x, if (i * dut.readerMaskByteStride < 32) 1 else 0)
      }
      expect(io.readerData.stream.valid, 1)
      expect(io.readerData.stream.id, if (tag == 31) 0 else 1)
      assert(peek(io.readerData.bytes_read) == 32)
    }
    if (peek(io.write.resp.valid) == 1) writesDone += 1
    expect(ctl.indexWrite, 0)
    step(1); cycle += 1
  }
  val virtualBase = if (scenario == "pages") BigInt("40000ff0", 16)
    else if (scenario == "manager-tail") BigInt("8ffffff0", 16)
    else if (scenario == "pa-high") BigInt("10080001000", 16)
    else BigInt("80001000", 16)
  val physicalBase = if (scenario == "pages") BigInt("80001ff0", 16) else virtualBase
  translation(virtualBase >> 12) = physicalBase >> 12
  translation((virtualBase >> 12) + 1) = BigInt("80005", 16)
  val translated = Set("pages", "high-ppn", "pending-refill",
    "sum", "sum-denied", "mxr", "mxr-denied").contains(scenario)
  if (translated) { poke(io.metadataPTW.ptbr.mode, 8); poke(io.metadataPTW.ptbr.ppn, 0x80000) }
  val pair = if (scenario == "pair1") 1 else 0
  val fixture = PoTalIndexMetadataFixture.ffn(if (pair == 0) 0 else 8).map(BigInt(_))
  val values = if (scenario == "pmp-size") fixture.take(8)
    else if (scenario == "capacity") Seq.tabulate(96)(i => BigInt(i % 256))
    else if (scenario == "uint32-high") Seq(BigInt("80000001", 16)) else fixture
  def physicalAddress(va: BigInt): BigInt =
    if (translated) (translation(va >> 12) << 12) | (va & 4095) else va
  for ((value, i) <- values.zipWithIndex; b <- 0 until 4)
    memory(physicalAddress(virtualBase + i * 4 + b)) = ((value >> (8 * b)) & 255).toInt
  val descriptor = ctl.submit.bits.descriptor
  val header = ctl.submit.bits.header
  poke(descriptor.source.sourceAddress,
    if (scenario == "canonical") BigInt("0000008000000000", 16) else virtualBase)
  poke(descriptor.source.bufferBase,
    if (scenario == "canonical") BigInt("0000008000000000", 16) else virtualBase)
  poke(descriptor.source.bufferBytes, values.size * 4)
  if (scenario == "allocation") poke(descriptor.source.bufferBytes, values.size * 4 - 4)
  poke(descriptor.source.count, values.size)
  poke(descriptor.status.dprv, if (translated || scenario == "canonical") 0 else 3)
  if (scenario.startsWith("sum")) {
    poke(descriptor.status.dprv, 1)
    poke(descriptor.status.sum, if (scenario == "sum") 1 else 0)
  }
  if (scenario.startsWith("mxr")) poke(descriptor.status.mxr, if (scenario == "mxr") 1 else 0)
  if (scenario == "pmp-size") {
    poke(descriptor.status.dprv, 0)
    poke(io.metadataPTW.pmp(0).cfg.a, 1)
    poke(io.metadataPTW.pmp(0).addr, (virtualBase + 16) >> 2)
  }
  if (scenario == "mode") poke(io.metadataPTW.ptbr.mode, 15)
  if (scenario == "canonical") poke(io.metadataPTW.ptbr.mode, 8)
  poke(header.owner, if (scenario == "capacity") 4095 else 17)
  poke(header.residency, 23); poke(header.pair, pair)
  poke(header.bBase, if (pair == 0) 2048 else 6144)
  poke(header.jTiles, 1); poke(header.sourceKRows, 256); poke(header.activeRows, values.size)
  if (scenario == "rejected") {
    expectedControlStatus = INVALID
    poke(header.activeRows, 0)
    poke(ctl.submit.valid, 1)
    until("invalid submit ready")(peek(ctl.submit.ready) == 1)
    tick(); poke(ctl.submit.valid, 0)
    until("explicit nonacceptance")(lastControlStatus.contains(BigInt(INVALID)))
    until("rejected source lease retirement")(peek(ctl.sourceLease) == 0)
    assert(accepted.isEmpty && ptwRequests == 0 && delivered.isEmpty)
    expect(ctl.transportBusy, 0); expect(ctl.metadataCloseBlocked, 0)
    expect(ctl.faulted, 0)
    expectedControlStatus = ACCEPTED
    lastControlStatus = None
    poke(header.activeRows, values.size)
  }
  poke(ctl.submit.valid, 1)
  until("submit ready")(peek(ctl.submit.ready) == 1)
  tick(); poke(ctl.submit.valid, 0)
  // Only unaccepted descriptor pins change; leased memory/context stay frozen.
  zero(ctl.submit.bits)
  if (scenario == "held-load") {
    poke(ctl.passLoad, 0)
    until("held load offer")(peek(ctl.loadOffered) == 1)
    val held = key(ctl.load.bits.key) ++ Seq(peek(ctl.load.bits.kind), peek(ctl.load.bits.residentK))
    tick() // Sample valid && !ready before injecting the fault.
    expect(ctl.loadOffered, 1)
    assert(held == key(ctl.load.bits.key) ++ Seq(peek(ctl.load.bits.kind), peek(ctl.load.bits.residentK)))
    log.println(s"LOAD_STALLED cycle=$cycle key=${key(ctl.load.bits.key)}")
    poke(ctl.mappingEpoch, 2); tick()
    expect(ctl.loadOffered, 1); expect(ctl.sourceLease, 1)
    assert(held == key(ctl.load.bits.key) ++ Seq(peek(ctl.load.bits.kind), peek(ctl.load.bits.residentK)))
    poke(ctl.passLoad, 1)
  }
  if (family == "B") {
    // Initialize the actual SRAM row subsequently consumed by StreamWriter.
    poke(io.sramWrite(0).en, 1); poke(io.sramWrite(0).addr, 64)
    io.sramWrite(0).mask.foreach(x => poke(x, 1))
    poke(io.sramWrite(0).data, BigInt("03020100", 16))
    tick(); poke(io.sramWrite(0).en, 0)
    zero(io.write.req.bits)
    poke(io.write.req.bits.vaddr, BigInt("80009000", 16))
    poke(io.write.req.bits.laddr.data, 64)
    poke(io.write.req.bits.len, 4); poke(io.write.req.bits.cmd_id, 59)
    poke(io.write.req.bits.store_en, 1)
    poke(io.write.req.valid, 1)
    until("writer request ready")(peek(io.write.req.ready) == 1)
    tick(); poke(io.write.req.valid, 0)
    for (tag <- Seq(31, 47)) {
      // The production tracker has one command slot in this DIM32 geometry.
      // Reuse slot zero only after completion; stream serials identify A/B.
      if (tag == 47) until("first operand command completes")(reads(BigInt(31)))
      zero(io.read.req.bits)
      val address = BigInt("80008000", 16) + tag * 64
      val golden = (0 until 32).map { i =>
        val a = address + i
        ((a ^ (a >> 4) ^ 0x5b) & 255).toInt
      }
      operandGoldens(BigInt(tag)) = golden
      golden.zipWithIndex.foreach { case (byte, i) => memory(address + i) = byte }
      poke(io.read.req.bits.vaddr, address)
      poke(io.read.req.bits.cols, 32); poke(io.read.req.bits.scale, 0x3f800000)
      poke(io.read.req.bits.pixel_repeats, 1); poke(io.read.req.bits.block_stride, 1)
      poke(io.read.req.bits.cmd_id, 0)
      poke(io.read.req.bits.stream.valid, 1)
      poke(io.read.req.bits.stream.id, if (tag == 31) 0 else 1)
      poke(io.read.req.bits.stream.serial, tag + 100)
      poke(io.read.req.bits.status.dprv, 3)
      poke(io.read.req.valid, 1)
      until("operand request ready")(peek(io.read.req.ready) == 1)
      tick(); poke(io.read.req.valid, 0)
    }
  }
  if (scenario == "held-a") {
    until("held metadata A")(peek(io.memory.a.valid) == 1)
    poke(ctl.mappingEpoch, 2)
    poke(ctl.submit.valid, 1)
    expect(ctl.submit.ready, 0)
    tick(); expect(ctl.metadataCloseBlocked, 1)
    poke(ctl.submit.valid, 0)
    stallA = false
  }
  if (scenario == "pending-refill") {
    until("private refill accepted")(peek(ctl.ptwPending) == 1)
    poke(ctl.flush, 1); tick(); poke(ctl.flush, 0)
    expect(ctl.sourceLease, 1); expect(ctl.metadataFenceBusy, 1)
    suppressPTW = false
  }
  val bad = Set("canonical", "high-ppn", "pending-refill", "held-a",
    "denied", "corrupt", "missing", "partial", "sum-denied", "mxr-denied", "pmp-size",
    "mode", "held-load", "uint32-high", "manager-tail", "allocation", "pa-high").contains(scenario)
  if (bad) {
    until("sticky fault", 2048)(peek(ctl.faulted) == 1)
    until("exact-key Queue ERROR", 512)(peek(ctl.entryState(0)) == ERROR)
    until("accepted control reply before fault attribution")(resultKey.nonEmpty)
    assert(resultKey.nonEmpty && key(ctl.faultKey) == resultKey)
    assert(!done)
    expect(ctl.metadataFenceBusy, 1); expect(ctl.metadataCloseBlocked, 1)
    if (Set("canonical", "high-ppn", "pending-refill", "sum-denied",
      "mxr-denied", "pmp-size", "mode", "manager-tail", "allocation",
      "pa-high").contains(scenario)) assert(accepted.isEmpty)
    if (scenario == "missing" || scenario == "partial") {
      expect(ctl.sourceLease, 1); assert(pending.nonEmpty)
      expect(io.metadataUncertain, 1)
      if (scenario == "partial") assert(selected.exists(r => r.source == 32 && r.beat == 1))
      suppressD = false
      until("late physical drain")(pending.isEmpty && selected.isEmpty && peek(io.metadataUncertain) == 0)
      expect(ctl.faulted, 1); assert(!done)
    }
    if (scenario == "held-a") {
      expect(ctl.sourceLease, 1)
      until("held A accepted after fault")(accepted.exists(_.source == 32))
    }
    if (Set("held-a", "denied", "corrupt").contains(scenario)) {
      expect(ctl.sourceLease, 1)
      until("faulted physical transaction fully drained")(
        pending.isEmpty && selected.isEmpty && peek(io.metadataUncertain) == 0)
      expect(ctl.faulted, 1); expect(ctl.entryState(0), ERROR); assert(!done)
      log.println(s"FAULT_DRAINED cycle=$cycle scenario=$scenario")
    }
    if (scenario == "pending-refill") {
      expect(ctl.sourceLease, 1)
      until("late private refill fully drained")(
        ptwPending.isEmpty && peek(ctl.ptwPending) == 0 && peek(io.metadataUncertain) == 0)
      expect(ctl.sourceLease, 1); expect(ctl.faulted, 1)
      expect(ctl.entryState(0), ERROR); assert(!done)
      log.println(s"REFILL_DRAINED_QUARANTINED cycle=$cycle")
    }
    if (scenario == "pmp-size") assert(translationSizes(2) && translationSizes(5))
  } else {
    until("metadata DONE")(done)
    until("healthy transport drain")(peek(ctl.transportBusy) == 0)
    until("checked DONE source lease retirement")(peek(ctl.sourceLease) == 0)
    assert(delivered.toSeq == values, s"independent golden mismatch $delivered")
    expect(ctl.entryState(0), READY); expect(ctl.entryCount(0), values.size)
    expect(ctl.metadataFenceBusy, 0); expect(ctl.metadataCloseBlocked, 1)
    expect(ctl.sourceLease, 0); expect(ctl.faulted, 0)
    val metadata = accepted.filter(_.source == 32)
    assert(metadata.map(r => 1 << r.size).sum == values.size * 4)
    metadata.foreach(r => assert((0 until (1 << r.size)).forall(i => memory.contains(r.address + i))))
    if (scenario == "small-get") assert(metadata.forall(_.size <= 4))
    if (scenario == "pages") assert(metadata.map(_.address >> 12).distinct.size == 2 && ptwRequests >= 2)
    if (scenario == "pages") {
      // READY retains copied metadata, not the old source lease. A quiescent
      // new epoch/root must invalidate the private cache before translating the
      // same VA to a different pair of physical pages.
      val secondStart = accepted.size
      val secondGolden = values.reverse
      translation(virtualBase >> 12) = BigInt("80007", 16)
      translation((virtualBase >> 12) + 1) = BigInt("80009", 16)
      val newAddresses = mutable.Set.empty[BigInt]
      for ((value, i) <- secondGolden.zipWithIndex; b <- 0 until 4) {
        val pa = physicalAddress(virtualBase + i * 4 + b)
        memory(pa) = ((value >> (8 * b)) & 255).toInt
        newAddresses += pa
      }
      poke(ctl.mappingEpoch, 2); poke(io.metadataPTW.ptbr.ppn, 0x81000)
      zero(ctl.submit.bits)
      poke(descriptor.source.sourceAddress, virtualBase)
      poke(descriptor.source.bufferBase, virtualBase)
      poke(descriptor.source.bufferBytes, secondGolden.size * 4)
      poke(descriptor.source.count, secondGolden.size)
      poke(descriptor.status.dprv, 0)
      poke(header.owner, 18); poke(header.residency, 24); poke(header.pair, 1)
      poke(header.bBase, 6144); poke(header.jTiles, 1)
      poke(header.sourceKRows, 256); poke(header.activeRows, secondGolden.size)
      done = false; delivered.clear(); resultKey = Seq.empty
      poke(ctl.submit.valid, 1)
      until("quiescent new context admission")(peek(ctl.submit.ready) == 1)
      tick(); poke(ctl.submit.valid, 0); zero(ctl.submit.bits)
      until("new context checked DONE")(done)
      until("new context lease retirement")(peek(ctl.sourceLease) == 0)
      assert(delivered.toSeq == secondGolden)
      expect(ctl.entryState(0), READY); expect(ctl.entryState(1), READY)
      expect(ctl.metadataFenceBusy, 0); expect(ctl.metadataCloseBlocked, 1)
      expect(ctl.faulted, 0)
      accepted.drop(secondStart).foreach(r =>
        assert((0 until (1 << r.size)).forall(i => newAddresses(r.address + i))))
    }
    if (family == "B") {
      until("real DMA completions")(reads == Set(BigInt(31), BigInt(47)))
      assert(readTags == reads)
      assert(threeWayOverlap, "require at least one actual three-way overlap")
      assert(metadataBackpressure, "require held metadata A under real bus backpressure")
      assert(operandGoldens(BigInt(31)) != operandGoldens(BigInt(47)))
      assert(aSources.exists(s => s >= 16 && s < 32) && aSources(32))
      assert(aSources.exists(_ < 16), "actual writer must own a distinct source")
      until("writer final AccessAck and full legacy drain")(
        pending.isEmpty && selected.isEmpty && sources.isEmpty && peek(io.legacyBusy) == 0)
      assert(writesDone == 1)
      expect(io.legacyInterrupt, 0)
    }
  }
  log.println(s"RESULT family=$family scenario=$scenario cycles=$cycle done=$done error=$error")
  log.close()
}

final class PoTalIndexMetadataTransportSpec extends AnyFlatSpec {
  behavior of "I25D production metadata transport"
  private def run(family: String, scenario: String, shared: Boolean, deadline: Int = 4096): Unit = {
    val root = Paths.get(sys.env("POTAL_INDEX_METADATA_TRANSPORT_TEST_DIR"))
    val directory = Files.createTempDirectory(root, s"$family-$scenario-").toString
    val (parameters, captured) = PoTalIndexMetadataTransportProfile.capture(directory, shared)
    // Deliberately unsupported capability profile: the real TLB requires
    // Get through cacheBlockBytes=64. Verify rejection rather than invent
    // support for a 16B-only production manager.
    val profile = if (scenario == "small-get") captured.copy(managers = captured.managers.map { m =>
      if (m.address.exists(_.contains(BigInt("80001000", 16))))
        m.copy(supportsGet = TransferSizes(1, 16),
          supportsAcquireB = if (m.supportsAcquireB) TransferSizes(16, 16) else TransferSizes.none,
          supportsAcquireT = if (m.supportsAcquireT) TransferSizes(16, 16) else TransferSizes.none)
      else m
    }) else captured
    implicit val p = parameters
    val diagnostics = new java.io.ByteArrayOutputStream
    val diagnosticStream = new java.io.PrintStream(diagnostics)
    val passed = Console.withOut(diagnosticStream) { Console.withErr(diagnosticStream) {
      def execute(): Boolean = Driver.execute(Array("--backend-name", "verilator", "--target-dir", directory),
        () => {
          val harness = LazyModule(new PoTalIndexMetadataTransportHarness(profile, shared, deadline,
            variedWidths = scenario == "pair1" || scenario == "capacity"))
          harness.module
        }) {
        dut => new PoTalIndexMetadataTransportTester(dut, family, scenario, directory)
      }
      if (scenario == "small-get") {
        val rejection = intercept[IllegalArgumentException] { execute() }
        assert(rejection.getMessage.contains("TransferSizes[1, 16] Get") &&
          rejection.getMessage.contains("must support TransferSizes[1, 64]"))
        assert(rejection.getStackTrace.exists(_.getClassName.contains("TLBPageLookup")))
        println("EXPECTED_UNSUPPORTED_PROFILE: real TLB rejects Get16-only manager")
        true
      } else if (scenario == "wrong-source") {
        val failure = intercept[Exception] { execute() }
        assert(failure.getClass.getName == "chisel3.iotesters.TestApplicationException")
        assert(failure.getMessage.contains("Monitor.scala"))
        false
      } else execute()
    } }
    diagnosticStream.flush()
    val diagnosticText = diagnostics.toString("UTF-8")
    print(diagnosticText)
    Files.write(Paths.get(directory, "driver.log"), diagnostics.toByteArray)
    diagnosticStream.close()
    if (scenario == "wrong-source") {
      assert(!passed, "illegal wire response must fail normal TileLink monitoring")
      assert(diagnosticText.contains("Assertion failed") &&
        diagnosticText.contains("'D' channel AccessAckData carries invalid source ID") &&
        diagnosticText.contains("Monitor"),
        "expected a TileLink source assertion, not an unrelated test failure")
      val events = new String(Files.readAllBytes(Paths.get(directory, "events.log")),
        java.nio.charset.StandardCharsets.UTF_8)
      assert(events.contains("INJECT_INVALID_SOURCE expected TileLink monitor failure"))
    } else assert(passed)
  }
  it should "A preserve real FFN snapshots and stop at READY for both pairs" in {
    run("A", "pair0", shared = true)
    run("A", "pair1", shared = false)
    run("A", "capacity", shared = true)
    run("A", "small-get", shared = false)
  }
  it should "B route overlapping real operand DMA and metadata by negotiated source" in {
    run("B", "concurrent", shared = true)
    run("B", "concurrent", shared = false)
  }
  it should "C certify translation and quarantine faults without cancelling debt" in {
    run("C", "rejected", shared = true)
    run("C", "pages", shared = false)
    run("C", "sum", shared = true)
    run("C", "mxr", shared = false)
    Seq("canonical", "high-ppn", "pending-refill", "held-a", "denied", "corrupt", "missing", "partial")
      .foreach(s => run("C", s, shared = true, deadline = 256))
    Seq("sum-denied", "mxr-denied", "pmp-size", "mode")
      .foreach(s => run("C", s, shared = false, deadline = 256))
    Seq("held-load", "uint32-high", "wrong-source")
      .foreach(s => run("C", s, shared = true, deadline = 256))
    Seq("manager-tail", "allocation", "pa-high")
      .foreach(s => run("C", s, shared = false, deadline = 256))
  }
}
