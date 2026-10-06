#!/bin/sh
set -e

export GCX_INSTALL_DIR="${GCX_INSTALL_DIR:-/root/.local/bin}"
export PATH="$GCX_INSTALL_DIR:$PATH"

if ! command -v gcx >/dev/null 2>&1; then
  echo "Installing gcx into $GCX_INSTALL_DIR ..."
  curl -fsSL https://raw.githubusercontent.com/grafana/gcx/main/scripts/install.sh | sh
else
  echo "gcx already installed at $(command -v gcx), skipping install."
fi

ln -sf "$GCX_INSTALL_DIR/gcx" /usr/local/bin/gcx

gcx --version || true

# Sanity-check the Grafana connection using the GRAFANA_* env vars above.
# Non-fatal: Grafana may still be starting up when this container boots.
gcx config check || echo "gcx config check failed - Grafana may still be starting, retry with: docker exec -it gcx-cli gcx config check"

# Keep the container up so you can `docker exec` into it whenever you want.
tail -f /dev/null