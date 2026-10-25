#!/usr/bin/env bash
# Run only the H3-5A DIM16 directed regression with request/response/Mesh traces.
set -eo pipefail
potal=$(cd "$(dirname "$0")/.." && pwd)
chipyard=$(cd "$potal/../.." && pwd)
if command -v conda >/dev/null; then
  source "$(conda info --base)/etc/profile.d/conda.sh"
fi
source "$chipyard/env.sh" || true
if ! command -v riscv64-unknown-elf-gcc >/dev/null && [[ -d "$chipyard/../../.conda-env" ]]; then
  conda activate "$chipyard/../../.conda-env"
fi
set -u
if [[ ! -d "$chipyard/sims/firesim/sim" && -d "$chipyard/../../sim" ]]; then
  export FIRESIM_STANDALONE=1
fi
logs=${POTAL_LOG_DIR:-$potal/test_run_dir/h3-5a}
mkdir -p "$logs"
rocc="$potal/software/gemmini-rocc-tests"
binary="$rocc/build/bareMetalC/potal_h3_5a_indexed_preload-baremetal"
make -C "$chipyard/sims/verilator" -j4 CONFIG=PoTalSat32GemminiRocketConfig CONFIG_PACKAGE=chipyard VERILATOR_THREADS=1 > "$logs/build.log" 2>&1
make -C "$rocc/build/bareMetalC" -f "$rocc/bareMetalC/Makefile" "abs_top_srcdir=$rocc" "src_dir=$rocc/bareMetalC" XLEN=64 BAREMETAL_ONLY=1 potal_h3_5a_indexed_preload-baremetal > "$logs/compile.log" 2>&1
"$chipyard/sims/verilator/simulator-chipyard.harness-PoTalSat32GemminiRocketConfig" +permissive +verbose +potal_index_trace=1 +dramsim "+dramsim_ini_dir=$chipyard/generators/testchipip/src/main/resources/dramsim2_ini" +max-cycles=10000000 "+loadmem=$binary" +permissive-off "$binary" </dev/null > "$logs/trace.log" 2>&1
grep 'H3_5A' "$logs/trace.log"
grep -q 'H3_5A PASS errors=0' "$logs/trace.log"
python3 "$potal/scripts/check_h3_5a_trace.py" "$logs/trace.log" "$logs/matched.csv"
printf 'Trace: %s/trace.log\n' "$logs"
