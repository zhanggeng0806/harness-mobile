package com.dshmobile.shell

/**
 * 把与 dsh 引擎/快照布局强耦合的路径与常量集中在此，隔离"壳"与"引擎内部"。
 * 升级 dsh 或更换快照布局时只需改这一处，业务代码不直接散落内部路径。
 */
object RuntimeConfig {

  /** 本机引擎 Web 服务地址（WebView 与探测共用）。 */
  const val ENGINE_URL = "http://127.0.0.1:3080"
  const val ENGINE_PORT = 3080

  /** 快照解压后的根目录（相对 filesDir）。 */
  const val USR_DIR = "usr"
  const val HOME_DIR = "home"

  /**
   * 内嵌快照资产名。注意不能用 ".gz" 结尾：AAPT2 会自动解压 .gz 资产并改名
   * 为去扩展名版本（实测 snapshot.tar.gz → assets/snapshot.tar，且内容被解成
   * 裸 tar），导致运行时 GZIP 解压失败。".bin" 不会被 AAPT2 特殊处理，内容保持 gzip。
   */
  const val SNAPSHOT_ASSET = "snapshot.bin"

  /** 引擎关键二进制（相对 filesDir）。 */
  const val NODE_BIN = "usr/bin/node"
  const val DSH_BIN = "usr/lib/node_modules/@deepseek-ai/dsh/lib/bin.js"

  /** termux-exec 预载库：Android 16 直连 exec app-data ELF 被拒时的重路由钩子。 */
  const val PRELOAD_LIB = "usr/lib/libtermux-exec-ld-preload.so"

  /** 看门狗启动冷却窗口：冷启动 node boot 需 20-45s，5s 轮询会误判双启动。 */
  const val START_COOLDOWN_MS = 90_000L

  /** 引擎 Web 服务就绪轮询上限（秒）。 */
  const val BOOT_TIMEOUT_SEC = 45

  /** 目录选择桥鉴权 token 的环境变量名（dsh 侧 web-compat 插件校验）。 */
  const val ENV_PICK_TOKEN = "DSH_PICK_TOKEN"

  /** 本地引擎 API 鉴权 token（供 dsh 侧插件对全部 HTTP 请求校验的钩子）。 */
  const val ENV_ENGINE_TOKEN = "DSH_ENGINE_TOKEN"

  /**
   * 在线更新 manifest 默认地址。生产必须为 HTTPS；为空表示未配置，
   * UI 会提示用户填写（首次运行前不会自动更新）。
   */
  const val DEFAULT_MANIFEST_URL = ""
}
