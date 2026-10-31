package potal

import chisel3._
import chisel3.util._
import midas.widgets.{Bridge, PlusArgsBridgeHostIO, PlusArgsBridgeModule,
  PlusArgsBridgeParams, PlusArgsBridgeTargetIO}

// FireSim extracts the bridge and initializes its immutable value before target
// execution. Native simulation keeps the original 32-bit plusarg semantics.
private[potal] class PoTalStreamTraceEnable
    extends RawModule with Bridge[PlusArgsBridgeHostIO, PlusArgsBridgeModule] {
  private val params = PlusArgsBridgeParams("potal_stream_trace=%d", default = 0, width = 32)
  val io = IO(new PlusArgsBridgeTargetIO(params))
  // Native DCE must retain the clock referenced by the bridge annotation.
  dontTouch(io)
  val bridgeIO = new PlusArgsBridgeHostIO(params)(io)
  val constructorArg = Some(params)
  io.out := freechips.rocketchip.util.PlusArg("potal_stream_trace", 0)
  generateAnnotations()
}

// An execution epoch is not a scheduler slot, physical pair, or work ID.
// Entries cannot be recycled until generator, RS and work references retire.
object PoTalStream {
  val Entries = 8
  val IdWidth = 3
  val SerialWidth = 32
  val Query = BigInt("4853384800000000", 16)
  val Signature = BigInt("4853384801200803", 16)
  val EarlyQuery = BigInt("4853384801000000", 16)
  val EarlySignature = BigInt("4853384801200807", 16)
  def trace(event: String, fire: Bool, tag: PoTalStreamTag): Unit = {
    // All production sites inherit the PoTal target clock and reset. This is
    // target-clock observation, never host API elapsed time or saved cycles.
    val cycle = RegInit(0.U(64.W))
    cycle := cycle + 1.U
    val enable = Module(new PoTalStreamTraceEnable)
    enable.io.clock := Module.clock
    when(enable.io.out =/= 0.U && fire && tag.valid) {
      midas.targetutils.SynthesizePrintf(
        printf(p"[POTAL_STREAM] cycle=${cycle} event=${event} epoch=${tag.id} serial=${tag.serial} pair=${tag.pair} generation=${tag.generation}\n"))
    }
  }
}

// Raw stream mode state. Commit only after Controller accepts a canonical,
// drained control command; rejected commands cannot change permissions.
class PoTalStreamMode(supported: Boolean) extends Module {
  val io = IO(new Bundle {
    val op = Input(UInt(4.W))
    val funct = Input(UInt(7.W))
    val rs1 = Input(UInt(64.W))
    val commit = Input(Bool())
    val legal = Output(Bool())
    val active = Output(Bool())
    val continuous = Output(Bool())
    val early = Output(Bool())
    val bOnly = Output(Bool())
    val producer = Output(Bool())
    val pair = Output(Bool())
    val generation = Output(UInt(PoTalProtocol.GenerationWidth.W))
  })
  val active = RegInit(false.B)
  val continuous = RegInit(false.B)
  val early = RegInit(false.B)
  val begin = io.op === 1.U || io.op === 2.U || io.op === 6.U
  io.legal := supported.B && io.op >= 1.U && io.op <= 6.U &&
    Mux(begin, !active, active)
  io.active := active
  io.continuous := continuous
  io.early := early
  io.bOnly := active && early && io.funct === GemminiISA.POTAL_LDB_ONLY
  io.producer := active && (io.funct === GemminiISA.LOOP_WS || io.bOnly)
  io.pair := Mux(io.bOnly, io.rs1(0), io.rs1(17, 16) === 2.U)
  io.generation := Mux(io.bOnly, 0.U, io.rs1(44, 37))
  when(io.commit && io.legal) {
    when(begin) {
      active := true.B
      continuous := io.op === 2.U || io.op === 6.U
      early := io.op === 6.U
    }.elsewhen(io.op === 3.U) {
      active := false.B
      early := false.B
    }
  }
}

class PoTalStreamTag extends Bundle {
  val valid = Bool()
  val id = UInt(PoTalStream.IdWidth.W)
  val serial = UInt(PoTalStream.SerialWidth.W)
  val pair = Bool()
  val generation = UInt(PoTalProtocol.GenerationWidth.W)
}

// The raw-dispatch reference is held until generator close (including rejected
// runs). Each generated command transfers one reference to the RS terminal;
// each startsWork transfers one additional reference through workDone.
class PoTalStreamEpochTable(commandBound: Int, workBound: Int = 128,
                          serialWidth: Int = PoTalStream.SerialWidth) extends Module {
  require(commandBound > 0 && workBound > 0)
  require(serialWidth >= 2 && serialWidth <= PoTalStream.SerialWidth)
  val io = IO(new Bundle {
    val allocate = Flipped(Decoupled(new Bundle {
      val pair = Bool()
      val generation = UInt(PoTalProtocol.GenerationWidth.W)
    }))
    val allocated = Output(new PoTalStreamTag)
    val continuous = Input(Bool())
    val close = Input(Vec(2, Valid(new PoTalStreamTag)))
    val generated = Flipped(Valid(new PoTalStreamTag))
    // Three complete-on-issue lanes plus the single response terminal.
    val terminal = Input(Vec(4, Valid(new PoTalStreamTag)))
    val workStart = Flipped(Valid(new PoTalStreamTag))
    val workDone = Flipped(Valid(new PoTalStreamTag))
    val pairBusy = Output(Vec(2, Bool()))
    val generation = Input(UInt(PoTalProtocol.GenerationWidth.W))
    val generationBusy = Output(Bool())
    val drained = Output(Bool())
  })
  val active = RegInit(VecInit(Seq.fill(PoTalStream.Entries)(false.B)))
  val closed = RegInit(VecInit(Seq.fill(PoTalStream.Entries)(false.B)))
  val tags = Reg(Vec(PoTalStream.Entries, new PoTalStreamTag))
  val commands = RegInit(VecInit(Seq.fill(PoTalStream.Entries)(
    0.U(log2Ceil(commandBound + 1).W))))
  val works = RegInit(VecInit(Seq.fill(PoTalStream.Entries)(
    0.U(log2Ceil(workBound + 1).W))))
  val serial = RegInit(0.U(serialWidth.W))
  val free = VecInit(active.zipWithIndex.map { case (a, i) =>
    !a && (io.continuous || (i < 2).B)
  })
  val chosen = PriorityEncoder(free.asUInt)
  io.drained := !active.asUInt.orR
  // Serial wrap is permitted only with no surviving identity.
  io.allocate.ready := free.asUInt.orR && (!serial.andR || io.drained)
  io.allocated.valid := true.B
  io.allocated.id := chosen
  io.allocated.serial := serial
  io.allocated.pair := io.allocate.bits.pair
  io.allocated.generation := io.allocate.bits.generation
  for (pair <- 0 until 2) {
    io.pairBusy(pair) := active.zip(tags).map { case (a, t) =>
      a && t.pair === pair.U
    }.reduce(_ || _)
  }
  io.generationBusy := active.zip(tags).map { case (a, t) =>
    a && t.generation === io.generation
  }.reduce(_ || _)

  val events = io.close.toSeq ++ io.terminal.toSeq ++
    Seq(io.generated, io.workStart, io.workDone)
  for (event <- events) {
    when(event.valid && event.bits.valid) {
      assert(active(event.bits.id), "PoTal stream reference to free epoch")
      assert(tags(event.bits.id).asUInt === event.bits.asUInt,
        "PoTal stream stale epoch identity")
    }
  }
  for (i <- 0 until PoTalStream.Entries) {
    def here(event: ValidIO[PoTalStreamTag]): Bool =
      event.valid && event.bits.valid && event.bits.id === i.U
    val add = here(io.generated).asUInt
    val sub = PopCount(io.terminal.map(here))
    val start = here(io.workStart).asUInt
    val done = here(io.workDone).asUInt
    val close = io.close.map(here).reduce(_ || _)
    when(active(i)) {
      assert(!closed(i) || !here(io.generated),
        "PoTal stream generated command after generator close")
      assert(commands(i) +& add >= sub, "PoTal stream command underflow")
      assert(commands(i) +& add <= commandBound.U +& sub,
        "PoTal stream command overflow")
      assert(works(i) +& start >= done, "PoTal stream work underflow")
      assert(works(i) +& start <= workBound.U +& done,
        "PoTal stream work overflow")
      commands(i) := commands(i) + add - sub
      works(i) := works(i) + start - done
      when(close) {
        assert(!closed(i), "PoTal stream duplicate generator close")
        closed(i) := true.B
      }
      when(closed(i) && commands(i) === 0.U && works(i) === 0.U) {
        assert(!here(io.generated) && !here(io.workStart),
          "PoTal stream producer after close")
        active(i) := false.B
      }
    }
    when(io.allocate.fire && chosen === i.U) {
      active(i) := true.B
      closed(i) := false.B
      commands(i) := 0.U
      works(i) := 0.U
      tags(i) := io.allocated
    }
  }
  when(io.allocate.fire) { serial := serial + 1.U }
  PoTalStream.trace("raw_admit", io.allocate.fire, io.allocated)
  for (i <- 0 until PoTalStream.Entries) {
    PoTalStream.trace("local_retire",
      active(i) && closed(i) && commands(i) === 0.U && works(i) === 0.U, tags(i))
  }
}
