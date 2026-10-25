/* H3-5A: DIM16 Pair0 indexed resident-weight regression. No gemmini.h dependency. */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include "include/gemmini_potal_params.h"
#include "rocc-software/src/xcustom.h"

#define K 64
#define N 32
#define STRIDE 64
#define JTILES 2
#define B_BASE 8064
#define ACC0 (5u << (ADDR_LEN - 3))
#define ACC1 (ACC0 + ACC_ROWS / 2)
#if DIM != 16 || BANK_ROWS != 4096
#error "H3-5A requires DIM16, 4096 rows per SRAM bank"
#endif

static int8_t a[2][DIM][K] __attribute__((aligned(64)));
static int8_t b[K][STRIDE] __attribute__((aligned(64)));
static volatile int32_t got[2][JTILES][DIM][DIM] __attribute__((aligned(64)));
static int32_t golden[2][DIM][N];
static unsigned source[DIM];

static void check(const char *op, uint64_t status) {
    if (status) {
        printf("H3_5A FAIL %s status=%llu\n", op, (unsigned long long)status);
        exit(1);
    }
}

static void fence(void) {
    uint64_t status;
    ROCC_INSTRUCTION_R_R_R(3, status, 0ULL, 0ULL, 25);
    check("FENCE", status);
    asm volatile("fence rw,rw" ::: "memory");
}

static void scales(unsigned phase, int release) {
    for (unsigned j = 0; j < (phase ? 2 : 4); ++j) {
        for (unsigned col = 0; col < DIM; ++col) {
            uint64_t status;
            uint64_t key = ((uint64_t)col << (POTAL_SCALE_SLOT_BITS + 8)) |
                ((uint64_t)(1 + phase) << POTAL_SCALE_SLOT_BITS) |
                ((phase ? 4 : 0) + j);
            if (release) {
                ROCC_INSTRUCTION_R_R_R(3, status, key, 0ULL, 24);
            } else {
                ROCC_INSTRUCTION_R_R_R(3, status, key, 0ULL, 23);
            }
            check("SCALE", status);
        }
    }
}

static void configure(unsigned residual) {
    for (unsigned id = 0; id < 2; ++id) {
        uint64_t rs1 = ((uint64_t)DIM << 16) | (1ULL << 8) | (id << 3) | 1;
        ROCC_INSTRUCTION_0_R_R(3, rs1, (uint64_t)(residual && id == 1 ? 0 : STRIDE), 0);
    }
    ROCC_INSTRUCTION_0_R_R(3, (1ULL << 16) | (1ULL << 2), 1ULL << 48, 0);
    ROCC_INSTRUCTION_0_R_R(3, 2ULL, DIM * sizeof(int32_t), 0);
}

static void loop(unsigned residual) {
    unsigned passes = residual ? 1 : K / DIM;
    uint64_t bounds = ((uint64_t)passes << 32) | ((uint64_t)JTILES << 16) | 1;
    uint64_t flags = (1ULL << 61) |
        (residual ? (1ULL << 62) | (1ULL << 63) : 0ULL) |
        (1ULL << 1) | (1ULL << 20) | (1ULL << 18) | (1ULL << 16) |
        ((uint64_t)(residual ? 4 : 0) << 21) |
        ((uint64_t)(1 + residual) << 37) | ((uint64_t)(residual ? 1 : 2) << 45);
    uint64_t ap = (uint64_t)(uintptr_t)&a[residual][0][0];
    uint64_t bp = residual ? 0ULL : (uint64_t)(uintptr_t)&b[0][0];
    ROCC_INSTRUCTION_0_R_R(3, 0ULL, bounds, 9);
    ROCC_INSTRUCTION_0_R_R(3, ap, bp, 10);
    ROCC_INSTRUCTION_0_R_R(3, 0ULL, 0ULL, 11);
    ROCC_INSTRUCTION_0_R_R(3, (uint64_t)K, (uint64_t)(residual ? 0 : STRIDE), 12);
    ROCC_INSTRUCTION_0_R_R(3, 0ULL, 0ULL, 13);
    ROCC_INSTRUCTION_0_R_R(3, flags, residual ? (uint64_t)(K / DIM) << 3 : 0ULL, 8);
}

static void read_acc(unsigned bank) {
    ROCC_INSTRUCTION_0_R_R(3, 2ULL, DIM * sizeof(int32_t), 0);
    for (unsigned j = 0; j < JTILES; ++j) {
        uint64_t desc = ((uint64_t)DIM << 48) | ((uint64_t)DIM << 32) |
            ((bank ? ACC1 : ACC0) + j * DIM);
        ROCC_INSTRUCTION_0_R_R(3, (uint64_t)(uintptr_t)&got[bank][j][0][0], desc, 3);
    }
    fence();
}

static unsigned run(unsigned mapping, const char *name) {
    for (unsigned r = 0; r < DIM; ++r)
        source[r] = mapping == 2 ? 0 : mapping == 1 ? (r == 0 ? 11 : 0) : r;
    for (unsigned i = 0; i < DIM; ++i) {
        for (unsigned k = 0; k < K; ++k) {
            a[0][i][k] = k < DIM && i == k;
            a[1][i][k] = k < DIM && (mapping == 1 ? k == 0 : i == k);
        }
    }
    for (unsigned k = 0; k < K; ++k)
        for (unsigned n = 0; n < STRIDE; ++n)
            b[k][n] = (int8_t)(k + 1);
    /* Independent INT32 Golden: one-hot A selects exactly one logical B row.
     * No Scratchpad layout, Index lookup implementation, or observed RTL data is used.
     */
    for (unsigned i = 0; i < DIM; ++i) {
        for (unsigned n = 0; n < N; ++n) {
            golden[0][i][n] = (int32_t)b[i][n];
            golden[1][i][n] = (int32_t)b[source[mapping == 1 ? 0 : i]][n];
        }
    }
    printf("H3_5A BEGIN %s DIM=16 pair=0 mainK=64 residualK=16 N=32 stride=64 B_base=8064\n", name);
    asm volatile("fence rw,rw" ::: "memory");
    ROCC_INSTRUCTION_0_R_R(3, 0ULL, 0ULL, 7);
    fence();
    scales(0, 0);
    configure(0);
    loop(0);
    fence();
    read_acc(0);

    uint64_t status;
    ROCC_INSTRUCTION_R_R_R(3, status, ((uint64_t)JTILES << 32) | B_BASE,
        ((uint64_t)DIM << 32) | K, 27);
    check("INDEX_CONFIG", status);
    for (unsigned r = 0; r < DIM; ++r) {
        ROCC_INSTRUCTION_R_R_R(3, status, (uint64_t)r, (uint64_t)source[r], 26);
        check("INDEX_WRITE", status);
    }
    scales(1, 0);
    configure(1);
    loop(1);
    fence();
    read_acc(0);
    read_acc(1);
    scales(0, 1);
    scales(1, 1);
    unsigned total = 0;
    for (unsigned bank = 0; bank < 2; ++bank) {
        unsigned errors = 0;
        for (unsigned i = 0; i < DIM; ++i) {
            for (unsigned n = 0; n < N; ++n) {
                int32_t actual = got[bank][n / DIM][i][n % DIM];
                int32_t expected = golden[bank][i][n];
                if (actual != expected) {
                    if (errors < 8)
                        printf("H3_5A DIFF %s ACC%u i=%u n=%u J=%u sourceK=%u want=%ld got=%ld\n",
                            name, bank, i, n, n / DIM, source[i], (long)expected, (long)actual);
                    ++errors;
                }
            }
        }
        printf("H3_5A RESULT %s ACC%u errors=%u/512\n", name, bank, errors);
        total += errors;
    }
    return total;
}

int main(void) {
    unsigned errors = run(0, "K_TAG_B");
    errors += run(1, "SLOT0_REMAP11");
    errors += run(2, "ALL_SLOTS_K0");
    printf("H3_5A %s errors=%u\n", errors ? "FAIL" : "PASS", errors);
    return errors ? 1 : 0;
}
