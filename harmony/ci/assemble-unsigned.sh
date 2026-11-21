#!/usr/bin/env bash
set -euo pipefail
project_dir="$(realpath "${1:-harmony}")"
compile_only=false
if [ "${2:-}" = '--compile-only' ]; then
  compile_only=true
elif [ -n "${2:-}" ]; then
  printf 'Unknown build option: %s\n' "$2" >&2
  exit 2
fi
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
if [ "$compile_only" = true ]; then
  mkdir -p build-evidence
  python3 - <<'PY'
import json
from pathlib import Path
Path('build-evidence/frontend-compile-report.json').write_text(json.dumps({
    'mode': 'compile-only', 'arktsAndNativeBridgeCompiled': True,
    'hapArchiveValidated': True, 'completeGuestRuntimeValidated': False,
    'harmonyDeviceValidation': 'not performed', 'downloadableHapProduced': False,
}, indent=2) + '\n')
PY
  rm "$hap"
  printf 'Frontend compile check passed; no HAP delivery created.\n'
  exit 0
fi
mkdir -p delivery
cp "$hap" delivery/whaletalk-harmony-api18-arm64-unsigned.hap
cp -R build-evidence delivery/
sha256sum delivery/*.hap > delivery/SHA256SUMS
python3 ci/audit-hap.py delivery/whaletalk-harmony-api18-arm64-unsigned.hap \
  --receipt build-evidence/guest-build-receipt.json \
  --smoke build-evidence/guest-smoke/smoke-report.json \
  --lock runtime-sources.lock.json --output delivery/build-report.json
