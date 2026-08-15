# dsh-mobile 设计文档（改进版）

> 从零重写 [dsh-mobile-apk](https://github.com/kelai141/dsh-mobile-apk) 的改进实现。
> 目标：一个 APK 装完即用，在 Android 上跑完整的 DeepSeek Harness（dsh web agent，能真实执行 bash）。

## 1. 形态与边界

- **纯壳**：WebView 只消费 `http://127.0.0.1:3080`（内嵌运行时里的 dsh web 服务）。
- **内嵌运行时**：tar.gz 快照（node + bash + coreutils + dsh + termux-exec），首启解压、从应用自身目录启动，无需 Termux app。
- **零侵入**：页面侧不改动，桥能力全部经 `@JavascriptInterface` 注入，桥协议版本化。

## 2. 模块

| 文件 | 职责 |
|---|---|
| `MainActivity` | WebView 壳 + 引擎引导页 + 桥接线 + SAF 目录选择 + 文件上传 + 通知 + 深色主题桥 + 会话导出 + 电池豁免 |
| `EngineManager` | 快照安装（staging 解压 → 原子换 usr）+ 引擎进程生命周期（env 注入 + linker64 回退 + CAS + 冷却） |
| `EngineService` | 前台服务 + 升级版看门狗（进程存活 + wedge 检测） |
| `SnapshotExtractor` | tar.gz 解压（JDK GZIP + commons-compress tar），保留 symlink/exec 位，打 `security.android.exec` |
| `AndroidBridge` | `window.androidBridge` JS 桥 |
| `UpdateManager` | 在线更新（HTTPS + ECDSA 签名 + sha256 + 原子安装） |
| `UpdateCrypto` | ECDSA P-256 签名校验（固定公钥） |
| `EngineProbe` / `Prefs` / `RuntimeConfig` | 探测 / 用户设置 / 路径与常量收口 |

## 3. 桥协议 v1（`window.androidBridge`）

| 方法 | 签名 | 说明 |
|---|---|---|
| `version` | getter → string | `"1.0"`，页面 feature-detect |
| `checkEngine` | () → string | 探测 127.0.0.1:3080，JSON `{running, latencyMs, httpCode}` |
| `keepScreenOn` | (enable) | 屏幕常亮 |
| `showNotification` | (title, text) | 通知测试通道 |
| `pickDirectory` | (callbackId) | SAF 目录选择；结果经 `window.__dshBridge.onDirectoryPicked(callbackId, path)` 异步回传 |
| `pauseEngine` | () | 暂停引擎（省电） |
| `hasAllFilesAccess` / `requestAllFilesAccess` | () → bool / () | All Files Access 探测与授权 |
| `getPickToken` / `getEngineToken` | () → string? | 目录选择/API 鉴权 token |

## 4. 快照格式

`snapshot.tar.gz`（gzip + tar）内含：

```text
usr/
  bin/{node,bash,sh,coreutils...}
  lib/（ldd 解析出的共享库 + libtermux-exec-ld-preload.so）
  lib/node_modules/@deepseek-ai/dsh/
home/（首启骨架，App 首次安装才写入）
```

由 `scripts/make-snapshot.sh` 在 Termux 设备上组装（`ldd` 只收集实际依赖，比整包 prefix 更小）。

## 5. 在线更新协议（签名）

1. App 拉取 `manifest.json`：`{url, sha256, size, signature}`。
2. `signature` 是 ECDSA P-256 对 `"sha256=" + sha256` 的 DER 签名（base64）；App 用固定公钥校验（`UpdateCrypto`）。
3. 下载快照 → 校验 sha256 → `EngineManager` 解压到 staging → 原子换 `usr` → 杀旧引擎 → 看门狗重启。

相对原项目（明文 HTTP + 裸 sha256）的两点加固：**强制 HTTPS**（debug 构建才放行 http 供局域网测试）、**ECDSA 签名**防 manifest 被 MITM 替换。

密钥：`scripts/gen-keys.mjs` 生成（公钥已内嵌 App，私钥在 `scripts/update-key.private.pem`，勿提交）；`scripts/gen-manifest.mjs` 签名；`scripts/snapshot-server.mjs` 提供测试服务器。

## 6. 引擎启动的关键坑位（沿用原项目实测教训）

- **targetSdk 34**：Android 15+ 禁止 targetSdk 35+ 的应用直接 exec app-data ELF。
- **linker64 回退**：直连 exec 被拒时改用 `/system/bin/linker64` 加载。
- **termux-exec LD_PRELOAD**：`TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE=force` 把被拒 exec 重路由到系统 linker（Android 16 实测需要）。
- **冷却窗口 90s vs 看门狗 5s**：node 冷启动 20-45s，5s 轮询会误判双启动（EADDRINUSE）。
- **CAS 双启动防护**：MainActivity 与 Service 各自 new EngineManager，实例字段互不可见，必须 companion 级 CAS。

## 7. 相对原项目的改进

1. 快照格式 xz → **tar.gz**：去掉三方 xz 依赖，离线可复现，JDK 自带 GZIP。
2. **统一快照安装**：staging 解压 + 原子换 usr + 永不覆盖 home/.dsh（原项目资产路径/下载路径两套语义 + 备份恢复舞蹈）。
3. **看门狗修复**：原版在"引擎已运行"时直接 return 导致看门狗从不武装；此处始终武装，并加**进程存活 + wedge 检测**。
4. **签名更新**：HTTPS + ECDSA 签名（原为 HTTP + 裸 sha256）。
5. **本地引擎 API token**：`DSH_ENGINE_TOKEN` 钩子（原只有 pick token）。
6. **电池友好**：电池优化豁免 + 暂停引擎。
7. **去除 Shizuku 依赖**（原为未实现的 stub）与未使用的三方依赖。
8. **构建不再强制要求快照资产**：无快照也能编译，运行时外部化（assets 或下载）。
9. **工具链入库**：make-snapshot / gen-keys / gen-manifest / snapshot-server 均在仓库内（原项目引用但缺失）。

## 8. 构建

```sh
# 要求：JDK 17、Android SDK（compileSdk 34）、Gradle 8.5+
export JAVA_HOME=<jdk17> ANDROID_HOME=<android-sdk>
gradle assembleDebug          # 产物 app/build/outputs/apk/debug/app-debug.apk

# 可选：放置运行时快照（不放则运行时外部化：首次运行从更新服务器下载）
cp snapshot.tar.gz app/src/main/assets/snapshot.tar.gz
echo "<sha256>" > app/src/main/assets/snapshot.sha256
```
