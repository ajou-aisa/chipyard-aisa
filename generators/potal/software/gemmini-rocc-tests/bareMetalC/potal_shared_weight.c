/*
 * PoTal DIM16 / WS / Sat32 - paired ACC path routing smoke test.
 * Requires experimental LOOP_WS rs1[61] = paired ACC mode,
 * rs1[62] = residual path, implemented in LoopMatmul.scala.
 *
 * Phase 0: Main GEMM -> ACC bank 0, C DRAM store disabled.
 * Phase 1: Residual GEMM -> ACC bank 1, C DRAM store disabled.
 * Both banks must retain their own exact SCU-scaled INT32 outputs.
 * Spill bank 1, overwrite it, restore, then:
 * Phase 2: Residual GEMM -> ACC bank 1, ex_accumulate=1.
 * Bank 1 must equal Sat32(previous + new SCU result), and bank 0
 * must remain unchanged.
 *
 * This is a BANK ROUTING / ACC CONTINUATION test. It does NOT claim
 * shared scratchpad weight residency or 1:1 paired micro-tile scheduling.
 */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <limits.h>

#include "include/gemmini_potal_params.h"
#include "rocc-software/src/xcustom.h"

#if DIM != 16 || ACC_ROWS != 1024
#error "Requires PoTal DIM16 with 2 x 512-row ACC banks"
#endif
#ifndef ACC_READ_FULL_WIDTH
#error "Requires ACC_READ_FULL_WIDTH"
#endif

#define NPHASE 3
#define PAIRED_ACC_FLAG      (UINT64_C(1) << 61)
#define PAIRED_RESIDUAL_FLAG (UINT64_C(1) << 62)
#define ZERO_CARRIER        UINT32_C(0x80000000)
#define ACC0_FULL_ADDR      (5u << (ADDR_LEN - 3))
#define ACC1_FULL_ADDR      (ACC0_FULL_ADDR + ACC_ROWS / 2)

static int8_t a[NPHASE][DIM][DIM] __attribute__((aligned(64)));
static int8_t b[NPHASE][DIM][DIM] __attribute__((aligned(64)));
static int8_t b_ref[DIM][DIM] __attribute__((aligned(64)));
static volatile int32_t got[2][DIM][DIM] __attribute__((aligned(64)));
static volatile int32_t saved[DIM][DIM] __attribute__((aligned(64)));
static int32_t poison[DIM][DIM] __attribute__((aligned(64)));

static const uint32_t shifts[NPHASE][DIM] = {
    {0,1,2,3,20,27,28,30,32,ZERO_CARRIER,1,24,30,0,4,2},
    {1,0,2,20,27,30,32,ZERO_CARRIER,0,1,23,28,31,2,1,0},
    {2,1,0,27,28,30,ZERO_CARRIER,32,1,0,20,29,31,3,1,0}
};

static void die(const char *what, int bank, int row, int col,
                int32_t expected, int32_t actual) {
    printf("FAIL %s bank=%d row=%d col=%d want=%ld got=%ld\n",
           what, bank, row, col, (long)expected, (long)actual);
    exit(1);
}

static void fence_potal(const char *what) {
    uint64_t response;
    ROCC_INSTRUCTION_R_R_R(3, response, 0ULL, 0ULL, 25);
    if (response != 0) {
        printf("FAIL %s fence status=%llu\n", what, (unsigned long long)response);
        exit(1);
    }
    asm volatile("fence rw,rw" ::: "memory");
}

static void config_ld(unsigned id, unsigned row_bytes) {
    uint64_t rs1 = ((uint64_t)DIM << 16) | (1ULL << 8) |
                   ((uint64_t)id << 3) | 1ULL;
    ROCC_INSTRUCTION_0_R_R(3, rs1, row_bytes, 0);
}

static void configure_gemm(void) {
    config_ld(0, DIM); /* A: int8 row stride */
    config_ld(1, DIM); /* B: int8 row stride */
    ROCC_INSTRUCTION_0_R_R(3, (1ULL << 16) | (1ULL << 2), 1ULL << 48, 0);
    ROCC_INSTRUCTION_0_R_R(3, 2ULL, DIM * sizeof(int32_t), 0);
}

static uint64_t acc_desc(unsigned bank) {
    uint32_t address = bank == 0 ? ACC0_FULL_ADDR : ACC1_FULL_ADDR;
    return ((uint64_t)DIM << 48) | ((uint64_t)DIM << 32) | address;
}

static void mvout(unsigned bank, volatile int32_t *dst) {
    ROCC_INSTRUCTION_0_R_R(3, (uint64_t)(uintptr_t)dst, acc_desc(bank), 3);
}

static void mvin(unsigned bank, const volatile int32_t *src) {
    ROCC_INSTRUCTION_0_R_R(3, (uint64_t)(uintptr_t)src, acc_desc(bank), 2);
}

static uint64_t key(unsigned phase, unsigned col) {
    const uint64_t slot = 8 + phase;
    const uint64_t generation = 7 + phase;

    return ((uint64_t)col
                << (POTAL_SCALE_SLOT_BITS + 8)) |
           (generation << POTAL_SCALE_SLOT_BITS) |
           slot;
}

static void scale_command(unsigned phase, int release) {
    for (unsigned col = 0; col < DIM; ++col) {
        uint64_t response;
        if (release) {
            ROCC_INSTRUCTION_R_R_R(3, response, key(phase, col), 0ULL, 24);
        } else {
            ROCC_INSTRUCTION_R_R_R(3, response, key(phase, col), (uint64_t)shifts[phase][col], 23);
        } if (response != 0) {
            printf("FAIL scale phase=%u release=%d col=%u status=%llu\n",
                   phase, release, col, (unsigned long long)response);
            exit(1);
        }
    }
}

static void issue_loop(unsigned phase, int accumulate) {
    /* One 16x16x16 GEMM, full 32-bit ACC output retained on device. */
    const uint64_t bounds = (1ULL << 32) | (1ULL << 16) | 1ULL;
    const uint64_t paired = PAIRED_ACC_FLAG |
            (phase == 0 ? 0ULL : PAIRED_RESIDUAL_FLAG);
    const uint64_t shared_spad =
        (1ULL << 18) |  /* A SP region 1: lower half */
        (2ULL << 16);   /* B SP region 2: upper half */

    const uint64_t flags = paired | shared_spad |
            (accumulate ? 1ULL : 0ULL) |
            (1ULL << 1) | (1ULL << 20) |
            ((uint64_t)(8 + phase) << 21) |
            ((uint64_t)(7 + phase) << 37) |
            (1ULL << 45); /* passesPerBlock=1 */
    ROCC_INSTRUCTION_0_R_R(3, 0ULL, bounds, 9);

    uint64_t b_dram_addr =
        (phase == 0)
        ? (uint64_t)(uintptr_t)b[0]
        : 0ULL;
    ROCC_INSTRUCTION_0_R_R(3, (uint64_t)(uintptr_t)a[phase], b_dram_addr, 10);
    ROCC_INSTRUCTION_0_R_R(3, 0ULL, 0ULL, 11); /* no D, no C store */
    ROCC_INSTRUCTION_0_R_R(3, DIM, DIM, 12);
    ROCC_INSTRUCTION_0_R_R(3, 0ULL, DIM, 13);
    ROCC_INSTRUCTION_0_R_R(3, flags, 0ULL, 8);
}

static int32_t sat32(int64_t x) {
    return x > INT32_MAX ? INT32_MAX : x < INT32_MIN ? INT32_MIN : (int32_t)x;
}

static int32_t scu(int32_t raw, uint32_t shift) {
    if (raw == 0 || shift == ZERO_CARRIER) return 0;
    if (shift >= 32) return raw > 0 ? INT32_MAX : INT32_MIN;
    return sat32((int64_t)raw * (INT64_C(1) << shift));
}

static int32_t contribution(unsigned phase, int row, int col) {
    int32_t dot = 0;
    for (int k = 0; k < DIM; ++k)
        dot += (int32_t)a[phase][row][k] * b_ref[k][col];
    return scu(dot, shifts[phase][col]);
}

static void verify(unsigned bank, int phase) {
    for (int row = 0; row < DIM; ++row) {
        for (int col = 0; col < DIM; ++col) {
            int32_t expected = phase == 3
                ? sat32((int64_t)contribution(1, row, col) + contribution(2, row, col))
                : contribution((unsigned)phase, row, col);
            int32_t actual = got[bank][row][col];
            if (actual != expected)
                die("INT32 ACC", bank, row, col, expected, actual);
        }
    }
    printf("PASS ACC bank %u phase %d\n", bank, phase);
}

int main(void) {
    printf("POTAL_SHARED_WEIGHT starting DIM=%d\n", DIM);
    for (unsigned phase = 0; phase < NPHASE; ++phase) {
        for (int row = 0; row < DIM; ++row) {
            int sign = ((row + (int)phase) & 1) ? -1 : 1;
            for (int k = 0; k < DIM; ++k)
                a[phase][row][k] = (int8_t)(sign * (1 + ((row * 3 + k + (int)phase) % 5)));
            for (int col = 0; col < DIM; ++col)
                b[phase][row][col] = (int8_t)(1 + ((row * 2 + col + (int)phase * 5) % 7));
        }
    }
    for (int row = 0; row < DIM; ++row)
        for (int col = 0; col < DIM; ++col) {
            poison[row][col] = (int32_t)(UINT32_C(0x87654321) ^
                                   ((uint32_t)row << 8) ^ (uint32_t)col);
            saved[row][col] = 0;
            got[0][row][col] = -999;
            got[1][row][col] = -999;
        }

    /* Save the original Main weight for CPU reference. */
    for (int k = 0; k < DIM; ++k) {
        for (int n = 0; n < DIM; ++n) {
            b_ref[k][n] = b[0][k][n];
        }
    }

    ROCC_INSTRUCTION_0_R_R(3, 0ULL, 0ULL, 7); /* TLB flush */
    fence_potal("initial FLUSH");
    configure_gemm();
    asm volatile("fence rw,rw" ::: "memory");

    scale_command(0, 0);
    issue_loop(0, 0); /* Main, ACC0 */
    fence_potal("Main GEMM");
    scale_command(0, 1);

    /*
     * Poison the original B in DRAM.
     * Residual must use the previously loaded Scratchpad B.
     */
    for (int k = 0; k < DIM; ++k) {
        for (int n = 0; n < DIM; ++n) {
            ((volatile int8_t *)b[0])[k * DIM + n] = -19;
       }
    }

    asm volatile("fence rw,rw" ::: "memory");

    printf("H2-0: Main B retained in SP, DRAM B poisoned\n");

    scale_command(1, 0);
    issue_loop(1, 0); /* Residual, ACC1 */
    fence_potal("Residual GEMM");
    scale_command(1, 1);

    mvout(0, &got[0][0][0]);
    mvout(1, &got[1][0][0]);
    fence_potal("both ACC outputs");
    verify(0, 0);
    verify(1, 1);

    /* Destroy ACC1, then restore its preceding SCU partial. */
    mvout(1, &saved[0][0]);
    fence_potal("ACC1 spill");
    config_ld(0, DIM * sizeof(int32_t));
    mvin(1, &poison[0][0]);
    fence_potal("ACC1 poison");
    mvin(1, &saved[0][0]);
    fence_potal("ACC1 restore");
    configure_gemm();

    scale_command(2, 0);
    issue_loop(2, 1); /* Residual, ACC1 += SCU(second residual) */
    fence_potal("Residual ACC1 resume");
    scale_command(2, 1);

    mvout(0, &got[0][0][0]);
    mvout(1, &got[1][0][0]);
    fence_potal("final ACC outputs");
    verify(0, 0); /* Main partial must remain untouched */
    verify(1, 3); /* Residual partial must continue at ACC1 */
    printf("POTAL_SHARED_WEIGHT PASS\n");
    return 0;
}

