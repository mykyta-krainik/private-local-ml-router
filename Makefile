.PHONY: up down logs server server-jar frontend dev test

# ── asdf Java shim (resolves .tool-versions; no-op if asdf is not installed) ─
_ASDF_JAVA_VER := $(shell awk '/^java /{print $$2}' .tool-versions 2>/dev/null)
_ASDF_JAVA_DIR := $(HOME)/.asdf/installs/java/$(_ASDF_JAVA_VER)
ifneq ($(shell test -x $(_ASDF_JAVA_DIR)/bin/java && echo yes),)
  export JAVA_HOME := $(_ASDF_JAVA_DIR)
  export PATH      := $(JAVA_HOME)/bin:$(PATH)
endif

# ── Docker (recommended) ────────────────────────────────────────────────────
up:
	docker compose up --build

down:
	docker compose down

logs:
	docker compose logs -f

# ── Local (no Docker) ───────────────────────────────────────────────────────
server:
	./gradlew :demo-server:run

server-jar:
	./gradlew :demo-server:shadowJar
	java -jar demo-server/build/libs/demo-server-1.0.0-all.jar

frontend:
	cd frontend && npm install && npm run dev

test:
	./gradlew :demo-server:test

dev:
	$(MAKE) -j2 server frontend
