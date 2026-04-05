SHELL := /bin/bash

APP_ID ?= ai.mishell.app
ANDROID_SERIAL ?= adb-R3CW70EQ9CE-Ckclu8

.PHONY: help build-debug phone run-device doctor-device logcat

help:
	@echo "Targets:"
	@echo "  make phone           Build, install, and launch on connected phone"
	@echo "  make build-debug     Build debug APK"
	@echo "  make doctor-device   Show adb/device status"
	@echo "  make logcat          Tail logs for app/runtime crashes"
	@echo ""
	@echo "Optional vars:"
	@echo "  APP_ID=<applicationId>       (default: $(APP_ID))"
	@echo "  ANDROID_SERIAL=<device-id>   (default: $(ANDROID_SERIAL))"

build-debug:
	./gradlew assembleDebug

phone:
	APP_ID="$(APP_ID)" ANDROID_SERIAL="$(ANDROID_SERIAL)" ./scripts/run-device.sh

run-device: phone

doctor-device:
	@command -v adb >/dev/null || { echo "adb not found in PATH"; exit 1; }
	@adb devices -l
	@echo ""
	@echo "Pinned device:"
	@adb -s "$(ANDROID_SERIAL)" get-state

logcat:
	@bash -lc 'if command -v rg >/dev/null; then adb -s "$(ANDROID_SERIAL)" logcat | rg --line-buffered "$(APP_ID)|AndroidRuntime|FATAL EXCEPTION"; else adb -s "$(ANDROID_SERIAL)" logcat | grep --line-buffered -E "$(APP_ID)|AndroidRuntime|FATAL EXCEPTION"; fi'
