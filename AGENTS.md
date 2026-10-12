# Chipyard AGENTS.md

> AI assistant instructions for working with the Chipyard RISC-V SoC framework

## Project Overview

Chipyard is an open source framework for agile development of Chisel-based RISC-V systems-on-chip (SoCs). It integrates processor cores (Rocket, BOOM, CVA6), accelerators (Gemmini, Hwacha, NVDLA), memory systems, and peripherals using the Chisel HDL and Rocket Chip SoC generator.

**Key Technologies:** Scala 2.13, Chisel 3.5, FIRRTL, Verilator, SBT, Conda

## Environment Setup (CRITICAL)

**ALWAYS source the environment before any work:**

```bash
# Must be done in every new shell
cd /home/alveo/firesim/target-design/chipyard
source env.sh
```

The `env.sh` script:
- Activates the conda environment at `.conda-env`
- Sets up `RISCV` toolchain paths
- Adds FireMarshal to PATH
- Runs `fix-open-files.sh` for ulimits

**Without this, all make/sbt commands will fail with "RISCV is unset" errors.**

## Build System

### Primary Build Tools

| Tool | Purpose | Key Files |
|------|---------|-----------|
| **SBT** | Scala/Chisel compilation | `build.sbt`, `project/build.properties` |
| **Make** | Simulation, tests, RTL generation | `common.mk`, `variables.mk`, sim makefiles |
| **Conda** | Dependency/environment management | `conda-reqs/chipyard.yaml` |
| **FireMarshal** | Linux workload generation | `software/firemarshal/` |

### Key Build Commands

```bash
# Generate Verilog from Chisel (in sims/verilator/ or sims/vcs/)
make verilog CONFIG=RocketConfig

# Build simulator
make CONFIG=RocketConfig

# Run tests (requires compiled simulator)
make run-asm-tests CONFIG=RocketConfig
make run-bmark-tests CONFIG=RocketConfig

# Run specific binary
make run-binary CONFIG=RocketConfig BINARY=/path/to/riscv.elf

# Debug build with waveforms
make debug CONFIG=RocketConfig
make run-binary-debug CONFIG=RocketConfig BINARY=/path/to/riscv.elf

# Launch SBT console for Chisel development
make launch-sbt
# Or directly:
cd $CHIPYARD_ROOT && sbt
```

### SBT Projects

Key SBT projects defined in `build.sbt`:

```bash
# Main projects
cd $CHIPYARD_ROOT && sbt "project chipyard"      # Main SoC generator
cd $CHIPYARD_ROOT && sbt "project rocketchip"    # Rocket Chip cores
cd $CHIPYARD_ROOT && sbt "project gemmini"       # Gemmini accelerator
cd $CHIPYARD_ROOT && sbt "project boom"          # BOOM out-of-order core
cd $CHIPYARD_ROOT && sbt "project firechip"      # FireSim integration
```

## Project Structure

```
chipyard/
├── generators/          # SoC generators (Chisel/Scala)
│   ├── chipyard/       # Main generator & configs
│   ├── rocket-chip/    # Rocket/BOOM cores (submodule)
│   ├── gemmini/        # Matrix accelerator
│   ├── boom/           # Out-of-order core
│   ├── hwacha/         # Vector accelerator
│   ├── testchipip/     # Test chip IP library
│   └── ...
├── sims/               # Simulators
│   ├── verilator/      # Verilator simulation (free)
│   ├── vcs/            # VCS simulation (commercial)
│   ├── xcelium/        # Xcelium (commercial)
│   └── firesim/        # FPGA-accelerated sim
├── software/           # Software tools
│   ├── firemarshal/    # Workload generation
│   ├── coremark/       # Benchmarks
│   └── tutorial/       # Tutorial workloads
├── tests/              # C test programs
├── toolchains/         # RISC-V toolchain (submodule)
├── tools/              # Additional tools
│   ├── barstools/      # Tapeout/ASIC tools
│   ├── dsptools/       # DSP libraries
│   └── cde/            # Configuration library
├── fpga/               # FPGA prototyping
├── vlsi/               # VLSI/ASIC flow (Hammer)
├── docs/               # Sphinx documentation
└── scripts/            # Setup/utility scripts
```

## Configuration System

Chipyard uses a **parameterized config system** via Scala traits:

### Config Fragments

Configs are composed in `generators/chipyard/src/main/scala/config/`:

```scala
// Example: RocketConfig, BoomConfig, GemminiConfig
class RocketConfig extends Config(
  new chipyard.config.WithNLargeCores(1) ++      // 1 large Rocket core
  new chipyard.config.WithPeripheryBusFrequency(BigInt(500000000)) ++
  new chipyard.config.WithMemoryBusFrequency(BigInt(500000000)) ++
  new chipyard.config.WithFrontBusFrequency(BigInt(500000000)) ++
  new chipyard.config.WithSystemBusFrequency(BigInt(500000000)) ++
  new chipyard.config.WithIncoherentBusTopology ++
  new freechips.rocketchip.system.BaseConfig
)
```

### Finding Configs

```bash
# List all available config fragments
make find-config-fragments

# Check a specific generator
ls generators/gemmini/src/main/scala/config/
```

## Testing

### Test Categories

| Test Type | Location | Command |
|-----------|----------|---------|
| **Unit tests** | Generator test dirs | `sbt "test"` |
| **Assembly tests** | RISC-V tests | `make run-asm-tests` |
| **Benchmarks** | `tests/` directory | `make run-bmark-tests` |
| **Integration** | FireMarshal workloads | `marshal build` |

### Running Tests

```bash
cd sims/verilator

# Assembly tests (quick smoke test)
make run-asm-tests CONFIG=RocketConfig

# Benchmark tests (longer)
make run-bmark-tests CONFIG=RocketConfig

# Run custom binary
make run-binary CONFIG=RocketConfig BINARY=../../tests/hello.riscv

# With instruction trace
make run-binary CONFIG=RocketConfig BINARY=../../tests/hello.riscv verbose

# Fast mode (no trace)
make run-binary-fast CONFIG=RocketConfig BINARY=../../tests/hello.riscv
```

### CI Test Groups

From `.github/workflows/chipyard-run-tests.yml`:

- `group-cores`: Rocket, BOOM, heterogeneous configs
- `group-accels`: Gemmini, Hwacha, NVDLA
- `group-peripherals`: Peripherals, buses, devices
- `group-constellation`: NoC/interconnect tests
- `group-fpga`: FPGA-specific builds

## Code Style & Conventions

### Pre-commit Hooks

```bash
# Install hooks
pre-commit install

# Run manually
pre-commit run --all-files
```

Checks: trailing whitespace, EOF fixer, YAML/JSON syntax, checkmake, large files

### Scala/Chisel Style

- Use 2-space indentation
- Follow existing generator patterns in `generators/chipyard/src/main/scala/`
- Configs use `WithXxx` naming for traits
- Use `LazyModule` for hardware modules with diplomatic parameters

### Git Workflow

```bash
# Branch management (from CONTRIBUTING.md)
# - main: pre-release, latest features (via PR only)
# - tags: official releases
# - Submodules track commits in main

# Initialize submodules
./scripts/init-submodules-no-riscv-tools.sh --force

# Or full setup (with toolchain)
./build-setup.sh riscv-tools
```

## Common Tasks

### Adding a New Generator

1. Create directory in `generators/your-generator/`
2. Add SBT project to `build.sbt`:
   ```scala
   lazy val yourgenerator = (project in file("generators/your-generator"))
     .dependsOn(rocketchip)
     .settings(libraryDependencies ++= rocketLibDeps.value)
     .settings(commonSettings)
   ```
3. Add to `chipyard` project dependencies
4. Create configs in `src/main/scala/config/`

### Building for FPGA

```bash
cd fpga
make SUB_PROJECT=arty CONFIG=RocketArtyConfig
# or
make SUB_PROJECT=vc707 CONFIG=RocketVC707Config
```

### FireSim Integration

```bash
# Initialize FireSim
./scripts/firesim-setup.sh

# Build FireSim simulator
cd sims/firesim/sim
make CONFIG=FireSimRocketConfig

# Build workload
./marshal build workloads/br-base.json
```

## Documentation

```bash
# Build docs locally
cd docs
make html

# View at _build/html/index.html
```

**Key Documentation:**
- Setup: `docs/Chipyard-Basics/Initial-Repo-Setup.rst`
- Configs: `docs/Chipyard-Basics/Configs-Parameters-Mixins.rst`
- Components: `docs/Chipyard-Basics/Chipyard-Components.rst`

## Troubleshooting

### Common Issues

| Issue | Solution |
|-------|----------|
| `RISCV is unset` | Run `source env.sh` |
| `Did not find Verilator` | Install via conda or check PATH |
| SBT out of memory | Set `JAVA_HEAP_SIZE=16G` |
| Submodule issues | `git submodule update --init --recursive` |
| Conda env issues | Delete `.conda-env` and re-run `build-setup.sh` |

### Key Environment Variables

```bash
RISCV           # RISC-V toolchain prefix (required)
JAVA_HEAP_SIZE  # Default: 8G (increase for large designs)
SBT_OPTS        # SBT options (set in variables.mk)
FIRRTL_LOGLEVEL # Default: error (set to info/debug for debugging)
```

### Checking Setup

```bash
# Verify toolchain
which riscv64-unknown-elf-gcc
riscv64-unknown-elf-gcc --version

# Verify verilator
which verilator
verilator --version  # Should be ~5.008

# Check conda
conda info --envs
```

## Important Notes

1. **Always source `env.sh`** before any development work
2. **Use submodules extensively** - most code lives in submodules
3. **Configs compose via `++`** - understand the parameter system
4. **SBT assembly is slow** - use `make` targets to leverage caching
5. **Test with Verilator first** - it's free and faster than VCS for small tests
6. **FireMarshal workloads** - use for Linux-based testing
7. **Check CI workflows** - `.github/workflows/chipyard-run-tests.yml` shows how tests are structured

## References

- Main docs: https://chipyard.readthedocs.io/
- GitHub: https://github.com/ucb-bar/chipyard
- Mailing list: https://groups.google.com/forum/#!forum/chipyard
- Stable releases: Tagged versions (e.g., `1.10.0`)
- Development: `main` branch
