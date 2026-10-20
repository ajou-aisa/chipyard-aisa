/*
 * PoTal DIM16 / WS / Sat32: resume a PoTal GEMM after raw INT32 ACC spill.
 *
 * LOOP_WS #0: no C DRAM store, leaves SCU-scaled result in ACC bank 0.
 *   -> raw INT32 MVOUT (spill) -> poison ACC0 -> raw INT32 MVIN (restore)
 * LOOP_WS #1: ex_accumulate=1, adds the second SCU result to restored ACC0.
 *
 * The old LoopMatmul advances its EX/STORE ACC half only when C DRAM
 * address is nonzero. Therefore LOOP_WS #0 MUST have C=0, and #1 uses
 * C!=0 for the final store. This exercises ACC0 only, not paired ACC routing.
 */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <limits.h>

#include "include/gemmini_potal_params.h"
#include "rocc-software/src/xcustom.h"

#if DIM != 16 || ACC_ROWS != 1024
#error "This probe is for PoTal DIM16 with two 512-row ACC banks"
#endif
#ifndef ACC_READ_FULL_WIDTH
#error "PoTal needs ACC_READ_FULL_WIDTH"
#endif

#define SLOT0 8u
#define SLOT1 9u
#define GEN0 7u
#define GEN1 8u
#define ZERO_CARRIER UINT32_C(0x80000000)
#define ACC0_FULL_ADDR (5u << (ADDR_LEN - 3))

static int8_t A[2][DIM][DIM] __attribute__((aligned(64)));
static int8_t B[2][DIM][DIM] __attribute__((aligned(64)));
static volatile int32_t spill[DIM][DIM] __attribute__((aligned(64)));
static int32_t poison[DIM][DIM] __attribute__((aligned(64)));
static volatile int32_t probe[DIM][DIM] __attribute__((aligned(64)));
static volatile int32_t C[DIM][DIM] __attribute__((aligned(64)));

static const uint32_t carrier[2][DIM] = {
    { 0, 1, 2, 3, 20, 27, 28, 30, 32, ZERO_CARRIER, 1, 24, 30, 0, 4, 2 },
    { 1, 0, 2, 20, 27, 30, 32, ZERO_CARRIER, 0, 1, 23, 28, 31, 2, 1, 0 },
};

static void die(const char *where, int row, int col, int32_t want, int32_t got) {
    printf("FAIL %s [%d,%d]: want=%ld got=%ld\n", where, row, col,
           (long)want, (long)got);
    exit(1);
}

static void fence_potal(const char *where) {
    uint64_t status = 0;
    ROCC_INSTRUCTION_R_R_R(3, status, 0ULL, 0ULL, 25);
    if (status != 0) {
        printf("FAIL %s POTAL_FENCE status=%llu\n", where,
               (unsigned long long)status);
        exit(1);
    }
    asm volatile("fence rw,rw" ::: "memory");
}

static void config_ld(unsigned state, unsigned bytes_per_row) {
    const uint64_t rs1 = ((uint64_t)DIM << 16) | (1ULL << 8) |
                         ((uint64_t)state << 3) | 1ULL;
    ROCC_INSTRUCTION_0_R_R(3, rs1, (uint64_t)bytes_per_row, 0);
}

static void config_gemm(void) {
    config_ld(0, DIM * sizeof(int8_t));
    config_ld(1, DIM * sizeof(int8_t));
    ROCC_INSTRUCTION_0_R_R(3, (1ULL << 16) | (1ULL << 2), 1ULL << 48, 0);
    ROCC_INSTRUCTION_0_R_R(3, 2ULL, DIM * sizeof(int32_t), 0);
}

static void mvin_acc0(const volatile int32_t *src) {
    const uint64_t rs2 = ((uint64_t)DIM << 48) | ((uint64_t)DIM << 32) |
                         ACC0_FULL_ADDR;
    ROCC_INSTRUCTION_0_R_R(3, (uint64_t)(uintptr_t)src, rs2, 2);
}

static void mvout_acc0(volatile int32_t *dst) {
    const uint64_t rs2 = ((uint64_t)DIM << 48) | ((uint64_t)DIM << 32) |
                         ACC0_FULL_ADDR;
    ROCC_INSTRUCTION_0_R_R(3, (uint64_t)(uintptr_t)dst, rs2, 3);
}

static uint64_t scale_key(int phase, int col) {
    const uint64_t slot = phase == 0 ? SLOT0 : SLOT1;
    const uint64_t gen = phase == 0 ? GEN0 : GEN1;
    return ((uint64_t)col << 16) | (gen << 8) | slot;
}

static void set_scales(int phase, int release) {
    for (int col = 0; col < DIM; ++col) {
        uint64_t status = 0;
        if (release) {
            ROCC_INSTRUCTION_R_R_R(3, status, scale_key(phase, col), 0ULL, 24);
        } else {
            ROCC_INSTRUCTION_R_R_R(3, status, scale_key(phase, col),
                                    (uint64_t)carrier[phase][col], 23);
        }
        if (status != 0) {
            printf("FAIL scale phase=%d release=%d col=%d status=%llu\n",
                   phase, release, col, (unsigned long long)status);
            exit(1);
        }
    }
}

static void issue_loop(int phase, int accumulate, volatile int32_t *c_addr) {
    const uint64_t bounds = (1ULL << 32) | (1ULL << 16) | 1ULL;
    const uint64_t slot = phase == 0 ? SLOT0 : SLOT1;
    const uint64_t gen = phase == 0 ? GEN0 : GEN1;
    const uint64_t flags = (accumulate ? 1ULL : 0ULL) | (1ULL << 1) |
                           (1ULL << 20) | (slot << 21) | (gen << 37) |
                           (1ULL << 45); // passesPerBlock = 1 (K=16)

    ROCC_INSTRUCTION_0_R_R(3, 0ULL, bounds, 9);
    ROCC_INSTRUCTION_0_R_R(3,
        (uint64_t)(uintptr_t)&A[phase][0][0],
        (uint64_t)(uintptr_t)&B[phase][0][0], 10);
    ROCC_INSTRUCTION_0_R_R(3, 0ULL, (uint64_t)(uintptr_t)c_addr, 11);
    ROCC_INSTRUCTION_0_R_R(3, DIM, DIM, 12);  // strides in int8 elements
    ROCC_INSTRUCTION_0_R_R(3, 0ULL, DIM, 13); // C stride in int32 elements
    ROCC_INSTRUCTION_0_R_R(3, flags, 0ULL, 8);
}

static int32_t sat32(int64_t v) {
    return v > INT32_MAX ? INT32_MAX : v < INT32_MIN ? INT32_MIN : (int32_t)v;
}

static int32_t scu(int32_t raw, uint32_t shift) {
    if (raw == 0 || shift == ZERO_CARRIER) return 0;
    if (shift >= 32) return raw > 0 ? INT32_MAX : INT32_MIN;
    return sat32((int64_t)raw * (INT64_C(1) << shift));
}

static int32_t partial(int phase, int r, int c) {
    int32_t sum = 0;
    for (int k = 0; k < DIM; ++k)
        sum += (int32_t)A[phase][r][k] * (int32_t)B[phase][k][c];
    return sum;
}

static void check_matrix(const char *name,
                         const volatile int32_t data[DIM][DIM], int which) {
    for (int r = 0; r < DIM; ++r) {
        for (int c = 0; c < DIM; ++c) {
            int32_t want;
            if (which == 0) want = scu(partial(0, r, c), carrier[0][c]);
            else if (which == 1) want = poison[r][c];
            else if (which == 2) want = spill[r][c];
            else {
                const int32_t x = scu(partial(0, r, c), carrier[0][c]);
                const int32_t y = scu(partial(1, r, c), carrier[1][c]);
                want = sat32((int64_t)x + y);
            }
            if (data[r][c] != want) die(name, r, c, want, data[r][c]);
        }
    }
    printf("PASS %s\n", name);
}

int main(void) {
    printf("POTAL_ACC_RESUME starting DIM=%d\n", DIM);
    for (int r = 0; r < DIM; ++r) {
        const int sign0 = (r & 1) ? -1 : 1;
        for (int k = 0; k < DIM; ++k) {
            A[0][r][k] = (int8_t)(sign0 * (1 + ((r + k) % 3)));
            A[1][r][k] = (int8_t)(-sign0 * (1 + ((2*r + k) % 4)));
        }
        for (int c = 0; c < DIM; ++c) {
            B[0][r][c] = (int8_t)(1 + ((r + c) % 4));
            B[1][r][c] = (int8_t)(1 + ((3*r + c) % 5));
            poison[r][c] = (int32_t)(UINT32_C(0x13579bdf) ^ (uint32_t)(r << 8) ^ (uint32_t)c);
            spill[r][c] = 0;
            probe[r][c] = 0;
            C[r][c] = -999999;
        }
    }

    // No GEMM is active. Flush stale accelerator TLB translations.
    ROCC_INSTRUCTION_0_R_R(3, 0ULL, 0ULL, 7);
    fence_potal("startup FLUSH");
    asm volatile("fence rw,rw" ::: "memory");

    config_gemm();
    set_scales(0, 0);
    // Without a DRAM C output, the legacy LoopMatmul EX ACC base remains 0.
    issue_loop(0, 0, (volatile int32_t *)0);
    fence_potal("first LOOP_WS");
    set_scales(0, 1);

    mvout_acc0(&spill[0][0]);
    fence_potal("spill MVOUT");
    check_matrix("SCU partial retained in ACC0", spill, 0);

    // Deliberately destroy ACC0. Verify that restore, not mere residency,
    // provides the value needed by the second GEMM.
    config_ld(0, DIM * sizeof(int32_t));
    mvin_acc0(&poison[0][0]);
    fence_potal("poison MVIN");
    mvout_acc0(&probe[0][0]);
    fence_potal("poison MVOUT");
    check_matrix("ACC0 overwritten by poison", probe, 1);

    mvin_acc0(&spill[0][0]);
    fence_potal("restore MVIN");
    mvout_acc0(&probe[0][0]);
    fence_potal("restore MVOUT");
    check_matrix("ACC0 restored bitwise", probe, 2);

    // Restore INT8 GEMM load stride (the preceding MVIN used INT32 stride).
    config_gemm();
    set_scales(1, 0);
    issue_loop(1, 1, &C[0][0]); // ex_accumulate=1; final C store
    fence_potal("second LOOP_WS");
    set_scales(1, 1);
    check_matrix("SCU + raw-restored ACC Sat32", C, 3);
    printf("POTAL_ACC_RESUME PASS\n");
    return 0;
}

