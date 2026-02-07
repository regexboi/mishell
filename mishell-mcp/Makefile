SHELL := /bin/bash

UV ?= uv
CONFIG ?= ./mishell.toml
PORT ?= 8067
PYTEST_ARGS ?= -q

.PHONY: help setup sync lock test run-http run-stdio ui config reload smoke clean

help: ## Show available commands
	@echo "mishell-mcp common commands"
	@echo
	@awk 'BEGIN {FS = ":.*## "} /^[a-zA-Z0-9_.-]+:.*## / {printf "  %-12s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

setup: ## Create/update environment with dev dependencies
	$(UV) sync --extra dev

sync: ## Alias for setup
	$(UV) sync --extra dev

lock: ## Refresh uv.lock
	$(UV) lock

test: ## Run test suite
	$(UV) run pytest $(PYTEST_ARGS)

run-http: ## Start HTTP server + UI on localhost
	$(UV) run mishell-mcp serve-http --config $(CONFIG) --port $(PORT)

run-stdio: ## Start STDIO MCP server
	$(UV) run mishell-mcp serve-stdio --config $(CONFIG)

ui: ## Open local UI page (macOS)
	open http://127.0.0.1:$(PORT)/

config: ## Print current config file path and contents
	@echo "CONFIG=$(CONFIG)"
	@cat $(CONFIG)

reload: ## Trigger admin reload endpoint (HTTP mode must be running)
	curl -sS -X POST http://127.0.0.1:$(PORT)/api/reload | python3 -m json.tool

smoke: ## Quick smoke check against running HTTP server
	curl -sS http://127.0.0.1:$(PORT)/api/config | python3 -m json.tool

clean: ## Remove common local test/build caches
	find . -type d \( -name __pycache__ -o -name .pytest_cache -o -name .mypy_cache -o -name '*.egg-info' \) -prune -exec rm -rf {} +
