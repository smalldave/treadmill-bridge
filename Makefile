.PHONY: build release install clean version lint help

VERSION ?= $(shell git describe --tags --always 2>/dev/null | sed 's/^v//' || echo "0.0.0")
GRADLE  := ./gradlew
APK_DIR := app/build/outputs/apk

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  %-12s %s\n", $$1, $$2}'

build: ## Build debug APK
	$(GRADLE) assembleDebug -PVERSION=$(VERSION)
	@echo "\nAPK: $(APK_DIR)/debug/treadmill-bridge-$(VERSION)-debug.apk"

release: ## Build release APK
	$(GRADLE) assembleRelease -PVERSION=$(VERSION)
	@echo "\nAPK: $(APK_DIR)/release/treadmill-bridge-$(VERSION).apk"

install: build ## Build and install debug APK via adb
	adb install $(APK_DIR)/debug/treadmill-bridge-$(VERSION)-debug.apk

clean: ## Clean build artifacts
	$(GRADLE) clean

version: ## Print current version
	@echo $(VERSION)

lint: ## Run Android lint
	$(GRADLE) lint
