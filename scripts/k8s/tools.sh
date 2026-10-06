#!/usr/bin/env bash
# Installs the pinned versions (deploy/k8s/versions.env) of kind, kubectl and kubeconform into .tools/bin,
# verifying each download against its upstream SHA-256. Re-running is a no-op when the version already matches.
# Usage: scripts/k8s/tools.sh [kind] [kubectl] [kubeconform]   (no argument = all)
set -euo pipefail
# shellcheck source=SCRIPTDIR/lib.sh
source "$(dirname "$0")/lib.sh"

case "$(uname -s)" in
  Linux) os=linux ;;
  Darwin) os=darwin ;;
  *) die "unsupported OS $(uname -s)" ;;
esac
case "$(uname -m)" in
  x86_64 | amd64) arch=amd64 ;;
  arm64 | aarch64) arch=arm64 ;;
  *) die "unsupported architecture $(uname -m)" ;;
esac

sha256() {
  if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

# verify <file> <expected-sha256>
verify() {
  local actual
  actual="$(sha256 "$1")"
  [ -n "$2" ] && [ "$actual" = "$2" ] || die "checksum mismatch for $(basename "$1"): expected '$2', got $actual"
}

installed() { [ -x "$TOOLS_BIN/$1" ] && [ "$(cat "$TOOLS_BIN/.$1.version" 2>/dev/null)" = "$2" ]; }

# place <name> <version> <downloaded-binary>
place() {
  install -m 0755 "$3" "$TOOLS_BIN/$1"
  printf '%s\n' "$2" >"$TOOLS_BIN/.$1.version"
  log "installed $1 $2 in $TOOLS_BIN"
}

install_kind() {
  installed kind "$KIND_VERSION" && return
  local url="https://github.com/kubernetes-sigs/kind/releases/download/$KIND_VERSION/kind-$os-$arch"
  curl -fsSLo "$tmp/kind" "$url"
  verify "$tmp/kind" "$(curl -fsSL "$url.sha256sum" | cut -d' ' -f1)"
  place kind "$KIND_VERSION" "$tmp/kind"
}

install_kubectl() {
  installed kubectl "$KUBECTL_VERSION" && return
  local url="https://dl.k8s.io/release/$KUBECTL_VERSION/bin/$os/$arch/kubectl"
  curl -fsSLo "$tmp/kubectl" "$url"
  verify "$tmp/kubectl" "$(curl -fsSL "$url.sha256")"
  place kubectl "$KUBECTL_VERSION" "$tmp/kubectl"
}

install_kubeconform() {
  installed kubeconform "$KUBECONFORM_VERSION" && return
  local file="kubeconform-$os-$arch.tar.gz"
  local base="https://github.com/yannh/kubeconform/releases/download/$KUBECONFORM_VERSION"
  curl -fsSLo "$tmp/$file" "$base/$file"
  verify "$tmp/$file" "$(curl -fsSL "$base/CHECKSUMS" | awk -v f="$file" '$2 == f { print $1 }')"
  tar -xzf "$tmp/$file" -C "$tmp" kubeconform
  place kubeconform "$KUBECONFORM_VERSION" "$tmp/kubeconform"
}

[ "$#" -gt 0 ] || set -- kind kubectl kubeconform
mkdir -p "$TOOLS_BIN"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
for tool in "$@"; do
  case "$tool" in
    kind | kubectl | kubeconform) "install_$tool" ;;
    *) die "unknown tool $tool" ;;
  esac
done
