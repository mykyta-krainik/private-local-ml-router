#!/usr/bin/env python3
"""Pull and verify the Ollama model used by the demo server."""

import os
import shutil
import subprocess
import sys

MODEL = os.environ.get("OLLAMA_MODEL", "gemma2:4b")


def check_ollama() -> None:
    if not shutil.which("ollama"):
        print("ERROR: 'ollama' not found on PATH.")
        print("Install it from https://ollama.com/download then re-run.")
        sys.exit(1)


def pull_model(model: str) -> None:
    print(f"Pulling model '{model}' (this may take a while on first run)…")
    result = subprocess.run(["ollama", "pull", model], check=False)
    if result.returncode != 0:
        print(f"ERROR: 'ollama pull {model}' failed.")
        sys.exit(result.returncode)


def list_local_models() -> list[str]:
    result = subprocess.run(["ollama", "list"], capture_output=True, text=True)
    return result.stdout


def main() -> None:
    check_ollama()

    local = list_local_models()
    model_base = MODEL.split(":")[0]
    if model_base in local:
        print(f"Model '{MODEL}' already present locally.")
    else:
        pull_model(MODEL)

    print(f"\nOllama is ready. Model '{MODEL}' is available.")
    print("The Ollama daemon starts automatically on first API call.")
    print(f"API endpoint: http://localhost:11434/v1/chat/completions")


if __name__ == "__main__":
    main()
