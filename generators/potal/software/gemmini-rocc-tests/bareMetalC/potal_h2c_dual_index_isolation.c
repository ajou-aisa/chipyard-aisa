/*
 * PoTal H2-2C: Dual Indexed-Weight Table/Descriptor isolation regression.
 *
 * Compile with the existing potal_shared_weight_pair_switch.c in the same
 * bareMetalC directory.  The included test supplies the RoCC helpers,
 * matrices, index generation, and precomputed DIM-specific golden results.
 *
 * This test deliberately interleaves two different INDEX_CONFIG windows:
 *   Buffer 0 (Bank 1): residual window 0, R=WINDOW_CAP
 *   Buffer 1 (Bank 3): residual window 1, R=TOTAL_ACTIVE_R-WINDOW_CAP
 * It then switches PRELOAD selection without reconfiguring either buffer.
 * Reconfiguring buffer 0 later must NOT invalidate buffer 1.
 *
 * All INDEX_CONFIG/WRITE operations retain the current global-drain rule.
 * This validates table/descriptor isolation, not concurrent index updates.
 */
#define main potal_pair_switch_reference_main_unused
#include "potal_shared_weight_pair_switch.c"
#undef main

static void warmup_weight_pair(unsigned pair) {
    current_pair = pair;
    configure_gemm();
    scale_command(0, 0);
    issue_loop(0, 0, KROWS, 0);
    fence_potal("warmup Main GEMM");
    scale_command(0, 1);
    printf("WEIGHT_RESIDENT buffer=%u B_base=%u\n", pair, B_BASE);
}

static void program_index_buffer(unsigned pair, unsigned first,
                                 unsigned rows) {
    current_pair = pair;
    configure_index_window(first, rows);
    printf("INDEX_PROGRAMMED buffer=%u first=%u rows=%u\n",
           pair, first, rows);
}

/*
 * G1 is the first residual window's result.
 * G2 is the accumulated first-plus-second-window result.
 * Any combination below is therefore a sum of independent residual
 * windows without a separate CPU reference GEMM.
 */
static void verify_acc1_combination(int coefficient_g1,
                                     int coefficient_g2,
                                     const char *checkpoint) {
    for (unsigned j = 0; j < JTILES; ++j) {
        for (unsigned i = 0; i < DIM; ++i) {
            for (unsigned col = 0; col < DIM; ++col) {
                const unsigned n = j * DIM + col;
                const int64_t g1 = potal_multiwindow_golden[1][i][n];
                const int64_t g2 = potal_multiwindow_golden[2][i][n];
                int64_t want64 = (int64_t)coefficient_g1 * g1 +
                                 (int64_t)coefficient_g2 * g2;
                if (want64 > INT32_MAX) want64 = INT32_MAX;
                if (want64 < INT32_MIN) want64 = INT32_MIN;
                const int32_t want = (int32_t)want64;
                const int32_t actual = got[1][j][i][col];
                if (actual != want) {
                    printf("FAIL INDEX_ISOLATION %s row=%u col=%u "
                           "want=%ld got=%ld\n",
                           checkpoint, i, n, (long)want, (long)actual);
                    exit(1);
                }
            }
        }
    }
    printf("PASS INDEX_ISOLATION %s\n", checkpoint);
}

static void run_indexed_buffer(unsigned pair, unsigned first,
                               unsigned rows, int accumulate,
                               int coefficient_g1, int coefficient_g2,
                               const char *checkpoint) {
    current_pair = pair;
    scale_command(1, 0);
    issue_loop(1, first, rows, accumulate);
    fence_potal("indexed residual GEMM");
    scale_command(1, 1);

    read_acc_banks();
    verify(0, 0);  /* Main ACC0 must remain unchanged. */
    verify_acc1_combination(coefficient_g1, coefficient_g2, checkpoint);
}

int main(void) {
    const unsigned first_window_rows = WINDOW_CAP;
    const unsigned second_window_first = WINDOW_CAP;
    const unsigned second_window_rows = TOTAL_ACTIVE_R - WINDOW_CAP;

    printf("POTAL_DUAL_INDEX_ISOLATION start DIM=%d K=%d N=%d "
           "window0=%u window1=%u\n",
           DIM, KROWS, NCOLS, first_window_rows, second_window_rows);

    init_matrices();
    ROCC_INSTRUCTION_0_R_R(3, 0ULL, 0ULL, 7);
    fence_potal("initial");

    /* Load the SAME original B into both physical Weight banks. */
    warmup_weight_pair(0);
    warmup_weight_pair(1);

    /* Poison DRAM B: all indexed passes must use resident SPAD B. */
    for (unsigned k = 0; k < KROWS; ++k) {
        for (unsigned n = 0; n < NCOLS; ++n) {
            ((volatile int8_t *)b)[k * NCOLS + n] = -19;
        }
    }
    asm volatile("fence rw,rw" ::: "memory");
    read_acc_banks();
    verify(0, 0);

    /* Distinct index lengths and contents; last config selects buffer 1. */
    program_index_buffer(0, 0, first_window_rows);
    program_index_buffer(1, second_window_first, second_window_rows);

    /* Buffer 0 must still contain its full 32-row (DIM16) index window. */
    run_indexed_buffer(0, 0, first_window_rows, 0,
                       1, 0, "buffer0 survives buffer1 config");

    /* Buffer 1's independent 8-row (DIM16) index window. */
    run_indexed_buffer(1, second_window_first, second_window_rows, 1,
                       0, 1, "buffer1 independent first use");

    /* Return to buffer 0 WITHOUT issuing another INDEX_CONFIG. */
    run_indexed_buffer(0, 0, first_window_rows, 1,
                       1, 1, "buffer0 reused after buffer1 execution");

    /* Reconfigure ONLY buffer 0; buffer 1 must remain intact. */
    program_index_buffer(0, second_window_first, second_window_rows);
    run_indexed_buffer(1, second_window_first, second_window_rows, 1,
                       0, 2, "buffer1 survives buffer0 reconfig");

    /* Verify buffer 0 itself now uses the newly programmed window. */
    run_indexed_buffer(0, second_window_first, second_window_rows, 1,
                       -1, 3, "buffer0 reconfigured window correct");

    printf("POTAL_DUAL_INDEX_ISOLATION PASS DIM=%d\n", DIM);
    return 0;
}

