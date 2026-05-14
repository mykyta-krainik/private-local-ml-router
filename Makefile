.PHONY: up down logs server server-jar frontend dev test train-classifier train-ner train-visual

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

# ── Model training ───────────────────────────────────────────────────────────
train-classifier:
	cd training && pip install -r requirements.txt && \
	  python stage1_classifier/train_mobilebert.py && \
	  python stage1_classifier/export_tflite.py

train-ner:
	cd training && pip install -r requirements.txt && \
	  python stage2_ner/train_xlm_roberta_ner.py && \
	  python stage2_ner/export_tflite.py

train-visual:
	cd training && pip install -r requirements.txt && \
	  python stage2b_visual/prepare_dataset.py && \
	  python stage2b_visual/train_yolov8n.py && \
	  python stage2b_visual/export_tflite.py
