/*
 * PoTal H2-2B2-0: two Main GEMMs submitted back-to-back without an
 * intervening POTAL_FENCE, using independent A/B Scratchpad bank pairs.
 *
 * Pair 0 computes A*B. Pair 1 computes A*(2B) and accumulates into ACC0.
 * Final expected ACC0 = 3*(A*B) element-wise. Both loop slots must drain.
 *
 * This is a functional/concurrency-enablement test, NOT evidence that
 * DMA and PE computation overlapped in time. Instrument RTL for that.
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

int main(void) {
    printf("POTAL_DUAL_MAIN start DIM=%d K=%d N=%d\n", DIM, KROWS, NCOLS);
    init_matrices();
    prepare_next_weight();

    ROCC_INSTRUCTION_0_R_R(3, 0ULL, 0ULL, 7);
    fence_potal("initial");
    configure_gemm();
    scale_command(0, 0);
    scale_command(1, 0);

    // IMPORTANT: no fence between these two LOOP_WS submissions.
    submit_main(0, 0, &b[0][0], 0);       // SP A0, B1; overwrite ACC0
    submit_main(1, 1, &b_next[0][0], 1);  // SP A2, B3; accumulate ACC0

    fence_potal("two Main loops");
    scale_command(0, 1);
    scale_command(1, 1);
    read_acc_banks();
    verify_three_times_main();
    printf("POTAL_DUAL_MAIN PASS DIM=%d\n", DIM);
    return 0;
}
