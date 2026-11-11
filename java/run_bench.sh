#!/bin/sh
# usage: run_bench.sh <label> <affinity hex> <modes...>
# Runs Bench at high priority pinned to the given logical CPUs.
label=$1; aff=$2; shift 2
JR='C:\Program Files\Tracker\OpenJDK-21.0.5.jre\bin\java.exe'
if [ "$label" = orig ]; then CP='bench\out;lib\tracker.jar'; else CP='bench\out;patched;lib\tracker.jar'; fi
cmd //c "start \"\" /b /wait /high /affinity $aff \"$JR\" -Djava.library.path=patched -cp $CP Bench $label $*"
