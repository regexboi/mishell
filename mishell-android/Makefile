SHELL := /bin/bash

APP_ID ?= ai.mishell.app
ANDROID_SERIAL ?=

.PHONY: help build-debug run-device doctor-device logcat

help:
	@echo "Targets:"
	@echo "  make run-device      Build, install, and launch on connected phone"
	@echo "  make build-debug     Build debug APK"
	@echo "  make doctor-device   Show adb/device status"
	@echo "  make logcat          Tail logs for app/runtime crashes"
	@echo ""
	@echo "Optional vars:"
	@echo "  APP_ID=<applicationId>       (default: $(APP_ID))"
	@echo "  ANDROID_SERIAL=<device-id>   (pick a specific phone if multiple connected)"

build-debug:
	./gradlew assembleDebug

run-device:
	APP_ID="$(APP_ID)" ANDROID_SERIAL="$(ANDROID_SERIAL)" ./scripts/run-device.sh

doctor-device:
	@command -v adb >/dev/null || { echo "adb not found in PATH"; exit 1; }
	@adb devices -l

logcat:
	@bash -lc 'if command -v rg >/dev/null; then adb logcat | rg --line-buffered "$(APP_ID)|AndroidRuntime|FATAL EXCEPTION"; else adb logcat | grep --line-buffered -E "$(APP_ID)|AndroidRuntime|FATAL EXCEPTION"; fi'
