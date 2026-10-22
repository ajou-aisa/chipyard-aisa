/*
 * PoTal H2-2C: Cross-buffer Index Update regression.
 *
 * Buffer 0 Indexed GEMM -> Buffer 1 INDEX_CONFIG/WRITE
 * Buffer 1 Indexed GEMM -> Buffer 0 INDEX_CONFIG/WRITE
 *
 * No POTAL_FENCE is issued between the Indexed LOOP_WS and
 * the opposite-buffer Index commands.
 *
 * Numerical correctness is checked using existing offline
 * Golden values. Actual same-cycle overlap must be confirmed
 * separately by the Controller RTL monitor.
 */

#define main potal_pair_switch_reference_main_unused
#include "potal_shared_weight_pair_switch.c"
#undef main


static void warmup_weight_buffer(unsigned pair) {
    current_pair = pair;

    configure_gemm();

    scale_command(0, 0);
    issue_loop(0, 0, KROWS, 0);

    fence_potal("Weight residency warmup");
    scale_command(0, 1);

    printf(
        "WEIGHT_RESIDENT buffer=%u B_base=%u\n",
        pair, B_BASE
    );
}


static void verify_cross_golden(
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

                const int32_t want = (int32_t)expected;
                const int32_t actual =
                    got[1][j][i][col];

                if (actual != want) {
                    printf(
                        "FAIL CROSS_INDEX %s "
                        "row=%u col=%u want=%ld got=%ld\n",
                        checkpoint,
                        i, n,
                        (long)want,
                        (long)actual
                    );
                    exit(1);
                }
            }
        }
    }

    printf("PASS CROSS_INDEX %s\n", checkpoint);
}


static void verify_checkpoint(
    int coefficient_g1,
    int coefficient_g2,
    const char *checkpoint
) {
    read_acc_banks();

    /* Original Main ACC must remain unchanged. */
    verify(0, 0);

    verify_cross_golden(
        coefficient_g1,
        coefficient_g2,
        checkpoint
    );
}


/*
 * Issue Indexed GEMM on peer_pair, immediately followed
 * by INDEX_CONFIG/WRITE for target_pair.
 *
 * The Index commands are issued BEFORE POTAL_FENCE.
 *
 * A numerical PASS does not by itself prove overlap.
 * The RTL monitor must report peer_busy updates > 0.
 */
static void run_cross_update(
    unsigned peer_pair,
    unsigned target_pair,
    unsigned peer_first,
    unsigned peer_rows,
    unsigned target_first,
    unsigned target_rows,
    int accumulate,
    int coefficient_g1,
    int coefficient_g2,
    const char *checkpoint
) {
    printf(
        "CROSS_BEGIN peer=%u target=%u "
        "peer_rows=%u target_rows=%u\n",
        peer_pair,
        target_pair,
        peer_rows,
        target_rows
    );

    current_pair = peer_pair;

    scale_command(1, 0);

    /* Start Indexed GEMM on the peer buffer. */
    issue_loop(
        1,
        peer_first,
        peer_rows,
        accumulate
    );

    /*
     * No fence here.
     *
     * Program the opposite buffer while the previous
     * Indexed GEMM is potentially still executing.
     */
    current_pair = target_pair;

    configure_index_window(
        target_first,
        target_rows
    );

    printf(
        "CROSS_UPDATE_ISSUED peer=%u target=%u\n",
        peer_pair,
        target_pair
    );

    /* Global drain only AFTER the opposite-buffer update. */
    fence_potal("Cross-buffer update completion");

    scale_command(1, 1);

    verify_checkpoint(
        coefficient_g1,
        coefficient_g2,
        checkpoint
    );
}


static void run_existing_index(
    unsigned pair,
    unsigned first,
    unsigned rows,
    int coefficient_g1,
    int coefficient_g2,
    const char *checkpoint
) {
    current_pair = pair;

    scale_command(1, 0);

    issue_loop(
        1,
        first,
        rows,
        1
    );

    fence_potal("Existing Index replay");
    scale_command(1, 1);

    verify_checkpoint(
        coefficient_g1,
        coefficient_g2,
        checkpoint
    );
}


int main(void) {
    const unsigned window0_rows = WINDOW_CAP;
    const unsigned window1_first = WINDOW_CAP;
    const unsigned window1_rows =
        TOTAL_ACTIVE_R - WINDOW_CAP;

    printf(
        "POTAL_CROSS_INDEX_UPDATE start "
        "DIM=%d K=%d N=%d W0=%u W1=%u\n",
        DIM,
        KROWS,
        NCOLS,
        window0_rows,
        window1_rows
    );

    init_matrices();

    ROCC_INSTRUCTION_0_R_R(
        3, 0ULL, 0ULL, 7
    );

    fence_potal("Initial");

    /* Load original B into both physical Weight banks. */
    warmup_weight_buffer(0);
    warmup_weight_buffer(1);

    /*
     * Poison DRAM B.
     * Indexed passes must read resident Scratchpad B.
     */
    for (unsigned k = 0; k < KROWS; ++k) {
        for (unsigned n = 0; n < NCOLS; ++n) {
            ((volatile int8_t *)b)[k * NCOLS + n] = -19;
        }
    }

    asm volatile("fence rw,rw" ::: "memory");

    read_acc_banks();
    verify(0, 0);

    /*
     * Initial Index setup for Buffer 0.
     * Buffer 1 has not been configured yet.
     */
    current_pair = 0;
    configure_index_window(0, window0_rows);

    /*
     * Phase 1:
     * Buffer 0 computes Window 0.
     * Buffer 1 receives Window 0 metadata.
     */
    run_cross_update(
        0, 1,
        0, window0_rows,
        0, window0_rows,
        0,
        1, 0,
        "buffer0_compute_buffer1_update"
    );

    /*
     * Phase 2:
     * Buffer 1 computes Window 0.
     * Buffer 0 receives Window 1 metadata.
     */
    run_cross_update(
        1, 0,
        0, window0_rows,
        window1_first, window1_rows,
        1,
        2, 0,
        "buffer1_compute_buffer0_update"
    );

    /*
     * Verify Buffer 0's newly programmed Window 1.
     * Expected: 2*G1 + (G2-G1) = G1+G2.
     */
    run_existing_index(
        0,
        window1_first,
        window1_rows,
        1, 1,
        "buffer0_new_window"
    );

    /*
     * Verify Buffer 1's original Window 0 survived
     * the opposite-buffer configuration.
     *
     * Expected: G1+G2 + G1 = 2*G1+G2.
     */
    run_existing_index(
        1,
        0,
        window0_rows,
        2, 1,
        "buffer1_index_preserved"
    );

    printf(
        "POTAL_CROSS_INDEX_NUMERIC PASS DIM=%d\n",
        DIM
    );

    return 0;
}
