# Harness mobile

在 Android 上运行完整的 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)（dsh web agent，能真实执行 bash）。

一个 APK 装完即用：WebView 壳 + 内嵌运行时快照（解压即跑）+ 前台保活看门狗 + SAF 目录桥 + 签名在线更新。内置运行时为 dsh 0.1.5-rc.1（arm64）。

## 快照路径重定向

内置运行时快照是在**构建它的那个 App 的数据目录**里做出来的，因此宿主包名与数据目录被烘进了 `profiles/*/cordis.patch.yml`、`@dsh-android/*` 插件代码、`usr/bin` 脚本的 shebang 以及 `usr/lib` 的构建元数据。本 App 的包名不同，直接照搬会让 `shell-termux` 的 `bashPath` 指向不存在的目录，**所有 bash 能力不可用**。

App 会在启动引擎前做一次路径重定向（外来宿主包名 → 本 App 包名、`/data/data/<pkg>/files` → 真实 `filesDir`）。它幂等、带版本化标记，并在每次启动时复查 profile 配置：**用 dsh 的「撤销」恢复到修复前的历史快照后，下一次启动会自动重写回来**。

安装后如需确认是否已生效：

```sh
grep -n bashPath ~/.dsh/profiles/web/cordis.patch.yml   # 应是本 App 包名
ls ~/../.runtime-retarget-v*                             # 标记文件，存在即已跑过
```

> 判定「已修 / 未修」请看 **App 版本号（v0.2.2 起）** 与上面的标记文件——快照内的移植层插件版本号由上游维护，不会随本次修复变化。

## 构建

需 JDK 17 + Android SDK 34 + Gradle 8.5+：

```sh
bash scripts/gen-debug-keystore.sh
gradle assembleDebug
```

> 运行时快照（约 157MB）不入库，构建前需放到 `app/src/main/assets/snapshot.bin`，详见 `scripts/make-snapshot.sh` 与 `docs/design.md`。

## 下载

预编译 APK 见 [Releases](https://github.com/zhanggeng0806/harness-mobile/releases)。

## License

MIT
