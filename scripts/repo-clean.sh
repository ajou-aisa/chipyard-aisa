#!/usr/bin/env bash

set -e

# this should be run from chipyard repo top
RDIR=$(git rev-parse --show-toplevel)

rm -rf $RDIR/toolchains/libgloss/build.log
rm -rf $RDIR/toolchains/riscv-tools/riscv-isa-sim/build.log
rm -rf $RDIR/toolchains/riscv-tools/riscv-pk/build.log
rm -rf $RDIR/toolchains/riscv-tools/riscv-tests/build.log
rm -rf $RDIR/toolchains/esp-tools/riscv-isa-sim/build.log
rm -rf $RDIR/toolchains/esp-tools/riscv-pk/build.log
rm -rf $RDIR/toolchains/esp-tools/riscv-tests/build.log
(
    pushd $RDIR/generators/constellation
    if [ -e espresso/.git ]
    then
	git submodule deinit -f espresso
    fi
    popd
)
(
    if [ -d $RDIR/generators/cva6/src/main/resources/cva6/vsrc ]
    then
        pushd $RDIR/generators/cva6/src/main/resources/cva6/vsrc
        if [ -e cva6/.git ]
        then
	    git submodule deinit -f cva6
        fi
        popd
    fi
)
