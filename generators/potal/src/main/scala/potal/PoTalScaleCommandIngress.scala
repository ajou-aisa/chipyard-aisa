
package potal

import chisel3._
import chisel3.util._

import GemminiISA._

// ============================================================
// PoTal control-command status
//
// Status is returned to the CPU through RoCCResponse.data
// when Controller integration is implemented.
// ============================================================

object PoTalScaleCommandStatus {
  val OK               = 0.U(8.W)
  val INVALID_COLUMN   = 1.U(8.W)
  val INVALID_SCALE    = 2.U(8.W)
  val SCALE_CONFLICT   = 3.U(8.W)
  val RELEASE_MISS     = 4.U(8.W)
  val INVALID_ADDRESS  = 5.U(8.W)
  val INVALID_ENCODING = 6.U(8.W)
  val BAD_COMMAND      = 7.U(8.W)
}

// Rocket-independent command bundle for unit testing.
// Controller will translate RoCCCommand into this bundle.
final class PoTalScaleCommand extends Bundle {
  val funct = UInt(7.W)
  val rs1 = UInt(64.W)
  val rs2 = UInt(64.W)
  val rd = UInt(5.W)
  val xd = Bool()
}

final class PoTalScaleCommandResponse extends Bundle {
  val rd = UInt(5.W)
  val status = UInt(8.W)
}

final class PoTalIndexWrite(entries: Int) extends Bundle {
  val slot = UInt(log2Ceil(entries).W)
  val sourceK = UInt(32.W)
  val weightBufferId = UInt(PoTalProtocol.WeightBufferIdWidth.W)
}

final class PoTalIndexConfig extends Bundle {
  // Start of the resident B region in SP rows
  val bBase = UInt(32.W)

  // Number of original B N tiles
  val jTiles = UInt(32.W)

  // Total K rows in original B
  val sourceKRows = UInt(32.W)

  // Selected residual K rows in this index window
  val activeRows = UInt(32.W)
  val weightBufferId = UInt(PoTalProtocol.WeightBufferIdWidth.W)
}

// ============================================================
// Scale command ingress
//
// This module does not own ScaleMemory.
// It talks to the existing PoTalWriteback scale ports.
//
// - WRITE: one carrier to one column/slot
// - RELEASE: generation-qualified entry release
// - FENCE: wait for an external drain condition
//
// Exactly one CPU response may be pending.
// ============================================================

final class PoTalScaleCommandIngress(
  dim: Int,
  scaleEntries: Int,
  indexEntries: Int,
  spRows: Int,
  generationWidth: Int = PoTalProtocol.GenerationWidth,
) extends Module {

  require(Set(16, 32, 64).contains(dim))
  require(scaleEntries >= 2 && isPow2(scaleEntries))
  require(scaleEntries <= (1 << 16))
  require(generationWidth == 8)

  require(isPow2(dim))
  require(indexEntries >= dim)
  require(spRows > 0)
  require(PoTalProtocol.WeightBufferCount == 2)
  require(spRows % 4 == 0)

  private val columnWidth = log2Ceil(dim)
  private val slotWidth = log2Ceil(scaleEntries)

  val io = IO(new Bundle {

    val cmd = Flipped(
      Decoupled(new PoTalScaleCommand)
    )

    val resp = Decoupled(
      new PoTalScaleCommandResponse
    )

    val scaleLoad = Decoupled(
      new PoTalScaleLoad(
        dim, scaleEntries, generationWidth
      )
    )

    val scaleRelease = Decoupled(
      new PoTalScaleRelease(
        dim, scaleEntries, generationWidth
      )
    )

    // From PoTalWriteback diagnostics.
    val scaleConflict = Input(Bool())
    val releaseMiss = Input(Bool())

    // From Controller integration.
    // Both must exclude the current control command
    // itself from the drain condition.
    val releaseSafe = Input(Bool())
    val fenceDrained = Input(Bool())

    // Index metadata may be updated independently for
    // each resident Weight buffer.
    //
    // This does not change POTAL_FENCE or SCALE_RELEASE.
    val indexUpdateSafe =
      Input(Vec(PoTalProtocol.WeightBufferCount, Bool()))

    val indexWrite = Valid(new PoTalIndexWrite(indexEntries))
    val indexConfigWrite = Valid(new PoTalIndexConfig)
    val indexDescriptor = Output(new PoTalIndexConfig)
    val indexConfigured = Output(Bool())

    // Independent descriptors for two resident Weight buffers.
    val indexDescriptors =
      Output(Vec(PoTalProtocol.WeightBufferCount, new PoTalIndexConfig))

    val indexConfiguredByBuffer =
      Output(Vec(PoTalProtocol.WeightBufferCount, Bool()))

    // A descriptor is readable only after its entire declared window is
    // written. Mirror exactly the transactions also sent to Execute.
    val indexComplete =
      Output(Vec(PoTalProtocol.WeightBufferCount, Bool()))
  })

  // ----------------------------------------------------------
  // Command decoding
  // ----------------------------------------------------------

  val req = io.cmd.bits

  val isWrite =
    req.funct === POTAL_SCALE_WRITE

  val isRelease =
    req.funct === POTAL_SCALE_RELEASE

  val isFence =
    req.funct === POTAL_FENCE

  val isIndexWrite = req.funct === POTAL_INDEX_WRITE
  val isIndexConfig = req.funct === POTAL_INDEX_CONFIG

  private val keyWidth = slotWidth + generationWidth + columnWidth
  require(keyWidth <= 64)

  val address = req.rs1(slotWidth - 1, 0)
  val generation = req.rs1(slotWidth + generationWidth - 1, slotWidth)
  val column = req.rs1(keyWidth - 1, slotWidth + generationWidth)
  val carrier = req.rs2(31, 0)

  val columnValid = column < dim.U
  val addressValid = address < scaleEntries.U
  val carrierValid =
    PoTalScaleEncoding.isValid(carrier)

  val keyReservedZero =
    if (keyWidth < 64)
      !req.rs1(63, keyWidth).orR
    else
      true.B

  val writeEncodingValid =
    keyReservedZero &&
    !req.rs2(63, 32).orR

  val releaseEncodingValid =
    keyReservedZero &&
    req.rs2 === 0.U

  val fenceEncodingValid =
    req.rs1 === 0.U &&
    req.rs2 === 0.U

  // INDEX_CONFIG encoding:
  //
  // rs1[31:0]  = B scratchpad base
  // rs1[63:32] = original J tiles
  // rs2[31:0]  = original K rows
  // rs2[63:32] = active K rows

  val cfgValue = Wire(new PoTalIndexConfig)

  cfgValue.bBase := req.rs1(31, 0)
  cfgValue.jTiles := req.rs1(63, 32)
  cfgValue.sourceKRows := req.rs2(31, 0)
  cfgValue.activeRows := req.rs2(63, 32)

  // Determine the resident Weight buffer from its SP base.
  // Buffer 0: Bank 1
  // Buffer 1: Bank 3
  val configuredWeightBufferId =
    (cfgValue.bBase >= (spRows / 2).U).asUInt

  cfgValue.weightBufferId := configuredWeightBufferId

  val sourceKTiles =
    (cfgValue.sourceKRows +& (dim - 1).U) >>
      log2Ceil(dim)

  val occupiedRows =
    sourceKTiles * cfgValue.jTiles * dim.U

  val regionEnd =
    cfgValue.bBase +& occupiedRows

  val cfgEncodingValid = req.xd 

  // A resident Weight block must fit entirely inside its
  // corresponding physical Weight bank.
  val weightRegionStart =
    Mux(configuredWeightBufferId === 0.U,
      (spRows / 4).U,
      (3 * spRows / 4).U)

  val weightRegionEnd =
    Mux(configuredWeightBufferId === 0.U,
      (spRows / 2).U,
      spRows.U)

  val cfgAddressValid =
    cfgValue.jTiles =/= 0.U &&
    cfgValue.sourceKRows =/= 0.U &&
    cfgValue.activeRows =/= 0.U &&
    cfgValue.activeRows <= indexEntries.U &&
    cfgValue.bBase >= weightRegionStart &&
    cfgValue.bBase < weightRegionEnd &&
    regionEnd <= weightRegionEnd

  // Two independent Weight descriptors and configured bits.
  val indexDescriptorRegs = RegInit(VecInit(Seq.fill(
    PoTalProtocol.WeightBufferCount)(
      0.U.asTypeOf(new PoTalIndexConfig)
    )
  ))

  val indexConfiguredRegs = RegInit(VecInit(Seq.fill(
    PoTalProtocol.WeightBufferCount)(false.B)
  ))
  val indexWritten = RegInit(VecInit(Seq.fill(PoTalProtocol.WeightBufferCount)(
    VecInit(Seq.fill(indexEntries)(false.B)))))

  for (buffer <- 0 until PoTalProtocol.WeightBufferCount) {
    io.indexComplete(buffer) := indexConfiguredRegs(buffer) &&
      (0 until indexEntries).map { slot =>
        slot.U >= indexDescriptorRegs(buffer).activeRows || indexWritten(buffer)(slot)
      }.reduce(_ && _)
  }

  // INDEX_WRITE targets the last successfully configured buffer.
  val selectedWeightBufferId =
    RegInit(0.U(PoTalProtocol.WeightBufferIdWidth.W))

  // Legacy single-descriptor outputs remain available until
  // ReservationStation and ExecuteController are converted.
  io.indexDescriptor :=
    indexDescriptorRegs(selectedWeightBufferId)

  io.indexConfigured :=
    indexConfiguredRegs(selectedWeightBufferId)

  io.indexDescriptors := indexDescriptorRegs
  io.indexConfiguredByBuffer := indexConfiguredRegs

  // INDEX_WRITE encoding:
  //
  // rs1[31:0] = logical index slot
  // rs2[31:0] = absolute original K row
  // Upper halves must be zero.

  val idxEncodingValid =
    req.xd &&
    !req.rs1(63, 32).orR &&
    !req.rs2(63, 32).orR

  val writeDescriptor =
    indexDescriptorRegs(selectedWeightBufferId)

  val idxAddressValid =
    indexConfiguredRegs(selectedWeightBufferId) &&
    req.rs1(31, 0) < writeDescriptor.activeRows &&
    req.rs1(31, 0) < indexEntries.U &&
    req.rs2(31, 0) < writeDescriptor.sourceKRows


  val writeLegal =
    req.xd &&
    writeEncodingValid &&
    columnValid &&
    addressValid &&
    carrierValid

  val releaseLegal =
    req.xd &&
    releaseEncodingValid &&
    columnValid &&
    addressValid

  // ----------------------------------------------------------
  // Registered CPU response
  // ----------------------------------------------------------

  val responsePending = RegInit(false.B)
  val responseReg =
    Reg(new PoTalScaleCommandResponse)

  io.resp.valid := responsePending
  io.resp.bits := responseReg

  // ----------------------------------------------------------
  // ScaleMemory interfaces
  // ----------------------------------------------------------

  io.scaleLoad.valid :=
    io.cmd.valid &&
    !responsePending &&
    isWrite &&
    writeLegal

  io.scaleLoad.bits.column :=
    column(columnWidth - 1, 0)

  io.scaleLoad.bits.address :=
    address(slotWidth - 1, 0)

  io.scaleLoad.bits.generation :=
    generation

  io.scaleLoad.bits.carrier :=
    carrier

  // RELEASE may be issued only after prior
  // work has drained safely.
  io.scaleRelease.valid :=
    io.cmd.valid &&
    !responsePending &&
    isRelease &&
    releaseLegal &&
    io.releaseSafe

  io.scaleRelease.bits.column :=
    column(columnWidth - 1, 0)

  io.scaleRelease.bits.address :=
    address(slotWidth - 1, 0)

  io.scaleRelease.bits.generation := generation

  io.indexConfigWrite.valid :=
    io.cmd.fire &&
    isIndexConfig &&
    cfgEncodingValid &&
    cfgAddressValid

  io.indexConfigWrite.bits := cfgValue

  io.indexWrite.valid :=
    io.cmd.fire &&
    isIndexWrite &&
    idxEncodingValid &&
    idxAddressValid

  io.indexWrite.bits.slot :=
    req.rs1(log2Ceil(indexEntries) - 1, 0)

  io.indexWrite.bits.sourceK := req.rs2(31, 0)
  io.indexWrite.bits.weightBufferId := selectedWeightBufferId

  when(io.indexConfigWrite.valid) {
    indexDescriptorRegs(configuredWeightBufferId) := cfgValue
    indexConfiguredRegs(configuredWeightBufferId) := true.B
    indexWritten(configuredWeightBufferId).foreach(_ := false.B)
    selectedWeightBufferId := configuredWeightBufferId
  }
  when(io.indexWrite.valid) {
    indexWritten(selectedWeightBufferId)(io.indexWrite.bits.slot) := true.B
  }

  // ----------------------------------------------------------
  // Command admission and status generation
  // ----------------------------------------------------------

  io.cmd.ready := false.B

  val nextStatus =
    WireDefault(PoTalScaleCommandStatus.OK)

  when(!responsePending) {

    when(isWrite) {

      when(!req.xd || !writeEncodingValid) {
        io.cmd.ready := true.B
        nextStatus :=
          PoTalScaleCommandStatus.INVALID_ENCODING

      }.elsewhen(!columnValid) {
        io.cmd.ready := true.B
        nextStatus :=
          PoTalScaleCommandStatus.INVALID_COLUMN

      }.elsewhen(!addressValid) {
        io.cmd.ready := true.B
        nextStatus :=
          PoTalScaleCommandStatus.INVALID_ADDRESS

      }.elsewhen(!carrierValid) {
        io.cmd.ready := true.B
        nextStatus :=
          PoTalScaleCommandStatus.INVALID_SCALE

      }.otherwise {
        io.cmd.ready :=
          io.scaleLoad.ready || io.scaleConflict

        when(io.scaleConflict) {
          nextStatus :=
            PoTalScaleCommandStatus.SCALE_CONFLICT
        }
      }

    }.elsewhen(isRelease) {

      when(!req.xd || !releaseEncodingValid) {
        io.cmd.ready := true.B
        nextStatus :=
          PoTalScaleCommandStatus.INVALID_ENCODING

      }.elsewhen(!columnValid) {
        io.cmd.ready := true.B
        nextStatus :=
          PoTalScaleCommandStatus.INVALID_COLUMN

      }.elsewhen(!addressValid) {
        io.cmd.ready := true.B
        nextStatus :=
          PoTalScaleCommandStatus.INVALID_ADDRESS

      }.otherwise {
        io.cmd.ready :=
          io.releaseSafe && io.scaleRelease.ready

        when(io.releaseMiss) {
          nextStatus :=
            PoTalScaleCommandStatus.RELEASE_MISS
        }
      }

    }.elsewhen(isIndexConfig) {

      when(!cfgEncodingValid) {
        io.cmd.ready := true.B
        nextStatus :=
          PoTalScaleCommandStatus.INVALID_ENCODING

      }.elsewhen(!cfgAddressValid) {
        io.cmd.ready := true.B
        nextStatus :=
          PoTalScaleCommandStatus.INVALID_ADDRESS

      }.otherwise {
        // Target buffer is derived from the new B base.
        // A busy target stalls without modifying metadata.
        io.cmd.ready :=
          io.indexUpdateSafe(configuredWeightBufferId)
      }

    }.elsewhen(isIndexWrite) {

      when(!idxEncodingValid) {
        io.cmd.ready := true.B
        nextStatus :=
          PoTalScaleCommandStatus.INVALID_ENCODING

      }.elsewhen(!idxAddressValid) {
        io.cmd.ready := true.B
        nextStatus :=
          PoTalScaleCommandStatus.INVALID_ADDRESS

      }.otherwise {
        // INDEX_WRITE belongs to the last successfully
        // configured Weight buffer.
        io.cmd.ready :=
          io.indexUpdateSafe(selectedWeightBufferId)
      }

    }.elsewhen(isFence) {

      when(!req.xd || !fenceEncodingValid) {
        io.cmd.ready := true.B
        nextStatus :=
          PoTalScaleCommandStatus.INVALID_ENCODING

      }.otherwise {
        io.cmd.ready := io.fenceDrained
      }

    }.otherwise {
      io.cmd.ready := true.B
      nextStatus :=
        PoTalScaleCommandStatus.BAD_COMMAND
    }
  }

  // ============================================================
  // H2-2C: Same-buffer Index Update protection monitor.
  //
  // Count only cycles where a legal Index command is blocked
  // by the targeted Weight buffer being busy.
  //
  // Exclude responsePending cycles so the measurement does
  // not include RoCC response backpressure.
  //
  // Report the number of blocked cycles when the command
  // is finally accepted. A zero-wait update is not printed.
  // ============================================================

  val blockedIndexConfigCycles =
    RegInit(VecInit(
      Seq.fill(PoTalProtocol.WeightBufferCount)(0.U(32.W))
    ))

  val blockedIndexWriteCycles =
    RegInit(VecInit(
      Seq.fill(PoTalProtocol.WeightBufferCount)(0.U(32.W))
    ))

  for (buffer <- 0 until PoTalProtocol.WeightBufferCount) {

    val configRequest =
      io.cmd.valid &&
      !responsePending &&
      isIndexConfig &&
      cfgEncodingValid &&
      cfgAddressValid &&
      (configuredWeightBufferId === buffer.U)

    val writeRequest =
      io.cmd.valid &&
      !responsePending &&
      isIndexWrite &&
      idxEncodingValid &&
      idxAddressValid &&
      (selectedWeightBufferId === buffer.U)

    val configBlocked =
      configRequest &&
      !io.indexUpdateSafe(buffer)

    val writeBlocked =
      writeRequest &&
      !io.indexUpdateSafe(buffer)

    when(configBlocked) {
      blockedIndexConfigCycles(buffer) :=
        blockedIndexConfigCycles(buffer) + 1.U
    }

    when(writeBlocked) {
      blockedIndexWriteCycles(buffer) :=
        blockedIndexWriteCycles(buffer) + 1.U
    }

    val configAccepted =
      io.indexConfigWrite.valid &&
      (io.indexConfigWrite.bits.weightBufferId === buffer.U)

    val writeAccepted =
      io.indexWrite.valid &&
      (io.indexWrite.bits.weightBufferId === buffer.U)

    when(configAccepted) {
      assert(
        io.indexUpdateSafe(buffer),
        "PoTal same-buffer INDEX_CONFIG accepted while busy"
      )

      when(blockedIndexConfigCycles(buffer) =/= 0.U) {
        printf(
          p"[POTAL_INDEX_GUARD_RESUME] " +
          p"buffer=${buffer.U} kind=CONFIG " +
          p"blocked_cycles=${blockedIndexConfigCycles(buffer)}\n"
        )
      }

      blockedIndexConfigCycles(buffer) := 0.U
    }

    when(writeAccepted) {
      assert(
        io.indexUpdateSafe(buffer),
        "PoTal same-buffer INDEX_WRITE accepted while busy"
      )

      when(blockedIndexWriteCycles(buffer) =/= 0.U) {
        printf(
          p"[POTAL_INDEX_GUARD_RESUME] " +
          p"buffer=${buffer.U} kind=WRITE " +
          p"blocked_cycles=${blockedIndexWriteCycles(buffer)}\n"
        )
      }

      blockedIndexWriteCycles(buffer) := 0.U
    }
  }


  // ----------------------------------------------------------
  // Response lifetime
  // ----------------------------------------------------------

  when(io.cmd.fire) {
    responsePending := true.B
    responseReg.rd := req.rd
    responseReg.status := nextStatus

  }.elsewhen(io.resp.fire) {
    responsePending := false.B
  }

  assert(
    !(io.scaleLoad.fire && io.scaleRelease.fire),
    "PoTal: simultaneous scale load and release"
  )
}

