#!/usr/bin/env bash
# One shell owns the test status and evidence capture. The emulator action
# executes each line of its script independently, so state must live here.
set -euo pipefail

PROOF_DIR=dist/vm-android-proof
mkdir -p "$PROOF_DIR"

capture_evidence() {
  local proof_status=$?
  trap - EXIT
  printf '%s\n' "$proof_status" > "$PROOF_DIR/test-exit-status.txt"
  timeout 15s adb shell ps -A > "$PROOF_DIR/processes-after.txt" 2> "$PROOF_DIR/processes-after.error" || true
  timeout 15s adb logcat -b all -d -v threadtime -t 5000 > "$PROOF_DIR/logcat.txt" 2> "$PROOF_DIR/logcat.error" || true
  for launch in 0 1; do
    for file in "vm-proof-$launch.json" "vm-proof-$launch.log" "vm-proof-$launch-exit.json"; do
      timeout 10s adb exec-out run-as com.linex.vm.test cat "files/$file" \
        > "$PROOF_DIR/$file" 2> "$PROOF_DIR/$file.error" || true
    done
  done
  timeout 10s adb exec-out run-as com.linex.vm.test cat files/vm-proof-host-control.json \
    > "$PROOF_DIR/vm-proof-host-control.json" 2> "$PROOF_DIR/vm-proof-host-control.json.error" || true
  exit "$proof_status"
}
trap capture_evidence EXIT

timeout 15s adb shell getprop > "$PROOF_DIR/device-properties.txt"
timeout 15s adb shell ps -A > "$PROOF_DIR/processes-before.txt"

if ./gradlew :vm-engine:testDebugUnitTest :vm-engine:connectedDebugAndroidTest \
  -PvmNativeDir=dist/vm-engine --no-daemon; then
  exit 0
else
  exit "$?"
fi
