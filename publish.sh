#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROD_DIR="/home/ebarrab/pro/controlex"
PROD_PUBLIC="$PROD_DIR/public"
PROD_SERVER="$PROD_DIR/server.js"

echo "==> Compilando plugin..."
cd "$SCRIPT_DIR"
./gradlew buildPlugin --quiet

ZIP_PATH=$(ls "$SCRIPT_DIR/build/distributions/"*.zip | sort -V | tail -1)
ZIP_NAME=$(basename "$ZIP_PATH")
VERSION="${ZIP_NAME%.zip}"
VERSION="${VERSION#controlex-}"

echo "==> Plugin compilado: $ZIP_NAME (v$VERSION)"

echo "==> Copiando a producción (dev → prod)..."
# Plugin ZIP
cp "$ZIP_PATH" "$PROD_PUBLIC/$ZIP_NAME"
# Backend (server.js) — el de dev es la fuente de verdad; debe referenciar
# ya el zip recién compilado (lo hace /agh antes de invocar publish).
cp "$SCRIPT_DIR/server/server.js" "$PROD_SERVER"
# Panel (index.html) — fuente de verdad también el dev.
cp "$SCRIPT_DIR/server/public/index.html" "$PROD_PUBLIC/index.html"

# Sanity check: que el server.js apunte al ZIP que acabamos de copiar
if ! grep -q "$ZIP_NAME" "$PROD_SERVER"; then
    echo "AVISO: $PROD_SERVER no referencia $ZIP_NAME. Revisa que /agh haya"
    echo "       actualizado la constante PLUGIN_ZIP en server/server.js."
fi

echo "==> Reload PM2 (zero-downtime)..."
pm2 reload controlex --update-env

echo ""
echo "✓ v$VERSION desplegado en https://controlex.ebarrab.com"
echo "  Plugin: https://controlex.ebarrab.com/plugin"
