# 第三方许可声明（Third-Party Notices）

Harness mobile 使用了以下开源组件，各自保留其原始许可证。分发本应用时需遵守相应条款。

## 应用本体（本仓库）

| 组件 | 许可证 | 上游 |
|---|---|---|
| DeepSeek Harness（`@deepseek-ai/dsh` 及其 `@deepseek-ai/*` 插件） | MIT | https://github.com/deepseek-ai/deepseek-harness |
| Node.js | MIT | https://nodejs.org |
| Apache Commons Compress | Apache-2.0 | https://commons.apache.org/proper/commons-compress |
| Kotlin / AndroidX / Android SDK | Apache-2.0 | https://developer.android.com |

## 内嵌运行时快照（外部化，构建时加入）

运行时快照内含来自 Termux 的二进制，主要许可证：

| 组件 | 许可证 |
|---|---|
| bash | GPL-3.0 |
| coreutils | GPL-3.0 |
| node | MIT |
| termux-exec | GPL-3.0 |
| 其余 Termux 包 | 见各自上游（多数为 GPL 或宽松许可） |

> ⚠️ **GPL 合规提示**：分发包含 GPL 组件（bash、coreutils、termux-exec 等）的产物时，
> 需遵守 GPL 相应义务（向接收者提供这些组件的对应源码）。上架分发前请核对快照内每个二进制
> 的许可证，或改用许可证更宽松的替代组件。

## 商标声明

「DeepSeek」名称与鲸鱼 logo 为 DeepSeek 的商标。本项目**不包含、也未获授权使用** DeepSeek
商标素材；应用图标与品牌请使用自有原创素材。
