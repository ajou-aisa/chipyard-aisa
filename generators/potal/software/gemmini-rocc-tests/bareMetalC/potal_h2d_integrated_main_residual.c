/*
 * PoTal H2-D: Integrated Main + Residual pipeline.
 *
 * Main:
 *   Pair 0: A * B
 *   Pair 1: A * (2B)
 *   ACC0 = 3 * Main Golden
 *
 * Residual:
 *   Pair 0 Window 0 -> G1
 *   Pair 1 Window 0 -> +2G1
 *   Pair 0 Window 1 -> +(G2-G1)
 *   Pair 1 Window 0 -> +2G1
 *
 * Final ACC1 = 4G1 + G2
 *
 * Includes bidirectional cross-buffer Index updates.
 * Numerical correctness and physical overlap are checked
 * separately.
 */

#define main potal_h2b1_base_main_unused
#include "potal_h2b1_a_cohabit.c"
#undef main

static int8_t b_next[KROWS][NCOLS] __attribute__((aligned(64)));

static void prepare_next_weight(void) {
    for (unsigned k = 0; k < KROWS; ++k)
        for (unsigned n = 0; n < NCOLS; ++n)
            b_next[k][n] = (int8_t)(2 * (int)b[k][n]);
    asm volatile("fence rw,rw" ::: "memory");
}

static void submit_main(unsigned pair, unsigned scale_phase,
                        const int8_t *weight, int accumulate) {
    const uint64_t bounds = ((uint64_t)KTILES << 32) |
        ((uint64_t)JTILES << 16) | UINT64_C(1);
    const uint64_t flags = PAIRED_FLAG |
        (accumulate ? UINT64_C(1) : 0ULL) |
        (UINT64_C(1) << 1) | (UINT64_C(1) << 20) |
        ((uint64_t)(pair + 1) << 18) |
        ((uint64_t)(pair + 1) << 16) |
        ((uint64_t)(scale_phase * JTILES) << 21) |
        ((uint64_t)(7 + scale_phase) << 37) |
        ((uint64_t)KTILES << 45);
    const uint64_t a_ptr = (uint64_t)(uintptr_t)&a[0][0][0];
    const uint64_t b_ptr = (uint64_t)(uintptr_t)weight;

    ROCC_INSTRUCTION_0_R_R(3, 0ULL, bounds, 9);
    ROCC_INSTRUCTION_0_R_R(3, a_ptr, b_ptr, 10);
    ROCC_INSTRUCTION_0_R_R(3, 0ULL, 0ULL, 11);
    ROCC_INSTRUCTION_0_R_R(3, (uint64_t)KROWS, (uint64_t)NCOLS, 12);
    ROCC_INSTRUCTION_0_R_R(3, 0ULL, (uint64_t)NCOLS, 13);
    ROCC_INSTRUCTION_0_R_R(3, flags, 0ULL, 8);
}

static void verify_three_times_main(void) {
    for (unsigned j = 0; j < JTILES; ++j) {
        for (unsigned i = 0; i < DIM; ++i) {
            for (unsigned col = 0; col < DIM; ++col) {
                const unsigned n = j * DIM + col;
                const int32_t want = 3 * potal_multiwindow_golden[0][i][n];
                const int32_t actual = got[0][j][i][col];
                if (want != actual) {
                    printf("FAIL DUAL_MAIN row=%u col=%u want=%ld got=%ld\n",
                           i, n, (long)want, (long)actual);
                    exit(1);
                }
            }
        }
    }
    printf("PASS DUAL_MAIN: ACC0 = Main(B) + Main(2B)\n");
}


static void verify_integrated_residual(
    int coefficient_g1,
    int coefficient_g2,
    const char *checkpoint
) {
    for (unsigned j = 0; j < JTILES; ++j) {
        for (unsigned i = 0; i < DIM; ++i) {
            for (unsigned col = 0; col < DIM; ++col) {

                const unsigned n = j * DIM + col;

                const int64_t g1 =
                    potal_multiwindow_golden[1][i][n];

                const int64_t g2 =
                    potal_multiwindow_golden[2][i][n];

                int64_t expected =
                    (int64_t)coefficient_g1 * g1 +
                    (int64_t)coefficient_g2 * g2;

                if (expected > INT32_MAX)
                    expected = INT32_MAX;

                if (expected < INT32_MIN)
                    expected = INT32_MIN;

                const int32_t actual =
                    got[1][j][i][col];

                if (actual != (int32_t)expected) {
                    printf(
                        "FAIL INTEGRATED %s "
                        "row=%u col=%u want=%ld got=%ld\n",
                        checkpoint,
                        i, n,
                        (long)expected,
                        (long)actual
                    );
                    exit(1);
                }
            }
        }
    }

    printf("PASS INTEGRATED %s\n", checkpoint);
}


static void integrated_checkpoint(
    int coefficient_g1,
    int coefficient_g2,
    const char *checkpoint
) {
    read_acc_banks();

    /* Both Main results must remain accumulated in ACC0. */
    verify_three_times_main();

    verify_integrated_residual(
        coefficient_g1,
        coefficient_g2,
        checkpoint
    );
}


int main(void) {
    const unsigned window1_rows =
        TOTAL_ACTIVE_R - WINDOW_CAP;

    printf(
        "POTAL_INTEGRATED start "
        "DIM=%d K=%d N=%d R=%d\n",
        DIM, KROWS, NCOLS, TOTAL_ACTIVE_R
    );

    init_matrices();
    prepare_next_weight();

    ROCC_INSTRUCTION_0_R_R(3, 0ULL, 0ULL, 7);
    fence_potal("Initial");

    configure_gemm();

    /*
     * Main GEMM: two physical Weight bank pairs.
     * No fence between LOOP_WS submissions.
     */
    scale_command(0, 0);
    scale_command(1, 0);

    submit_main(0, 0, &b[0][0], 0);
    submit_main(1, 1, &b_next[0][0], 1);

    fence_potal("Dual Main");

    scale_command(0, 1);
    scale_command(1, 1);

    read_acc_banks();
    verify_three_times_main();

    printf("INTEGRATED_MAIN PASS\n");

    /*
     * Poison both DRAM Weight arrays.
     * Subsequent Indexed GEMMs must reuse resident B.
     */
    for (unsigned k = 0; k < KROWS; ++k) {
        for (unsigned n = 0; n < NCOLS; ++n) {
            ((volatile int8_t *)b)
                [k * NCOLS + n] = -19;

            ((volatile int8_t *)b_next)
                [k * NCOLS + n] = -21;
        }
    }

    asm volatile("fence rw,rw" ::: "memory");

    /*
     * Configure Buffer 0's first Index window.
     */
    current_pair = 0;
    configure_index_window(0, WINDOW_CAP);

    scale_command(1, 0);

    /*
     * Residual 0:
     * Buffer 0 computes while Buffer 1's Index is
     * configured and written.
     */
    current_pair = 0;
    issue_loop(1, 0, WINDOW_CAP, 0, 0);

    current_pair = 1;
    configure_index_window(0, WINDOW_CAP);

    fence_potal("Residual0 + Index1");

    integrated_checkpoint(
        1, 0,
        "buffer0_residual_buffer1_index_update"
    );

    /*
     * Residual 1:
     * Buffer 1 holds 2B and contributes 2G1.
     * Simultaneously update Buffer 0's next window.
     *
     * ACC1 = 3G1
     */
    current_pair = 1;
    issue_loop(1, 0, WINDOW_CAP, 1, 0);

    current_pair = 0;
    configure_index_window(
        WINDOW_CAP,
        window1_rows
    );

    fence_potal("Residual1 + Index0");

    integrated_checkpoint(
        3, 0,
        "buffer1_residual_buffer0_index_update"
    );

    /*
     * Residual 2:
     * Buffer 0 uses its newly configured Window 1.
     *
     * ACC1 = 3G1 + (G2-G1) = 2G1+G2
     */
    current_pair = 0;
    issue_loop(1, WINDOW_CAP, window1_rows, 1, 0);

    fence_potal("Residual2");

    integrated_checkpoint(
        2, 1,
        "buffer0_second_window"
    );

    /*
     * Residual 3:
     * Buffer 1 reuses its original Window 0.
     *
     * ACC1 = 2G1+G2+2G1 = 4G1+G2
     */
    current_pair = 1;
    issue_loop(1, 0, WINDOW_CAP, 1, 0);

    fence_potal("Residual3");

    integrated_checkpoint(
        4, 1,
        "buffer1_index_reuse"
    );

    scale_command(1, 1);

    printf(
        "POTAL_INTEGRATED_MAIN_RESIDUAL PASS DIM=%d\n",
        DIM
    );

    return 0;
}
