#!/usr/bin/env bash
set -euo pipefail
project_dir="$(realpath "${1:-harmony}")"
test -n "${DEVECO_SDK_HOME:-}"
test -f "${HVIGOR_JS:-}"
cd "$project_dir"
printf 'sdk.dir=%s\n' "$DEVECO_SDK_HOME" > local.properties
# Hvigor lives outside the checkout; the project's hvigorfile imports its plugin.
mkdir -p node_modules/@ohos
for package in hvigor hvigor-ohos-plugin; do
  ln -sfn "$NODE_PATH/@ohos/$package" "node_modules/@ohos/$package"
done
"$TOOL_HOME/bin/ohpm" --version
"$TOOL_HOME/bin/ohpm" install
(cd entry && "$TOOL_HOME/bin/ohpm" install)
node "$HVIGOR_JS" clean --no-daemon
node "$HVIGOR_JS" --mode module -p product=default \
  -p module=entry@default -p buildMode=debug assembleHap --no-daemon
hap='entry/build/default/outputs/default/entry-default-unsigned.hap'
test -s "$hap"
unzip -tq "$hap"
mkdir -p delivery
cp "$hap" delivery/whaletalk-harmony-api18-arm64-unsigned.hap
cp -R build-evidence delivery/
sha256sum delivery/*.hap > delivery/SHA256SUMS
python3 ci/audit-hap.py delivery/whaletalk-harmony-api18-arm64-unsigned.hap \
  --receipt build-evidence/guest-build-receipt.json \
  --smoke build-evidence/guest-smoke/smoke-report.json \
  --lock runtime-sources.lock.json --output delivery/build-report.json
