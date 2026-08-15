#!/usr/bin/env bash
#
# make-snapshot.sh — 在 Termux 设备（aarch64）或带 proot 的 Linux 上组装
# dsh 运行时快照。产出 snapshot.tar.gz（usr/ + home/）与 snapshot.sha256。
#
# 快照自足（无需 Termux app）：App 首启解压后直接从自身目录 exec node 启动
# dsh web。相比原项目打包整个 Termux prefix（~70MB），这里用 ldd 只收集
# node/bash/coreutils 实际依赖的共享库，体积更小。
#
# 前置（在 Termux 内）：
#   pkg install nodejs-lts coreutils bash tar binutils   # binutils 提供 ldd
#   npm i -g @deepseek-ai/dsh                            # 或 pnpm add -g
#
# 用法：bash make-snapshot.sh [输出名，默认 snapshot.tar.gz]
set -euo pipefail

PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
OUT="${1:-snapshot.tar.gz}"

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
mkdir -p "$STAGE/usr/bin" "$STAGE/usr/lib" "$STAGE/home"

# 把二进制与其运行时共享库一并拷入快照（cp -L 解引用符号链接）。
copy_with_deps() {
  local bin="$1"
  [ -e "$bin" ] || return 0
  cp -L "$bin" "$STAGE/usr/bin/"
  ldd "$bin" 2>/dev/null | grep -oE '/[^ ]+\.so[^ ]*' | while read -r so; do
    local rel="${so#$PREFIX/}"
    mkdir -p "$STAGE/usr/$(dirname "$rel")"
    cp -L "$so" "$STAGE/usr/$rel"
  done
}

# 1. 运行时二进制：node + dsh 的 bash 工具所需的最小 coreutils 集。
copy_with_deps "$PREFIX/bin/node"
for b in bash sh ls cat cp mv rm mkdir tar xz grep sed awk find ps env dirname; do
  copy_with_deps "$PREFIX/bin/$b"
done

# 2. termux-exec 预载库：Android 15+/16 直连 exec app-data ELF 被拒时，
#    把 exec 重路由到 /system/bin/linker64 的钩子（EngineManager 依赖它）。
for preload in libtermux-exec-ld-preload.so libtermux-exec.so; do
  [ -e "$PREFIX/lib/$preload" ] && cp -L "$PREFIX/lib/$preload" "$STAGE/usr/lib/"
done

# 3. dsh 包（全局 node_modules）。
DSH_SRC="$PREFIX/lib/node_modules/@deepseek-ai/dsh"
if [ -d "$DSH_SRC" ]; then
  mkdir -p "$STAGE/usr/lib/node_modules/@deepseek-ai"
  cp -a "$DSH_SRC" "$STAGE/usr/lib/node_modules/@deepseek-ai/dsh"
else
  echo "!! 未找到 $DSH_SRC —— 请先 npm i -g @deepseek-ai/dsh" >&2
  exit 1
fi

# 4. 打包 + 哈希（tar.gz：App 用 JDK GZIP + commons-compress tar 解压）。
( cd "$STAGE" && tar -czf - usr home ) > "$OUT"
sha256sum "$OUT" | awk '{print $1}' > snapshot.sha256

echo "OK: $OUT ($(du -h "$OUT" | cut -f1))"
echo "sha256: $(cat snapshot.sha256)"
echo "下一步：把 $OUT 放到 app/src/main/assets/snapshot.tar.gz，"
echo "       并把 sha256 写入 app/src/main/assets/snapshot.sha256（供升级时指纹比对）。"
