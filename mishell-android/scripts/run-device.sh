#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLEW="$ROOT_DIR/gradlew"
APP_ID="${APP_ID:-ai.mishell.app}"
APK_PATH="${APK_PATH:-$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk}"
ANDROID_SERIAL="${ANDROID_SERIAL:-}"

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

adb start-server >/dev/null

ADB_SERIAL_ARGS=()
if [[ -n "$ANDROID_SERIAL" ]]; then
  ADB_SERIAL_ARGS=(-s "$ANDROID_SERIAL")
else
  first_device="$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')"
  if [[ -n "$first_device" ]]; then
    ADB_SERIAL_ARGS=(-s "$first_device")
  fi
fi

if [[ "${#ADB_SERIAL_ARGS[@]}" -eq 0 ]]; then
  echo "No authorized Android device found."
  echo "Check USB debugging and run: adb devices -l"
  exit 1
fi

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
install_output="$(adb "${ADB_SERIAL_ARGS[@]}" install -r "$APK_PATH" 2>&1)"
install_status=$?
set -e

if [[ $install_status -ne 0 ]]; then
  if [[ "$install_output" == *"INSTALL_FAILED_UPDATE_INCOMPATIBLE"* ]]; then
    echo "Signature mismatch detected. Reinstalling package after uninstall..."
    adb "${ADB_SERIAL_ARGS[@]}" uninstall "$APP_ID" >/dev/null || true
    adb "${ADB_SERIAL_ARGS[@]}" install "$APK_PATH"
  else
    echo "$install_output"
    exit $install_status
  fi
else
  echo "$install_output"
fi

resolved_activity="$(adb "${ADB_SERIAL_ARGS[@]}" shell cmd package resolve-activity --brief "$APP_ID" 2>/dev/null | tr -d '\r' | tail -n 1)"
if [[ -z "$resolved_activity" || "$resolved_activity" == "No activity found" ]]; then
  resolved_activity="$APP_ID/.MainActivity"
fi

echo "Launching: $resolved_activity"
adb "${ADB_SERIAL_ARGS[@]}" shell am start -W -n "$resolved_activity"

echo "Done. App is installed and launched on $serial."
