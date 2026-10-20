
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <limits.h>

#include "include/gemmini_potal_params.h"
#include "rocc-software/src/xcustom.h"

#if DIM != 16 || ACC_ROWS != 1024
#error "Test requires PoTal DIM16 / two 512-row ACC banks"
#endif

#ifndef ACC_READ_FULL_WIDTH
#error "Full-width accumulator read is required"
#endif

#define ACC_FULL_ADDR (5u << (ADDR_LEN - 3))
#define ACC_BANK_ROWS (ACC_ROWS / 2)

static int32_t input[2][DIM][DIM]
    __attribute__((aligned(MAX_BYTES)));

static int32_t output[2][DIM][DIM]
    __attribute__((aligned(MAX_BYTES)));

static int32_t replacement[DIM][DIM]
    __attribute__((aligned(MAX_BYTES)));

static uint32_t acc_addr(unsigned bank) {
    return ACC_FULL_ADDR + bank * ACC_BANK_ROWS;
}

static uint64_t dma_desc(uint32_t addr) {
    return ((uint64_t)DIM << 48) |
           ((uint64_t)DIM << 32) |
           addr;
}

static void drain(const char *stage) {
    uint64_t status;
    ROCC_INSTRUCTION_R_R_R(3, status, 0ULL, 0ULL, 25);

    if (status != 0) {
        printf("FAIL %s status=%llu\n",
               stage, (unsigned long long)status);
        exit(1);
    }
    asm volatile("fence rw,rw" ::: "memory");
}

static void configure(void) {
    // CONFIG_LD state 0: 32-bit ACC input, 64-byte stride
    uint64_t ld = ((uint64_t)DIM << 16) |
                  (1ULL << 8) | 1ULL;

    ROCC_INSTRUCTION_0_R_R(
        3, ld, (uint64_t)(DIM * sizeof(int32_t)), 0);

    // CONFIG_EX: WS, no activation, stride 1
    ROCC_INSTRUCTION_0_R_R(
        3, (1ULL << 16) | (1ULL << 2), 1ULL << 48, 0);

    // CONFIG_ST: raw 32-bit output, 64-byte stride
    ROCC_INSTRUCTION_0_R_R(
        3, 2ULL, (uint64_t)(DIM * sizeof(int32_t)), 0);
}

static void mvin_acc(const int32_t *src, unsigned bank) {
    ROCC_INSTRUCTION_0_R_R(
        3,
        (uint64_t)(uintptr_t)src,
        dma_desc(acc_addr(bank)),
        2);
}

static void mvout_acc(int32_t *dst, unsigned bank) {
    ROCC_INSTRUCTION_0_R_R(
        3,
        (uint64_t)(uintptr_t)dst,
        dma_desc(acc_addr(bank)),
        3);
}

static void verify(unsigned bank, const int32_t *expected) {
    for (int i = 0; i < DIM; ++i) {
        for (int j = 0; j < DIM; ++j) {
            int idx = i * DIM + j;
            int32_t actual = output[bank][i][j];

            if (actual != expected[idx]) {
                printf("FAIL bank=%u row=%d col=%d "
                       "expected=%d actual=%d\n",
                       bank, i, j, expected[idx], actual);
                exit(1);
            }
        }
    }
    printf("PASS ACC bank %u INT32 round-trip\n", bank);
}

int main(void) {
    static const int32_t values[] = {
        0, 1, -1, INT32_MAX, INT32_MIN,
        0x12345678, -123456789, 0x40000000,
        -0x40000000, 127, -128, 32767, -32768
    };

    printf("POTAL_ACC_ROUNDTRIP starting\n");

    for (unsigned b = 0; b < 2; ++b) {
        for (int i = 0; i < DIM; ++i) {
            for (int j = 0; j < DIM; ++j) {
                unsigned index =
                    (b * 5 + i * 7 + j * 11) %
                    (sizeof(values) / sizeof(values[0]));
                input[b][i][j] = values[index];
                output[b][i][j] = 0x55555555;
                replacement[i][j] = ~input[0][i][j];
            }
        }
    }

    // Drop stale accelerator translations.
    ROCC_INSTRUCTION_0_R_R(3, 0ULL, 0ULL, 7);
    drain("startup flush");

    configure();
    asm volatile("fence rw,rw" ::: "memory");

    // Both ACC banks must hold independent INT32 data.
    mvin_acc(&input[0][0][0], 0);
    mvin_acc(&input[1][0][0], 1);
    drain("initial MVIN");

    mvout_acc(&output[0][0][0], 0);
    mvout_acc(&output[1][0][0], 1);
    drain("initial MVOUT");

    verify(0, &input[0][0][0]);
    verify(1, &input[1][0][0]);

    // Overwrite bank 0 only.
    // Bank 1 must retain its original data.
    mvin_acc(&replacement[0][0], 0);
    drain("bank 0 replacement");

    mvout_acc(&output[0][0][0], 0);
    mvout_acc(&output[1][0][0], 1);
    drain("bank isolation");

    verify(0, &replacement[0][0]);
    verify(1, &input[1][0][0]);

    printf("POTAL_ACC_ROUNDTRIP PASS\n");
    return 0;
}

