
package potal

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PoTalConfigSpec extends AnyFlatSpec with Matchers {

  behavior of "PoTal A8W8-D16 configuration"

  private val cfg = PoTalConfigs.a8w8D16Sat32

  it should "use the required WS array geometry" in {
    cfg.dataflow shouldBe Dataflow.WS

    cfg.DIM shouldBe 16

    cfg.tileRows shouldBe 1
    cfg.tileColumns shouldBe 1

    cfg.meshRows shouldBe 16
    cfg.meshColumns shouldBe 16

    cfg.inputType.getWidth shouldBe 8
    cfg.spatialArrayOutputType.getWidth shouldBe 20
    cfg.accType.getWidth shouldBe 32
  }

  it should "preserve the intended SP and ACC capacities" in {
    cfg.sp_banks shouldBe 4
    cfg.sp_singleported shouldBe false
    cfg.sp_bank_entries shouldBe 4096

    cfg.acc_banks shouldBe 2
    cfg.acc_singleported shouldBe false
    cfg.acc_sub_banks shouldBe 2

    cfg.acc_bank_entries shouldBe 512

    cfg.spad_read_delay shouldBe 4
    cfg.acc_latency shouldBe 2

    cfg.dma_maxbytes shouldBe 64
    cfg.dma_buswidth shouldBe 128
  }

  it should "enable Sat32 without enabling SCU writeback" in {
    cfg.enablePoTalSat32Acc shouldBe true
    cfg.enablePoTalWriteback shouldBe false

    cfg.mvin_scale_args shouldBe None
    cfg.mvin_scale_acc_args shouldBe None
    cfg.acc_scale_args shouldBe None

    cfg.acc_read_full_width shouldBe true

    cfg.ex_read_from_spad shouldBe true
    cfg.ex_read_from_acc shouldBe false
    cfg.ex_write_to_spad shouldBe false
    cfg.ex_write_to_acc shouldBe true
  }

  it should "reject premature writeback activation" in {
    intercept[IllegalArgumentException] {
      cfg.copy(enablePoTalWriteback = true)
    }
  }

  it should "generate a separate PoTal C header" in {
    cfg.headerFileName shouldBe "gemmini_potal_params.h"

    val header = cfg.generateHeader()

    header should include ("#define DIM 16")
    header should include ("#define BANK_NUM 4")
    header should include ("#define BANK_ROWS 4096")
    header should include ("#define ACC_ROWS 1024")

    header should not include ("#define HAS_MVIN_SCALE")
  }

  it should "leave the default Gemmini configuration unchanged" in {
    val baseline = GemminiConfigs.defaultConfig

    baseline.enablePoTalSat32Acc shouldBe false
    baseline.enablePoTalWriteback shouldBe false

    baseline.dataflow shouldBe Dataflow.BOTH
    baseline.headerFileName shouldBe "gemmini_params.h"
  }

  it should "derive DIM32 geometry from the same byte capacities" in {
    val d32 = PoTalConfigs.a8w8D32Sat32
    d32.DIM shouldBe 32
    d32.dataflow shouldBe Dataflow.WS
    d32.inputType.getWidth shouldBe 8
    d32.spatialArrayOutputType.getWidth shouldBe 21
    d32.accType.getWidth shouldBe 32
    d32.sp_banks shouldBe 4
    d32.sp_bank_entries shouldBe 2048
    d32.acc_banks shouldBe 2
    d32.acc_bank_entries shouldBe 256
    d32.acc_sub_banks shouldBe 2
    d32.dma_maxbytes shouldBe 64
    d32.dma_buswidth shouldBe 128
    d32.enablePoTalSat32Acc shouldBe true
    d32.potalScaleEntries shouldBe 256
    d32.potalIndexEntries shouldBe 64
    PoTalProtocol.passesPerBlock(d32.DIM, 32) shouldBe 1
    val header = d32.generateHeader()
    header should include ("#define DIM 32")
    header should include ("#define BANK_ROWS 2048")
    header should include ("#define ACC_ROWS 512")
    header should include ("#define POTAL_INDEX_ENTRIES 64")
    d32.headerFileName shouldBe "gemmini_potal_d32_params.h"
  }
}

// Isolated parameter generation uses the same RTL configuration and generator
// as Controller elaboration, without touching an existing source header.
object PoTalDim32Header extends App {
  require(args.length == 1, "Provide one output path for the DIM32 parameter header")
  val path = java.nio.file.Paths.get(args(0))
  java.nio.file.Files.write(path,
    PoTalConfigs.a8w8D32Sat32.generateHeader().getBytes(java.nio.charset.StandardCharsets.UTF_8))
  println(s"POTAL_DIM32_HEADER=$path")
}
