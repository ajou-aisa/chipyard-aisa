
/*
 * PoTal H2-2A: Multi-window Indexed Weight Test
 *
 * Original K = 3 * DIM
 * N          = 2 * DIM
 * Active K   = 5 * DIM / 2
 *
 * Index capacity = 2 * DIM
 *
 * Residual Window 0: 2 * DIM
 * Residual Window 1: DIM / 2
 *
 * Main       -> ACC0
 * Residual 1 -> ACC1
 * Residual 2 -> ACC1 accumulate
 *
 * Original B is loaded into SP once.
 * Residual GEMMs do not reload B from DRAM.
 */

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <limits.h>

#ifndef POTAL_TEST_DIM
#define POTAL_TEST_DIM 16
#endif

#if POTAL_TEST_DIM == 16
#include "include/gemmini_potal_params.h"
#elif POTAL_TEST_DIM == 32
#include "include/gemmini_potal_d32_params.h"
#elif POTAL_TEST_DIM == 64
#include "include/gemmini_potal_d64_params.h"
#else
#error "Unsupported DIM"
#endif

#include "rocc-software/src/xcustom.h"

#ifndef POTAL_INDEX_ENTRIES
#error "Missing POTAL_INDEX_ENTRIES"
#endif

#ifndef POTAL_SCALE_ENTRIES
#error "Missing POTAL_SCALE_ENTRIES"
#endif

#ifndef POTAL_SCALE_SLOT_BITS
#error "Missing POTAL_SCALE_SLOT_BITS"
#endif

#if DIM != POTAL_TEST_DIM
#error "DIM and RTL header mismatch"
#endif

#define PHASES 3
#define KTILES 3
#define JTILES 2

#define KROWS (KTILES * DIM)
#define NCOLS (JTILES * DIM)

#define TOTAL_ACTIVE_R ((5 * DIM) / 2)
#define WINDOW_CAP POTAL_INDEX_ENTRIES

#define SP_ROWS (BANK_NUM * BANK_ROWS)
#define B_SP_ROWS (KTILES * JTILES * DIM)
#define B_BASE (SP_ROWS - B_SP_ROWS)

#define ACC0_FULL_ADDR (5u << (ADDR_LEN - 3))
#define ACC1_FULL_ADDR \
    (ACC0_FULL_ADDR + ACC_ROWS / 2)

#define PAIRED_FLAG \
    (UINT64_C(1) << 61)

#define RESIDUAL_FLAG \
    (UINT64_C(1) << 62)

#define INDEXED_FLAG \
    (UINT64_C(1) << 63)

#if POTAL_INDEX_ENTRIES < DIM
#error "Index capacity smaller than DIM"
#endif

#if (POTAL_INDEX_ENTRIES % DIM) != 0
#error "Index window must contain full K tiles"
#endif

#if POTAL_INDEX_ENTRIES >= TOTAL_ACTIVE_R
#error "Active K must exceed Index capacity"
#endif

#if POTAL_SCALE_ENTRIES < (PHASES * JTILES)
#error "Scale cache capacity too small"
#endif

#if SP_ROWS < B_SP_ROWS
#error "B exceeds Scratchpad capacity"
#endif

#if (ACC_ROWS / 2) < (JTILES * DIM)
#error "Paired ACC bank capacity too small"
#endif

/* --------------------------------------------------
 * Test matrices
 * -------------------------------------------------- */

static int8_t a[PHASES][DIM][KROWS]
    __attribute__((aligned(64)));

static int8_t b[KROWS][NCOLS]
    __attribute__((aligned(64)));

static int8_t b_ref[KROWS][NCOLS]
    __attribute__((aligned(64)));

static uint32_t active_k[TOTAL_ACTIVE_R]
    __attribute__((aligned(64)));

static volatile int32_t got[2][JTILES][DIM][DIM]
    __attribute__((aligned(64)));

/* CPU reference */

static int32_t ref_main[DIM][NCOLS];
static int32_t ref_residual[DIM][NCOLS];

/* --------------------------------------------------
 * Status / Fence
 * -------------------------------------------------- */

static void check_status(
    const char *step,
    uint64_t status
) {
    if (status != 0) {
        printf(
            "FAIL %s status=%llu\n",
            step,
            (unsigned long long)status
        );
        exit(1);
    }
}

static void fence_potal(const char *step) {
    uint64_t response = 0;

    ROCC_INSTRUCTION_R_R_R(
        3, response, 0ULL, 0ULL, 25
    );

    check_status(step, response);

    asm volatile(
        "fence rw,rw" ::: "memory"
    );
}

/* --------------------------------------------------
 * Gemmini Configuration
 * -------------------------------------------------- */

static void config_ld(
    unsigned id,
    unsigned row_stride_bytes
) {
    const uint64_t rs1 =
        ((uint64_t)DIM << 16) |
        (UINT64_C(1) << 8) |
        ((uint64_t)id << 3) |
        UINT64_C(1);

    ROCC_INSTRUCTION_0_R_R(
        3,
        rs1,
        (uint64_t)row_stride_bytes,
        0
    );
}

static void configure_gemm(void) {
    config_ld(0, KROWS);
    config_ld(1, NCOLS);

    ROCC_INSTRUCTION_0_R_R(
        3,
        (UINT64_C(1) << 16) |
        (UINT64_C(1) << 2),
        UINT64_C(1) << 48,
        0
    );

    ROCC_INSTRUCTION_0_R_R(
        3,
        2ULL,
        DIM * sizeof(int32_t),
        0
    );
}

/* --------------------------------------------------
 * Scale Commands
 * -------------------------------------------------- */

static uint64_t scale_key(
    unsigned phase,
    unsigned j,
    unsigned col
) {
    const uint64_t slot =
        phase * JTILES + j;

    const uint64_t generation =
        7 + phase;

    return
        ((uint64_t)col <<
            (POTAL_SCALE_SLOT_BITS + 8)) |
        (generation << POTAL_SCALE_SLOT_BITS) |
        slot;
}

static void scale_command(
    unsigned phase,
    int release
) {
    for (unsigned j = 0; j < JTILES; ++j) {
        for (unsigned col = 0; col < DIM; ++col) {
            uint64_t response = 0;

            const uint64_t key =
                scale_key(phase, j, col);

            if (release) {
                ROCC_INSTRUCTION_R_R_R(
                    3, response, key, 0ULL, 24
                );
            } else {
                /* Identity shift */
                ROCC_INSTRUCTION_R_R_R(
                    3, response, key, 0ULL, 23
                );
            }

            check_status(
                release ? "SCALE_RELEASE" : "SCALE_WRITE",
                response
            );
        }
    }
}

/* --------------------------------------------------
 * Invalid Index Tests
 * -------------------------------------------------- */

static void expect_invalid_index_writes(
    unsigned active_rows
) {
    uint64_t status = 0;

    /* Invalid slot */
    ROCC_INSTRUCTION_R_R_R(
        3,
        status,
        (uint64_t)active_rows,
        0ULL,
        26
    );

    if (status != 5) {
        printf(
            "FAIL invalid slot status=%llu\n",
            (unsigned long long)status
        );
        exit(1);
    }

    /* Invalid source K */
    ROCC_INSTRUCTION_R_R_R(
        3,
        status,
        0ULL,
        (uint64_t)KROWS,
        26
    );

    if (status != 5) {
        printf(
            "FAIL invalid source K status=%llu\n",
            (unsigned long long)status
        );
        exit(1);
    }
}

/* --------------------------------------------------
 * Index Window Configuration
 * -------------------------------------------------- */

static void configure_index_window(
    unsigned first,
    unsigned rows
) {
    if (rows == 0 ||
        rows > WINDOW_CAP ||
        first + rows > TOTAL_ACTIVE_R) {

        printf(
            "FAIL window first=%u rows=%u\n",
            first, rows
        );
        exit(1);
    }

    uint64_t response = 0;

    /* INDEX_CONFIG:
     * rs1 = {JTILES, B_BASE}
     * rs2 = {rows, KROWS}
     */

    const uint64_t rs1 =
        ((uint64_t)JTILES << 32) |
        (uint64_t)B_BASE;

    const uint64_t rs2 =
        ((uint64_t)rows << 32) |
        (uint64_t)KROWS;

    ROCC_INSTRUCTION_R_R_R(
        3, response, rs1, rs2, 27
    );

    check_status("INDEX_CONFIG", response);

    /* Each window starts at logical slot zero. */

    for (unsigned local = 0; local < rows; ++local) {
        const uint32_t source_k =
            active_k[first + local];

        ROCC_INSTRUCTION_R_R_R(
            3,
            response,
            (uint64_t)local,
            (uint64_t)source_k,
            26
        );

        check_status("INDEX_WRITE", response);
    }

    expect_invalid_index_writes(rows);

    printf(
        "WINDOW first=%u rows=%u "
        "K=%d N=%d cap=%d B_base=%d\n",
        first,
        rows,
        KROWS,
        NCOLS,
        WINDOW_CAP,
        B_BASE
    );
}

/* --------------------------------------------------
 * LOOP_WS
 * -------------------------------------------------- */

static void issue_loop(
    unsigned phase,
    unsigned first,
    unsigned rows,
    int accumulate
) {
    const int residual = phase != 0;

    const unsigned passes =
        residual ?
        (rows + DIM - 1) / DIM :
        KTILES;

    const unsigned pad_k =
        residual ?
        passes * DIM - rows :
        0;

    const uint64_t bounds =
        ((uint64_t)passes << 32) |
        ((uint64_t)JTILES << 16) |
        UINT64_C(1);

    const uint64_t pads =
        ((uint64_t)pad_k << 32);

    const uint64_t flags =
        PAIRED_FLAG |
        (residual ?
            (RESIDUAL_FLAG | INDEXED_FLAG) :
            0ULL) |
        (accumulate ? UINT64_C(1) : 0ULL) |
        (UINT64_C(1) << 1) |
        (UINT64_C(1) << 20) |
        (UINT64_C(1) << 18) |
        (UINT64_C(2) << 16) |
        ((uint64_t)(phase * JTILES) << 21) |
        ((uint64_t)(7 + phase) << 37) |
        ((uint64_t)passes << 45);

    /*
     * A uses physical stride KROWS.
     * Each window begins at A[..., first].
     */

    const uint64_t a_addr =
        (uint64_t)(uintptr_t)&a[phase][0][first];

    const uint64_t b_addr =
        residual ?
        0ULL :
        (uint64_t)(uintptr_t)b;

    /* BOUNDS */
    ROCC_INSTRUCTION_0_R_R(
        3, pads, bounds, 9
    );

    /* ADDRS_AB */
    ROCC_INSTRUCTION_0_R_R(
        3, a_addr, b_addr, 10
    );

    /* ADDRS_DC */
    ROCC_INSTRUCTION_0_R_R(
        3, 0ULL, 0ULL, 11
    );

    /* STRIDES_AB */
    ROCC_INSTRUCTION_0_R_R(
        3,
        (uint64_t)KROWS,
        (uint64_t)NCOLS,
        12
    );

    /* STRIDES_DC */
    ROCC_INSTRUCTION_0_R_R(
        3,
        0ULL,
        (uint64_t)NCOLS,
        13
    );

    /* Execute */
    ROCC_INSTRUCTION_0_R_R(
        3, flags, 0ULL, 8
    );
}

/* --------------------------------------------------
 * ACC Readback
 * -------------------------------------------------- */

static uint64_t acc_desc(
    unsigned bank,
    unsigned j
) {
    const uint32_t addr =
        (bank ? ACC1_FULL_ADDR : ACC0_FULL_ADDR)
        + j * DIM;

    return
        ((uint64_t)DIM << 48) |
        ((uint64_t)DIM << 32) |
        addr;
}

static void read_acc_banks(void) {
    for (unsigned bank = 0; bank < 2; ++bank) {
        for (unsigned j = 0; j < JTILES; ++j) {
            ROCC_INSTRUCTION_0_R_R(
                3,
                (uint64_t)(uintptr_t)&got[bank][j][0][0],
                acc_desc(bank, j),
                3
            );
        }
    }

    fence_potal("ACC readback");
}

/* --------------------------------------------------
 * CPU Reference
 * -------------------------------------------------- */

static int32_t sat32(int64_t value) {
    if (value > INT32_MAX)
        return INT32_MAX;

    if (value < INT32_MIN)
        return INT32_MIN;

    return (int32_t)value;
}

static void reference_main(void) {
    for (unsigned i = 0; i < DIM; ++i) {
        for (unsigned n = 0; n < NCOLS; ++n) {
            int64_t sum = 0;

            for (unsigned k = 0; k < KROWS; ++k) {
                sum +=
                    (int32_t)a[0][i][k] *
                    (int32_t)b_ref[k][n];
            }

            ref_main[i][n] = sat32(sum);
        }
    }
}

static void reference_residual_window(
    unsigned phase,
    unsigned first,
    unsigned rows,
    int accumulate
) {
    for (unsigned i = 0; i < DIM; ++i) {
        for (unsigned n = 0; n < NCOLS; ++n) {
            int64_t partial = 0;

            for (unsigned t = first;
                 t < first + rows;
                 ++t) {

                partial +=
                    (int32_t)a[phase][i][t] *
                    (int32_t)b_ref[active_k[t]][n];
            }

            const int64_t previous =
                accumulate ? ref_residual[i][n] : 0;

            ref_residual[i][n] =
                sat32(previous + partial);
        }
    }
}

static void verify(
    unsigned bank,
    unsigned step
) {
    const int32_t (*reference)[NCOLS] =
        bank == 0 ?
        ref_main :
        ref_residual;

    for (unsigned j = 0; j < JTILES; ++j) {
        for (unsigned i = 0; i < DIM; ++i) {
            for (unsigned col = 0; col < DIM; ++col) {
                const unsigned n =
                    j * DIM + col;

                const int32_t actual =
                    got[bank][j][i][col];

                const int32_t want =
                    reference[i][n];

                if (actual != want) {
                    printf(
                        "FAIL ACC bank=%u step=%u "
                        "row=%u col=%u want=%ld got=%ld\n",
                        bank,
                        step,
                        i,
                        n,
                        (long)want,
                        (long)actual
                    );
                    exit(1);
                }
            }
        }
    }

    printf(
        "PASS ACC bank %u step %u\n",
        bank,
        step
    );
}

/* --------------------------------------------------
 * Matrix Initialization
 * -------------------------------------------------- */

static void init_matrices(void) {
    /*
     * Unique non-contiguous source K indices.
     * gcd(7, 3*DIM) == 1 for DIM 16/32/64.
     */

    for (unsigned t = 0;
         t < TOTAL_ACTIVE_R;
         ++t) {

        active_k[t] =
            (7 * t + 5) % KROWS;
    }

    for (unsigned k = 0; k < KROWS; ++k) {
        for (unsigned n = 0; n < NCOLS; ++n) {
            int v =
                (int)((k * 37 +
                       n * 13 +
                       (k ^ n) * 7) % 15) - 7;

            if (v == 0)
                v = 1;

            b[k][n] = (int8_t)v;
            b_ref[k][n] = (int8_t)v;
        }
    }

    for (unsigned phase = 0;
         phase < PHASES;
         ++phase) {

        const unsigned usable =
            phase ? TOTAL_ACTIVE_R : KROWS;

        for (unsigned i = 0; i < DIM; ++i) {
            for (unsigned t = 0; t < KROWS; ++t) {
                int v =
                    (int)((i * 11 +
                           t * 5 +
                           phase * 17) % 7) - 3;

                if (v == 0)
                    v = phase ? 1 : -1;

                a[phase][i][t] =
                    (int8_t)(t < usable ? v : 0);
            }
        }
    }
}

/* --------------------------------------------------
 * Main
 * -------------------------------------------------- */

int main(void) {
    printf(
        "POTAL_MULTIWINDOW start "
        "DIM=%d K=%d N=%d R=%d window=%d\n",
        DIM,
        KROWS,
        NCOLS,
        TOTAL_ACTIVE_R,
        WINDOW_CAP
    );

    init_matrices();

    /* Initial flush */
    ROCC_INSTRUCTION_0_R_R(
        3, 0ULL, 0ULL, 7
    );

    fence_potal("initial");
    configure_gemm();

    asm volatile(
        "fence rw,rw" ::: "memory"
    );

    /* Main -> ACC0 */

    scale_command(0, 0);

    issue_loop(0, 0, KROWS, 0);

    fence_potal("Main");

    scale_command(0, 1);

    /* Poison original B in DRAM */

    for (unsigned k = 0; k < KROWS; ++k) {
        for (unsigned n = 0; n < NCOLS; ++n) {
            ((volatile int8_t *)b)[k * NCOLS + n] = -19;
        }
    }

    asm volatile(
        "fence rw,rw" ::: "memory"
    );

    printf(
        "H2: original B in SP; DRAM B poisoned\n"
    );

    reference_main();

    read_acc_banks();

    verify(0, 0);

    /*
     * Residual 1:
     *   W0: ACC1 overwrite
     *   W1: ACC1 accumulate
     *
     * Residual 2:
     *   W0/W1: ACC1 accumulate
     */

    unsigned step = 1;

    for (unsigned phase = 1;
         phase <= 2;
         ++phase) {

        for (unsigned first = 0;
             first < TOTAL_ACTIVE_R;
             first += WINDOW_CAP) {

            unsigned rows =
                TOTAL_ACTIVE_R - first;

            if (rows > WINDOW_CAP)
                rows = WINDOW_CAP;

            /* Configure window-local Index Table */
            configure_index_window(first, rows);

            /* Load identity scales */
            scale_command(phase, 0);

            const int accumulate =
                phase != 1 || first != 0;

            /* Indexed residual GEMM */
            issue_loop(
                phase,
                first,
                rows,
                accumulate
            );

            fence_potal("Residual window");

            scale_command(phase, 1);

            /* Update independent CPU reference */
            reference_residual_window(
                phase,
                first,
                rows,
                accumulate
            );

            read_acc_banks();

            verify(0, 0);
            verify(1, step++);
        }
    }

    if (step != 5) {
        printf(
            "FAIL unexpected checkpoint count %u\n",
            step
        );
        exit(1);
    }

    printf(
        "POTAL_SHARED_WEIGHT_MULTIWINDOW PASS DIM=%d\n",
        DIM
    );

    return 0;
}

