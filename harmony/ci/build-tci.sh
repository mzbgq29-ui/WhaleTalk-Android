#!/usr/bin/env bash
# Rebuild a genuine interpreter core. A JIT .so is never renamed to masquerade as TCI.
set -euo pipefail
project_dir="$(realpath "${1:-harmony}")"
build_dir="${2:-${RUNNER_TEMP:-/tmp}/whaletalk-tci}"
test -n "${TOOL_HOME:-}"
test -f "$TOOL_HOME/sdk/default/sdk-pkg.json"
mkdir -p "$build_dir"
build_dir="$(realpath "$build_dir")"
lock_file="$project_dir/runtime-sources.lock.json"
read_lock() { python3 -c 'import json,sys;x=json.load(open(sys.argv[1]));print(x[sys.argv[2]][sys.argv[3]])' "$lock_file" "$1" "$2"; }

checkout_pin() {
  local destination="$1" repository="$2" commit="$3"
  if [ ! -d "$destination/.git" ]; then
    git init -q "$destination"
    git -C "$destination" remote add origin "$repository"
  fi
  git -C "$destination" fetch --depth=1 origin "$commit"
  git -C "$destination" checkout --detach FETCH_HEAD
  test "$(git -C "$destination" rev-parse HEAD)" = "$commit"
}
checkout_pin "$build_dir/hish" "$(read_lock hish repository)" "$(read_lock hish commit)"
hish="$build_dir/hish"
checkout_pin "$hish/deps/libqemu/download/qemu" "$(read_lock qemu repository)" "$(read_lock qemu commit)"
checkout_pin "$hish/deps/libglib/download/glib" "$(read_lock glib repository)" "$(read_lock glib commit)"

# Upstream clone rule points at GitLab; use the equivalent pinned GitHub mirror.
# Existing source directories satisfy the download targets without invoking clone.
# glib's subprojects use Meson wrap fallbacks if required; all source is retained.
mkdir -p "$hish/feature/hish_main/libs/arm64-v8a"
make -C "$hish/deps" aarch64-tci TOOL_HOME="$TOOL_HOME" \
  ARCH_FLAGS=-march=armv8-a LSE_FLAGS= OPT_LEVEL=-O2 LTO_FLAG=

test -s "$hish/feature/hish_main/libs/arm64-v8a/libqemu-system-aarch64-tci.so"
destination="$project_dir/entry/libs/arm64-v8a"
mkdir -p "$destination"
python3 "$project_dir/ci/stage-native-libs.py" \
  "$hish/deps/output" "$hish/deps/buildroot/lib" "$destination"
mkdir -p "$project_dir/entry/src/main/resources/rawfile/vm/keymaps"
if [ -f "$hish/deps/output/en-us" ]; then
  cp "$hish/deps/output/en-us" "$project_dir/entry/src/main/resources/rawfile/vm/keymaps/"
fi
if [ -f "$hish/deps/output/efi-virtio.rom" ]; then
  cp "$hish/deps/output/efi-virtio.rom" "$project_dir/entry/src/main/resources/rawfile/vm/"
fi
mkdir -p "$project_dir/build-evidence"
cp "$lock_file" "$project_dir/build-evidence/runtime-sources.lock.json"
sha256sum "$destination"/*.so > "$project_dir/build-evidence/native-SHA256SUMS"
