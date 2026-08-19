#!/usr/bin/env bash
set -euo pipefail

source_dir="${1:?usage: check-patch-assumptions.sh <ghostty-source-dir>}"
header="$source_dir/include/ghostty/vt/terminal.h"

max=$(grep -oE 'GHOSTTY_TERMINAL_OPT_[A-Z_]+ = [0-9]+' "$header" |
    grep -v CLIPBOARD_READ | awk '{print $NF}' | sort -n | tail -1)

if [[ "$max" != "37" ]]; then
    echo "upstream terminal option enum ends at $max (expected 37); renumber" \
        "GHOSTTY_TERMINAL_OPT_CLIPBOARD_READ in" \
        "patches/ghostty/0005-lib-vt-clipboard-read-effect.patch and" \
        "library/src/main/cpp/ghostty_jni.cpp" >&2
    exit 1
fi
