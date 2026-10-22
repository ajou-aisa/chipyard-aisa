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



#ifndef POTAL_SERIAL
#error "POTAL_SERIAL must be defined by the wrapper"
#endif

#if POTAL_SERIAL != 0 && POTAL_SERIAL != 1
#error "POTAL_SERIAL must be 0 or 1"
#endif

#if POTAL_SERIAL
#define BENCH_MODE "serial"
#else
#define BENCH_MODE "pipelined"
#endif

/*
 * Rocket core cycle counter.
 *
 * The compiler memory clobber prevents compiler reordering
 * across measurement boundaries.
 *
 * Accelerator completion is enforced separately by
 * POTAL_FENCE before taking the ending timestamp.
 */
static inline uint64_t cycle_now(void) {
    uint64_t value;
    asm volatile("rdcycle %0" : "=r"(value) :: "memory");
    return value;
}

/*
 * Quiet INDEX_CONFIG + INDEX_WRITE.
 *
 * Same valid Index commands as configure_index_window(),
 * but without success-path UART prints or negative probes
 * inside the measured interval.
 */
static void index_window_quiet(unsigned first, unsigned rows) {
    if (rows == 0 ||
        rows > WINDOW_CAP ||
        first + rows > TOTAL_ACTIVE_R) {
        printf("FAIL invalid benchmark Index window\n");
        exit(1);
    }

    uint64_t status = 0;

    const uint64_t rs1 =
        ((uint64_t)JTILES << 32) | (uint64_t)B_BASE;

    const uint64_t rs2 =
        ((uint64_t)rows << 32) | (uint64_t)KROWS;

    ROCC_INSTRUCTION_R_R_R(3, status, rs1, rs2, 27);
    check_status("Benchmark INDEX_CONFIG", status);

    for (unsigned local = 0; local < rows; ++local) {
        ROCC_INSTRUCTION_R_R_R(
            3, status,
            (uint64_t)local,
            (uint64_t)active_k[first + local],
            26
        );
        check_status("Benchmark INDEX_WRITE", status);
    }
}

int main(void) {
    const unsigned window1_rows =
        TOTAL_ACTIVE_R - WINDOW_CAP;

    uint64_t cycles[5] = {0};
    uint64_t start;

    printf(
        "POTAL_H2D_BENCH start DIM=%d mode=%s\n",
        DIM, BENCH_MODE
    );

    init_matrices();
    prepare_next_weight();

    ROCC_INSTRUCTION_0_R_R(3, 0ULL, 0ULL, 7);
    fence_potal("Initial");

    configure_gemm();

    scale_command(0, 0);
    scale_command(1, 0);

    /*
     * MEASURE 0: Dual Main GEMM.
     *
     * Both modes execute exactly the same two GEMMs.
     * Serial adds a drain between the two submissions.
     */
    start = cycle_now();

    submit_main(0, 0, &b[0][0], 0);

#if POTAL_SERIAL
    fence_potal("Serial Main 0");
#endif

    submit_main(1, 1, &b_next[0][0], 1);
    fence_potal("Both Main GEMMs");

    cycles[0] = cycle_now() - start;

    scale_command(0, 1);
    scale_command(1, 1);

    read_acc_banks();
    verify_three_times_main();

    /*
     * Poison DRAM Weight outside the timed region.
     * Indexed Residual must reuse resident SP Weight.
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

    current_pair = 0;
    index_window_quiet(0, WINDOW_CAP);

    scale_command(1, 0);

    /*
     * MEASURE 1:
     * Buffer 0 Residual + Buffer 1 Index preparation.
     */
    current_pair = 0;
    start = cycle_now();

    issue_loop(1, 0, WINDOW_CAP, 0, 0);

#if POTAL_SERIAL
    fence_potal("Serial Residual 0");
#endif

    current_pair = 1;
    index_window_quiet(0, WINDOW_CAP);

    fence_potal("Residual 0 and Index 1");

    cycles[1] = cycle_now() - start;

    integrated_checkpoint(
        1, 0,
        "buffer0_residual_buffer1_index_update"
    );

    /*
     * MEASURE 2:
     * Buffer 1 Residual + Buffer 0 Index preparation.
     */
    current_pair = 1;
    start = cycle_now();

    issue_loop(1, 0, WINDOW_CAP, 1, 0);

#if POTAL_SERIAL
    fence_potal("Serial Residual 1");
#endif

    current_pair = 0;
    index_window_quiet(WINDOW_CAP, window1_rows);

    fence_potal("Residual 1 and Index 0");

    cycles[2] = cycle_now() - start;

    integrated_checkpoint(
        3, 0,
        "buffer1_residual_buffer0_index_update"
    );

    /*
     * MEASURE 3:
     * Buffer 0 Window 1.
     *
     * This stage is identical in both modes.
     */
    current_pair = 0;
    start = cycle_now();

    issue_loop(1, WINDOW_CAP, window1_rows, 1, 0);
    fence_potal("Residual 2");

    cycles[3] = cycle_now() - start;

    integrated_checkpoint(
        2, 1,
        "buffer0_second_window"
    );

    /*
     * MEASURE 4:
     * Buffer 1 Window 0 reuse.
     *
     * This stage is identical in both modes.
     */
    current_pair = 1;
    start = cycle_now();

    issue_loop(1, 0, WINDOW_CAP, 1, 0);
    fence_potal("Residual 3");

    cycles[4] = cycle_now() - start;

    integrated_checkpoint(
        4, 1,
        "buffer1_index_reuse"
    );

    scale_command(1, 1);

    const uint64_t residual_total =
        cycles[1] + cycles[2] + cycles[3] + cycles[4];

    const uint64_t scheduled_total =
        cycles[0] + residual_total;

    printf(
        "POTAL_H2D_CYCLES mode=%s "
        "main=%llu "
        "res0=%llu res1=%llu "
        "res2=%llu res3=%llu "
        "residual_total=%llu scheduled_total=%llu\n",
        BENCH_MODE,
        (unsigned long long)cycles[0],
        (unsigned long long)cycles[1],
        (unsigned long long)cycles[2],
        (unsigned long long)cycles[3],
        (unsigned long long)cycles[4],
        (unsigned long long)residual_total,
        (unsigned long long)scheduled_total
    );

    printf(
        "POTAL_H2D_CYCLE_BENCH PASS DIM=%d mode=%s\n",
        DIM, BENCH_MODE
    );

    return 0;
}
