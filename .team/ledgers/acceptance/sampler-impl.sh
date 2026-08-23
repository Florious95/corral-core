#!/bin/sh
set -eu
[ -s tools/perfbase/run-input-ab.sh ] && [ -s tools/perfbase/parse-input-ab.py ] && [ -s .team/nodes/input-full-auto/sampler-impl/IMPL.md ] || exit 1
sh -n tools/perfbase/run-input-ab.sh || exit 2
sh tools/perfbase/run-input-ab.sh --self-test
