# Harness mobile

在 Android 上运行完整的 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)（dsh web agent，能真实执行 bash）。

一个 APK 装完即用：WebView 壳 + 内嵌运行时快照（解压即跑）+ 前台保活看门狗 + SAF 目录桥 + 签名在线更新。

## 构建

需 JDK 17 + Android SDK 34 + Gradle 8.5+：

```sh
bash scripts/gen-debug-keystore.sh
gradle assembleDebug
```

> 运行时快照（约 136MB）不入库，构建前需放到 `app/src/main/assets/snapshot.bin`，详见 `scripts/make-snapshot.sh` 与 `docs/design.md`。

## 下载

预编译 APK 见 [Releases](https://github.com/zhanggeng0806/harness-mobile/releases)。

## License

MIT
