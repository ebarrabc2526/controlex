#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROD_PUBLIC="/home/ebarrab/pro/controlex/public"
PROD_SERVER="/home/ebarrab/pro/controlex/server.js"

echo "==> Compilando plugin..."
cd "$SCRIPT_DIR"
./gradlew buildPlugin --quiet

ZIP_PATH=$(ls "$SCRIPT_DIR/build/distributions/"*.zip | sort -V | tail -1)
ZIP_NAME=$(basename "$ZIP_PATH")
VERSION="${ZIP_NAME%.zip}"
VERSION="${VERSION#controlex-}"

echo "==> Plugin compilado: $ZIP_NAME (v$VERSION)"

echo "==> Copiando a producción..."
cp "$ZIP_PATH" "$PROD_PUBLIC/$ZIP_NAME"

# Actualizar la constante PLUGIN_ZIP en server.js
sed -i "s|controlex-[0-9.]*\.zip|$ZIP_NAME|g" "$PROD_SERVER"

# Sincronizar también server.js de desarrollo
cp "$PROD_SERVER" "$SCRIPT_DIR/server/server.js"

echo "==> Reiniciando servidor..."
pm2 reload controlex

echo ""
echo "Plugin v$VERSION publicado en https://controlex.ebarrab.com/plugin"
