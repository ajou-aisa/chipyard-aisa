
package potal

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tile._
import freechips.rocketchip.util.ClockGate
import freechips.rocketchip.tilelink.TLIdentityNode
import GemminiISA._
import Util._

class GemminiCmd(rob_entries: Int)(implicit p: Parameters) extends Bundle {
  val cmd = new RoCCCommand
  val rob_id = UDValid(UInt(log2Up(rob_entries).W))
  val from_matmul_fsm = Bool()
  val from_conv_fsm = Bool()

  // PoTal metadata follows the command through LoopConv, LoopMatmul and RS
  val potal = new PoTalCommandMetadata
}

class Gemmini[T <: Data : Arithmetic, U <: Data, V <: Data](val config: GemminiArrayConfig[T, U, V])
                                     (implicit p: Parameters)
  extends LazyRoCC (
    opcodes = config.opcodes,
    nPTWPorts = if (config.use_shared_tlb) 1 else 2) {

  Files.write(Paths.get(config.headerFilePath), config.generateHeader().getBytes(StandardCharsets.UTF_8))
  if (System.getenv("GEMMINI_ONLY_GENERATE_GEMMINI_H") == "1") {
    System.exit(1)
  }

  val xLen = p(XLen)
  val spad = LazyModule(new Scratchpad(config))

  override lazy val module = new GemminiModule(this)
  override val tlNode = if (config.use_dedicated_tl_port) spad.id_node else TLIdentityNode()
  override val atlNode = if (config.use_dedicated_tl_port) TLIdentityNode() else spad.id_node

  val node = if (config.use_dedicated_tl_port) tlNode else atlNode
}

class GemminiModule[T <: Data: Arithmetic, U <: Data, V <: Data]
    (outer: Gemmini[T, U, V])
    extends LazyRoCCModuleImp(outer)
    with HasCoreParameters {

  import outer.config._
  import outer.spad

  val ext_mem_io = if (use_shared_ext_mem) Some(IO(new ExtSpadMemIO(sp_banks, acc_banks, acc_sub_banks))) else None
  ext_mem_io.foreach(_ <> outer.spad.module.io.ext_mem.get)

  val tagWidth = 32

  // Counters
  val residentViewSupported = PoTalResidentCapability.supported(outer.config, outer.xLen)
  val prefetchObservationSupported = PoTalPrefetchCapability.supported(
    outer.config, outer.xLen, p(PoTalPrefetchObservationEnabled))
  require(!p(PoTalPrefetchObservationEnabled) || prefetchObservationSupported,
    "PoTal prefetch observation requires the supported DIM32 physical geometry")
  val ldbOnlySupported = PoTalLdbOnlyCapability.supported(outer.config, outer.xLen,
    p(PoTalEarlyPrefetchEnabled), p(PoTalPrefetchObservationEnabled))
  require(!p(PoTalEarlyPrefetchEnabled) || ldbOnlySupported,
    "PoTal early prefetch requires the supported DIM32 observation geometry")
  val counters = Module(new CounterController(outer.config.num_counter, outer.xLen,
    residentViewSupported, prefetchObservationSupported, ldbOnlySupported, ldbOnlySupported))
  counters.io.event_io.external_values(0) := 0.U
  counters.io.event_io.event_signal(0) := false.B
  counters.io.in.valid := false.B
  counters.io.in.bits := DontCare
  counters.io.event_io.collect(spad.module.io.counter)
  val earlyTraceDrained = WireDefault(false.B)
  val earlyTraceFenceRequest = WireDefault(false.B)
  val earlyTraceFenceAccepted = WireDefault(false.B)

  // TLB
  implicit val edge = outer.spad.id_node.edges.out.head
  val tlb = Module(new FrontendTLB(2, tlb_size, dma_maxbytes, use_tlb_register_filter, use_firesim_simulation_counters, use_shared_tlb))
  (tlb.io.clients zip outer.spad.module.io.tlb).foreach(t => t._1 <> t._2)

  tlb.io.exp.foreach(_.flush_skip := false.B)
  tlb.io.exp.foreach(_.flush_retry := false.B)

  io.ptw <> tlb.io.ptw

  counters.io.event_io.collect(tlb.io.counter)

  spad.module.io.flush := tlb.io.exp.map(_.flush()).reduce(_ || _)

  val clock_en_reg = RegInit(true.B)
  val gated_clock = if (clock_gate) ClockGate(clock, clock_en_reg, "gemmini_clock_gate") else clock
  outer.spad.module.clock := gated_clock

  
  // ============================================================
  // W3-2b-2: PoTal scale-command ingress
  //
  // At this stage PoTalWriteback owns the real ScaleMemory,
  // but its raw EX/ACC data path remains disconnected.
  //
  // PoTal execution writeback is NOT enabled yet.
  // ============================================================

  val potalIngressOpt =
    if (enablePoTalSat32Acc) {
      Some(withClock(gated_clock) {
        Module(new PoTalScaleCommandIngress(
          dim = DIM,
          scaleEntries = potalScaleEntries,
          indexEntries = potalIndexEntries,
          spRows = potalSpRows
        ))
      })
    } else {
      None
    }

  val potalWritebackOpt =
    if (enablePoTalSat32Acc) {
      Some(withClock(gated_clock) {
        Module(new PoTalWriteback(outer.config, scaleEntries = potalScaleEntries))
      })
    } else {
      None
    }

  // W3-2c-2: PRELOAD and context reservation handshake
  val potalPreloadGateOpt =
    if (enablePoTalSat32Acc) {
      Some(withClock(gated_clock) {
        Module(new PoTalPreloadReservationGate(
          dim = DIM,
          robWidth = ROB_ID_WIDTH
        ))
      })
    } else {
      None
    }

  // W3-2c-4b: route conventional and PoTal ACC writes.
  val potalAccRouterOpt =
    if (enablePoTalSat32Acc) {
      Some(withClock(gated_clock) {
        Module(new PoTalAccWriteRouter(
          dim = DIM,
          accBanks = acc_banks,
          accBankEntries = acc_bank_entries
        ))
      })
    } else {
      None
    }

  // Connect scale commands to the actual ScaleMemory
  // owned by PoTalWriteback.
  potalIngressOpt.zip(potalWritebackOpt).foreach {
    case (ingress, wb) =>

      wb.io.scaleLoad <> ingress.io.scaleLoad
      wb.io.scaleRelease <> ingress.io.scaleRelease

      ingress.io.scaleConflict := wb.io.error.scaleConflict

      ingress.io.releaseMiss := wb.io.error.releaseMiss

      wb.io.workDone.ready := true.B
   }

  // W3-2c-2: Connect atomic PRELOAD reservation
  // to the existing PoTalWriteback context queue.
  potalPreloadGateOpt.zip(potalWritebackOpt).foreach {
    case (gate, wb) =>
      wb.io.contextIssue <> gate.io.contextIssue
  }

  /*
  //=========================================================================
  // Frontends: Incoming commands and ROB
  //=========================================================================

  // forward cmd to correct frontend. if the rob is busy, do not forward new
  // commands to tiler, and vice versa
  val is_cisc_mode = RegInit(false.B)

  val raw_cmd = Queue(io.cmd)
  val funct = raw_cmd.bits.inst.funct

  val is_cisc_funct = (funct === CISC_CONFIG) ||
                      (funct === ADDR_AB) ||
                      (funct === ADDR_CD) ||
                      (funct === SIZE_MN) ||
                      (funct === SIZE_K) ||
                      (funct === RPT_BIAS) ||
                      (funct === RESET) ||
                      (funct === COMPUTE_CISC)

  val raw_cisc_cmd = WireInit(raw_cmd)
  val raw_risc_cmd = WireInit(raw_cmd)
  raw_cisc_cmd.valid := false.B
  raw_risc_cmd.valid := false.B
  raw_cmd.ready := false.B

  //-------------------------------------------------------------------------
  // cisc
  val cmd_fsm = CmdFSM(outer.config)
  cmd_fsm.io.cmd <> raw_cisc_cmd
  val tiler = TilerController(outer.config)
  tiler.io.cmd_in <> cmd_fsm.io.tiler

  //-------------------------------------------------------------------------
  // risc
  val unrolled_cmd = LoopUnroller(raw_risc_cmd, outer.config.meshRows * outer.config.tileRows)
  */

  val reservation_station = withClock (gated_clock) { Module(new ReservationStation(outer.config, new GemminiCmd(reservation_station_entries))) }
  counters.io.event_io.collect(reservation_station.io.counter)

  when (io.cmd.valid && io.cmd.bits.inst.funct === CLKGATE_EN && !io.busy) {
    clock_en_reg := io.cmd.bits.rs1(0)
  }

  val raw_cmd_q = Module(new Queue(new GemminiCmd(reservation_station_entries), entries = 2))
  raw_cmd_q.io.enq.valid := io.cmd.valid
  io.cmd.ready := raw_cmd_q.io.enq.ready
  raw_cmd_q.io.enq.bits.cmd := io.cmd.bits
  raw_cmd_q.io.enq.bits.rob_id := DontCare
  raw_cmd_q.io.enq.bits.from_conv_fsm := false.B
  raw_cmd_q.io.enq.bits.from_matmul_fsm := false.B
  raw_cmd_q.io.enq.bits.potal := 0.U.asTypeOf(new PoTalCommandMetadata)

  val raw_cmd = raw_cmd_q.io.deq


  // ============================================================
  // Dispatch PoTal control commands before LoopConv/LoopMatmul
  // ============================================================

  val rawFunct = raw_cmd.bits.cmd.inst.funct
  val streamMode = Module(new PoTalStreamMode(ldbOnlySupported && outer.xLen == 64))
  val streamActive = streamMode.io.active
  val streamContinuous = streamMode.io.continuous
  streamMode.io.op := raw_cmd.bits.cmd.rs1(3, 0)
  streamMode.io.funct := rawFunct
  streamMode.io.rs1 := raw_cmd.bits.cmd.rs1
  streamMode.io.commit := false.B
  val streamStarted = RegInit(false.B)
  val streamDrained = WireDefault(false.B)
  val streamResponsePending = RegInit(false.B)
  val streamResponse = Reg(new PoTalScaleCommandResponse)
  val epochs = Module(new PoTalStreamEpochTable(reservation_station_entries + 4))
  epochs.io.continuous := streamContinuous
  epochs.io.generated.valid := false.B
  epochs.io.generated.bits := 0.U.asTypeOf(new PoTalStreamTag)
  epochs.io.close := 0.U.asTypeOf(epochs.io.close)
  epochs.io.terminal := reservation_station.io.streamTerminal
  epochs.io.workStart := 0.U.asTypeOf(epochs.io.workStart)
  epochs.io.workDone := 0.U.asTypeOf(epochs.io.workDone)
  potalWritebackOpt.foreach { wb =>
    epochs.io.workStart := wb.io.streamWorkStart
    epochs.io.workDone := wb.io.streamWorkDone
  }
  val isStreamControl = rawFunct === POTAL_STREAM
  // Stream sources are immutable until scoped retirement. Direct DMA, load
  // reconfiguration, convolution and legacy extensions are not stream ops.
  val forbiddenStreamCommand = streamActive && !(isStreamControl ||
    rawFunct === LOOP_WS ||
    streamMode.io.bOnly ||
    (rawFunct >= LOOP_WS_CONFIG_BOUNDS && rawFunct <= LOOP_WS_CONFIG_STRIDES_DC) ||
    rawFunct === POTAL_SCALE_WRITE || rawFunct === POTAL_SCALE_RELEASE ||
    rawFunct === POTAL_FENCE || rawFunct === POTAL_INDEX_CONFIG ||
    rawFunct === POTAL_INDEX_WRITE || rawFunct === COUNTER_OP ||
    (rawFunct === CONFIG_CMD && epochs.io.drained &&
      (!streamStarted || raw_cmd.bits.cmd.rs1(1, 0) === CONFIG_STORE)) ||
    (rawFunct === STORE_CMD && epochs.io.drained))
  val streamOp = raw_cmd.bits.cmd.rs1(3, 0)
  val streamPair = raw_cmd.bits.cmd.rs1(4)
  val streamEncoding = raw_cmd.bits.cmd.inst.xd &&
    Mux(streamOp === 4.U, !raw_cmd.bits.cmd.rs1(63, 5).orR,
      !raw_cmd.bits.cmd.rs1(63, 4).orR) &&
    Mux(streamOp === 5.U, !raw_cmd.bits.cmd.rs2(63, 21).orR,
      raw_cmd.bits.cmd.rs2 === 0.U)
  val streamLegal = streamEncoding && streamMode.io.legal
  val scopedRelease = isStreamControl && streamLegal && streamOp === 5.U
  epochs.io.generation := raw_cmd.bits.cmd.rs2(15, 8)
  val streamControlReady = !streamResponsePending &&
    (forbiddenStreamCommand || !streamLegal || Mux(streamOp === 4.U, !epochs.io.pairBusy(streamPair),
      streamDrained))
  val streamRun = streamMode.io.producer
  val streamRunReady = !streamRun || epochs.io.allocate.ready
  epochs.io.allocate.valid := false.B
  epochs.io.allocate.bits.pair := streamMode.io.pair
  epochs.io.allocate.bits.generation := streamMode.io.generation

  val isPoTalControl =
    rawFunct === POTAL_SCALE_WRITE ||
    rawFunct === POTAL_SCALE_RELEASE ||
    rawFunct === POTAL_FENCE ||
    rawFunct === POTAL_INDEX_WRITE ||
    rawFunct === POTAL_INDEX_CONFIG || scopedRelease

  val gemminiRawCmd = Wire(Decoupled(new GemminiCmd(reservation_station_entries)))

  gemminiRawCmd.bits := raw_cmd.bits
  when(streamRun) { gemminiRawCmd.bits.potal.stream := epochs.io.allocated }

  // Preserve the original command path for non-PoTal
  // configurations.
    if (enablePoTalSat32Acc) {
      gemminiRawCmd.valid := raw_cmd.valid && !isPoTalControl &&
        !isStreamControl && !forbiddenStreamCommand && streamRunReady
    } else {
      gemminiRawCmd.valid := raw_cmd.valid
    }

  potalIngressOpt match {
    case Some(ingress) =>

      ingress.io.cmd.valid := raw_cmd.valid && isPoTalControl

      ingress.io.cmd.bits.funct := rawFunct
      ingress.io.cmd.bits.rs1 := raw_cmd.bits.cmd.rs1
      ingress.io.cmd.bits.rs2 := raw_cmd.bits.cmd.rs2
      ingress.io.cmd.bits.rd := raw_cmd.bits.cmd.inst.rd
      ingress.io.cmd.bits.xd := raw_cmd.bits.cmd.inst.xd
      when(scopedRelease) {
        ingress.io.cmd.bits.funct := POTAL_SCALE_RELEASE
        ingress.io.cmd.bits.rs1 := raw_cmd.bits.cmd.rs2
        ingress.io.cmd.bits.rs2 := 0.U
      }

      // One FIFO head is routed to exactly one consumer.
      raw_cmd.ready := Mux(
        isPoTalControl,
        ingress.io.cmd.ready,
        Mux(isStreamControl || forbiddenStreamCommand, streamControlReady,
          gemminiRawCmd.ready && streamRunReady)
      )

    case None =>
      raw_cmd.ready := gemminiRawCmd.ready
  }
  epochs.io.allocate.valid := gemminiRawCmd.fire && streamRun
  when(epochs.io.allocate.fire) { streamStarted := true.B }
  when(enablePoTalSat32Acc.B && raw_cmd.fire &&
      (isStreamControl || forbiddenStreamCommand) && !scopedRelease) {
    when(raw_cmd.bits.cmd.inst.xd) {
      streamResponsePending := true.B
      streamResponse.rd := raw_cmd.bits.cmd.inst.rd
      streamResponse.status := Mux(forbiddenStreamCommand, PoTalScaleCommandStatus.BAD_COMMAND, Mux(!streamEncoding,
        PoTalScaleCommandStatus.INVALID_ENCODING,
        Mux(!streamLegal, PoTalScaleCommandStatus.BAD_COMMAND, PoTalScaleCommandStatus.OK)))
    }
    when(streamLegal && !forbiddenStreamCommand) {
      streamMode.io.commit := true.B
      when(streamOp === 1.U || streamOp === 2.U || streamOp === 6.U) {
        streamStarted := false.B
      }
    }
  }


  val max_lds = reservation_station_entries_ld
  val max_exs = reservation_station_entries_ex
  val max_sts = reservation_station_entries_st

  val (conv_cmd, loop_conv_unroller_busy) = withClock (gated_clock) { LoopConv(gemminiRawCmd, reservation_station.io.conv_ld_completed, reservation_station.io.conv_st_completed, reservation_station.io.conv_ex_completed,
    meshRows*tileRows, coreMaxAddrBits, reservation_station_entries, max_lds, max_exs, max_sts, sp_banks * sp_bank_entries, acc_banks * acc_bank_entries,
    inputType.getWidth, accType.getWidth, dma_maxbytes,
    new ConfigMvinRs1(mvin_scale_t_bits, block_stride_bits, pixel_repeats_bits), new MvinRs2(mvin_rows_bits, mvin_cols_bits, local_addr_t),
    new ConfigMvoutRs2(acc_scale_t_bits, 32), new MvoutRs2(mvout_rows_bits, mvout_cols_bits, local_addr_t),
    new ConfigExRs1(acc_scale_t_bits), new PreloadRs(mvin_rows_bits, mvin_cols_bits, local_addr_t),
    new PreloadRs(mvout_rows_bits, mvout_cols_bits, local_addr_t),
    new ComputeRs(mvin_rows_bits, mvin_cols_bits, local_addr_t), new ComputeRs(mvin_rows_bits, mvin_cols_bits, local_addr_t),
    has_training_convs, has_max_pool, has_first_layer_optimizations, has_dw_convs) }

  val residentIndexDescriptors = Wire(Vec(PoTalProtocol.WeightBufferCount, new PoTalIndexConfig))
  val residentIndexComplete = Wire(Vec(PoTalProtocol.WeightBufferCount, Bool()))
  residentIndexDescriptors := 0.U.asTypeOf(residentIndexDescriptors)
  residentIndexComplete := VecInit(Seq.fill(PoTalProtocol.WeightBufferCount)(false.B))
  potalIngressOpt.foreach { ingress =>
    residentIndexDescriptors := ingress.io.indexDescriptors
    residentIndexComplete := ingress.io.indexComplete
  }

  val (loop_cmd, loop_matmul_unroller_busy, indexedLoopActive, indexedLoopConfigured, loop_response, ldbOnlyAdmission, streamClose) = withClock (gated_clock) { LoopMatmul(conv_cmd, reservation_station.io.matmul_ld_completed, reservation_station.io.matmul_st_completed, reservation_station.io.matmul_ex_completed,
    meshRows*tileRows, coreMaxAddrBits, reservation_station_entries, max_lds, max_exs, max_sts, sp_banks * sp_bank_entries, acc_banks * acc_bank_entries,
    inputType.getWidth, accType.getWidth, dma_maxbytes, new MvinRs2(mvin_rows_bits, mvin_cols_bits, local_addr_t),
    new PreloadRs(mvin_rows_bits, mvin_cols_bits, local_addr_t), new PreloadRs(mvout_rows_bits, mvout_cols_bits, local_addr_t),
    new ComputeRs(mvin_rows_bits, mvin_cols_bits, local_addr_t), new ComputeRs(mvin_rows_bits, mvin_cols_bits, local_addr_t),
    new MvoutRs2(mvout_rows_bits, mvout_cols_bits, local_addr_t), potalScaleEntries,
     residentViewSupported, residentIndexDescriptors, residentIndexComplete, ldbOnlySupported) }
  epochs.io.close := streamClose
  epochs.io.generated.valid := loop_cmd.fire && loop_cmd.bits.potal.stream.valid
  epochs.io.generated.bits := loop_cmd.bits.potal.stream
  counters.io.earlyEvents.foreach { events =>
    events := 0.U.asTypeOf(events)
    events.admission := ldbOnlyAdmission
    events.drained := earlyTraceDrained
    events.fenceRequest := earlyTraceFenceRequest
    events.fenceAccepted := earlyTraceFenceAccepted
  }

  val unrolled_cmd = Queue(loop_cmd)
  unrolled_cmd.ready := false.B

  // H2-2C: Buffer-specific Indexed PRELOAD occupancy
  // of the two-entry unrolled command queue.
  val queuedIndexedPreloads =
    RegInit(VecInit(
      Seq.fill(PoTalProtocol.WeightBufferCount)(0.U(3.W))
    ))

  // Indexed LOOP_WS commands already accepted by LoopConv
  // but not yet admitted into a LoopMatmul scheduler slot.
  //
  // This includes the internal queues of both LoopConv
  // and LoopMatmul. Count both buffers independently.
  val pendingIndexedLoopRuns =
    RegInit(VecInit(
      Seq.fill(PoTalProtocol.WeightBufferCount)(0.U(4.W))
    ))

  // Preliminary protection state.
  // Do not use this to relax INDEX_CONFIG/WRITE yet:
  // ExecuteController internal consumers must also be covered.
  val indexBufferBusy =
    Wire(Vec(PoTalProtocol.WeightBufferCount, Bool()))

  // Routed after ExecuteController is instantiated.
  val indexedLookupInExecute =
    Wire(Vec(PoTalProtocol.WeightBufferCount, Bool()))

  for (buffer <- 0 until PoTalProtocol.WeightBufferCount) {

    // First acceptance into the LoopConv pipeline.
    val rawIndexedLoopDispatch =
      gemminiRawCmd.fire &&
      (gemminiRawCmd.bits.cmd.inst.funct === LOOP_WS) &&
      gemminiRawCmd.bits.cmd.rs1(63) &&
      (gemminiRawCmd.bits.cmd.rs1(17, 16) === (buffer + 1).U)

    // Final acceptance into a LoopMatmul scheduler slot.
    // Refused extended runs also consume this pending ownership.
    val loopConfigured =
      indexedLoopConfigured(buffer)

    when(rawIndexedLoopDispatch && !loopConfigured) {
      assert(
        pendingIndexedLoopRuns(buffer) < 15.U,
        "PoTal pending Indexed LOOP_WS overflow"
      )

      pendingIndexedLoopRuns(buffer) :=
        pendingIndexedLoopRuns(buffer) + 1.U

    }.elsewhen(loopConfigured && !rawIndexedLoopDispatch) {
      assert(
        pendingIndexedLoopRuns(buffer) =/= 0.U,
        "PoTal pending Indexed LOOP_WS underflow"
      )

      pendingIndexedLoopRuns(buffer) :=
        pendingIndexedLoopRuns(buffer) - 1.U
    }

    val enqIndexed =
      loop_cmd.fire &&
      (loop_cmd.bits.cmd.inst.funct === PRELOAD_CMD) &&
      loop_cmd.bits.potal.valid &&
      loop_cmd.bits.potal.indexedWeight &&
      (loop_cmd.bits.potal.weightBufferId === buffer.U)

    val deqIndexed =
      unrolled_cmd.fire &&
      (unrolled_cmd.bits.cmd.inst.funct === PRELOAD_CMD) &&
      unrolled_cmd.bits.potal.valid &&
      unrolled_cmd.bits.potal.indexedWeight &&
      (unrolled_cmd.bits.potal.weightBufferId === buffer.U)

    // Update occupancy only if enqueue/dequeue differ.
    // Simultaneous enqueue/dequeue preserves the count.
    when(enqIndexed && !deqIndexed) {
      assert(
        queuedIndexedPreloads(buffer) < 2.U,
        "PoTal Indexed PRELOAD queue count overflow"
      )

      queuedIndexedPreloads(buffer) :=
        queuedIndexedPreloads(buffer) + 1.U

    }.elsewhen(deqIndexed && !enqIndexed) {
      assert(
        queuedIndexedPreloads(buffer) =/= 0.U,
        "PoTal Indexed PRELOAD queue count underflow"
      )

      queuedIndexedPreloads(buffer) :=
        queuedIndexedPreloads(buffer) - 1.U
    }

    indexBufferBusy(buffer) :=
      indexedLoopActive(buffer) ||
      (pendingIndexedLoopRuns(buffer) =/= 0.U) ||
      rawIndexedLoopDispatch ||
      (queuedIndexedPreloads(buffer) =/= 0.U) ||
      enqIndexed ||
      reservation_station.io.indexedPreloadOutstanding(buffer) ||
      indexedLookupInExecute(buffer)
  }

  // Retain the preliminary state for RTL inspection.
  dontTouch(indexBufferBusy)
  counters.io.event_io.connectEventSignal(CounterEvent.LOOP_MATMUL_ACTIVE_CYCLES, loop_matmul_unroller_busy)

  // Wire up controllers to ROB
  reservation_station.io.alloc.valid := false.B
  reservation_station.io.alloc.bits := unrolled_cmd.bits

  /*
  //-------------------------------------------------------------------------
  // finish muxing control signals to rob (risc) or tiler (cisc)
  when (raw_cmd.valid && is_cisc_funct && !rob.io.busy) {
    is_cisc_mode       := true.B
    raw_cisc_cmd.valid := true.B
    raw_cmd.ready      := raw_cisc_cmd.ready
  }
  .elsewhen (raw_cmd.valid && !is_cisc_funct && !tiler.io.busy) {
    is_cisc_mode       := false.B
    raw_risc_cmd.valid := true.B
    raw_cmd.ready      := raw_risc_cmd.ready
  }
  */

  //=========================================================================
  // Controllers
  //=========================================================================
  val load_controller = withClock (gated_clock) { Module(new LoadController(outer.config, coreMaxAddrBits, local_addr_t)) }
  val store_controller = withClock (gated_clock) { Module(new StoreController(outer.config, coreMaxAddrBits, local_addr_t)) }
  val ex_controller = withClock (gated_clock) { Module(new ExecuteController(xLen, tagWidth, outer.config)) }

  // H2-2C: Actual Index Table address-consumption activity.
  indexedLookupInExecute :=
    ex_controller.io.indexedTableLookupActive

  // Dual Index Tables and buffer-specific update safety.
  potalIngressOpt match {
    case Some(ingress) =>

      for (buffer <- 0 until PoTalProtocol.WeightBufferCount) {
        ingress.io.indexUpdateSafe(buffer) :=
          !indexBufferBusy(buffer)
      }

      ex_controller.io.potalIndexWrite := ingress.io.indexWrite
      ex_controller.io.potalIndexConfig := ingress.io.indexConfigWrite

      // An accepted update must never target an active buffer.
      when(ingress.io.indexConfigWrite.valid) {
        assert(
          !indexBufferBusy(
            ingress.io.indexConfigWrite.bits.weightBufferId
          ),
          "PoTal INDEX_CONFIG targets active Weight buffer"
        )
      }

      when(ingress.io.indexWrite.valid) {
        assert(
          !indexBufferBusy(
            ingress.io.indexWrite.bits.weightBufferId
          ),
          "PoTal INDEX_WRITE targets active Weight buffer"
        )
      }

      reservation_station.io.potalIndexDescriptors :=
        ingress.io.indexDescriptors

      reservation_station.io.potalIndexConfiguredByBuffer :=
        ingress.io.indexConfiguredByBuffer

    case None =>
      ex_controller.io.potalIndexWrite.valid := false.B
      ex_controller.io.potalIndexWrite.bits := DontCare
      ex_controller.io.potalIndexConfig.valid := false.B
      ex_controller.io.potalIndexConfig.bits := DontCare

      reservation_station.io.potalIndexDescriptors :=
        0.U.asTypeOf(
          Vec(
            PoTalProtocol.WeightBufferCount,
            new PoTalIndexConfig
          )
        )

      reservation_station.io.potalIndexConfiguredByBuffer :=
        VecInit(
          Seq.fill(PoTalProtocol.WeightBufferCount)(false.B)
        )
  }

  counters.io.event_io.collect(load_controller.io.counter)
  counters.io.event_io.collect(store_controller.io.counter)
  counters.io.event_io.collect(ex_controller.io.counter)

  /*
  tiler.io.issue.load.ready := false.B
  tiler.io.issue.store.ready := false.B
  tiler.io.issue.exec.ready := false.B
  */

  reservation_station.io.issue.ld.ready := false.B
  reservation_station.io.issue.st.ready := false.B
  reservation_station.io.issue.ex.ready := false.B

  /*
  when (is_cisc_mode) {
    load_controller.io.cmd  <> tiler.io.issue.load
    store_controller.io.cmd <> tiler.io.issue.store
    ex_controller.io.cmd  <> tiler.io.issue.exec
  }
  .otherwise {
    load_controller.io.cmd.valid := rob.io.issue.ld.valid
    rob.io.issue.ld.ready := load_controller.io.cmd.ready
    load_controller.io.cmd.bits.cmd := rob.io.issue.ld.cmd
    load_controller.io.cmd.bits.cmd.inst.funct := rob.io.issue.ld.cmd.inst.funct
    load_controller.io.cmd.bits.rob_id.push(rob.io.issue.ld.rob_id)

    store_controller.io.cmd.valid := rob.io.issue.st.valid
    rob.io.issue.st.ready := store_controller.io.cmd.ready
    store_controller.io.cmd.bits.cmd := rob.io.issue.st.cmd
    store_controller.io.cmd.bits.cmd.inst.funct := rob.io.issue.st.cmd.inst.funct
    store_controller.io.cmd.bits.rob_id.push(rob.io.issue.st.rob_id)

    ex_controller.io.cmd.valid := rob.io.issue.ex.valid
    rob.io.issue.ex.ready := ex_controller.io.cmd.ready
    ex_controller.io.cmd.bits.cmd := rob.io.issue.ex.cmd
    ex_controller.io.cmd.bits.cmd.inst.funct := rob.io.issue.ex.cmd.inst.funct
    ex_controller.io.cmd.bits.rob_id.push(rob.io.issue.ex.rob_id)
  }
  */

  load_controller.io.cmd.valid := reservation_station.io.issue.ld.valid
  reservation_station.io.issue.ld.ready := load_controller.io.cmd.ready
  load_controller.io.cmd.bits := reservation_station.io.issue.ld.cmd
  load_controller.io.cmd.bits.rob_id.push(reservation_station.io.issue.ld.rob_id)

  store_controller.io.cmd.valid := reservation_station.io.issue.st.valid
  reservation_station.io.issue.st.ready := store_controller.io.cmd.ready
  store_controller.io.cmd.bits := reservation_station.io.issue.st.cmd
  store_controller.io.cmd.bits.rob_id.push(reservation_station.io.issue.st.rob_id)


  // ============================================================
  // W3-2c-2: ReservationStation -> ExecuteController
  //
  // PoTal PRELOAD requires atomic context reservation.
  // Normal Gemmini commands do not require a context.
  // ============================================================

  val exIssue = reservation_station.io.issue.ex

  potalPreloadGateOpt match {

    case Some(gate) =>

      gate.io.sourceValid := exIssue.valid

      gate.io.isPreload := exIssue.cmd.cmd.inst.funct === PRELOAD_CMD

      gate.io.metadata := exIssue.cmd.potal

      gate.io.robId := exIssue.rob_id

      // PRELOAD rs2 format:
      // [63:48] C rows
      // [47:32] C columns
      // [31: 0] C local address
      gate.io.validRows :=
        exIssue.cmd.cmd.rs2(
          48 + log2Up(DIM + 1) - 1, 48
        )

      gate.io.validColumns :=
        exIssue.cmd.cmd.rs2(
          32 + log2Up(DIM + 1) - 1, 32
        )

      gate.io.executeReady := ex_controller.io.cmd.ready

      ex_controller.io.cmd.valid := gate.io.executeValid

      exIssue.ready := gate.io.sourceReady

    case None =>

      // Original Gemmini command path.
      ex_controller.io.cmd.valid := exIssue.valid
      exIssue.ready := ex_controller.io.cmd.ready
  }

  // Command data and ROB ID are unchanged.
  ex_controller.io.cmd.bits := exIssue.cmd
  ex_controller.io.cmd.bits.rob_id.push(exIssue.rob_id)

  
  // Wire up scratchpad to controllers
  spad.module.io.dma.read <> load_controller.io.dma
  spad.module.io.dma.write <> store_controller.io.dma
  ex_controller.io.srams.read <> spad.module.io.srams.read
  ex_controller.io.srams.write <> spad.module.io.srams.write
  spad.module.io.acc.read_req <> ex_controller.io.acc.read_req
  ex_controller.io.acc.read_resp <> spad.module.io.acc.read_resp

  
  // ============================================================
  // W3-2c-4b: ACC write routing
  //
  // Normal EX output -> physical ACC
  // PoTal EX output  -> PoTalWriteback.rawWrite
  // Scaled output    -> physical ACC
  // ============================================================

  potalAccRouterOpt.zip(potalWritebackOpt) match {
    case Some((router, wb)) =>

      for (bank <- 0 until acc_banks) {

        // ExecuteController raw output
        router.io.exWrite(bank) <> ex_controller.io.acc.write(bank)

        // PoTal classification from Mesh tag
        router.io.isPoTal(bank) := ex_controller.io.potalRawWriteValid(bank)

        // Raw PoTal output enters SCU writeback
        wb.io.rawWrite(bank) <> router.io.rawWrite(bank)

        // Scaled SCU output returns to ACC arbiter
        router.io.scaledWrite(bank) <> wb.io.scaledWrite(bank)

        // Final physical ACC destination
        spad.module.io.acc.write(bank) <> router.io.accWrite(bank)
      }

    case None =>
      // Preserve original Gemmini configuration.
      ex_controller.io.acc.write <> spad.module.io.acc.write
  }

  potalAccRouterOpt.foreach { router =>
    for (bank <- 0 until acc_banks) {
      when(router.io.exWrite(bank).valid && router.io.isPoTal(bank)) {
        assert(router.io.rawWrite(bank).fire, "PoTal raw ACC output was not accepted")
      }
    }
  }
  // Im2Col unit
  val im2col = withClock (gated_clock) { Module(new Im2Col(outer.config)) }

  // Wire up Im2col
  counters.io.event_io.collect(im2col.io.counter)
  // im2col.io.sram_reads <> spad.module.io.srams.read
  im2col.io.req <> ex_controller.io.im2col.req
  ex_controller.io.im2col.resp <> im2col.io.resp

  // Wire arbiter for ExecuteController and Im2Col scratchpad reads
  (ex_controller.io.srams.read, im2col.io.sram_reads, spad.module.io.srams.read).zipped.foreach { case (ex_read, im2col_read, spad_read) =>
    val req_arb = Module(new Arbiter(new ScratchpadReadReq(n=sp_bank_entries), 2))

    req_arb.io.in(0) <> ex_read.req
    req_arb.io.in(1) <> im2col_read.req

    spad_read.req <> req_arb.io.out

    // TODO if necessary, change how the responses are handled when fromIm2Col is added to spad read interface

    ex_read.resp.valid := spad_read.resp.valid
    im2col_read.resp.valid := spad_read.resp.valid

    ex_read.resp.bits := spad_read.resp.bits
    im2col_read.resp.bits := spad_read.resp.bits

    spad_read.resp.ready := ex_read.resp.ready || im2col_read.resp.ready
  }

  // Wire up controllers to ROB
  reservation_station.io.alloc.valid := false.B
  // rob.io.alloc.bits := compressed_cmd.bits
  reservation_station.io.alloc.bits := unrolled_cmd.bits

  /*
  //=========================================================================
  // committed insn return path to frontends
  //=========================================================================

  //-------------------------------------------------------------------------
  // cisc
  tiler.io.completed.exec.valid := ex_controller.io.completed.valid
  tiler.io.completed.exec.bits := ex_controller.io.completed.bits

  tiler.io.completed.load <> load_controller.io.completed
  tiler.io.completed.store <> store_controller.io.completed

  // mux with cisc frontend arbiter
  tiler.io.completed.exec.valid  := ex_controller.io.completed.valid && is_cisc_mode
  tiler.io.completed.load.valid  := load_controller.io.completed.valid && is_cisc_mode
  tiler.io.completed.store.valid := store_controller.io.completed.valid && is_cisc_mode
  */

  //-------------------------------------------------------------------------


  // ============================================================
  // W3-2c-4b: ROB completion routing
  //
  // Normal EX:
  //   EX completed -> WB immediate queue -> RS
  //
  // PoTal EX:
  //   EX completed -> WB rawDone
  //   Scaled ACC commit -> WB writeDone
  //   Both done -> WB delayed queue -> RS
  // ============================================================

  val reservation_station_completed_arb =
    Module(new Arbiter(
      UInt(ROB_ID_WIDTH.W), 3
    ))

  potalWritebackOpt match {

    case Some(wb) =>

      // All EX completion events enter PoTalWriteback.
      // WB decides whether the ROB ID belongs to
      // a normal EX command or an active PoTal context.
      wb.io.rawCompletion.valid :=
        ex_controller.io.completed.valid

      wb.io.rawCompletion.bits :=
        ex_controller.io.completed.bits

      // This is the ONLY EX completion source
      // connected to the ReservationStation arbiter.
      reservation_station_completed_arb.io.in(0) <>
        wb.io.robCompletion

      // Check that a PoTal Mesh completion and the
      // normal EX completion refer to the same ROB ID.
      when(ex_controller.io.potalMeshCompleted.valid) {

        assert(
          ex_controller.io.completed.valid,
          "PoTal Mesh completion missing EX completion"
        )

        assert(
          ex_controller.io.completed.bits ===
            ex_controller.io.potalMeshCompleted.bits,
          "PoTal Mesh/EX ROB ID mismatch"
        )
      }

    case None =>

      // Original Gemmini path without PoTal.
      reservation_station_completed_arb.io.in(0).valid :=
        ex_controller.io.completed.valid

      reservation_station_completed_arb.io.in(0).bits :=
        ex_controller.io.completed.bits
  }

  // Load/Store completion paths are unchanged.
  reservation_station_completed_arb.io.in(1) <>
    load_controller.io.completed

  reservation_station_completed_arb.io.in(2) <>
    store_controller.io.completed

  // Deliver one completed ROB ID to ReservationStation.
  reservation_station.io.completed.valid :=
    reservation_station_completed_arb.io.out.valid

  reservation_station.io.completed.bits :=
    reservation_station_completed_arb.io.out.bits

  reservation_station_completed_arb.io.out.ready := true.B


  // ============================================================
  // PoTal RELEASE / FENCE drain condition
  // ============================================================
  
  // Do not include raw_cmd.valid:
  // the current RELEASE/FENCE command occupies raw_cmd.
  val olderGemminiDrainedExceptUnrolled =
    !loop_conv_unroller_busy &&
    !loop_matmul_unroller_busy &&
    !conv_cmd.valid &&
    !loop_cmd.valid &&
    reservation_station.io.drained &&
    !load_controller.io.busy &&
    !store_controller.io.busy &&
    !ex_controller.io.busy &&
    !spad.module.io.busy

  val olderGemminiDrained =
    olderGemminiDrainedExceptUnrolled && !unrolled_cmd.valid

  potalIngressOpt.zip(potalWritebackOpt).foreach {
    case (ingress, wb) =>
      val allDrained =
        olderGemminiDrained && wb.io.drained

      streamDrained := allDrained && epochs.io.drained
      ingress.io.releaseSafe := Mux(scopedRelease,
        !epochs.io.generationBusy, allDrained)
      wb.io.scaleRelease.bits.scoped := scopedRelease
      ingress.io.fenceDrained := allDrained
      // COUNTER reaches this queue, unlike the intercepted Fence/Release.
      // Exclude only canonical TraceReset's own head, never older work.
      earlyTraceDrained := PoTalTraceReset.drained(unrolled_cmd,
        olderGemminiDrainedExceptUnrolled && wb.io.drained, ldbOnlySupported)
      earlyTraceFenceRequest := ingress.io.cmd.valid && ingress.io.cmd.bits.funct === POTAL_FENCE
      earlyTraceFenceAccepted := ingress.io.cmd.fire && ingress.io.cmd.bits.funct === POTAL_FENCE &&
        ingress.io.cmd.bits.xd && ingress.io.cmd.bits.rs1 === 0.U &&
        ingress.io.cmd.bits.rs2 === 0.U && allDrained
  }

  // ============================================================
  // H2-2C: Physical evidence for cross-buffer Index updates.
  //
  // An update is counted only when the other Weight buffer
  // is busy in the SAME clock cycle as the accepted command.
  //
  // Indexed lookup and Mesh SP input overlaps are measured
  // independently; neither is assumed from a busy indication.
  // ============================================================

  if (enablePoTalSat32Acc && sp_banks >= 4) {
    potalIngressOpt.foreach { ingress =>

      for (peer <- 0 until PoTalProtocol.WeightBufferCount) {

        val target = 1 - peer

        val configAccepted =
          ingress.io.indexConfigWrite.valid &&
          (ingress.io.indexConfigWrite.bits.weightBufferId === target.U)

        val writeAccepted =
          ingress.io.indexWrite.valid &&
          (ingress.io.indexWrite.bits.weightBufferId === target.U)

        val updateAccepted = configAccepted || writeAccepted

        val peerBusy = indexBufferBusy(peer)
        val peerLookup = indexedLookupInExecute(peer)

        val peerMeshInput =
          ex_controller.io.meshSpadInputAccepted(2 * peer) ||
          ex_controller.io.meshSpadInputAccepted(2 * peer + 1)

        val crossUpdate = updateAccepted && peerBusy

        val crossUpdates = RegInit(0.U(32.W))
        val crossConfigs = RegInit(0.U(32.W))
        val crossWrites = RegInit(0.U(32.W))
        val crossLookups = RegInit(0.U(32.W))
        val crossMeshInputs = RegInit(0.U(32.W))

        when(crossUpdate) {
          crossUpdates := crossUpdates + 1.U

          when(configAccepted) {
            crossConfigs := crossConfigs + 1.U
          }

          when(writeAccepted) {
            crossWrites := crossWrites + 1.U
          }

          when(peerLookup) {
            crossLookups := crossLookups + 1.U
          }

          when(peerMeshInput) {
            crossMeshInputs := crossMeshInputs + 1.U
          }

          printf(
            p"[POTAL_INDEX_UPDATE_OVERLAP] " +
            p"peer=${peer.U} target=${target.U} " +
            p"config=${configAccepted} write=${writeAccepted} " +
            p"peer_lookup=${peerLookup} " +
            p"peer_mesh_input=${peerMeshInput}\n"
          )
        }

        // Global FENCE is a convenient reporting checkpoint.
        // It retains its original whole-accelerator drain rule.
        when(
          ingress.io.cmd.fire &&
          ingress.io.cmd.bits.funct === POTAL_FENCE
        ) {
          printf(
            p"[POTAL_INDEX_UPDATE_SUMMARY] " +
            p"peer=${peer.U} target=${target.U} " +
            p"updates=${crossUpdates} " +
            p"configs=${crossConfigs} " +
            p"writes=${crossWrites} " +
            p"lookup_overlap=${crossLookups} " +
            p"mesh_input_overlap=${crossMeshInputs}\n"
          )
        }
      }
    }
  }


  // ============================================================
  // Wire up global RoCC signals
  // ============================================================

  val potalResponsePending =
    potalIngressOpt
      .map(_.io.resp.valid)
      .getOrElse(false.B)

  io.busy :=
    raw_cmd.valid ||
    loop_conv_unroller_busy ||
    loop_matmul_unroller_busy ||
    reservation_station.io.busy ||
    spad.module.io.busy ||
    unrolled_cmd.valid ||
    loop_cmd.valid ||
    conv_cmd.valid ||
    potalResponsePending ||
    streamResponsePending || !epochs.io.drained ||
    io.resp.valid

 
  io.interrupt := tlb.io.exp.map(_.interrupt).reduce(_ || _)

  // assert(!io.interrupt, "Interrupt handlers have not been written yet")

  // ============================================================
  // RoCC response arbitration
  // ============================================================

  val arbitratedResponse = Wire(Decoupled(new RoCCResponse))
  potalIngressOpt match {
    case Some(ingress) =>

      val responseArb =
        Module(new RRArbiter(new RoCCResponse, 4))

      responseArb.io.in(0) <> counters.io.out

      responseArb.io.in(1).valid :=
        ingress.io.resp.valid

      responseArb.io.in(1).bits.rd :=
        ingress.io.resp.bits.rd

      responseArb.io.in(1).bits.data :=
        ingress.io.resp.bits.status

      ingress.io.resp.ready :=
        responseArb.io.in(1).ready

      responseArb.io.in(2).valid := loop_response.valid
      responseArb.io.in(2).bits.rd := loop_response.bits.rd
      responseArb.io.in(2).bits.data := loop_response.bits.status
      loop_response.ready := responseArb.io.in(2).ready
      responseArb.io.in(3).valid := streamResponsePending
      responseArb.io.in(3).bits.rd := streamResponse.rd
      responseArb.io.in(3).bits.data := streamResponse.status
      when(responseArb.io.in(3).fire) { streamResponsePending := false.B }

      arbitratedResponse <> responseArb.io.out

    case None =>
      val responseArb = Module(new RRArbiter(new RoCCResponse, 2))
      responseArb.io.in(0) <> counters.io.out
      responseArb.io.in(1).valid := loop_response.valid
      responseArb.io.in(1).bits.rd := loop_response.bits.rd
      responseArb.io.in(1).bits.data := loop_response.bits.status
      loop_response.ready := responseArb.io.in(1).ready
      arbitratedResponse <> responseArb.io.out
  }
  // Hold the chosen rd/data even if another source becomes valid while the
  // CPU stalls. Per-source registers alone do not lock an RR arbiter's choice.
  io.resp <> Queue(arbitratedResponse, entries = 1)

  // Cycle counters
  val incr_ld_cycles = load_controller.io.busy && !store_controller.io.busy && !ex_controller.io.busy
  val incr_st_cycles = !load_controller.io.busy && store_controller.io.busy && !ex_controller.io.busy
  val incr_ex_cycles = !load_controller.io.busy && !store_controller.io.busy && ex_controller.io.busy

  val incr_ld_st_cycles = load_controller.io.busy && store_controller.io.busy && !ex_controller.io.busy
  val incr_ld_ex_cycles = load_controller.io.busy && !store_controller.io.busy && ex_controller.io.busy
  val incr_st_ex_cycles = !load_controller.io.busy && store_controller.io.busy && ex_controller.io.busy

  val incr_ld_st_ex_cycles = load_controller.io.busy && store_controller.io.busy && ex_controller.io.busy

  counters.io.event_io.connectEventSignal(CounterEvent.MAIN_LD_CYCLES, incr_ld_cycles)
  counters.io.event_io.connectEventSignal(CounterEvent.MAIN_ST_CYCLES, incr_st_cycles)
  counters.io.event_io.connectEventSignal(CounterEvent.MAIN_EX_CYCLES, incr_ex_cycles)
  counters.io.event_io.connectEventSignal(CounterEvent.MAIN_LD_ST_CYCLES, incr_ld_st_cycles)
  counters.io.event_io.connectEventSignal(CounterEvent.MAIN_LD_EX_CYCLES, incr_ld_ex_cycles)
  counters.io.event_io.connectEventSignal(CounterEvent.MAIN_ST_EX_CYCLES, incr_st_ex_cycles)
  counters.io.event_io.connectEventSignal(CounterEvent.MAIN_LD_ST_EX_CYCLES, incr_ld_st_ex_cycles)

  if (prefetchObservationSupported) {
    // Tap existing physical SRAM port masks without changing Scratchpad's
    // arbitration or interface. BoringUtils inserts wires only, no registers.
    // dmaSpadWrite is high for the selected DMA data write, excluding both
    // zero-fill and Execute writes. Odd banks alone are B in the paired layout.
    val bWrites = Seq(1, 3).map { bank =>
      val source = spad.module.spad_mems(bank).io.write.mask
      val mask = WireInit(0.U.asTypeOf(chiselTypeOf(source)))
      BoringUtils.bore(source, Seq(mask))
      PoTalPrefetchObservation.bWrite(spad.module.io.dmaSpadWrite(bank), mask.asUInt)
    }
    PoTalPrefetchObservation.connect(counters.io.event_io,
      bWrites(0), bWrites(1), ex_controller.io.qualifiedMac.get)
    counters.io.earlyEvents.foreach { events =>
      events.bWrite := VecInit(bWrites)
      for (pair <- 0 until 2) {
        val mac = ex_controller.io.qualifiedMac.get
        events.mac(pair) := mac.valid && mac.bits === (pair == 1).B
        val request = spad.module.io.dma.read.req
        // Actual LoadController -> Scratchpad DMA-request acceptance, not
        // TileLink request issue or DRAM response completion.
        events.dmaRequest(pair) := request.fire && !request.bits.all_zeros &&
          !request.bits.laddr.is_acc_addr && request.bits.laddr.sp_bank() === (2 * pair + 1).U
      }
    }
  } else {
    for (event <- CounterEvent.POTAL_B0_DMA_WRITE until CounterEvent.n) {
      counters.io.event_io.connectEventSignal(event, false.B)
    }
  }

  // ------------------------------------------------------------
  // PoTal physical Scratchpad / Mesh data-path overlap monitor.
  //
  // Pair 0: Scratchpad banks 0 and 1
  // Pair 1: Scratchpad banks 2 and 3
  //
  // This measures accepted data transfers at the physical
  // Scratchpad interfaces. It does not measure all internal
  // systolic-array MAC-active cycles.
  // ------------------------------------------------------------

  if (enablePoTalSat32Acc && sp_banks >= 4) {

    // Actual DMA writes to physical Scratchpad banks.
    val dmaWritePair0 =
      spad.module.io.dmaSpadWrite(0) ||
      spad.module.io.dmaSpadWrite(1)

    val dmaWritePair1 =
      spad.module.io.dmaSpadWrite(2) ||
      spad.module.io.dmaSpadWrite(3)

    // Actual Scratchpad responses accepted by the Mesh input path.
    val meshInputPair0 =
      ex_controller.io.meshSpadInputAccepted(0) ||
      ex_controller.io.meshSpadInputAccepted(1)

    val meshInputPair1 =
      ex_controller.io.meshSpadInputAccepted(2) ||
      ex_controller.io.meshSpadInputAccepted(3)

    // Both directions of ping-pong execution.
    val pair0ComputePair1Prefetch =
      meshInputPair0 && dmaWritePair1

    val pair1ComputePair0Prefetch =
      meshInputPair1 && dmaWritePair0

    // Physical interface activity counters.
    val dmaWritePair0Cycles = RegInit(0.U(32.W))
    val dmaWritePair1Cycles = RegInit(0.U(32.W))

    val meshInputPair0Cycles = RegInit(0.U(32.W))
    val meshInputPair1Cycles = RegInit(0.U(32.W))

    // Same-cycle cross-pair data-path overlap counters.
    val pair0ComputePair1PrefetchCycles = RegInit(0.U(32.W))
    val pair1ComputePair0PrefetchCycles = RegInit(0.U(32.W))

    val totalCrossPairOverlapCycles = RegInit(0.U(32.W))

    when(dmaWritePair0) {
      dmaWritePair0Cycles := dmaWritePair0Cycles + 1.U
    }

    when(dmaWritePair1) {
      dmaWritePair1Cycles := dmaWritePair1Cycles + 1.U
    }

    when(meshInputPair0) {
      meshInputPair0Cycles := meshInputPair0Cycles + 1.U
    }

    when(meshInputPair1) {
      meshInputPair1Cycles := meshInputPair1Cycles + 1.U
    }

    when(pair0ComputePair1Prefetch) {
      pair0ComputePair1PrefetchCycles :=
        pair0ComputePair1PrefetchCycles + 1.U
    }

    when(pair1ComputePair0Prefetch) {
      pair1ComputePair0PrefetchCycles :=
        pair1ComputePair0PrefetchCycles + 1.U
    }

    when(
      pair0ComputePair1Prefetch ||
      pair1ComputePair0Prefetch
    ) {
      totalCrossPairOverlapCycles :=
        totalCrossPairOverlapCycles + 1.U
    }

    // Track whether the test exercised both bank pairs.
    val pair0DmaObserved = RegInit(false.B)
    val pair1DmaObserved = RegInit(false.B)

    val pair0MeshObserved = RegInit(false.B)
    val pair1MeshObserved = RegInit(false.B)

    when(dmaWritePair0) {
      pair0DmaObserved := true.B
    }

    when(dmaWritePair1) {
      pair1DmaObserved := true.B
    }

    when(meshInputPair0) {
      pair0MeshObserved := true.B
    }

    when(meshInputPair1) {
      pair1MeshObserved := true.B
    }

    // Print once after both pairs have been exercised and
    // all previously issued accelerator work has drained.
    val overlapReportIssued = RegInit(false.B)

    when(
      pair0DmaObserved &&
      pair1DmaObserved &&
      pair0MeshObserved &&
      pair1MeshObserved &&
      olderGemminiDrained &&
      !overlapReportIssued
    ) {
      printf(
        p"[POTAL_DATA_OVERLAP] " +
        p"dma_pair0_cycles=${dmaWritePair0Cycles} " +
        p"dma_pair1_cycles=${dmaWritePair1Cycles} " +
        p"mesh_pair0_cycles=${meshInputPair0Cycles} " +
        p"mesh_pair1_cycles=${meshInputPair1Cycles} " +
        p"pair0_compute_pair1_prefetch=${pair0ComputePair1PrefetchCycles} " +
        p"pair1_compute_pair0_prefetch=${pair1ComputePair0PrefetchCycles} " +
        p"total_overlap_cycles=${totalCrossPairOverlapCycles}\n"
      )

      overlapReportIssued := true.B
    }
  }


  // Issue commands to controllers
  // TODO we combinationally couple cmd.ready and cmd.valid signals here
  // when (compressed_cmd.valid) {
  when (unrolled_cmd.valid) {
    // val config_cmd_type = cmd.bits.rs1(1,0) // TODO magic numbers

    //val funct = unrolled_cmd.bits.inst.funct
    val risc_funct = unrolled_cmd.bits.cmd.inst.funct

    val is_flush = risc_funct === FLUSH_CMD
    val is_counter_op = risc_funct === COUNTER_OP
    val is_clock_gate_en = risc_funct === CLKGATE_EN

    /*
    val is_load = (funct === LOAD_CMD) || (funct === CONFIG_CMD && config_cmd_type === CONFIG_LOAD)
    val is_store = (funct === STORE_CMD) || (funct === CONFIG_CMD && config_cmd_type === CONFIG_STORE)
    val is_ex = (funct === COMPUTE_AND_FLIP_CMD || funct === COMPUTE_AND_STAY_CMD || funct === PRELOAD_CMD) ||
    (funct === CONFIG_CMD && config_cmd_type === CONFIG_EX)
    */

    when (is_flush) {
      val skip = unrolled_cmd.bits.cmd.rs1(0)
      tlb.io.exp.foreach(_.flush_skip := skip)
      tlb.io.exp.foreach(_.flush_retry := !skip)

      unrolled_cmd.ready := true.B // TODO should we wait for an acknowledgement from the TLB?
    }

    .elsewhen (is_counter_op) {
      // If this is a counter access/configuration command, execute immediately
      counters.io.in.valid := unrolled_cmd.valid
      unrolled_cmd.ready := counters.io.in.ready
      counters.io.in.bits := unrolled_cmd.bits.cmd
    }

    .elsewhen (is_clock_gate_en) {
      unrolled_cmd.ready := true.B
    }

    .otherwise {
      reservation_station.io.alloc.valid := true.B

      when(reservation_station.io.alloc.fire) {
        // compressed_cmd.ready := true.B
        unrolled_cmd.ready := true.B
      }
    }
  }

  // Debugging signals
  val pipeline_stall_counter = RegInit(0.U(32.W))
  when (io.cmd.fire()) {
    pipeline_stall_counter := 0.U
  }.elsewhen(io.busy) {
    pipeline_stall_counter := pipeline_stall_counter + 1.U
  }
  assert(pipeline_stall_counter < 10000000.U, "pipeline stall")

  /*
  //=========================================================================
  // Wire up global RoCC signals
  //=========================================================================
  io.busy := raw_cmd.valid || unrolled_cmd.valid || rob.io.busy || spad.module.io.busy || tiler.io.busy
  io.interrupt := tlb.io.exp.interrupt

  // hack
  when(is_cisc_mode || !(unrolled_cmd.valid || rob.io.busy || tiler.io.busy)){
    tlb.io.exp.flush_retry := cmd_fsm.io.flush_retry
    tlb.io.exp.flush_skip  := cmd_fsm.io.flush_skip
  }
  */

  //=========================================================================
  // Performance Counters Access
  //=========================================================================

}
