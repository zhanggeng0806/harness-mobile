# Harness mobile — DeepSeek Harness Android 壳

在 Android 上跑完整的 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)（dsh web agent，能真实执行 bash）。
一个 APK 装完即用：**WebView 壳 + 内嵌运行时快照（解压即跑）+ 前台保活 + 升级版看门狗 + SAF 目录桥 + 签名在线更新**。

## 功能

- **内嵌运行时**：tar.gz 快照（node + bash + coreutils + dsh + termux-exec），首启解压、从应用自身目录启动，完全离线、无需 Termux app；
- **移动 UI**：系统 WebView 加载 `http://127.0.0.1:3080`；
- **保活**：前台服务 + 看门狗（进程存活判定 + 「活着但不响应」的 wedge 检测 + 冷却窗口防双启）；
- **签名在线更新**：manifest 驱动快照热替换（HTTPS + ECDSA 签名 + sha256 → 原子换 usr → 自动重启）；
- **SAF 目录桥**：`pickDirectory` 把所选目录映射为真实路径（`/storage/emulated/0/…`）；
- **电池友好**：电池优化豁免 + 暂停引擎。

## 架构

```
MainActivity ── WebView ── http://127.0.0.1:3080  (dsh web)
     │ 桥(window.androidBridge)  SAF 目录选择 / 通知 / 深色主题 / 会话导出
     ▼
EngineManager ── 快照安装(staging→原子换 usr) + 启动 node .../dsh/lib/bin.js web --port 3080
     │
EngineService ── 前台服务 + 看门狗(5s，进程存活 + wedge 检测)
     │
UpdateManager ── HTTPS manifest + ECDSA 签名 + sha256 + 原子安装
```

关键坑位（Android 15/16 实测教训）：`targetSdk=34`（35+ 禁止 exec app-data ELF）、
`/system/bin/linker64` 回退、`termux-exec` 的 `LD_PRELOAD` 重路由、90s 冷却窗口 vs 5s 看门狗、companion 级 CAS 防双启动。

详见 [docs/design.md](docs/design.md)。

## 构建

要求：JDK 17+、Android SDK（compileSdk 34）、Gradle 8.5+。

```sh
# 1. 生成 debug 签名密钥库（本仓库不提交 *.keystore；本机 ~/.android 不可写时也要用它）
bash scripts/gen-debug-keystore.sh

# 2. 构建
export JAVA_HOME=<jdk17> ANDROID_HOME=<android-sdk>
gradle assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

> 首次可能需联网下载依赖；`gradle.properties` 已关文件监视以适配受限环境。

## 运行时快照（外部化，约 136MB 不入库）

不放快照也能编译；App 首启会提示放入快照或配置更新服务器。快照可从
[dsh-mobile-apk 的 Releases](https://github.com/kelai141/dsh-mobile-apk/releases) 下载 `snapshot-arm64.tar.xz`，
或按 `scripts/make-snapshot.sh` 在 Termux 设备自打。

```sh
# 方式 A：用现成的 arm64 快照
#   下载 snapshot-arm64.tar.xz 后，转成 gzip tar 并改名为 snapshot.bin（AAPT2 会解压 .gz，故用 .bin 后缀）：
python - <<'PY'
import tarfile
with tarfile.open('snapshot-arm64.tar.xz','r:xz') as src, tarfile.open('snapshot.bin','w:gz') as dst:
    for m in src:
        dst.addfile(m, src.extractfile(m) if m.isfile() else None)
PY
cp snapshot.bin app/src/main/assets/snapshot.bin
sha256sum snapshot.bin | awk '{print $1}' > app/src/main/assets/snapshot.sha256

# 方式 B：Termux 设备自打（ldd 只收集实际依赖）
bash scripts/make-snapshot.sh            # 产出 snapshot.tar.gz + snapshot.sha256
cp snapshot.tar.gz app/src/main/assets/snapshot.bin

# 在线更新（可选）：生成密钥 + 签名 manifest + 起服务器
node scripts/gen-keys.mjs                 # 公钥贴入 UpdateCrypto.kt，私钥勿提交
node scripts/gen-manifest.mjs snapshot.bin https://host/snapshot.bin > manifest.json
node scripts/snapshot-server.mjs --manifest manifest.json --snapshot snapshot.bin
```

## 桥协议 v1（`window.androidBridge`）

| 方法 | 说明 |
|---|---|
| `version()` | 桥版本 `"1.0"`，页面 feature-detect |
| `checkEngine()` | 探测 127.0.0.1:3080，JSON `{running,latencyMs,httpCode}` |
| `keepScreenOn(enable)` | 屏幕常亮 |
| `showNotification(title,text)` | 通知测试 |
| `pickDirectory(callbackId)` | SAF 目录选择，结果经 `__dshBridge.onDirectoryPicked` 回传 |
| `pauseEngine()` | 暂停引擎 |
| `hasAllFilesAccess()` / `requestAllFilesAccess()` | All Files Access 探测/授权 |
| `getPickToken()` / `getEngineToken()` | 目录选择 / API 鉴权 token |

## 权限

`INTERNET`、`POST_NOTIFICATIONS`、`FOREGROUND_SERVICE(_DATA_SYNC)`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`；
可选 `MANAGE_EXTERNAL_STORAGE`（外部工作区，用户主动授权）。SAF 目录选择本身无需权限。

## License

MIT。
