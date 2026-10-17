#!/usr/bin/env bash
# macOS only: installs Ollama (Homebrew) and pulls a Qwen model sized for an 8GB-RAM Mac, or cleans it all up.
#   scripts/ollama.sh install   install Ollama, start the server, pull $OLLAMA_MODEL
#   scripts/ollama.sh clean     remove $OLLAMA_MODEL (keeps Ollama and other models)
#   scripts/ollama.sh purge     stop and uninstall Ollama and delete ~/.ollama (all models)
# Config (env): OLLAMA_MODEL (default qwen3:4b, ~2.5GB at Q4; fits 8GB RAM). Alternatives: qwen2.5:3b, qwen3:1.7b.
# Safe to re-run.
set -euo pipefail

MODEL="${OLLAMA_MODEL:-qwen3:4b}"
OLLAMA_HOST_URL="${OLLAMA_HOST_URL:-http://localhost:11434}"

log() { printf '[ollama] %s\n' "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }

[ "$(uname -s)" = Darwin ] || die "ollama.sh supports macOS only"

server_up() { curl -fsS --max-time 2 "$OLLAMA_HOST_URL/api/version" >/dev/null 2>&1; }

start_server() {
  server_up && return 0
  log "starting the Ollama server (brew services)"
  brew services start ollama >/dev/null
  for _ in $(seq 1 30); do
    server_up && return 0
    sleep 1
  done
  die "Ollama server did not become ready at $OLLAMA_HOST_URL"
}

install() {
  command -v brew >/dev/null || die "Homebrew is required (https://brew.sh)"
  if command -v ollama >/dev/null; then
    log "ollama already installed ($(ollama --version 2>&1 | tail -n1))"
  else
    log "installing ollama"
    brew install ollama
  fi
  start_server
  log "pulling $MODEL"
  ollama pull "$MODEL"
  log "done: run 'ollama run $MODEL' or call $OLLAMA_HOST_URL/v1/chat/completions (OpenAI-compatible)"
}

clean() {
  command -v ollama >/dev/null || { log "ollama not installed; nothing to clean"; return 0; }
  start_server
  if ollama list | awk 'NR > 1 { print $1 }' | grep -qx "$MODEL"; then
    log "removing $MODEL"
    ollama rm "$MODEL"
  else
    log "$MODEL not present"
  fi
}

purge() {
  if command -v brew >/dev/null && brew list --formula ollama >/dev/null 2>&1; then
    log "stopping and uninstalling ollama"
    brew services stop ollama >/dev/null 2>&1 || true
    brew uninstall ollama
  fi
  pkill -x ollama 2>/dev/null || true
  if [ -d "$HOME/.ollama" ]; then
    log "deleting $HOME/.ollama (all downloaded models)"
    rm -rf "$HOME/.ollama"
  fi
  log "done"
}

case "${1:-}" in
  install) install ;;
  clean) clean ;;
  purge) purge ;;
  *) die "usage: $0 {install|clean|purge}" ;;
esac
