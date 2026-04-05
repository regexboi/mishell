#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLEW="$ROOT_DIR/gradlew"
APP_ID="${APP_ID:-ai.mishell.app}"
APK_PATH="${APK_PATH:-$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk}"
ANDROID_SERIAL="${ANDROID_SERIAL:-}"
ADB_RECOVERY_USED=0
ADB_SERIAL_ARGS=()
RESOLVED_SERIAL=""

if [[ -z "${ANDROID_SDK_ROOT:-}" ]]; then
  if [[ -d "/opt/homebrew/share/android-commandlinetools" ]]; then
    export ANDROID_SDK_ROOT="/opt/homebrew/share/android-commandlinetools"
  elif [[ -d "$HOME/Library/Android/sdk" ]]; then
    export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
  fi
fi

if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
  export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
  export PATH="$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$PATH"
fi

java_works() {
  java -version >/dev/null 2>&1
}

if ! java_works; then
  if [[ -x "/opt/homebrew/opt/openjdk@17/bin/java" ]]; then
    export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
    export PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH"
  fi
fi

if ! java_works; then
  echo "Java is not available in PATH. Open a new terminal or set JAVA_HOME."
  exit 1
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "adb is not available in PATH. Set ANDROID_SDK_ROOT and include platform-tools."
  exit 1
fi

restart_adb_server() {
  adb kill-server >/dev/null 2>&1 || true
  adb start-server >/dev/null
}

list_connected_devices() {
  adb devices | while IFS=$'\t' read -r serial state _; do
    if [[ -z "$serial" || "$serial" == "List of devices attached" ]]; then
      continue
    fi
    if [[ "$state" == "device" ]]; then
      printf '%s\n' "$serial"
    fi
  done
}

resolve_requested_serial() {
  local requested="$1"
  local -a matches=()
  local serial=""

  while IFS= read -r serial; do
    [[ -z "$serial" ]] && continue
    if [[ "$serial" == "$requested" || "$serial" == "$requested"* ]]; then
      matches+=("$serial")
    fi
  done < <(list_connected_devices)

  if [[ ${#matches[@]} -eq 0 ]]; then
    return 1
  fi

  if [[ ${#matches[@]} -eq 1 ]]; then
    printf '%s\n' "${matches[0]}"
    return 0
  fi

  for serial in "${matches[@]}"; do
    if [[ "$serial" == "$requested" ]]; then
      printf '%s\n' "$serial"
      return 0
    fi
  done

  echo "ANDROID_SERIAL prefix is ambiguous: $requested" >&2
  printf 'Matches:\n' >&2
  printf '  %s\n' "${matches[@]}" >&2
  return 2
}

select_device_once() {
  local -a devices=()

  if [[ -n "$ANDROID_SERIAL" ]]; then
    RESOLVED_SERIAL="$(resolve_requested_serial "$ANDROID_SERIAL")" || return $?
  else
    mapfile -t devices < <(list_connected_devices)
    if [[ ${#devices[@]} -eq 0 ]]; then
      return 1
    fi
    RESOLVED_SERIAL="${devices[0]}"
  fi

  ADB_SERIAL_ARGS=(-s "$RESOLVED_SERIAL")
}

select_device() {
  if select_device_once; then
    return 0
  fi

  if [[ $ADB_RECOVERY_USED -eq 0 ]]; then
    ADB_RECOVERY_USED=1
    echo "Could not resolve a connected device. Restarting adb server and retrying once..."
    restart_adb_server
    if select_device_once; then
      return 0
    fi
  fi

  if [[ -n "$ANDROID_SERIAL" ]]; then
    echo "No attached device matched ANDROID_SERIAL prefix: $ANDROID_SERIAL"
  else
    echo "No authorized Android device found."
  fi
  echo "Check USB debugging and run: adb devices -l"
  exit 1
}

run_adb_command() {
  local output=""
  local status=0

  set +e
  output="$(adb "${ADB_SERIAL_ARGS[@]}" "$@" 2>&1)"
  status=$?
  set -e

  if [[ $status -eq 0 ]]; then
    printf '%s' "$output"
    return 0
  fi

  if [[ $ADB_RECOVERY_USED -eq 0 ]]; then
    ADB_RECOVERY_USED=1
    echo "adb command failed. Restarting adb server and retrying once..."
    restart_adb_server
    if ! select_device_once; then
      return 1
    fi

    set +e
    output="$(adb "${ADB_SERIAL_ARGS[@]}" "$@" 2>&1)"
    status=$?
    set -e
  fi

  printf '%s' "$output"
  return $status
}

adb start-server >/dev/null
select_device

serial="${ADB_SERIAL_ARGS[1]}"
echo "Using device: $serial"

echo "Building debug APK..."
"$GRADLEW" assembleDebug

if [[ ! -f "$APK_PATH" ]]; then
  echo "Debug APK not found at: $APK_PATH"
  exit 1
fi

echo "Installing APK..."
set +e
install_output="$(run_adb_command install -r "$APK_PATH" 2>&1)"
install_status=$?
set -e

if [[ $install_status -ne 0 ]]; then
  if [[ "$install_output" == *"INSTALL_FAILED_UPDATE_INCOMPATIBLE"* ]]; then
    echo "Signature mismatch detected. Reinstalling package after uninstall..."
    run_adb_command uninstall "$APP_ID" >/dev/null || true
    run_adb_command install "$APK_PATH"
  else
    echo "$install_output"
    exit $install_status
  fi
else
  echo "$install_output"
fi

resolved_activity="$(
  run_adb_command shell cmd package resolve-activity --brief "$APP_ID" 2>/dev/null |
    tr -d '\r' |
    tail -n 1
)"
if [[ -z "$resolved_activity" || "$resolved_activity" == "No activity found" ]]; then
  resolved_activity="$APP_ID/.MainActivity"
fi

echo "Launching: $resolved_activity"
run_adb_command shell am start -W -n "$resolved_activity" >/dev/null

echo "Done. App is installed and launched on $RESOLVED_SERIAL."
