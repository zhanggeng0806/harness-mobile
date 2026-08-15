#!/usr/bin/env bash
# 生成 debug 签名密钥库（标准 Android debug key，无保密性）。
# 本仓库不提交 *.keystore；构建前运行一次即可。
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/../app/debug.keystore"

if [ -f "$OUT" ]; then
  echo "已存在: $OUT（跳过）"
  exit 0
fi

keytool -genkeypair -v \
  -keystore "$OUT" \
  -alias androiddebugkey \
  -storepass android \
  -keypass android \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US"

echo "OK: $OUT"
