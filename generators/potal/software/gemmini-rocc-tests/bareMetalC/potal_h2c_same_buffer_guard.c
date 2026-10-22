/*
 * PoTal H2-2C: Same-buffer Index Update Protection.
 *
 * Four independent guard trials:
 *
 *   Buffer 0: CONFIG after Indexed GEMM
 *   Buffer 0: WRITE  after Indexed GEMM
 *   Buffer 1: CONFIG after Indexed GEMM
 *   Buffer 1: WRITE  after Indexed GEMM
 *
 * No POTAL_FENCE between Indexed LOOP_WS and Index update.
 *
 * Numerical correctness uses the existing offline Golden.
 * Positive stall cycles must be verified separately through
 * POTAL_INDEX_GUARD_RESUME RTL instrumentation.
 */

#define main potal_pair_switch_reference_main_unused
#include "potal_shared_weight_pair_switch.c"
#undef main


static void warmup_buffer(unsigned pair) {
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


static void verify_repeated_window(
    unsigned repeats,
    const char *checkpoint
) {
    for (unsigned j = 0; j < JTILES; ++j) {
        for (unsigned i = 0; i < DIM; ++i) {
            for (unsigned col = 0; col < DIM; ++col) {

                const unsigned n = j * DIM + col;

                int64_t expected =
                    (int64_t)repeats *
                    potal_multiwindow_golden[1][i][n];

                if (expected > INT32_MAX)
                    expected = INT32_MAX;

                if (expected < INT32_MIN)
                    expected = INT32_MIN;

                const int32_t want =
                    (int32_t)expected;

                const int32_t actual =
                    got[1][j][i][col];

                if (actual != want) {
                    printf(
                        "FAIL SAME_BUFFER %s "
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

    printf(
        "PASS SAME_BUFFER %s repeats=%u\n",
        checkpoint,
        repeats
    );
}


static void verify_checkpoint(
    unsigned repeats,
    const char *checkpoint
) {
    read_acc_banks();

    /* Original Main ACC0 must remain unchanged. */
    verify(0, 0);

    verify_repeated_window(
        repeats,
        checkpoint
    );
}


/*
 * Issue one Indexed GEMM, then request an update to the
 * SAME Weight buffer before issuing POTAL_FENCE.
 *
 * kind=0: INDEX_CONFIG, followed by full window programming
 * kind=1: INDEX_WRITE of the existing slot-0 mapping
 *
 * Each trial is independent with respect to the update kind:
 * a CONFIG stall must not be mistaken for a WRITE stall.
 */
static void run_guard_trial(
    unsigned pair,
    unsigned kind,
    unsigned repeats,
    const char *checkpoint
) {
    current_pair = pair;

    printf(
        "SAME_BUFFER_BEGIN buffer=%u "
        "kind=%s repeats=%u\n",
        pair,
        kind == 0 ? "CONFIG" : "WRITE",
        repeats
    );

    scale_command(1, 0);

    /*
     * Every trial computes the same first residual window.
     *
     * First iteration overwrites ACC1.
     * Subsequent iterations accumulate.
     */
    issue_loop(
        1,
        0,
        WINDOW_CAP,
        repeats != 1
    );

    /*
     * Deliberately no POTAL_FENCE here.
     *
     * The Index update targets the buffer currently
     * executing the Indexed GEMM.
     */
    if (kind == 0) {

        /*
         * Reconfigure the same window and restore all indices.
         *
         * The first CONFIG must wait if this buffer is busy.
         */
        configure_index_window(
            0,
            WINDOW_CAP
        );

    } else {

        /*
         * Re-write slot 0 with the same source K.
         *
         * Buffer selection comes from the last successful
         * INDEX_CONFIG. Trial order guarantees that this
         * selected buffer equals 'pair'.
         */
        uint64_t status = 0;

        ROCC_INSTRUCTION_R_R_R(
            3,
            status,
            0ULL,
            (uint64_t)active_k[0],
            26
        );

        check_status(
            "Same-buffer INDEX_WRITE",
            status
        );
    }

    printf(
        "SAME_BUFFER_UPDATE_RETURNED "
        "buffer=%u kind=%s\n",
        pair,
        kind == 0 ? "CONFIG" : "WRITE"
    );

    /*
     * Drain after the Index update, never before it.
     */
    fence_potal("Same-buffer update completion");

    scale_command(1, 1);

    verify_checkpoint(
        repeats,
        checkpoint
    );
}


int main(void) {
    printf(
        "POTAL_SAME_BUFFER_GUARD start "
        "DIM=%d K=%d N=%d window=%d\n",
        DIM,
        KROWS,
        NCOLS,
        WINDOW_CAP
    );

    init_matrices();

    ROCC_INSTRUCTION_0_R_R(
        3, 0ULL, 0ULL, 7
    );

    fence_potal("Initial");

    /*
     * Load the same original Weight matrix into
     * both resident Scratchpad Weight banks.
     */
    warmup_buffer(0);
    warmup_buffer(1);

    /*
     * Poison DRAM Weight.
     * Indexed GEMM must use resident B.
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
     * Initially configure both Index Tables.
     * The last selected buffer is Buffer 1.
     */
    current_pair = 0;
    configure_index_window(0, WINDOW_CAP);

    current_pair = 1;
    configure_index_window(0, WINDOW_CAP);

    /*
     * CONFIG 0 -> WRITE 0 -> CONFIG 1 -> WRITE 1.
     *
     * This order ensures that INDEX_WRITE targets the
     * same buffer as the Indexed GEMM.
     */

    run_guard_trial(
        0, 0, 1,
        "buffer0_config_guard"
    );

    run_guard_trial(
        0, 1, 2,
        "buffer0_write_guard"
    );

    run_guard_trial(
        1, 0, 3,
        "buffer1_config_guard"
    );

    run_guard_trial(
        1, 1, 4,
        "buffer1_write_guard"
    );

    printf(
        "POTAL_SAME_BUFFER_NUMERIC PASS DIM=%d\n",
        DIM
    );

    return 0;
}
