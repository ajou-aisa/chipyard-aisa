# H3-5A — Indexed PRELOAD root cause

Branch: `potal`. Checked-out baseline: `dc83ab7f010466301d1ec48316cf5f740387c676`.

The ordinary H2 DIM16 Verilator build passes the directed test. The archived FireSim RTL does not implement the same Index lookup. Its legacy FIRRTL lowering corrupts the nested dynamic access used by `absoluteSourceK`. Simulating the unchanged archived lookup/address cone reproduces the K11/K0 signature. A separate small legacy-FIRRTL test reproduces the error without FireSim, SRAM, or Mesh.

**First divergent stage: Index Table selection.** With identity entries, the first reverse PRELOAD row has logical K=15, K tile=0, J=0, `d_fire_counter=0`, `d_logical_row=15`, and `indexSlotWide=15`. The archived mux selects `indexTables_0_5`, returning 5 instead of 15. Its calculated physical Scratchpad address is **8069 instead of 8079**: bank 1, local row **3973 instead of 3983**. This is observed by simulation of the extracted generated RTL, not inferred from an effective numerical K.

The expected weight is 16 in every lane. Address 8069 contains the K5 row, whose weight would be 6. No live FireSim SRAM response or Mesh capture was taken; those downstream values are predictions from the established resident layout, not hardware measurements.

**Directed regression and latency proof**

The new bare-metal test uses DIM16, Pair0, Main K=64, Residual K=16, J=32, two J tiles, B stride=64, B base=8064, and B[k,n]=k+1. K_TAG_B uses identity Index entries 0–15 and diagonal one-hot Main/Residual A. Every carrier is zero-encoded multiplication by one. The INT32 Golden independently selects the logical B row specified by one-hot A; it uses no Scratchpad translation or observed RTL data.

Main uses passes-per-block=2, scale slots 0–3, generation 1. Residual uses passes-per-block=1, scale slots 4–5, generation 2. These match the existing FireSim software diagnostic. SLOT0_REMAP11 programs entry 0 to 11, other active entries to 0, and selects Residual slot 0 in every A row. ALL_SLOTS_K0 programs all active entries to 0.

| Case | ACC0 | ACC1 |
|---|---:|---:|
| K_TAG_B | 0/512 mismatches | 0/512 mismatches |
| SLOT0_REMAP11 | 0/512 mismatches | 0/512 mismatches |
| ALL_SLOTS_K0 | 0/512 mismatches | 0/512 mismatches |

The build's `.sfc_level` is `none`: CIRCT/firtool lowers the original nested access directly. Opt-in traces record scheduler loop ID, full PRELOAD metadata, K/J tiles, counters, logical row, slot, source K, calculated address, accepted SRAM requests, SRAM responses, Mesh weight data, and valid/ready signals.

The checker independently applies:

`8064 + ((sourceK / 16) * 2 + jTile) * 16 + sourceK % 16`

It matches accepted requests to responses in per-bank FIFO order and then to accepted Mesh transfers. It checks 96 indexed rows and all 1,536 Mesh lanes. Every measured request-to-consumed-response latency is five cycles. All three cases pass every stage through ACC readback. There are zero observed indexed control-queue stalls; no forced-stall campaign was run.

The first residual loop has scheduler ID 0. Representative K_TAG_B transactions:

| J | Logical K/slot | Physical SP | Bank/local row | Request cycle | Response cycle | Mesh cycle | Every lane |
|---|---:|---:|---|---:|---:|---:|---:|
| 0 | 15 | 8079 | 1 / 3983 | 40701 | 40706 | 40706 | 16 |
| 0 | 0 | 8064 | 1 / 3968 | 40716 | 40721 | 40721 | 1 |
| 1 | 15 | 8095 | 1 / 3999 | 40717 | 40722 | 40722 | 16 |
| 1 | 0 | 8080 | 1 / 3984 | 40732 | 40737 | 40737 | 1 |

J1 requests start before the final J0 responses return. The checker retains request metadata across that transition. It does not associate an old SRAM response with the current combinational J value. At cycle 40706, the first SRAM response and Mesh transfer both contain `10101010101010101010101010101010`, with response valid/ready=1/1 and Mesh valid/ready=1/1.

Checker self-tests independently corrupt an Index value, request address, response word, and Mesh word. All four corruptions fail at their intended stage.

**Why the FireSim artifact differs**

The original FireSim CHIRRTL contains the intended expression:

```text
indexTables[cmd_q.io.deq.bits[preload_cmd_place].potal.weightBufferId][indexSlot]
```

The wrong mux already exists in `post-bridge-extraction.fir` and remains in the archived `FireSim-generated.sv`. For example, generated `_GEN_357` selects `indexTables_0_1` when slot equals 3, while `_GEN_387` selects `indexTables_0_11` when slot equals 1 and command place is 0. The lowering has folded the three command choices into the slot enumeration. Comparing slot against `(3 * tableEntry + commandPlace) mod 32` explains the emitted mux; it is not the required independent table-entry selection.

The extracted 434-line lookup/address cone is copied unchanged from the archived RTL. Its standalone Verilator test evaluates initial command place 0 and overlapped command place 1:

| Probe | Expected source / SP | Generated source / SP |
|---|---|---|
| Identity, J0, slot 15 | 15 / 8079 | 5 / 8069 |
| Identity, J0, slot 1 | 1 / 8065 | 11 / 8075 |
| Identity, J1, slot 1 | 1 / 8081 | 0 / 8080 |
| Slot0=11, J0, slot 0 | 11 / 8075 | 11 / 8075 |
| Slot0=11, J1, slot 0 | 11 / 8091 | 0 / 8080 |

With inactive Index words initialized to zero, identity mapping gives 30 incorrect Weight rows out of 32. Diagonal one-hot A maps these to 480/512 incorrect outputs. The remap cone also misroutes slot 1 in J1, but that control's A selects only slot 0. ALL_SLOTS_K0 returns the correct numerical source for every row despite the faulty selector. Actual inactive Index words can depend on prior runtime state; the first K15-to-K5 divergence uses programmed active entries and does not depend on that assumption.

Translation follows the selected source correctly. The first error therefore precedes K/J-to-Scratchpad translation. SRAM corruption, Mesh delivery, and ACC1 arithmetic are not needed to explain this signature. Their live FireSim behavior was not measured in this investigation.

**Minimal patch proposal — not applied**

Materialize the existing buffer selector before using it in the nested lookup:

```diff
-  val indexedWeightBufferId = indexedPreloadMeta.weightBufferId
+  val indexedWeightBufferId = Wire(UInt(PoTalProtocol.WeightBufferIdWidth.W))
+  indexedWeightBufferId := indexedPreloadMeta.weightBufferId
```

A Scala `val` aliases the nested access; it does not create a FIRRTL wire. The proposal gives the legacy lowering a separate selector reference. It adds no register, cycle, arithmetic change, or software protocol change. The existing table, descriptor, and valid-bit lookups share this selector.

`PoTalIndexedLookupLoweringSpec` isolates exactly this nested-access pattern using the installed legacy FIRRTL 1.5.6 pipeline. The original form fails `expected 15, got 5`. The proposed materialized form passes 128 checks: two buffers, two legal command places, and all 32 Index slots. The suite treats the original failure as the expected reproduction; both suite cases pass. This validates the small proposal, not a complete rebuilt FireSim design.

The proposal is saved as [materialize-selector.patch](test_run_dir/h3-5a/materialize-selector.patch). No production Index logic was changed.

**FireSim configuration and bitstream provenance**

The active hardware database names the 2026-10-23 06:35:25 `alveo_u280_firesim_potal_quadcore_d16_no_nic` build. Its target is `FireSimQuadRocketSinglePoTalD16Config`, which composes `FireSimPoTalD16RocketConfig` and `PoTalSat32GemminiRocketConfig`, with one accelerator on hart 0. The shared PoTal parameters are DIM16, four 4096-row Scratchpad banks, two ACC banks, 32 Index entries, 256 scale entries, and Scratchpad read delay 4.

The current generated FireSim RTL and the archived build's `design/FireSim-generated.sv` have identical SHA256:

`47a9f25fd945d3d3face43a39e86e1055adbc9512dcf0606fa47e42f9bdb3239`

The archive bitstream and deployed `FIRESIM_RUNS_DIR/sim_slot_0/xilinx_alveo_u280/firesim.bit` have identical SHA256:

`cef06f10d950a0967eb9fff40e1069311780e420ee77a42f72a474f45d5a9378`

The latest inspected infrasetup log, `2026-10-23--17-52-31-infrasetup-YPW0LLBBD5PV6DPJ.log`, records flashing that slot-0 file at 2026-10-24 02:55. This is filesystem/log provenance, not FPGA configuration readback. The build's `stamp` is empty, so it does not establish a clean Git-commit provenance claim.

Read-only comparison with the preserved software diagnostic confirms Main LOOP_WS flags `0x2000402000150002`, Residual flags `0xe000204000950002`, bounds `0x0000000400020001` / `0x0000000100020001`, Residual A offset `0x20`, Main B stride 64, and Residual B stride 0 with resident B reuse. No backend or diagnostic source was changed.

**Reproduction and artifacts**

Run only the directed SoC regression:

```bash
bash scripts/run_h3_5a_indexed_preload.sh
```

The runner sources Chipyard `env.sh`, falls back to the installed parent FireSim Conda environment when the stale Chipyard environment path is missing, and uses the repository's `FIRESIM_STANDALONE=1` setting. It builds only the DIM16 simulator and this C binary. `+verbose` is required because TestDriver gates Chisel printf output. CPU trace and UART output can interleave; the checker reconstructs UART messages and ignores idle metadata that does not match a generated PRELOAD.

With the same environment active, run the small legacy-lowering test from the Chipyard root:

```bash
sbt -J-Xmx6G -J-XX:ActiveProcessorCount=2 \
  'set potal / Test / sources := Seq(file("generators/potal/src/test/scala/potal/PoTalIndexedLookupLoweringSpec.scala"))' \
  'potal/testOnly potal.PoTalIndexedLookupLoweringSpec'
```

The explicit source selection is necessary because two existing test sources, `PoTalDrainHandshakeSpec.scala:81` and `PoTalScaleCommandIngressSpec.scala:44`, omit the now-required `indexEntries` and `spRows` constructor arguments. They prevent ordinary Test compilation; they were not modified.

The archived-cone extractor takes `FireSim-generated.sv` and an output SV path. The recorded commands used:

```bash
python3 scripts/extract_h3_5a_firesim_lookup.py "$archived_rtl" test_run_dir/h3-5a/FireSimLookup.sv
verilator --binary --timing -Wno-fatal --top-module H3FireSimLookup \
  --Mdir test_run_dir/h3-5a/artifact-obj test_run_dir/h3-5a/FireSimLookup.sv
test_run_dir/h3-5a/artifact-obj/VH3FireSimLookup
```

The final command intentionally fails the independent Index/address contract on the archived bad artifact. The extractor targets the recorded DIM16 generated wire names.

Evidence: [UART results](test_run_dir/h3-5a/uart.log), [full cycle trace](test_run_dir/h3-5a/trace.log), [matched requests/responses/Mesh rows](test_run_dir/h3-5a/matched.csv), [trace checks](test_run_dir/h3-5a/trace-check.log), [checker corruption tests](test_run_dir/h3-5a/checker-self-test.log), [archived-cone failure](test_run_dir/h3-5a/artifact.log), and [legacy-lowering test](test_run_dir/h3-5a/lowering.log).

No arithmetic fix, Index fix, `gemmini.h` edit, FPGA rebuild, commit, or push was performed. Live FireSim handshakes and forced indexed stalls remain untested.
