package potal

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.tile.{RoCCCommand, RoCCResponse}

// Trusted single experimental issuer, not an OS authentication interface.
final class PoTalIndexControllerIntegration(dim: Int, spRows: Int)(implicit p: Parameters)
    extends Module {
  val io = IO(new Bundle {
    val cmd = Flipped(Decoupled(new RoCCCommand))
    val resp = Decoupled(new RoCCResponse)
    val cpuCmd = Flipped(Decoupled(new PoTalScaleCommand))
    val cpuResp = Decoupled(new PoTalScaleCommandResponse)
    val ingressCmd = Decoupled(new PoTalScaleCommand)
    val ingressResp = Flipped(Decoupled(new PoTalScaleCommandResponse))
    val olderDrained = Input(Bool())
    val olderResponsesEmpty = Input(Bool())
    val indexUpdateSafe = Input(Vec(2, Bool()))
    val rawHold = Output(Bool())
    val legacyHold = Output(Bool())
    val finiteBusy = Output(Bool())
    val guardSelected = Output(Bool())
    val guardDrain = Output(Bool())
  })
  val issuer = WireDefault(1.U(8.W))
  val epoch = WireDefault(0.U(64.W))
  val contextValid = WireDefault(true.B)
  val contextChanged = WireDefault(false.B)
  // Only test-side Boring transforms may override these local constants.
  val holdInstallerResponse = WireDefault(false.B)
  val dropInstallerResponse = WireDefault(false.B)
  val adapter = Module(new PoTalIndexRoCCAdapter(dim, spRows))
  val feeder = Module(new PoTalIndexRegisterFeeder(2, 64, 8, 8, 8, 4096))
  val queue = Module(new PoTalIndexWindowQueue(2, 64, dim, 64, spRows, 8, 8, 8, 16384, 16))
  adapter.io.cmd <> io.cmd
  io.resp <> adapter.io.resp
  feeder.io.req <> adapter.io.req
  adapter.io.result <> feeder.io.result
  adapter.io.contextValid := contextValid
  adapter.io.issuer := issuer
  adapter.io.epoch := epoch
  adapter.io.contextChanged := contextChanged
  feeder.io.contextValid := contextValid
  feeder.io.issuer := issuer
  feeder.io.epoch := epoch
  feeder.io.contextChanged := contextChanged || adapter.io.revoke
  adapter.io.feederFault := feeder.io.wrapperFault
  adapter.io.feederReason := feeder.io.wrapperReason
  feeder.io.q <> queue.io
  queue.io.indexUpdateSafe := io.indexUpdateSafe
  queue.io.consumer.valid := false.B
  queue.io.consumer.bits := 0.U.asTypeOf(queue.io.consumer.bits)

  val owner = RegInit(0.U(2.W))
  val offerOwner = RegInit(0.U(2.W))
  val offerBits = Reg(new PoTalScaleCommand)
  val installerDebt = RegInit(false.B)
  // APPLY_STARTED is combinational in Queue. The adapter already owns raw
  // dispatch on that edge; only registered Queue state may feed raw admission.
  val installedExclusion = queue.io.entryState.map(_ === PoTalIndexWindow.APPLYING.U).reduce(_ || _) ||
    queue.io.poisoned
  val adapterFault = RegNext(adapter.io.fault, false.B)
  val foreignEmpty = owner === 0.U && offerOwner === 0.U &&
    !adapter.io.guardDebt && !io.ingressResp.valid
  queue.io.installerAvailable := foreignEmpty
  queue.io.externalApplyPermit.foreach(_ := io.olderDrained && io.olderResponsesEmpty)
  adapter.io.guardEligible := io.olderDrained && io.olderResponsesEmpty &&
    foreignEmpty && !feeder.io.aggregateBusy && !installedExclusion
  adapter.io.guardDrained := io.olderDrained

  // Selection depends on offers, never ready. A withheld offer is irrevocable.
  val selected = Mux(offerOwner =/= 0.U, offerOwner,
    Mux(queue.io.cmd.valid, 2.U,
      Mux(adapter.io.guard.valid, 3.U,
        Mux(io.cpuCmd.valid && !io.legacyHold, 1.U, 0.U))))
  val selectedBits = Mux(selected === 2.U, queue.io.cmd.bits,
    Mux(selected === 3.U, adapter.io.guard.bits, io.cpuCmd.bits))
  io.ingressCmd.valid := selected =/= 0.U && owner === 0.U
  io.ingressCmd.bits := Mux(offerOwner =/= 0.U, offerBits, selectedBits)
  io.cpuCmd.ready := io.ingressCmd.ready && io.ingressCmd.valid && selected === 1.U
  queue.io.cmd.ready := io.ingressCmd.ready && io.ingressCmd.valid && selected === 2.U
  adapter.io.guard.ready := io.ingressCmd.ready && io.ingressCmd.valid && selected === 3.U
  when(io.ingressCmd.valid && !io.ingressCmd.ready && offerOwner === 0.U) {
    offerOwner := selected
    offerBits := selectedBits
  }
  when(io.ingressCmd.fire) {
    assert(owner === 0.U)
    owner := selected
    offerOwner := 0.U
  }
  io.cpuResp.valid := io.ingressResp.valid && owner === 1.U
  io.cpuResp.bits := io.ingressResp.bits
  queue.io.resp.valid := io.ingressResp.valid && owner === 2.U &&
    !holdInstallerResponse && !dropInstallerResponse
  queue.io.resp.bits := io.ingressResp.bits
  adapter.io.guardResp.valid := io.ingressResp.valid && owner === 3.U
  adapter.io.guardResp.bits := io.ingressResp.bits
  io.ingressResp.ready := Mux(owner === 1.U, io.cpuResp.ready,
    Mux(owner === 2.U, !holdInstallerResponse &&
      (dropInstallerResponse || queue.io.resp.ready),
      owner === 3.U && adapter.io.guardResp.ready))
  // Consumed loss is not a Queue acknowledgement: preserve owner and debt.
  when(io.cpuResp.fire || queue.io.resp.fire || adapter.io.guardResp.fire) { owner := 0.U }
  when(queue.io.cmd.valid) { installerDebt := true.B }
  when(queue.io.resp.fire) { installerDebt := false.B }
  when(io.ingressResp.valid) { assert(owner =/= 0.U) }
  assert(PopCount(Seq(io.cpuResp.valid, queue.io.resp.valid, adapter.io.guardResp.valid)) <= 1.U)
  val held = RegNext(io.ingressCmd.valid && !io.ingressCmd.ready, false.B)
  val previous = RegNext(io.ingressCmd.bits.asUInt)
  when(held) { assert(io.ingressCmd.valid && io.ingressCmd.bits.asUInt === previous) }
  io.rawHold := adapter.io.finiteBusy || installerDebt || queue.io.cmd.valid || adapter.io.guardDebt
  io.legacyHold := io.rawHold || installedExclusion ||
    feeder.io.wrapperFault || adapterFault || contextChanged || !contextValid
  io.finiteBusy := io.rawHold
  io.guardSelected := selected === 3.U
  io.guardDrain := !feeder.io.aggregateBusy
}
