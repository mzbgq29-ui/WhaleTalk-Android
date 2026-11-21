#!/usr/bin/env bash
# Sourced environment is written to the path passed as the second argument.
# This API18 tool bundle is also used by qemu-hmos's successful cloud builds.
set -euo pipefail

tools_dir="${1:-${RUNNER_TEMP:-/tmp}/whaletalk-harmony-tools}"
env_file="${2:-$tools_dir/environment.sh}"
tools_url='https://repo.huaweicloud.com/harmonyos/ohpm/5.1.0/commandline-tools-linux-x64-5.1.0.840.zip'
mkdir -p "$tools_dir"
tools_dir="$(realpath "$tools_dir")"
if ! find "$tools_dir" -path '*/sdk/default/sdk-pkg.json' -print -quit | rg -q .; then
  curl --fail --show-error --location --retry 3 --retry-delay 3 \
    "$tools_url" -o "$tools_dir/commandline-tools.zip"
  unzip -q "$tools_dir/commandline-tools.zip" -d "$tools_dir/bundle"
  rm "$tools_dir/commandline-tools.zip"
fi

sdk_home="$(find "$tools_dir" -path '*/sdk/default/sdk-pkg.json' -print -quit)"
test -n "$sdk_home"
sdk_home="${sdk_home%/default/sdk-pkg.json}"
tool_home="${sdk_home%/sdk}"
native_dir="$sdk_home/default/openharmony/native"
test -f "$native_dir/build/cmake/ohos.toolchain.cmake"
test -x "$tool_home/bin/ohpm"
rg -q '"apiVersion"\s*:\s*"?18"?' "$native_dir/oh-uni-package.json"

mkdir -p "$tools_dir/hvigor"
if [ ! -f "$tools_dir/hvigor/node_modules/@ohos/hvigor/bin/hvigor.js" ]; then
  npm install --prefix "$tools_dir/hvigor" --no-save --ignore-scripts \
    --registry=https://repo.harmonyos.com/npm/ \
    @ohos/hvigor@5.18.6 @ohos/hvigor-ohos-plugin@5.18.6
fi
hvigor_js="$tools_dir/hvigor/node_modules/@ohos/hvigor/bin/hvigor.js"
node "$hvigor_js" --version

mkdir -p "$(dirname "$env_file")"
{
  printf 'export TOOL_HOME=%q\n' "$tool_home"
  printf 'export HOS_SDK_HOME=%q\n' "$sdk_home"
  printf 'export DEVECO_SDK_HOME=%q\n' "$sdk_home"
  printf 'export OHOS_SDK_HOME=%q\n' "$sdk_home"
  printf 'export HARMONY_NATIVE_DIR=%q\n' "$native_dir"
  printf 'export OHOS_NDK_HOME=%q\n' "$native_dir"
  printf 'export HVIGOR_JS=%q\n' "$hvigor_js"
  printf 'export NODE_PATH=%q\n' "$tools_dir/hvigor/node_modules"
  printf 'export PATH=%q:"$PATH"\n' "$tool_home/bin"
} > "$env_file"
printf 'Environment file: %s\n' "$env_file"
