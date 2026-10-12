# Gemmini 하드웨어 분석 보고서

> 작성일: 2026년 4월 29일  
> 대상: Gemmini Accelerator (Chipyard 프로젝트 내)  
> 분석 범위: MVIN/MVOUT 연산 및 Optional Scaling 상세 분석

---

## 1. 개요

Gemmini는 RISC-V ISA 확장(RoCC - Rocket Custom Co-processor)으로 구현된 행렬 연산 가속기입니다. 핵심 데이터 이동 연산인 **mvin**(matrix input)과 **mvout**(matrix output)은 DRAM과 스크래치패드 메모리 간 데이터 전송을 담당합니다.

또한, Gemmini는 데이터 이동 과정에서 **선택적 스케일링(Optional Scaling)** 기능을 제공하여 양자화(quantization) 및 데이터 정규화를 하드웨어 수준에서 지원합니다.

---

## 2. 하드웨어 아키텍처 구조

### 2.1 주요 모듈 구성

```
/home/alveo/firesim/target-design/chipyard/generators/gemmini/src/main/scala/gemmini/cont
```

위 파일은 Gemmini의 핵심 하드웨어 모듈을 포함하고 있으며, 다음과 같은 주요 컴포넌트로 구성됩니다:

| 모듈 | 기능 |
|------|------|
| **GemminiModule** | 최상위 모듈, 모든 컴포넌트 연결 |
| **LoadController** | mvin 연산 처리 (DRAM → 스크래치패드) |
| **StoreController** | mvout 연산 처리 (스크래치패드 → DRAM) |
| **ExecuteController** | 행렬 연산 실행 제어 |
| **Scratchpad (spad)** | 온칩 메모리 저장소 |
| **ReservationStation** | 명령어 발행 및 완료 관리 |
| **FrontendTLB** | 주소 변환 (가상주소 → 물리주소) |

### 2.2 데이터 흐름도

```
[DRAM] ←→ [TLB] ←→ [DMA] ←→ [LoadController/StoreController] ←→ [Scratchpad]
                ↓
        [ReservationStation] ←→ [ExecuteController]
```

## 3. MVIN 연산 상세 분석

### 3.1 명령어 포맷

```scala
// ISA.py에서 정의된 MVIN 명령어 구조
funct = 1, 2, 또는 14 (mvin2, mvin1, mvin3)
rs1 = DRAM 주소 (64비트)
rs2 = [num_row(16비트) | num_col(16비트) | spad_addr(32비트)]
```

### 3.2 하드웨어 동작 흐름

**단계 1: 명령어 발행**
- CPU가 RoCC 명령어로 mvin 발행
- `GemminiModule`의 `raw_cmd_q` 큐에 수신
- `LoopConv` 또는 `LoopMatmul`에서 명령어 언롤링

**단계 2: 예약 스테이션 할당**
```scala
reservation_station.io.alloc.valid := true.B
reservation_station.io.alloc.bits := unrolled_cmd.bits
```

**단계 3: LoadController 실행**
```scala
// cont 파일의 216번 라인
val load_controller = withClock (gated_clock) { 
  Module(new LoadController(outer.config, coreMaxAddrBits, local_addr_t)) 
}

// DMA 연결
spad.module.io.dma.read <> load_controller.io.dma
```

**단계 4: DMA 읽기 동작**
- LoadController가 DMA read 요청 생성
- TLB를 통해 가상주소 → 물리주소 변환
- DRAM에서 데이터 읽기
- Scratchpad의 지정된 주소에 쓰기

### 3.3 소프트웨어 인터페이스

```c
// gemmini.h의 mvin 매크로 정의
#define gemmini_mvin(dram_addr, spad_addr) \
  gemmini_extended_mvin(dram_addr, spad_addr, DIM, DIM)

// 확장 버전 (행/열 크기 지정 가능)
#define gemmini_extended_mvin(dram_addr, spad_addr, cols, rows) \
  ROCC_INSTRUCTION_RS1_RS2(XCUSTOM_ACC, dram_addr, \
    ((uint64_t)(rows) << (ADDR_LEN + 16)) | \
    ((uint64_t)(cols) << ADDR_LEN) | \
    (uint64_t)(spad_addr), k_MVIN)
```

## 4. MVOUT 연산 상세 분석

### 4.1 명령어 포맷

```scala
// ISA.py에서 정의된 MVOUT 명령어 구조
funct = 3
rs1 = DRAM 주소 (64비트)
rs2 = [num_row(16비트) | num_col(16비트) | spad_addr(32비트)]
```

### 4.2 하드웨어 동작 흐름

**단계 1: 명령어 발행**
- CPU가 RoCC 명령어로 mvout 발행
- funct = 3으로 식별

**단계 2: StoreController 실행**
```scala
// cont 파일의 217번 라인
val store_controller = withClock (gated_clock) { 
  Module(new StoreController(outer.config, coreMaxAddrBits, local_addr_t)) 
}

// DMA 연결
spad.module.io.dma.write <> store_controller.io.dma
```

**단계 3: DMA 쓰기 동작**
- StoreController가 Scratchpad에서 데이터 읽기
- DMA write 요청 생성
- TLB를 통해 주소 변환
- DRAM에 데이터 쓰기

### 4.3 소프트웨어 인터페이스

```c
// gemmini.h의 mvout 매크로 정의
#define gemmini_mvout(dram_addr, spad_addr) \
  gemmini_extended_mvout(dram_addr, spad_addr, DIM, DIM)

// 확장 버전
#define gemmini_extended_mvout(dram_addr, spad_addr, cols, rows) \
  ROCC_INSTRUCTION_RS1_RS2(XCUSTOM_ACC, dram_addr, \
    ((uint64_t)(rows) << (ADDR_LEN + 16)) | \
    ((uint64_t)(cols) << ADDR_LEN) | \
    (uint64_t)(spad_addr), k_MVOUT)
```

## 5. 컨트롤러 연결 및 제어 신호

### 5.1 ReservationStation 연결

```scala
// LoadController 연결 (286-289 라인)
load_controller.io.cmd.valid := reservation_station.io.issue.ld.valid
reservation_station.io.issue.ld.ready := load_controller.io.cmd.ready
load_controller.io.cmd.bits := reservation_station.io.issue.ld.cmd
load_controller.io.cmd.bits.rob_id.push(reservation_station.io.issue.ld.rob_id)

// StoreController 연결 (291-295 라인)
store_controller.io.cmd.valid := reservation_station.io.issue.st.valid
reservation_station.io.issue.st.ready := store_controller.io.cmd.ready
store_controller.io.cmd.bits := reservation_station.io.issue.st.cmd
store_controller.io.cmd.bits.rob_id.push(reservation_station.io.issue.st.rob_id)
```

### 5.2 완료 신호 아비트레이션

```scala
// 373-388 라인: 3-way 아비터
val reservation_station_completed_arb = Module(new Arbiter(UInt(log2Up(reservation_station_entries).W), 3))

reservation_station_completed_arb.io.in(0) // ExecuteController
reservation_station_completed_arb.io.in(1) // LoadController
reservation_station_completed_arb.io.in(2) // StoreController
```

## 6. 메모리 구조 및 DMA

### 6.1 스크래치패드 구조

- **sp_banks**: 일반 행렬 데이터 저장
- **acc_banks**: 누적기(accumulator) 데이터 저장
- **acc_sub_banks**: 누적기 서브뱅크

### 6.2 DMA 인터페이스

```scala
// DMA 연결 구조
spad.module.io.dma.read  <> load_controller.io.dma   // MVIN 경로
spad.module.io.dma.write <> store_controller.io.dma  // MVOUT 경로
```

### 6.3 TLB (Translation Lookaside Buffer)

```scala
// 72-74 라인: TLB 설정
val tlb = Module(new FrontendTLB(2, tlb_size, dma_maxbytes, ...))
(tlb.io.clients zip outer.spad.module.io.tlb).foreach(t => t._1 <> t._2)
```

## 7. 성능 카운터 및 프로파일링

하드웨어는 다음과 같은 성능 이벤트를 모니터링합니다:

```scala
// Load/Store/Execute 컨트롤러별 사이클 카운터
counters.io.event_io.connectEventSignal(CounterEvent.MAIN_LD_CYCLES, incr_ld_cycles)
counters.io.event_io.connectEventSignal(CounterEvent.MAIN_ST_CYCLES, incr_st_cycles)
counters.io.event_io.connectEventSignal(CounterEvent.MAIN_EX_CYCLES, incr_ex_cycles)
```

## 8. 관련 파일 경로

| 파일 | 설명 |
|------|------|
| `/home/alveo/firesim/target-design/chipyard/generators/gemmini/src/main/scala/gemmini/cont` | 메인 하드웨어 모듈 |
| `/home/alveo/firesim/target-design/chipyard/generators/gemmini/software/gemmini-rocc-tests/include/gemmini.h` | 소프트웨어 인터페이스 |
| `/home/alveo/firesim/target-design/chipyard/generators/gemmini/ISA.py` | ISA 디코딩 및 명령어 정의 |
| `/home/alveo/firesim/target-design/chipyard/generators/gemmini/software/gemmini-rocc-tests/bareMetalC/mvin_mvout.c` | MVIN/MVOUT 테스트 코드 |

## 9. 요약

| 연산 | 방향 | 컨트롤러 | DMA 채널 |
|------|------|----------|----------|
| **mvin** | DRAM → Scratchpad | LoadController | read |
| **mvout** | Scratchpad → DRAM | StoreController | write |

mvin과 mvout은 Gemmini의 핵심 데이터 이동 연산으로, RoCC 명령어 인터페이스를 통해 CPU에서 발행되고, ReservationStation을 통해 스케줄링되며, 각각 LoadController와 StoreController에 의해 DMA를 통해 실행됩니다. TLB는 주소 변환을 담당하며, Scratchpad는 온칩 메모리로 데이터를 임시 저장합니다.

---

# Gemmini Scratchpad, Systolic Array, Accumulator 상세 동작 분석

> 작성일: 2026-05-02  
> 대상: Chipyard Gemmini Accelerator (RISC-V RoCC)

---

## 1. 개요 및 아키텍처

Gemmini는 RISC-V RoCC(Rocket Custom Coprocessor) 명령어를 통해 제어되는 행렬 가속기입니다. 핵심 구성요소는 **Scratchpad**, **Systolic Array(Mesh)**, **Accumulator** 세 가지이며, 이들은 Load Controller → Execute Controller → Store Controller 파이프라인을 통해 동작합니다.

### Hardware Top-Level 구조 (`src/main/scala/gemmini/cont`)

- **GemminiModule**: RoCC 명령어를 수신하고 `ReservationStation`(ROB)에 배치
- **LoadController**: DRAM → Scratchpad/Accumulator로 데이터 이동 (DMA)
- **ExecuteController**: Scratchpad/Accumulator에서 데이터를 읽어 Systolic Array로 계산
- **StoreController**: Scratchpad/Accumulator → DRAM으로 결과 저장
- **Scratchpad**: Banked on-chip SRAM
- **Mesh (Systolic Array)**: `Mesh`/`MeshWithDelays` + `PE` 그리드
- **Accumulator**: `AccumulatorMem` + `AccumulatorScale` 경로

---

## 2. Scratchpad 동작

### 2.1 주소 체계

Scratchpad는 **row-addressed** 방식입니다. 각 주소는 하나의 행(row)을 의미하며, `DIM`개의 `elem_t` 요소를 포함합니다.

**주소 인코딩 (32-bit):**
```
[31]     : accumulator 여부 (1 = accumulator, 0 = scratchpad)
[30]     : accumulate 여부 (1 = +=, 0 = overwrite)
[29:26]  : norm command (mvout 시 사용)
[25:0]   : base row address
```

### 2.2 소프트웨어 인터페이스

```c
// DRAM -> Scratchpad
gemmini_extended_mvin(dram_addr, spad_addr, cols, rows);

// Scratchpad -> DRAM  
gemmini_extended_mvout(dram_addr, spad_addr, cols, rows);
```

### 2.3 낵부 동작 (`gemmini.cc` 기준)

```cpp
// mvin: DRAM에서 scratchpad 또는 accumulator로 데이터 이동
void gemmini_t::mvin(reg_t dram_addr, reg_t sp_addr, int state_id) {
  bool accumulator = (sp_addr >> 31) & 0x1;
  bool accumulate  = (sp_addr >> 30) & 0x1;
  auto base_row_addr = (sp_addr & 0x1FFFFFFF);
  
  if (accumulator) {
    // accumulator에 저장 (acc_t 타입)
    if (accumulate) {
      gemmini_state.accumulator[spad_row][spad_col] += value;
    } else {
      gemmini_state.accumulator[spad_row][spad_col] = value;
    }
  } else {
    // scratchpad에 저장 (elem_t 타입)
    gemmini_state.spad[spad_row][spad_col] = value;
  }
}
```

**특징:**
- Scratchpad는 `std::vector<std::vector<elem_t>>`로 모델링
- 크기: `BANK_NUM * BANK_ROWS`
- 행렬은 `DIM`행 단위로 할당

---

## 3. Systolic Array 동작

### 3.1 구조

- **PE (Processing Element)**: `DIM x DIM` 그리드
- **pe_state**: 각 PE의 낵부 상태 (누적값 또는 weight 저장)
  - **OS 모드**: 누적 결과 저장
  - **WS 모드**: weight matrix 저장

### 3.2 두 가지 Dataflow 모드

#### Output Stationary (OS)

**목적:** 출력 행렬의 각 요소를 고정된 PE에 누적

**동작:**
1. `compute` 명령어 시 A와 B 모두 scratchpad에서 읽어옴
2. `pe_state[i][j] += A[i][k] * B[k][j]`
3. K 차원에 대해 순차적으로 누적

**결과 저장:** `pe_state`의 값을 accumulator 또는 scratchpad로 write

#### Weight Stationary (WS)

**목적:** Weight matrix를 PE에 preload하여 재사용

**동작:**
1. `preload` 명령어로 B(weight)를 `pe_state`에 로드
2. `compute` 시 A만 scratchpad에서 읽어오고, `pe_state`의 weight와 곱셈
3. `results[i][j] += A[i][k] * pe_state[k][j]`

**결과 저장:** 별도의 `results` 배열에 저장 후 출력

### 3.3 소프트웨어 제어

```c
// WS 모드 설정
gemmini_config_ex(WEIGHT_STATIONARY, NO_ACTIVATION, 0);

// B를 PE에 preload, C에 결과 저장할 주소 설정
gemmini_preload(B_sp_addr, C_sp_addr);

// A와 preload된 B로 계산
gemmini_compute_preloaded(A_sp_addr, GARBAGE_ADDR);
```

---

## 4. Accumulator 동작

### 4.1 역할

Accumulator는 Systolic Array의 출력을 **넓은 비폭(accumulation width)**으로 누적하는 메모리입니다. `acc_t` 타입을 사용하며, 정수/부동소수점 설정에 따라 `elem_t`보다 큰 비폭을 가집니다.

### 4.2 주요 특징

| 특징 | 설명 |
|------|------|
| 누적 모드 | `acc_accum` 비트가 1이면 기존 값에 더함 (`+=`), 0이면 덮어씀 (`=`) |
| Shift/Scale | 누적된 값을 출력할 때 비트 시프트 또는 실수 스케일링 적용 |
| Activation | ReLU, LayerNorm, I-GELU, Softmax 등 적용 가능 |

### 4.3 누적 및 출력 흐름 (`gemmini.cc`)

**Compute → Accumulator:**
```cpp
// compute() 낵부
if (acc) {  // 출력 주소가 accumulator를 가리킴
  if (acc_accum) {
    gemmini_state.accumulator[base_sp_addr + i][j] += value;
  } else {
    gemmini_state.accumulator[base_sp_addr + i][j] = value;
  }
}
```

**Accumulator → DRAM (mvout):**
```cpp
void gemmini_t::mvout(reg_t dram_addr, reg_t sp_addr) {
  bool accumulator = (sp_addr >> 31) & 0x1;
  
  if (accumulator) {
    acc_t acc_value = gemmini_state.accumulator[spad_row][spad_col];
    
    // 1. Pre-activation (if any)
    acc_value = apply_pre_activation_acc(acc_value);
    
    // 2. Scale/Shift
    auto shifted = acc_scale(acc_value, gemmini_state.acc_shift);
    
    // 3. Activation (ReLU, etc.)
    elem_t activated = apply_activation_acc(shifted);
    
    // 4. Write to DRAM (full mode: acc_t, otherwise: elem_t)
    if (full) {
      write_to_dram<acc_t>(dram_byte_addr, acc_value);
    } else {
      write_to_dram<elem_t>(dram_byte_addr, activated);
    }
  }
}
```

---

## 5. End-to-End 동작 예시: 행렬 곱셈

### 5.1 기본 소프트웨어 흐름 (`template.c`)

```c
// 1. Configure strides
gemmini_config_ld(DIM * sizeof(elem_t));
gemmini_config_st(DIM * sizeof(elem_t));

// 2. Move A and B from DRAM to scratchpad
gemmini_mvin(In, In_sp_addr);
gemmini_mvin(Identity, Identity_sp_addr);

// 3. Set dataflow mode
gemmini_config_ex(OUTPUT_STATIONARY, 0, 0);

// 4. Preload output address (bias = 0)
gemmini_preload_zeros(Out_sp_addr);

// 5. Compute: A * B
gemmini_compute_preloaded(In_sp_addr, Identity_sp_addr);

// 6. Move result from scratchpad to DRAM
gemmini_mvout(Out, Out_sp_addr);

// 7. Fence to ensure completion
gemmini_fence();
```

### 5.2 Tile 기반 큰 행렬 곱셈 (`sp_tiled_matmul_os`)

큰 행렬은 `DIM x DIM` 타일로 분할되어 처리됩니다:

```
for i in I_tiles:
  for j in J_tiles:
    // Move bias D to accumulator
    gemmini_extended_mvin(D_dram, D_sp_addr_acc, cols, rows);
    
    for k in K_tiles:
      // Move A and B tiles to scratchpad
      gemmini_extended_mvin(A_dram, A_sp_addr, cols, rows);
      gemmini_extended_mvin(B_dram, B_sp_addr, cols, rows);
      
      // Compute and accumulate
      if k == 0:
        gemmini_extended_compute_preloaded(A_sp_addr, B_sp_addr, ...);
      else:
        gemmini_extended_compute_accumulated(A_sp_addr, B_sp_addr, ...);
    
    // Move out result from accumulator
    gemmini_extended_mvout(C_dram, C_sp_addr, cols, rows);
```

---

## 6. Hardware-Software 인터페이스 매핑

| 소프트웨어 (C 매크로) | Spike Extension (`gemmini.cc`) | Hardware 모듈 |
|----------------------|-------------------------------|---------------|
| `gemmini_mvin()` | `mvin()` → `read_from_dram()` → `spad[]` | LoadController → DMA → Scratchpad |
| `gemmini_mvout()` | `mvout()` → `spad[]`/`accumulator[]` → `write_to_dram()` | StoreController → DMA |
| `gemmini_preload()` | `preload()` → `pe_state[][] = spad[]` | ExecuteController → Mesh preload |
| `gemmini_compute_*()` | `compute()` → `pe_state += A*B` or `results += A*weight` | ExecuteController → Mesh → Accumulator |
| `gemmini_config_ex()` | `config()` → `mode`, `sys_act`, `sys_shift` | Controller 파이프라인 설정 |

---

## 7. 핵심 파라미터 (`gemmini_params.h` 예시)

| 파라미터 | 의미 | 예시 값 |
|---------|------|---------|
| `DIM` | Systolic array 차원 | 4 (PE 4x4) |
| `BANK_NUM` | Scratchpad bank 수 | 4 |
| `BANK_ROWS` | Bank 당 row 수 | 4096 |
| `ACC_ROWS` | Accumulator row 수 | 4096 |
| `ADDR_LEN` | 주소 비트 수 | 32 |
| `elem_t` | 입력 데이터 타입 | float |
| `acc_t` | 누적 데이터 타입 | float |

---

## 8. 핵심 소스 파일

### Hardware (Chisel/Scala)
- `src/main/scala/gemmini/cont` - Top-level GemminiModule
- `gemmini_original/Scratchpad.scala` - Scratchpad 구현
- `gemmini_original/Mesh.scala` - Systolic Array (PE 그리드)
- `gemmini_original/AccumulatorMem.scala` - Accumulator 저장소
- `gemmini_original/AccumulatorScale.scala` - Accumulator 출력 경로
- `gemmini_original/ExecuteController.scala` - 실행 제어
- `gemmini_original/LoadController.scala` - 로드 제어
- `gemmini_original/StoreController.scala` - 스토어 제어

### Software (C/C++)
- `software/libgemmini/gemmini.h` - Spike extension 헤더
- `software/libgemmini/gemmini.cc` - Spike extension 구현
- `software/gemmini-rocc-tests/include/gemmini.h` - 소프트웨어 매크로 API
- `software/gemmini-rocc-tests/bareMetalC/template.c` - 기본 사용 예시
- `software/gemmini-rocc-tests/bareMetalC/matmul.c` - 행렬 곱셈 예시

---

## 요약

1. **Scratchpad**: DRAM과 Systolic Array 사이의 버퍼로, row-addressed banked SRAM입니다. `mvin`/`mvout`으로 접근하며, 주소의 상위 비트로 accumulator 모드를 구분합니다.

2. **Systolic Array**: `DIM x DIM` PE 그리드로, **Output Stationary**와 **Weight Stationary** 두 가지 모드를 지원합니다. OS는 결과를 PE 낵부에 누적하고, WS는 weight를 preload하여 입력 데이터와 재사용합니다.

3. **Accumulator**: 넓은 비폭의 누적 메모리로, 행렬 곱셈의 부분합을 누적(`+=`)하거나 덮어쓸 수 있습니다. `mvout` 시에는 `acc_scale`로 스케일링하고, activation 함수를 적용한 후 `elem_t` 또는 `acc_t` 형태로 DRAM에 기록합니다.

이 세 요소는 `ReservationStation`에 의해 명령어 순서가 관리되고, `LoadController` → `ExecuteController` → `StoreController` 파이프라인을 통해 효율적으로 동작합니다.

---

## 부록: Optional Scaling 상세 분석

### A.1 스케일링 타입 정의

```c
// gemmini_params.h

// elem_t: 기본 행렬 요소 타입 (int8_t)
typedef int8_t elem_t;

// acc_t: 누적기(accumulator) 타입 (int32_t)  
typedef int32_t acc_t;

// scale_t: MVIN 스케일링 계수 타입 (float)
typedef float scale_t;
typedef uint32_t scale_t_bits;

// acc_scale_t: MVOUT 스케일링 계수 타입 (float)
typedef float acc_scale_t;
typedef uint32_t acc_scale_t_bits;
```

### A.2 컴파일 타임 설정

```c
#define HAS_MVIN_SCALE        // MVIN 스케일링 활성화
#define HAS_MVIN_ACC_SCALE    // Accumulator MVIN 스케일링 활성화
#define ACC_SCALE_T_IS_FLOAT  // ACC_SCALE이 float 타입 사용
```

### A.3 MVIN 스케일링 (입력 데이터 스케일링)

MVIN 스케일링은 **`gemmini_config_ld()`** (load configuration) 명령어를 통해 설정됩니다:

```c
// 기본 설정 (스케일링 없음)
gemmini_config_ld(stride);  // scale = MVIN_SCALE_IDENTITY (1.0)

// 확장 설정 (스케일링 적용)
gemmini_extended_config_ld(stride, scale);  // 사용자 정의 스케일

// 최종 매크로 정의
#define gemmini_extended5_config_ld(stride, scale, shrunk, block_mvin_stride, pixel_repeats, id) \
  ROCC_INSTRUCTION_RS1_RS2(XCUSTOM_ACC, \
    ((uint64_t)(scale_t_to_scale_t_bits(scale)) << 32) | \
    ((uint64_t)(block_mvin_stride) << 16) | \
    ((uint64_t)(pixel_repeats) << 8) | \
    ((id) << 3) | ((shrunk) << 2) | CONFIG_LD, \
    stride, k_CONFIG)
```

#### 명령어 인코딩 (RS1 레지스터)

```
RS1 [63:32] = scale 값 (float → uint32_t 비트 변환)
RS1 [31:16] = block_mvin_stride (블록 stride)
RS1 [15:8]  = pixel_repeats
RS1 [7:3]   = id
RS1 [2]     = shrunk (데이터 압축 여부)
RS1 [1:0]   = CONFIG_LD (01)
```

#### 하드웨어 설정 구조체

```scala
// cont 파일의 168번 라인
new ConfigMvinRs1(mvin_scale_t_bits, block_stride_bits, pixel_repeats_bits)
```

#### 스케일링 수식

```c
#define MVIN_SCALE(x, scale) \
  ({float y = ROUND_NEAR_EVEN((x) * (scale)); \
    y > INT8_MAX ? INT8_MAX : \
    (y < INT8_MIN ? INT8_MIN : (elem_t)y);})

#define MVIN_SCALE_IDENTITY 1.0
```

**동작 설명:**
1. 입력 데이터 `x`에 스케일 계수 `scale`을 곱함
2. **Round-Nearest-Even** 방식으로 반올림
3. **Saturating Cast** - int8 범위(-128 ~ 127)로 클램핑
4. 결과를 `elem_t` (int8_t)로 변환

#### MVIN 스케일링 테스트 예제

```c
// mvin_scale.c
for (int n = 0; n < N; ++n) {
    // 각 반복마다 다른 스케일 값(n) 설정
    gemmini_extended_config_ld(DIM * sizeof(elem_t), n);
    
    // MVIN 실행 - 데이터에 자동으로 scale=n 적용
    gemmini_mvin(In[n], n*DIM);
    gemmini_mvout(Out[n], n*DIM);
}

// 검증
if (Out[n][i][j] != (elem_t)(MVIN_SCALE(In[n][i][j], n))) {
    // 오류 처리
}
```

### A.4 ACC_SCALE (MVOUT 스케일링 - 출력 데이터 스케일링)

MVOUT 스케일링은 **`gemmini_config_st()`** (store configuration) 명령어를 통해 설정:

```c
// 기본 설정 (스케일링 없음)
gemmini_config_st(stride);  // scale = ACC_SCALE_IDENTITY (1.0)

// 확장 설정 (스케일링 + 활성화 함수)
gemmini_extended_config_st(stride, activation, scale);

// 최종 매크로 정의
#define gemmini_extended2_config_st(stride, acc_act, acc_scale, ...) \
  ROCC_INSTRUCTION_RS1_RS2(XCUSTOM_ACC, \
    ((uint64_t)(ocols) << 56) | ... | \
    ((uint64_t)(acc_act) << 2) | CONFIG_ST, \
    ((uint64_t)acc_scale_t_to_acc_scale_t_bits((acc_scale_t)acc_scale) << 32) | \
    ((uint32_t)stride), k_CONFIG)
```

#### 명령어 인코딩 (RS2 레지스터)

```
RS2 [63:32] = acc_scale 값 (float → uint32_t 비트 변환)
RS2 [31:0]  = stride (DRAM stride)
```

RS1에는 활성화 함수 및 pooling 설정 포함:
```
RS1 [63:56] = ocols
RS1 [55:48] = orows
...
RS1 [3:2]   = acc_act (활성화 함수: NO_ACTIVATION, RELU, etc.)
RS1 [1:0]   = CONFIG_ST (10)
```

#### 하드웨어 설정 구조체

```scala
// cont 파일의 169번 라인
new ConfigMvoutRs2(acc_scale_t_bits, 32)
```

#### 스케일링 수식

```c
#define ACC_SCALE(x, scale) \
  ({float y = ROUND_NEAR_EVEN((x) * (scale)); \
    y > INT8_MAX ? INT8_MAX : \
    (y < INT8_MIN ? INT8_MIN : (acc_t)y);})

#define ACC_SCALE_IDENTITY 1.0
```

**동작 설명:**
1. Accumulator 데이터 `x` (int32_t)에 스케일 계수를 곱함
2. **Round-Nearest-Even** 방식으로 반올림
3. **Saturating Cast** - int8 범위로 클램핑
4. 결과를 `acc_t` (int32_t 중간값)로 변환

#### 활성화 함수와의 결합

MVOUT 시 스케일링은 활성화 함수(activation)와 함께 적용됩니다:

```c
// gemmini.h의 scale_and_sat 함수
static elem_t scale_and_sat(acc_t x, int act, acc_scale_t scale, acc_scale_t bert_scale) {
    // 1. 스케일링 적용
    x = ACC_SCALE(x, scale);
    
    // 2. 활성화 함수 적용 (RELU, LAYERNORM, IGELU, SOFTMAX)
    switch (act) {
        case RELU:
            x = x < 0 ? 0 : x;
            break;
        case IGELU:
            // IGELU 연산
            break;
        // ...
    }
    
    return (elem_t)x;
}
```

### A.5 행렬 곱셈에서의 스케일링 활용

```c
// 1. 입력 데이터 MVIN (A, B, D 행렬)
gemmini_mvin(A[n], A_addr + n*DIM);
gemmini_mvin(B[n], B_addr + n*DIM);
gemmini_mvin(D[n], D_addr + n*DIM);

// 2. 실행 모드 설정
gemmini_config_ex(WEIGHT_STATIONARY, 0, 0);

// 3. 출력 설정: 스케일링 + 활성화 함수
gemmini_extended_config_st(DIM * sizeof(elem_t), activation, scale);

// 4. 행렬 곱셈 실행
gemmini_preload(B_addr, C_addr);
gemmini_compute_preloaded(A_addr, D_addr);

// 5. 결과 MVOUT (자동으로 acc_scale 적용)
gemmini_mvout(C[c], C_addrs[c]);
```

### A.6 데이터 흐름 요약

#### MVIN 경로 (DRAM → Scratchpad)

```
DRAM 데이터 (elem_t 또는 acc_t)
    ↓
[MVIN_SCALE 적용] (선택적)
    ↓
ROUND_NEAR_EVEN 반올림
    ↓
Saturating Cast (INT8 범위)
    ↓
Scratchpad 저장 (elem_t 또는 acc_t)
```

#### MVOUT 경로 (Accumulator → DRAM)

```
Accumulator 데이터 (acc_t: int32_t)
    ↓
[ACC_SCALE 적용] (선택적)
    ↓
[활성화 함수 적용] (RELU, IGELU 등)
    ↓
ROUND_NEAR_EVEN 반올림
    ↓
Saturating Cast (INT8 범위)
    ↓
DRAM 저장 (elem_t: int8_t)
```

### A.7 스케일링 활용 시나리오

#### 양자화 (Quantization)

```c
// 모델 가중치를 int8로 양자화할 때 스케일링
float weight_scale = 0.0235f;
gemmini_extended_config_ld(DIM * sizeof(elem_t), weight_scale);
gemmini_mvin(weights, weight_addr);
```

#### 정규화 (Normalization)

```c
// 배치 정규화의 스케일 파라미터 적용
float bn_scale = 1.2f;
gemmini_extended_config_st(DIM * sizeof(elem_t), NO_ACTIVATION, bn_scale);
gemmini_mvout(output, output_addr);
```

#### 동적 범위 조정

```c
// 다양한 스케일 값 테스트
for (int scale = 0; scale <= 12; scale += 4) {
    gemmini_extended_config_st(stride, RELU, scale);
    gemmini_mvout(data, addr);
}
```

---

# WS/OS 모드 상세 동작 분석: Auto Tiling & Scratchpad 적재 관점

> 작성일: 2026-05-02
> 중심 주제: `tiled_matmul_auto`를 중심으로 한 소프트웨어 tiling과 하드웨어 dataflow 상호작용

---

## 1. Auto Tiling 개요 (`tiled_matmul_auto`)

`tiled_matmul_auto`는 주어진 행렬 크기(`dim_I`, `dim_J`, `dim_K`)를 Gemmini 하드웨어의 scratchpad/accumulator 용량에 맞춰 자동으로 tile 단위로 분할합니다.

### 1.1 Tile 크기 계산 공식

```c
// Scratchpad에 필요한 총 row 수
static size_t tiled_matmul_total_spad_rows(size_t I, size_t J, size_t K) {
    return (I * K + K * J) * DIM;  // A tiles + B tiles
}

// Accumulator에 필요한 총 row 수
static size_t tiled_matmul_total_acc_rows(size_t I, size_t J) {
    return (I * J) * DIM;  // C tiles
}
```

**핵심 제약:**
- **OS 모드**: 전체 scratchpad (`BANK_NUM * BANK_ROWS`)와 accumulator (`ACC_ROWS`) 사용
- **WS 모드**: double-buffering을 위해 scratchpad/accumulator를 절반씩만 사용 (`/2`)

### 1.2 Auto Tiling 알고리즘

```c
const bool double_buffered = (tiled_matmul_type == WS);
const size_t max_spad_rows = double_buffered ? BANK_NUM * BANK_ROWS / 2 : BANK_NUM * BANK_ROWS;
const size_t max_acc_rows  = double_buffered ? ACC_ROWS / 2 : ACC_ROWS;

// 1. 초기값: DIM 단위 행렬 블록 수의 이론적 최대 vs 실제 필요량 중 작은 값
tile_I = min(dim_I_padded/DIM, max_tile_i_j);
tile_J = min(dim_J_padded/DIM, max_tile_i_j);
tile_K = min(dim_K_padded/DIM, max_tile_k);

// 2. Greedy 확장: scratchpad/accumulator를 최대한 채울 때까지 늘림
while (true) {
    bool increased = false;
    
    // J 늘리기 (B와 C에 영향)
    if (tiled_matmul_total_spad_rows(tile_I, tile_J+1, tile_K) <= max_spad_rows &&
        tiled_matmul_total_acc_rows(tile_I, tile_J+1) <= max_acc_rows) {
        tile_J++; increased = true;
    }
    
    // I 늘리기 (A와 C에 영향)
    if (tiled_matmul_total_spad_rows(tile_I+1, tile_J, tile_K) <= max_spad_rows &&
        tiled_matmul_total_acc_rows(tile_I+1, tile_J) <= max_acc_rows) {
        tile_I++; increased = true;
    }
    
    // K 늘리기 (A와 B에 영향, accumulator 무관)
    if (tiled_matmul_total_spad_rows(tile_I, tile_J, tile_K+1) <= max_spad_rows) {
        tile_K++; increased = true;
    }
    
    if (!increased) break;
}
```

**핵심 인사이트:**
- `tile_I`와 `tile_J`는 scratchpad와 accumulator **양쪽** 제약을 받음
- `tile_K`는 scratchpad 제약만 받음 (accumulator에 영향 없음)
- WS 모드는 double-buffered이므로 절반 용량만 사용 → 작은 tile 사용 → 더 많은 반복

---

## 2. Output Stationary (OS) 모드 상세 분석

### 2.1 핵심 아이디어

**"출력의 각 요소를 고정된 PE에 누적한다"**

- PE `pe_state[i][j]`가 `C[i][j]`를 담당
- A와 B를 매 K-step마다 scratchpad에서 읽어와 곱셈-누적 수행
- K 차원(내적 차원)에 대해 순차적 누적

### 2.2 소프트웨어 흐름 (`sp_tiled_matmul_os`)

```c
// 1. Bias(D)를 accumulator로 mvin (초기값으로 사용)
for (i in I_tiles, j in J_tiles):
    gemmini_extended_mvin(D_dram, D_sp_addr_acc, cols, rows);

// 2. A, B tile을 scratchpad로 mvin 후 compute-accumulate
for (i in I_tiles, j in J_tiles):
    for (k in K_tiles):
        // A, B tiles 이동
        gemmini_extended_mvin(A_dram, A_sp_addr, cols, rows);
        gemmini_extended_mvin(B_dram, B_sp_addr, cols, rows);
        
        // Preload: output 주소만 설정 (B는 매번 scratchpad에서 읽음)
        gemmini_extended_preload(GARBAGE_ADDR, out_sp_addr, ...);
        
        // Compute
        if (k == 0):
            gemmini_extended_compute_preloaded(A_sp_addr, B_sp_addr, ...);
        else:
            gemmini_extended_compute_accumulated(A_sp_addr, B_sp_addr, ...);

// 3. 결과를 DRAM으로 mvout
for (i in I_tiles, j in J_tiles):
    gemmini_extended_mvout(C_dram, C_sp_addr, cols, rows);
```

### 2.3 하드웨어 동작 (`compute()` in `gemmini.cc`)

```cpp
// OS 모드 compute
for (i = 0; i < DIM; i++) {
  for (j = 0; j < DIM; j++) {
    for (k = 0; k < DIM; k++) {
      elem_t a = spad[a_addr + i][k];  // A tile
      elem_t b = spad[bd_addr + k][j]; // B tile
      pe_state[i][j] += a * b;         // PE 낵부 누적
    }
  }
}

// 결과 write: pe_state -> accumulator 또는 scratchpad
acc_t value = pe_state[i][j];
if (acc_output) {
    accumulator[addr] += value;  // accumulate 모드
} else {
    spad[addr] = apply_activation(value);  // scratchpad 직접 write
}
```

### 2.4 Scratchpad 적재 패턴

**OS 모드에서는:**
- **A tiles**: `tile_I × tile_K`개의 DIM×DIM 블록 = `tile_I * tile_K * DIM` rows
- **B tiles**: `tile_K × tile_J`개의 DIM×DIM 블록 = `tile_K * tile_J * DIM` rows
- **C (accumulator)**: `tile_I × tile_J`개 = `tile_I * tile_J * DIM` acc_rows

**전체 메모리 사용:**
- Scratchpad: `(I×K + K×J) × DIM` rows
- Accumulator: `(I×J) × DIM` rows

---

## 3. Weight Stationary (WS) 모드 상세 분석

### 3.1 핵심 아이디어

**"Weight(B)를 PE에 preload하여 K 차원에 걸쳐 재사용한다"**

- `preload` 명령으로 B(weight)를 `pe_state[][]`에 저장
- `compute` 시 A만 scratchpad에서 읽어오고, 저장된 weight와 곱셈
- Weight 재사용으로 K 차원에 대한 mvin 횟수 감소

### 3.2 소프트웨어 흐름 (`sp_tiled_matmul_ws` / `loop_ws`)

```c
// 1. Bias(D)를 accumulator로 mvin
for (i, j): gemmini_extended_mvin(D_dram, D_sp_addr_acc, ...);

// 2. B tiles를 scratchpad로 mvin
for (k, j): gemmini_extended_mvin2(B_dram, B_sp_addr, ...);

// 3. K-loop: A를 읽어오고, B를 preload한 후 compute
for (k in K_tiles):
    for (j in J_tiles):
        for (i in I_tiles):
            // Mvin A (j==0일 때만)
            if (j == 0) gemmini_extended_mvin(A_dram, A_sp_addr, ...);
            
            // Preload B (i==0일 때만 실제 preload, 이후는 GARBAGE_ADDR)
            uint32_t pre_sp_addr = (i == 0) ? B_sp_addr : GARBAGE_ADDR;
            gemmini_extended_preload(pre_sp_addr, out_sp_addr, ...);
            
            // Compute
            if (i == 0):
                gemmini_extended_compute_preloaded(A_sp_addr, GARBAGE_ADDR, ...);
            else:
                gemmini_extended_compute_accumulated(A_sp_addr, GARBAGE_ADDR, ...);
            
            // Mvout C (k==K-1일 때만)
            if (k == K-1 && C != NULL) gemmini_extended_mvout(C_dram, C_sp_addr, ...);
```

### 3.3 하드웨어 동작 (`compute()` in `gemmini.cc`)

```cpp
// WS 모드: preload 단계
if (preload) {
    for (i, j) {
        // B tile을 pe_state에 저장 (transpose 고려)
        size_t r = b_transpose ? j : i;
        size_t c = b_transpose ? i : j;
        pe_state[i][j] = spad[preload_addr + r][c];  // weight 저장
    }
}

// WS 모드: compute 단계
auto results = new acc_t[DIM][DIM];  // 별도 결과 배열
for (i, j) results[i][j] = bias[i][j];  // D로 초기화

for (i = 0; i < DIM; i++) {
  for (j = 0; j < DIM; j++) {
    for (k = 0; k < DIM; k++) {
      elem_t a = spad[a_addr + i][k];  // A tile (input)
      results[i][j] += a * pe_state[k][j];  // weight 재사용!
    }
  }
}

// 결과 write
acc_t value = results[i][j];
accumulator[addr] = value;  // WS는 항상 결과를 accumulator/scratchpad에 write
```

### 3.4 Double Buffering

WS 모드에서는 **double buffering**을 사용하여 A와 B의 적재와 compute를 overlap합니다:

```c
// Scratchpad를 두 개의 partition으로 나눔
A_sp_addr_start = 0;                          // Partition 0
B_sp_addr_start = (BANK_NUM * BANK_ROWS / 2); // Partition 1

// 동일한 행렬이 다음 K-iteration에서 다른 partition에 적재됨
// -> 이전 K의 compute와 다음 K의 mvin이 parallel하게 수행 가능
```

**Double buffering으로 인한 제약:**
- Scratchpad 사용량 절반: `BANK_NUM * BANK_ROWS / 2`
- Accumulator 사용량 절반: `ACC_ROWS / 2`
- 따라서 tile 크기가 OS에 비해 작아짐

### 3.5 Scratchpad 적재 패턴

**WS 모드에서는:**
- **A tiles**: `tile_I × tile_K`개 (double buffer의 한쪽 partition에)
- **B tiles**: `tile_K × tile_J`개 (preload되어 PE에 유지)
- **C (accumulator)**: `tile_I × tile_J`개 (double buffer의 한쪽 partition에)

**Tile 크기 예시 (DIM=16, BANK_NUM=4, BANK_ROWS=4096):**
```
OS 모드:
  max_spad_rows = 4 * 4096 = 16384
  max_acc_rows  = 4096
  → tile_I = tile_J ≈ 8, tile_K ≈ 64 (예시)

WS 모드:
  max_spad_rows = 4 * 4096 / 2 = 8192 (double buffering)
  max_acc_rows  = 4096 / 2 = 2048
  → tile_I = tile_J ≈ 4, tile_K ≈ 64 (예시)
```

---

## 4. OS vs WS 모드 비교 요약

| 항목 | Output Stationary (OS) | Weight Stationary (WS) |
|------|------------------------|------------------------|
| **핵심 아이디어** | 출력 요소를 PE에 고정하여 누적 | Weight를 PE에 preload하여 재사용 |
| **PE 상태 (`pe_state`)** | 누적 결과 저장 | Weight matrix 저장 |
| **Compute 시 데이터** | A, B 모두 scratchpad에서 읽음 | A만 읽음, B는 pe_state에서 재사용 |
| **Preload** | Bias(D)만 설정, weight는 읽지 않음 | B(weight)를 PE에 적재 |
| **결과 배열** | `pe_state` 직접 사용 | 별도 `results` 배열 사용 |
| **Double Buffering** | 없음 | 있음 (적재-연산 overlap) |
| **Scratchpad 사용** | 전체 (`BANK_NUM * BANK_ROWS`) | 절반 (`/2`) |
| **Accumulator 사용** | 전체 (`ACC_ROWS`) | 절반 (`/2`) |
| **Tile 크기** | 상대적으로 큼 | 상대적으로 작음 |
| **K-loop mvin 횟수** | 매 iteration마다 A, B 둘 다 mvin | A만 mvin, B는 처음에만 preload |
| **적합한 워크로드** | 일반적인 행렬 곱셈 | CNN의 conv layer (weight 재사용 많음) |

---

## 5. 전체 흐름도: `tiled_matmul_auto` → 하드웨어 실행

### 5.1 소프트웨어 흐름

```
[CPU]
  ↓
tiled_matmul_auto(dim_I, dim_J, dim_K, A, B, D, C, ..., WS/OS)
  ↓
1. Padding: dim_X → dim_X_padded (DIM 배수)
2. Auto tiling: tile_I, tile_J, tile_K 계산
3. tiled_matmul_outer() 호출
  ↓
tiled_matmul_outer()
  ↓
4. Config 설정: dataflow, activation, stride
5. Loop over tiles (i0, j0, k0)
   - inner = sp_tiled_matmul_os 또는 sp_tiled_matmul_ws
   - inner() 호출
     ↓
sp_tiled_matmul_os/ws()
  ↓
6. mvin: DRAM → Scratchpad/Accumulator
7. preload/compute: Scratchpad → Systolic Array → Accumulator
8. mvout: Accumulator → DRAM
  ↓
gemmini_fence()  // 완료 대기
```

### 5.2 하드웨어 흐름

```
[RoCC 명령어]
  ↓
[ReservationStation] - 명령어 스케줄링 및 의존성 관리
  ↓
[LoadController]  → [DMA Read]  → [Scratchpad/Accumulator 적재]
  ↓
[ExecuteController] → [Scratchpad Read] → [Mesh(PE Array)] → [Accumulator Write]
  ↓
[StoreController] → [DMA Write] → [DRAM 저장]
```

---

## 6. Scratchpad 주소 배치 상세

### 6.1 OS 모드 주소 배치

```c
// sp_tiled_matmul_os 낵부
const uint32_t A_sp_addr_start = 0;
const uint32_t B_sp_addr_start = BANK_NUM * BANK_ROWS - K * J * DIM;
const uint32_t D_sp_addr_start = 1 << (ADDR_LEN - 1);  // Accumulator 영역
const uint32_t C_sp_addr_start = (3 << (ADDR_LEN - 2)) | (full_C << (ADDR_LEN - 3));
```

**배치도:**
```
Scratchpad (0x00000000 ~ 0x0FFFFFFF):
  [A tiles: 0 ~ tile_I*tile_K*DIM]
  ...
  [B tiles: BANK_NUM*BANK_ROWS - tile_K*tile_J*DIM ~ 끝]

Accumulator (0x80000000 ~):
  [D (bias): 0x80000000 ~]
  [C (output): 0xC0000000 ~]
```

### 6.2 WS 모드 주소 배치 (Double Buffering)

```c
// sp_tiled_matmul_ws / loop_ws
const uint32_t A_sp_addr_start = 0;  // Partition 0
const uint32_t B_sp_addr_start = (BANK_NUM * BANK_ROWS / 2) - K * J * DIM;  // Partition 1 시작
const uint32_t D_sp_addr_start = 1 << (ADDR_LEN - 1);
const uint32_t C_sp_addr_start = (3 << (ADDR_LEN - 2)) | (full_C << (ADDR_LEN - 3));

// Double buffer: k iteration에 따라 partition 교체
if (a_spad_id == 2) A_sp_addr_start = (BANK_NUM * BANK_ROWS) / 2;
if (b_spad_id == 2) B_sp_addr_start = (BANK_NUM * BANK_ROWS) - K * J * DIM;
```

**배치도:**
```
Scratchpad Partition 0 (0 ~ BANK_NUM*BANK_ROWS/2):
  [A tiles (iteration k): 0 ~ ...]
  [B tiles (iteration k): 중간 ~ 끝]

Scratchpad Partition 1 (BANK_NUM*BANK_ROWS/2 ~ BANK_NUM*BANK_ROWS):
  [A tiles (iteration k+1): 0 ~ ...]
  [B tiles (iteration k+1): 중간 ~ 끝]
```

---

## 7. 핵심 소스 코드 경로

| 파일 | 설명 |
|------|------|
| `software/gemmini-rocc-tests/include/gemmini.h` | `tiled_matmul_auto`, `sp_tiled_matmul_os`, `sp_tiled_matmul_ws` 구현 |
| `software/libgemmini/gemmini.cc` | `compute()`, `loop_ws()` 하드웨어 동작 모델 |
| `software/gemmini-rocc-tests/bareMetalC/tiled_matmul_ws.c` | WS 모드 테스트 |
| `software/gemmini-rocc-tests/bareMetalC/tiled_matmul_os.c` | OS 모드 테스트 |

---

*분석 일자: 2026년 4월 29일*
*대상: Gemmini Accelerator (Chipyard 프로젝트 내)*
