# H3-5B — Legacy FIRRTL Indexed Lookup Fix

The H3-5A selector-wire proposal is applied exactly. Legacy FireSim RTL generation succeeds, and the generated lookup/address logic passes 128 selector checks plus all ten directed checks across both physical Index buffers. The unchanged archived bad RTL fails the same regression.

**Modified files and minimal RTL diff**

The only H3-5B production RTL change is in `src/main/scala/potal/ExecuteController.scala:182`:

```diff
-  val indexedWeightBufferId = indexedPreloadMeta.weightBufferId
+  val indexedWeightBufferId = Wire(UInt(PoTalProtocol.WeightBufferIdWidth.W))
+  indexedWeightBufferId := indexedPreloadMeta.weightBufferId
```

This creates a combinational FIRRTL selector reference before table indexing. It preserves the buffer-ID value and width, table contents, descriptor selection, address expression, counters, and valid/ready logic. It adds no register or cycle. No SCU, ACC arithmetic, DMA layout, or software protocol change was made.

The applied patch is byte-identical to the H3-5A proposal: [applied RTL patch](test_run_dir/h3-5b/applied-rtl.patch). Existing H3-5A trace instrumentation and unrelated local changes were preserved.

Added validation tool: `scripts/extract_h3_5b_firesim_lookup.py`. It follows dependencies from `sourceKTile`, `sourceKRow`, and `indexedSPRow` in the actual generated `ExecuteController` Verilog and copies those declarations without rewriting their expressions. Only command metadata, table contents, descriptor values, and slot are supplied by the testbench. It works on both the archived bad artifact and the newly generated fixed artifact; it does not model a replacement lookup implementation.

**Actual FireSim lowering and first divergence**

Generation used the original configuration and Golden Gate path:

```bash
make -C /home/alveo/firesim/sim verilog \
  PLATFORM=xilinx_alveo_u280 TARGET_PROJECT=firesim DESIGN=FireSim \
  TARGET_CONFIG=FireSimQuadRocketSinglePoTalD16Config \
  PLATFORM_CONFIG=BaseXilinxAlveoConfig \
  GENERATED_DIR=/home/alveo/firesim/target-design/chipyard/generators/potal/test_run_dir/h3-5b/generated
```

The installed FireSim environment, `FIRESIM_STANDALONE=1`, and `FIRESIM_ENV_SOURCED=1` were active. Golden Gate used legacy FIRRTL 1.5.6 and `--no-dedup`, matching the archived generation path. The target is still the four-Rocket, one-PoTal DIM16 configuration. No FPGA synthesis or bitstream target was invoked. New output was isolated from the archived bad RTL.

Before: J0, K tile 0, reverse row/Index15 selects source K5 and SP row 8069 instead of K15 and row 8079.

After: the same selector returns K15 and SP row 8079. The generated mux now explicitly selects `indexTables_0_15` when `!indexedWeightBufferId && indexSlot == 15`; the command-position choice no longer multiplies into the table-slot enumeration.

The following values are observed by simulating the copied generated FireSim lookup/address cone:

| Query | Source K | Buffer 0 SP row | Buffer 1 SP row |
|---|---:|---:|---:|
| J0, Index15 | 15 | 8079 | 16271 |
| J1, Index15 | 15 | 8095 | 16287 |
| J0, Index5 | 5 | 8069 | 16261 |
| J0, Index0 remapped to K11 | 11 | 8075 | 16267 |
| J1, Index0 remapped to K11 | 11 | 8091 | 16283 |

Buffer bases are 8064 and 16256, respectively. The generated-RTL regression also runs all 32 slots at both legal command positions for both buffers: 128 checks. Distinct table contents and opposing metadata in unselected commands expose wrong-buffer and wrong-command selection. All 138 generated-RTL checks pass after the patch. The archived artifact fails 132/138 checks under this deliberately distinguishing initialization, including Index15 returning K5. These are selector/address checks, not ACC-output mismatch counts.

Evidence: [before failure](test_run_dir/h3-5b/before.log), [after results](test_run_dir/h3-5b/after.log), [generated fixed RTL](test_run_dir/h3-5b/generated/FireSim-generated.sv), and [generation log](test_run_dir/h3-5b/firesim-generation.log).

**Regression results**

The retained `PoTalIndexedLookupLoweringSpec` passes both suite cases. Its unmaterialized form reproduces `expected 15, got 5`, which is the expected negative case. Its materialized form passes the existing 128 checks. The focused source selection used in H3-5A avoids compiling unrelated stale test callers; no broader test suite or full model suite was run.

The DIM16 H2-based directed regression passes K_TAG_B, SLOT0_REMAP11, and ALL_SLOTS_K0, with 0/512 mismatches in each ACC bank for every case. Its latency-aware checker passes 96 indexed rows and 1,536 Mesh lanes. Request-to-response latency remains five cycles, with zero observed indexed control-queue stalls.

The existing `potal_h2c_dual_index_isolation` regression also passes all five checkpoints: buffer 0 surviving buffer 1 configuration, buffer 1 independent use, buffer 0 reuse after buffer 1 execution, buffer 1 surviving buffer 0 reconfiguration, and buffer 0 using its new window. ACC0 remains unchanged at every checkpoint. This exercises both physical tables through the rebuilt SoC simulator, including different active window lengths and resident-weight reuse.

Evidence: [focused legacy checks](test_run_dir/h3-5b/focused-lowering.log), [H2 UART results](test_run_dir/h3-5b/h2/uart.log), [H2 matched transfers](test_run_dir/h3-5b/h2/matched.csv), [H2 runner/checker summary](test_run_dir/h3-5b/h2-run.log), and [H2 dual-buffer isolation](test_run_dir/h3-5b/h2-dual.log).

**Classification of broader unit compilation errors**

`PoTalDrainHandshakeSpec.scala:81` and `PoTalScaleCommandIngressSpec.scala:44` call `PoTalScaleCommandIngress` without required `indexEntries` and `spRows` arguments. Both test files and the complete constructor-owning source file are byte-identical to `dc83ab7f010466301d1ec48316cf5f740387c676`. These are pre-existing stale test/API calls, not failures introduced by the selector wire.

The comparison is recorded with baseline and current SHA256 values in [unit baseline source comparison](test_run_dir/h3-5b/unit-baseline-source-comparison.json). Classification used exact source comparison; a fresh full baseline test compilation was not run. The stale callers were not changed.

These errors block ordinary broader Test compilation. They do not block production FireSim RTL generation: that path completed successfully with the applied fix. No remaining RTL-generation error is known. FPGA synthesis, timing, implementation, and bitstream generation remain untested by instruction.

**Source and artifact identity**

Branch: `potal`. HEAD remains `dc83ab7f010466301d1ec48316cf5f740387c676`; the fix and prior H3-5A tracing are local, uncommitted changes.

`ExecuteController.scala` SHA256:

`4bc8c6a076f8f6f26d484f06df384f2f2f60a68d09bedfc296b6b3578aa49174`

Fixed artifact: `test_run_dir/h3-5b/generated/FireSim-generated.sv` SHA256:

`826fa47211c793ef19cfb587339ddfe8839c3acab8e312079cda8d97613bcebf`

Archived before-fix artifact SHA256:

`47a9f25fd945d3d3face43a39e86e1055adbc9512dcf0606fa47e42f9bdb3239`

Full source/configuration/artifact identities and check counts are recorded in [manifest.json](test_run_dir/h3-5b/manifest.json).

To exercise either generated artifact with the same test, use the installed environment and run from this PoTal directory:

```bash
python3 scripts/extract_h3_5b_firesim_lookup.py "$rtl" "$bench"
verilator --binary --timing -Wno-fatal --top-module H3FireSimLookup \
  --Mdir "$object_dir" "$bench"
"$object_dir/VH3FireSimLookup"
```

Use the archived RTL for the expected failure and the fixed artifact above for success. Recorded benches and binaries are retained under `test_run_dir/h3-5b/before*` and `after*`.

No FPGA rebuild, live FPGA validation, `gemmini.h`, GGML, RMD, or quantization change was made. No commit or push was performed.
