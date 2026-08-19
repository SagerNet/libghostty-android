#!/usr/bin/env bash
# 0005-lib-vt-clipboard-read-effect.patch appends
# GHOSTTY_TERMINAL_OPT_CLIPBOARD_READ = 38 after the upstream enum. Upstream
# adding its own option 38 would not necessarily fail git apply, so verify the
# upstream part of the enum still ends at 37.
set -euo pipefail

source_dir="${1:?usage: check-patch-assumptions.sh <ghostty-source-dir>}"
header="$source_dir/include/ghostty/vt/terminal.h"

if [[ ! -f "$header" ]]; then
    echo "terminal.h not found at $header" >&2
    exit 1
fi

max=$(grep -oE 'GHOSTTY_TERMINAL_OPT_[A-Z_]+ = [0-9]+' "$header" |
    grep -v CLIPBOARD_READ | awk '{print $NF}' | sort -n | tail -1)

if [[ "$max" != "37" ]]; then
    echo "upstream terminal option enum ends at $max (expected 37);" \
        "renumber GHOSTTY_TERMINAL_OPT_CLIPBOARD_READ in patch 0005 and ghostty_jni.cpp" >&2
    exit 1
fi
