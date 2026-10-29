package potal

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tile._
import freechips.rocketchip.tilelink.{TLIdentityNode}
import GemminiISA._
import Util._

// Counter Address list
object CounterEvent {
  val DISABLE = 0

  val MAIN_LD_CYCLES = 1
  val MAIN_ST_CYCLES = 2
  val MAIN_EX_CYCLES = 3
  val MAIN_LD_ST_CYCLES = 4
  val MAIN_LD_EX_CYCLES = 5
  val MAIN_ST_EX_CYCLES = 6
  val MAIN_LD_ST_EX_CYCLES = 7

  val LOAD_DMA_WAIT_CYCLE = 8
  val LOAD_ACTIVE_CYCLE = 9
  val LOAD_SCRATCHPAD_WAIT_CYCLE = 10

  val STORE_DMA_WAIT_CYCLE = 11
  val STORE_ACTIVE_CYCLE = 12
  val STORE_POOLING_CYCLE = 13
  val STORE_SCRATCHPAD_WAIT_CYCLE = 14

  val DMA_TLB_MISS_CYCLE = 15
  val DMA_TLB_HIT_REQ = 16
  val DMA_TLB_TOTAL_REQ = 17

  val RDMA_ACTIVE_CYCLE = 18
  val RDMA_TLB_WAIT_CYCLES = 19
  val RDMA_TL_WAIT_CYCLES = 20

  val WDMA_ACTIVE_CYCLE = 21
  val WDMA_TLB_WAIT_CYCLES = 22
  val WDMA_TL_WAIT_CYCLES = 23

  val EXE_ACTIVE_CYCLE = 24
  val EXE_FLUSH_CYCLE = 25
  val EXE_CONTROL_Q_BLOCK_CYCLE = 26
  val EXE_PRELOAD_HAZ_CYCLE = 27
  val EXE_OVERLAP_HAZ_CYCLE = 28

  val SCRATCHPAD_A_WAIT_CYCLE = 29
  val SCRATCHPAD_B_WAIT_CYCLE = 30
  val SCRATCHPAD_D_WAIT_CYCLE = 31

  val ACC_A_WAIT_CYCLE = 32
  val ACC_B_WAIT_CYCLE = 33
  val ACC_D_WAIT_CYCLE = 34

  val A_GARBAGE_CYCLES = 35
  val B_GARBAGE_CYCLES = 36
  val D_GARBAGE_CYCLES = 37

  val IM2COL_MEM_CYCLES = 38
  val IM2COL_ACTIVE_CYCLES = 39
  val IM2COL_TRANSPOSER_WAIT_CYCLE = 40

  val RESERVATION_STATION_FULL_CYCLES = 41
  val RESERVATION_STATION_ACTIVE_CYCLES = 42

  val LOOP_MATMUL_ACTIVE_CYCLES = 43
  val TRANSPOSE_PRELOAD_UNROLLER_ACTIVE_CYCLES = 44

  // Target-clock cycle events, not bytes, PE totals, or completion certificates.
  val POTAL_B0_DMA_WRITE = 45
  val POTAL_B1_DMA_WRITE = 46
  val POTAL_PAIR0_MAC = 47
  val POTAL_PAIR1_MAC = 48
  val POTAL_PAIR0_MAC_B1_WRITE = 49
  val POTAL_PAIR1_MAC_B0_WRITE = 50
  val POTAL_CROSS_PAIR_OVERLAP = 51

  val n = 52
}

object PoTalPrefetchObservation {
  // The DMA-origin selector excludes Execute and zero-writer arbitration.
  // The mask must be the selected physical SRAM mask, not a DMA request mask.
  def bWrite(dmaSelected: Bool, mask: UInt): Bool = dmaSelected && mask.orR

  def connect(events: CounterEventIO, b0: Bool, b1: Bool, mac: ValidIO[Bool]): Unit = {
    val mac0 = mac.valid && !mac.bits
    val mac1 = mac.valid && mac.bits
    val overlap01 = mac0 && b1
    val overlap10 = mac1 && b0
    events.connectEventSignal(CounterEvent.POTAL_B0_DMA_WRITE, b0)
    events.connectEventSignal(CounterEvent.POTAL_B1_DMA_WRITE, b1)
    events.connectEventSignal(CounterEvent.POTAL_PAIR0_MAC, mac0)
    events.connectEventSignal(CounterEvent.POTAL_PAIR1_MAC, mac1)
    events.connectEventSignal(CounterEvent.POTAL_PAIR0_MAC_B1_WRITE, overlap01)
    events.connectEventSignal(CounterEvent.POTAL_PAIR1_MAC_B0_WRITE, overlap10)
    events.connectEventSignal(CounterEvent.POTAL_CROSS_PAIR_OVERLAP, overlap01 || overlap10)
  }
}

object CounterExternal {
  val DISABLE = 0

  val RESERVATION_STATION_LD_COUNT = 1
  val RESERVATION_STATION_ST_COUNT = 2
  val RESERVATION_STATION_EX_COUNT = 3

  val RDMA_BYTES_REC = 4
  val WDMA_BYTES_SENT = 5

  val RDMA_TOTAL_LATENCY = 6
  val WDMA_TOTAL_LATENCY = 7

  val n = 8

  val EXTERNAL_WIDTH = 32
}

class CounterEventIO extends Bundle {
  val event_signal = Output(Vec(CounterEvent.n, Bool()))
  val external_values = Output(Vec(CounterExternal.n, UInt(CounterExternal.EXTERNAL_WIDTH.W)))
  val external_reset = Input(Bool())

  // Connect Event Signal
  private var connected = Array.fill(CounterEvent.n)(false)
  def connectEventSignal(addr: Int, sig: UInt) = {
    event_signal(addr) := sig
    connected(addr) = true
  }

  // Connect Event Signal
  private var connected_external = Array.fill(CounterEvent.n)(false)
  def connectExternalCounter(addr: Int, ext_value: UInt) = {
    external_values(addr) := ext_value
    connected_external(addr) = true
  }

  // Collect IO from submodule
  def collect(io: CounterEventIO) = {
    io.external_reset := external_reset
    for (i <- 0 until CounterEvent.n)
      if (io.connected(i)) {
        if (connected(i))
          throw new IllegalStateException("Port " + i + " is already connected in another IO")
        else {
          connected(i) = true
          event_signal(i) := io.event_signal(i)
        }
      }
    for (i <- 0 until CounterExternal.n)
      if (io.connected_external(i)) {
        if (connected_external(i))
          throw new IllegalStateException("External counter " + i + " is already connected in another IO")
        else {
          connected_external(i) = true
          external_values(i) := io.external_values(i)
        }
      }
  }
}

object CounterEventIO {
  def init(io: CounterEventIO) = {
    io.event_signal := 0.U.asTypeOf(io.event_signal.cloneType)
    io.external_values := 0.U.asTypeOf(io.external_values.cloneType)
  }
}

class CounterIO(nPerfCounter: Int, counterWidth: Int) extends Bundle {
  val counter_reset = Input(Bool())
  val snapshot = Input(Bool())
  val snapshot_reset = Input(Bool())
  val addr = Input(UInt(log2Ceil(nPerfCounter).W))
  val data = Output(UInt(counterWidth.W))
  val config_address = Flipped(Valid(UInt(log2Ceil(CounterEvent.n).W)))
  val external = Input(Bool())

  val event_io = Flipped(new CounterEventIO)
}

// A simple counter file. Every counter is incremented when the corresponding event signal is high on rising edge.
// There are two type of counters: Built-in counters and external counters. External counters have their value
// stored in other modules and can incremented by arbitary values.
class CounterFile(nPerfCounter: Int, counterWidth: Int) extends Module
{
  val io = IO(new CounterIO(nPerfCounter, counterWidth))

  val config_width = log2Ceil(scala.math.max(CounterEvent.n, CounterExternal.n)) + 1
  val counter_config = RegInit(VecInit.tabulate(nPerfCounter)(_ => 0.U(config_width.W)))
  val counter_is_external = Reg(Vec(nPerfCounter, Bool()))

  io.event_io.external_reset := io.counter_reset
  withReset(reset.asBool || io.counter_reset) {
    val counter_snapshot = RegInit(VecInit.tabulate(nPerfCounter)(_ => 0.U(counterWidth.W)))
    val counters = RegInit(VecInit.tabulate(nPerfCounter)(_ => 0.U(counterWidth.W)))
    val snapshot_enable = RegInit(false.B)

    // Function to take correct counter value.
    // If the highest bit of the config register is 1, it's an external counter; otherwise, take it from
    // local counter
    val take_value = (config: UInt, counter: UInt) => {
      // Set the width
      val external = io.event_io.external_values(config)
      val is_external = counter_is_external(io.addr)

      Mux(is_external, external, counter)
    }
    // Snapshot: In case a sequence of access instructions get interrupted (i.e. preempted by OS), it is possible
    // to take a snapshot when reading counter value by setting a bit in the instruction. All subsequent readings
    // return the values from the snapshot until it is cleared by a instruction with "clear" bit marked.
    // When the snapshot bit is set, the normal counters are still being incremented.
    when (io.snapshot_reset) {
      snapshot_enable := false.B
    } .elsewhen (io.snapshot) {
      snapshot_enable := true.B
      // Move counter values to snapshot register
      (counter_snapshot zip (counters zip counter_config)) map { case (snapshot, (counter, config)) => {
        snapshot := take_value(config, counter)
      }}
    }

    // Connect read port
    io.data := Mux(snapshot_enable, counter_snapshot(io.addr), take_value(counter_config(io.addr), counters(io.addr)))

    // Write configuration reg
    when (io.config_address.valid) {
      counter_config(io.addr) := io.config_address.bits
      counter_is_external(io.addr) := io.external
      counters(io.addr) := 0.U
    }

    // Update signal
    ((counters zip counter_config).zipWithIndex) map { case ((counter, config), idx) => {
      when (io.event_io.event_signal(config)) {
        when (!(io.config_address.valid && io.addr === idx.U)) {
          counter := counter + 1.U
        }
      }}
    }
  }
}

class PoTalLdbOnlyEvents extends Bundle {
  val admission = Valid(UInt(1.W))
  val bWrite = Vec(2, Bool())
  val dmaRequest = Vec(2, Bool())
  val mac = Vec(2, Bool())
  val drained = Bool()
  val fenceRequest = Bool()
  val fenceAccepted = Bool()
}

object PoTalTraceReset {
  def canonical(cmd: RoCCCommand): Bool =
    cmd.inst.funct === COUNTER_OP && cmd.inst.xd && cmd.rs2 === 0.U &&
      cmd.rs1 === PoTalLdbOnlyCapability.TraceReset.U(64.W)

  // Only the diagnostic reset's own unrolled head is exempt. All other
  // queue, scheduler, controller, Scratchpad and writeback drains stay required.
  def drained(head: DecoupledIO[GemminiCmd], otherDrained: Bool, supported: Boolean): Bool =
    otherDrained && (!head.valid || (supported.B && canonical(head.bits.cmd)))
}

class CounterController(nPerfCounter: Int, counterWidth: Int,
                        residentViewSupported: Boolean = false,
                        prefetchObservationSupported: Boolean = false,
                        ldbOnlySupported: Boolean = false)(implicit p: Parameters) extends Module {
  val io = IO(new Bundle() {
    val in = Flipped(Decoupled(new RoCCCommand))
    val out = Decoupled(new RoCCResponse)
    val event_io = Flipped(new CounterEventIO)
    val earlyEvents = if (ldbOnlySupported) Some(Input(new PoTalLdbOnlyEvents)) else None
  })

  if (nPerfCounter > 0) {
    val nCounterIndexBit = log2Ceil(nPerfCounter)

    val counterfile = Module(new CounterFile(nPerfCounter: Int, counterWidth: Int))
    counterfile.io.event_io <> io.event_io

    val out_reg = Reg(io.out.bits.cloneType)
    val out_valid_reg = RegInit(false.B)
    val residentCapabilityRead = (residentViewSupported && counterWidth == 64).B &&
      io.in.bits.inst.funct === COUNTER_OP && io.in.bits.inst.xd &&
      io.in.bits.rs1 === PoTalResidentCapability.Query.U(64.W) &&
      io.in.bits.rs2 === 0.U
    val observationCapabilityRead = (prefetchObservationSupported && counterWidth == 64).B &&
      io.in.bits.inst.funct === COUNTER_OP && io.in.bits.inst.xd &&
      io.in.bits.rs1 === PoTalPrefetchCapability.Query.U(64.W) &&
      io.in.bits.rs2 === 0.U
    val ldbOnlyCapabilityRead = (ldbOnlySupported && counterWidth == 64).B &&
      io.in.bits.inst.funct === COUNTER_OP && io.in.bits.inst.xd &&
      io.in.bits.rs1 === PoTalLdbOnlyCapability.Query.U(64.W) &&
      io.in.bits.rs2 === 0.U
    val traceReset = ldbOnlySupported.B && PoTalTraceReset.canonical(io.in.bits)
    val traceRead = ldbOnlySupported.B && io.in.bits.inst.funct === COUNTER_OP &&
      io.in.bits.inst.xd && io.in.bits.rs2 === 0.U &&
      io.in.bits.rs1(63, 5) === (PoTalLdbOnlyCapability.TraceRead >> 5).U
    val traceData = WireDefault(0.U(64.W))
    if (ldbOnlySupported) {
      val events = io.earlyEvents.get
      val clockCycle = RegInit(0.U(64.W))
      when(!clockCycle.andR) { clockCycle := clockCycle + 1.U }
      val armed = RegInit(false.B)
      val overflow = RegInit(false.B)
      val start = RegInit(0.U(64.W))
      val end = RegInit(0.U(64.W))
      val pair = RegInit(0.U(1.W))
      val seen = RegInit(VecInit(Seq.fill(10)(false.B)))
      val first = RegInit(VecInit(Seq.fill(10)(0.U(64.W))))
      val last = RegInit(VecInit(Seq.fill(10)(0.U(64.W))))
      val counts = RegInit(VecInit(Seq.fill(7)(0.U(64.W))))
      val cross0 = events.mac(0) && events.bWrite(1)
      val cross1 = events.mac(1) && events.bWrite(0)
      val timestamps = Seq(events.admission.valid, events.bWrite(0), events.bWrite(1),
        events.mac(0), events.mac(1), events.fenceRequest, events.fenceAccepted, !events.drained,
        events.dmaRequest(0), events.dmaRequest(1))
      val increments = Seq(events.bWrite(0), events.bWrite(1), events.mac(0), events.mac(1),
        cross0, cross1, cross0 || cross1)
      // Reset is admitted only at the same real global-drain boundary as Fence.
      // Freeze at the first accepted Fence; reads cannot create a certificate.
      when(traceReset && io.in.fire && events.drained) {
        armed := true.B
        overflow := clockCycle.andR
        start := clockCycle
        end := clockCycle
        pair := 0.U
        seen.foreach(_ := false.B)
        first.foreach(_ := 0.U)
        last.foreach(_ := 0.U)
        counts.foreach(_ := 0.U)
      }.elsewhen(armed) {
        when(clockCycle.andR) { overflow := true.B }
        for (i <- timestamps.indices) {
          when(timestamps(i)) {
            when(!seen(i)) { first(i) := clockCycle }
            seen(i) := true.B
            last(i) := clockCycle
          }
        }
        when(events.admission.valid) { pair := events.admission.bits }
        for (i <- increments.indices) {
          when(increments(i)) {
            when(counts(i).andR) { overflow := true.B }
              .otherwise { counts(i) := counts(i) + 1.U }
          }
        }
        when(events.fenceAccepted) {
          armed := false.B
          end := clockCycle
        }
      }
      val values = Wire(Vec(32, UInt(64.W)))
      values.foreach(_ := 0.U)
      values(0) := Cat(0.U(43.W), pair, 0.U(2.W), seen.asUInt, 0.U(6.W), overflow, armed)
      values(1) := Mux(armed, clockCycle, end) - start
      values(2) := first(0) // Scheduler admission, not DMA request/admission.
      values(3) := first(1); values(4) := last(1)
      values(5) := first(2); values(6) := last(2)
      values(7) := first(3); values(8) := last(3)
      values(9) := first(4); values(10) := last(4)
      for (i <- counts.indices) { values(11 + i) := counts(i) }
      values(18) := first(5); values(19) := first(6)
      values(20) := first(7); values(21) := last(7)
      values(22) := start; values(23) := end
      values(24) := first(8); values(25) := last(8)
      values(26) := first(9); values(27) := last(9)
      traceData := Mux(traceReset, events.drained.asUInt, values(io.in.bits.rs1(4, 0)))
    }
    val capabilityRead = residentCapabilityRead || observationCapabilityRead || ldbOnlyCapabilityRead ||
      traceReset || traceRead

    // Decode access option (assume 8 counters)
    // rs1[0] = Global counter value reset
    // rs1[1] = Snapshot reset
    // rs1[2] = Take snapshot
    // rs1[3] = Change config
    // rs1[6:4] = Counter index
    // rs1[17:12] = new counter address for counter with index specified in rs1[6:4]
    // We can change the number of physical counters up to 256 (which is really large)
    // rs1[31] = External counter flag

    io.in.ready := !out_valid_reg
    counterfile.io.addr := io.in.bits.rs1(nCounterIndexBit + 3, 4)
    counterfile.io.counter_reset := io.in.bits.rs1(0) & io.in.fire & !capabilityRead
    counterfile.io.snapshot_reset := io.in.bits.rs1(1) & io.in.fire & !capabilityRead
    counterfile.io.snapshot := io.in.bits.rs1(2) & io.in.fire & !capabilityRead
    counterfile.io.config_address.valid := io.in.bits.rs1(3) & io.in.fire & !capabilityRead
    counterfile.io.config_address.bits := io.in.bits.rs1(17, 12)
    counterfile.io.external := io.in.bits.rs1(31)

    when (io.out.fire) {
      out_valid_reg := false.B
    } .elsewhen (io.in.fire) {
      out_valid_reg := true.B
      out_reg.rd := io.in.bits.inst.rd
      out_reg.data := 0.U
      out_reg.data := Mux(traceReset || traceRead, traceData,
        Mux(ldbOnlyCapabilityRead, PoTalLdbOnlyCapability.Signature.U(64.W),
        Mux(observationCapabilityRead, PoTalPrefetchCapability.Signature.U(64.W),
          Mux(residentCapabilityRead, PoTalResidentCapability.Signature.U(64.W), counterfile.io.data))))
    }

    io.out.valid := out_valid_reg
    io.out.bits := out_reg
  } else {
    io <> DontCare
  }
}
