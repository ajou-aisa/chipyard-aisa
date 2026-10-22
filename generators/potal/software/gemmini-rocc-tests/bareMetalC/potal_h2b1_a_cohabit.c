/*
 * PoTal H2-2B1: co-resident Main A and Residual L in one A bank.
 * Main A stays at offset 0; each Residual window uses tile offset KTILES.
 * Original B stays in the selected B bank and is never reloaded by Residual.
 *
 * After four Residual windows, replay Main with BOTH A and B DMA disabled
 * and ACC0 accumulation enabled.  Correct doubled ACC0 proves that Main A
 * survived the Residual loads while original B remained resident.
 * ACC1 must retain the final two-Residual sum.
 *
 * Runs serialized on bank pairs 0/1 and 2/3. It does not establish
 * simultaneous DMA prefetch and compute (planned for H2-2B2).
 * Golden values are precomputed on the host, not on Rocket.
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
#error "POTAL_TEST_DIM must be 16,32,64"
#endif
#include "rocc-software/src/xcustom.h"

#ifndef POTAL_INDEX_ENTRIES
#error "Regenerate appropriate PoTal RTL header before compiling"
#endif
#ifndef POTAL_SCALE_ENTRIES
#error "Regenerate appropriate PoTal RTL header before compiling"
#endif
#ifndef POTAL_SCALE_SLOT_BITS
#error "Regenerate appropriate PoTal RTL header before compiling"
#endif
#if DIM != POTAL_TEST_DIM
#error "Test DIM does not match hardware header"
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
#define MAIN_A_OFFSET_TILES 0U
#define RES_A_OFFSET_TILES KTILES
#define RES_WINDOW_TILES ((WINDOW_CAP + DIM - 1) / DIM)
#define B_BASE (((current_pair + 1U) * (SP_ROWS / 2U)) - B_SP_ROWS)
#define ACC0_FULL_ADDR (5u << (ADDR_LEN - 3))
#define ACC1_FULL_ADDR (ACC0_FULL_ADDR + ACC_ROWS / 2)
#define PAIRED_FLAG (UINT64_C(1) << 61)
#define RESIDUAL_FLAG (UINT64_C(1) << 62)
#define INDEXED_FLAG (UINT64_C(1) << 63)

#if POTAL_INDEX_ENTRIES < DIM
#error "Index window does not fit one K tile"
#endif
#if (POTAL_INDEX_ENTRIES % DIM) != 0
#error "Multiwindow split requires whole-DIM index windows"
#endif
#if POTAL_INDEX_ENTRIES >= TOTAL_ACTIVE_R
#error "This is a multiwindow test: active R must exceed table capacity"
#endif
#if (POTAL_SCALE_ENTRIES < (PHASES * JTILES))
#error "Scale-cache capacity too small"
#endif
#if SP_ROWS < B_SP_ROWS
#error "Original B exceeds SP"
#endif
#if B_SP_ROWS > BANK_ROWS
#error "Original B must fit entirely in one weight bank"
#endif
#if ((RES_A_OFFSET_TILES + RES_WINDOW_TILES) * DIM > BANK_ROWS)
#error "Main and Residual A do not fit within one A bank"
#endif
#if (ACC_ROWS / 2) < (JTILES * DIM)
#error "Paired ACC bank is too small for 2 N tiles"
#endif

static unsigned current_pair = 0; // 0 -> Bank0/1, 1 -> Bank2/3

static int8_t a[PHASES][DIM][KROWS] __attribute__((aligned(64)));
static int8_t b[KROWS][NCOLS] __attribute__((aligned(64)));
static uint32_t active_k[TOTAL_ACTIVE_R] __attribute__((aligned(64)));
static volatile int32_t got[2][JTILES][DIM][DIM] __attribute__((aligned(64)));

#if POTAL_TEST_DIM == 16
#include "potal_multiwindow_golden_d16.h"
#elif POTAL_TEST_DIM == 32
#include "potal_multiwindow_golden_d32.h"
#else
#include "potal_multiwindow_golden_d64.h"
#endif
#if POTAL_MW_GOLDEN_DIM != DIM
#error "Golden DIM mismatch"
#endif
#if POTAL_MW_GOLDEN_STEPS != 5
#error "Unexpected number of expected checkpoints"
#endif

static void check_status(const char *step, uint64_t status) {
    if (status != 0) {
        printf("FAIL %s status=%llu\n", step, (unsigned long long)status);
        exit(1);
    }
}

static void fence_potal(const char *step) {
    uint64_t response = 0;
    ROCC_INSTRUCTION_R_R_R(3, response, 0ULL, 0ULL, 25);
    check_status(step, response);
    asm volatile("fence rw,rw" ::: "memory");
}

static void config_ld(unsigned id, unsigned row_stride_bytes) {
    const uint64_t rs1 = ((uint64_t)DIM << 16) |
        (UINT64_C(1) << 8) | ((uint64_t)id << 3) | UINT64_C(1);
    ROCC_INSTRUCTION_0_R_R(3, rs1, (uint64_t)row_stride_bytes, 0);
}

static void configure_gemm(void) {
    config_ld(0, KROWS);
    config_ld(1, NCOLS);
    ROCC_INSTRUCTION_0_R_R(3,
        (UINT64_C(1) << 16) | (UINT64_C(1) << 2),
        UINT64_C(1) << 48, 0);
    ROCC_INSTRUCTION_0_R_R(3, 2ULL, DIM * sizeof(int32_t), 0);
}

static uint64_t scale_key(unsigned phase, unsigned j, unsigned col) {
    const uint64_t slot = (uint64_t)(phase * JTILES + j);
    const uint64_t generation = (uint64_t)(7 + phase);
    return ((uint64_t)col << (POTAL_SCALE_SLOT_BITS + 8)) |
        (generation << POTAL_SCALE_SLOT_BITS) | slot;
}

static void scale_command(unsigned phase, int release) {
    for (unsigned j = 0; j < JTILES; ++j) {
        for (unsigned col = 0; col < DIM; ++col) {
            uint64_t response = 0;
            const uint64_t key = scale_key(phase, j, col);
            if (release) {
                ROCC_INSTRUCTION_R_R_R(3, response, key, 0ULL, 24);
            } else {
                ROCC_INSTRUCTION_R_R_R(3, response, key, 0ULL, 23);
            }
            check_status(release ? "SCALE_RELEASE" : "SCALE_WRITE", response);
        }
    }
}

static void expect_invalid_index_writes(unsigned active_rows) {
    // INVALID_ADDRESS == 5 in PoTalScaleCommandStatus.
    // These rejected writes must leave the table unchanged.
    uint64_t status = 0;
    ROCC_INSTRUCTION_R_R_R(3, status, (uint64_t)active_rows, 0ULL, 26);
    if (status != 5) {
        printf("FAIL invalid slot: expected status 5 got %llu\n",
               (unsigned long long)status);
        exit(1);
    }
    ROCC_INSTRUCTION_R_R_R(3, status, 0ULL, (uint64_t)KROWS, 26);
    if (status != 5) {
        printf("FAIL invalid source K: expected status 5 got %llu\n",
               (unsigned long long)status);
        exit(1);
    }
}

static void configure_index_window(unsigned first, unsigned rows) {
    if (rows == 0 || rows > WINDOW_CAP || first + rows > TOTAL_ACTIVE_R) {
        printf("FAIL bad window start=%u rows=%u\n", first, rows);
        exit(1);
    }
    uint64_t response = 0;
    // INDEX_CONFIG: rs1={originalJTiles[31:0], residentBBase[31:0]}
    //               rs2={activeWindowRows[31:0], originalKRows[31:0]}
    const uint64_t rs1 = ((uint64_t)JTILES << 32) | (uint64_t)B_BASE;
    const uint64_t rs2 = ((uint64_t)rows << 32) | (uint64_t)KROWS;
    ROCC_INSTRUCTION_R_R_R(3, response, rs1, rs2, 27);
    check_status("INDEX_CONFIG", response);

    // Table slots are always local to this window (0..rows-1).
    for (unsigned local = 0; local < rows; ++local) {
        ROCC_INSTRUCTION_R_R_R(
            3, response, (uint64_t)local,
            (uint64_t)active_k[first + local], 26);
        check_status("INDEX_WRITE", response);
    }
    // Probe the reject path without causing a hardware assertion.
    expect_invalid_index_writes(rows);

    printf("WINDOW first=%u rows=%u K=%d N=%d table_cap=%d B_base=%u pair=%u\n",
           first, rows, KROWS, NCOLS, WINDOW_CAP, B_BASE, current_pair);
}

static void issue_loop(unsigned phase, unsigned first, unsigned rows,
                       int accumulate, int reuse_sp) {
    const int residual = phase != 0;
    const unsigned passes = residual ? (rows + DIM - 1) / DIM : KTILES;
    const unsigned pad_k = residual ? passes * DIM - rows : 0;
    const unsigned a_offset_tiles = residual ? RES_A_OFFSET_TILES : MAIN_A_OFFSET_TILES;
    const uint64_t bounds = ((uint64_t)passes << 32) |
                            ((uint64_t)JTILES << 16) | UINT64_C(1);
    const uint64_t pads = ((uint64_t)pad_k << 32);
    const uint64_t flags = PAIRED_FLAG |
        (residual ? RESIDUAL_FLAG | INDEXED_FLAG : 0ULL) |
        (accumulate ? UINT64_C(1) : 0ULL) |
        (UINT64_C(1) << 1) | (UINT64_C(1) << 20) |
        ((uint64_t)(current_pair + 1) << 18) |
        ((uint64_t)(current_pair + 1) << 16) |
        ((uint64_t)(phase * JTILES) << 21) |
        ((uint64_t)(7 + phase) << 37) |
        ((uint64_t)passes << 45);

    // IMPORTANT: A has physical row stride KROWS, and window is offset
    // into every logical A row at column 'first'.  Since max_i=1,
    // LoopMatmulLdA offsets subsequent K tiles from this base correctly.
    const uint64_t a_addr = reuse_sp ? 0ULL :
        (uint64_t)(uintptr_t)&a[phase][0][first];
    const uint64_t b_addr = (residual || reuse_sp) ? 0ULL :
        (uint64_t)(uintptr_t)b;
    ROCC_INSTRUCTION_0_R_R(3, pads, bounds, 9);
    ROCC_INSTRUCTION_0_R_R(3, a_addr, b_addr, 10);
    ROCC_INSTRUCTION_0_R_R(3, 0ULL, 0ULL, 11);
    ROCC_INSTRUCTION_0_R_R(3, (uint64_t)KROWS, (uint64_t)NCOLS, 12);
    ROCC_INSTRUCTION_0_R_R(3, 0ULL, (uint64_t)NCOLS, 13);
    // rs2[31:3] is the A-bank-local offset in DIM-row tiles;
    // rs2[2:0] remain the legacy resadd/transpose bits (= 0 here).
    ROCC_INSTRUCTION_0_R_R(3, flags, ((uint64_t)a_offset_tiles << 3), 8);
}

static uint64_t acc_desc(unsigned bank, unsigned j) {
    const uint32_t addr = (bank ? ACC1_FULL_ADDR : ACC0_FULL_ADDR) + j * DIM;
    return ((uint64_t)DIM << 48) | ((uint64_t)DIM << 32) | addr;
}

static void read_acc_banks(void) {
    for (unsigned bank = 0; bank < 2; ++bank) {
        for (unsigned j = 0; j < JTILES; ++j) {
            ROCC_INSTRUCTION_0_R_R(
                3, (uint64_t)(uintptr_t)&got[bank][j][0][0],
                acc_desc(bank, j), 3);
        }
    }
    fence_potal("ACC readback");
}

static void verify(unsigned bank, unsigned expected_step) {
    for (unsigned j = 0; j < JTILES; ++j) {
        for (unsigned i = 0; i < DIM; ++i) {
            for (unsigned col = 0; col < DIM; ++col) {
                const unsigned n = j * DIM + col;
                const int32_t actual = got[bank][j][i][col];
                const int32_t want = potal_multiwindow_golden[expected_step][i][n];
                if (actual != want) {
                    printf("FAIL ACC bank=%u step=%u row=%u col=%u want=%ld got=%ld\n",
                           bank, expected_step, i, n, (long)want, (long)actual);
                    exit(1);
                }
            }
        }
    }
    printf("PASS ACC bank %u step %u\n", bank, expected_step);
}

static void verify_double_main(void) {
    for (unsigned j = 0; j < JTILES; ++j) {
        for (unsigned i = 0; i < DIM; ++i) {
            for (unsigned col = 0; col < DIM; ++col) {
                const unsigned n = j * DIM + col;
                const int32_t actual = got[0][j][i][col];
                const int32_t want = 2 * potal_multiwindow_golden[0][i][n];
                if (actual != want) {
                    printf("FAIL Main replay bank=0 row=%u col=%u want=%ld got=%ld\n",
                           i, n, (long)want, (long)actual);
                    exit(1);
                }
            }
        }
    }
    printf("PASS Main A/B resident replay: ACC0 doubled\n");
}

static void init_matrices(void) {
    // gcd(7, KROWS) = 1 for DIM 16, 32, 64, hence all unique.
    // Non-contiguous and cross-original-K-tile selection.
    for (unsigned t = 0; t < TOTAL_ACTIVE_R; ++t)
        active_k[t] = (7 * t + 5) % KROWS;

    for (unsigned k = 0; k < KROWS; ++k) {
        for (unsigned n = 0; n < NCOLS; ++n) {
            int v = (int)((k * 37 + n * 13 + (k ^ n) * 7) % 15) - 7;
            if (v == 0) v = 1;
            b[k][n] = (int8_t)v;
        }
    }
    for (unsigned phase = 0; phase < PHASES; ++phase) {
        const unsigned usable = phase ? TOTAL_ACTIVE_R : KROWS;
        for (unsigned i = 0; i < DIM; ++i) {
            for (unsigned t = 0; t < KROWS; ++t) {
                int v = (int)((i * 11 + t * 5 + phase * 17) % 7) - 3;
                if (v == 0) v = phase ? 1 : -1;
                a[phase][i][t] = (int8_t)(t < usable ? v : 0);
            }
        }
    }
}

static void restore_main_a_dram(void) {
    for (unsigned i = 0; i < DIM; ++i) {
        for (unsigned t = 0; t < KROWS; ++t) {
            int v = (int)((i * 11 + t * 5) % 7) - 3;
            if (v == 0) v = -1;
            a[0][i][t] = (int8_t)v;
        }
    }
    asm volatile("fence rw,rw" ::: "memory");
}

static void poison_main_a_dram(void) {
    volatile int8_t *src = (volatile int8_t *)&a[0][0][0];
    for (unsigned t = 0; t < DIM * KROWS; ++t)
        src[t] = -31;
    asm volatile("fence rw,rw" ::: "memory");
}

static void restore_weight_dram(void) {
    for (unsigned k = 0; k < KROWS; ++k) {
        for (unsigned n = 0; n < NCOLS; ++n) {
            int v = (int)((k * 37 + n * 13 + (k ^ n) * 7) % 15) - 7;
            if (v == 0) v = 1;
            b[k][n] = (int8_t)v;
        }
    }
    asm volatile("fence rw,rw" ::: "memory");
}

static void run_pair(unsigned pair) {
    current_pair = pair;
    restore_main_a_dram();
    restore_weight_dram();
    configure_gemm();
    printf("PAIR %u A_bank=%u B_bank=%u B_base=%u Main_A_tiles=%u Residual_A_tiles=%u\n",
           pair, 2 * pair, 2 * pair + 1, B_BASE,
           MAIN_A_OFFSET_TILES, RES_A_OFFSET_TILES);

    scale_command(0, 0);
    issue_loop(0, 0, KROWS, 0, 0);
    fence_potal("Main");
    scale_command(0, 1);

    for (unsigned k = 0; k < KROWS; ++k)
        for (unsigned n = 0; n < NCOLS; ++n)
            ((volatile int8_t *)b)[k * NCOLS + n] = -19;
    asm volatile("fence rw,rw" ::: "memory");

    read_acc_banks();
    verify(0, 0);
    unsigned step = 1;
    for (unsigned phase = 1; phase <= 2; ++phase) {
        for (unsigned first = 0; first < TOTAL_ACTIVE_R; first += WINDOW_CAP) {
            unsigned rows = TOTAL_ACTIVE_R - first;
            if (rows > WINDOW_CAP) rows = WINDOW_CAP;
            configure_index_window(first, rows);
            scale_command(phase, 0);
            issue_loop(phase, first, rows, phase != 1 || first != 0, 0);
            fence_potal("Residual window");
            scale_command(phase, 1);
            read_acc_banks();
            verify(0, 0);
            verify(1, step++);
        }
    }
    if (step != 5) exit(1);

    // Main A has been preserved at offset 0 throughout the four L windows.
    // Force a fresh GEMM to ACC0, without reloading either A or B from DRAM.
    // Accumulation makes this distinguishable from an unchanged stale ACC0.
    poison_main_a_dram();
    scale_command(0, 0);
    issue_loop(0, 0, KROWS, 1, 1);
    fence_potal("Main replay, A/B DRAM disabled");
    scale_command(0, 1);
    read_acc_banks();
    verify_double_main();
    verify(1, 4);

    printf("PAIR %u CORESIDENT PASS\n", pair);
}

int main(void) {
    printf("POTAL_A_COHABIT start DIM=%d R=%d window=%d A_offset_tiles=%u\n",
           DIM, TOTAL_ACTIVE_R, WINDOW_CAP, RES_A_OFFSET_TILES);
    init_matrices();
    ROCC_INSTRUCTION_0_R_R(3, 0ULL, 0ULL, 7);
    fence_potal("initial");
    run_pair(0);
    run_pair(1);
    printf("POTAL_A_COHABIT PASS DIM=%d\n", DIM);
    return 0;
}

