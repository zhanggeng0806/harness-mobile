package com.dshmobile.shell

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * 内嵌运行时（Termux 风格 rootfs 快照）的所有权者：
 *  - 首启把快照解压到 filesDir/usr（资产内嵌或在线下载二选一）；
 *  - 负责 dsh 引擎进程生命周期（PATH/LD_LIBRARY_PATH/HOME/DSH_HOME 显式
 *    注入——快照自足，无需 Termux app）。
 *
 * 快照安装采用统一、更安全的语义（相对原项目的一处改进）：
 *  1. 全部解压到 update-stage/（不碰线上目录）；
 *  2. 原子切换 usr（usr → usr-old，stage/usr → usr），失败回滚；
 *  3. home 骨架仅首次安装写入，更新时**永不覆盖** home/.dsh 用户数据——
 *     无需原项目那套"备份→重解压→恢复"舞蹈，也不依赖 dsh 内部目录清单。
 *
 * 与 dsh 内部路径的耦合全部收口在 RuntimeConfig，本类只引用配置。
 */
class EngineManager(
  private val context: Context,
  private val pickToken: String? = null,
  private val engineToken: String? = null,
) {

  val usrDir = File(context.filesDir, RuntimeConfig.USR_DIR)
  val homeDir = File(context.filesDir, RuntimeConfig.HOME_DIR)

  /** 公共导出仓库：/storage/emulated/0/Documents/dshdata（仅用户主动导出）。 */
  val dshDataDir: File
    get() {
      val publicDocs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        ?: File(context.filesDir, "dshdata-fallback")
      return File(publicDocs, "dshdata")
    }

  private val nodeBin = File(usrDir, RuntimeConfig.NODE_BIN.removePrefix("usr/"))
  private val dshBin = File(usrDir, RuntimeConfig.DSH_BIN.removePrefix("usr/"))

  val engineReady: Boolean get() = nodeBin.exists()

  /** 内嵌快照指纹（assets/snapshot.sha256）。 */
  private fun bundledFingerprint(): String = try {
    context.assets.open("snapshot.sha256").bufferedReader().use { it.readText().trim() }
  } catch (_: Exception) {
    ""
  }

  private fun fingerprintFile(): File = File(context.filesDir, ".snapshot-fingerprint")

  /** 快照是否已解压且与内嵌版本一致。 */
  fun snapshotFresh(): Boolean {
    if (!nodeBin.exists()) return false
    val fp = bundledFingerprint()
    if (fp.isEmpty()) return true // 无指纹（纯在线更新安装）不强制重解压
    return fingerprintFile().exists() && fingerprintFile().readText().trim() == fp
  }

  /** 用内嵌资产快照安装运行时（首启或升级时调用）。 */
  fun installFromAsset(onProgress: (Long, Long) -> Unit): Boolean {
    val stream = try {
      context.assets.open(RuntimeConfig.SNAPSHOT_ASSET)
    } catch (t: Throwable) {
      Log.e(TAG, "no bundled " + RuntimeConfig.SNAPSHOT_ASSET, t)
      return false
    }
    return stream.use { installSnapshot(it, 0L, bundledFingerprint(), onProgress) }
  }

  /** 用在线下载的快照文件安装运行时（UpdateManager 校验通过后调用）。 */
  fun installDownloaded(file: File, newFingerprint: String, onProgress: (Long, Long) -> Unit): Boolean {
    return file.inputStream().use { installSnapshot(it, file.length(), newFingerprint, onProgress) }
  }

  /**
   * 统一快照安装：解压到 staging → 校验 → 原子换 usr → 首启写 home 骨架 → 写指纹。
   * 任何失败回滚 usr（保留旧运行时），不污染线上目录。
   */
  private fun installSnapshot(
    stream: InputStream,
    totalBytes: Long,
    newFingerprint: String,
    onProgress: (Long, Long) -> Unit,
  ): Boolean {
    val stage = File(context.filesDir, "update-stage")
    stage.deleteRecursively()
    return try {
      SnapshotExtractor.extract(stream, totalBytes, stage, onProgress)

      val stageUsr = File(stage, RuntimeConfig.USR_DIR)
      if (!File(stageUsr, RuntimeConfig.NODE_BIN.removePrefix("usr/")).exists()) {
        throw IOException("快照缺少 " + RuntimeConfig.NODE_BIN)
      }

      // 原子切换 usr。
      val old = File(context.filesDir, "usr-old")
      old.deleteRecursively()
      if (usrDir.exists() && !usrDir.renameTo(old)) {
        throw IOException("usr 备份失败")
      }
      if (!stageUsr.renameTo(usrDir)) {
        if (!usrDir.exists()) old.renameTo(usrDir) // 回滚
        throw IOException("usr 切换失败")
      }

      // home 骨架：仅首次安装（home/.dsh 不存在时），更新绝不覆盖用户数据。
      homeDir.mkdirs()
      val stageHome = File(stage, RuntimeConfig.HOME_DIR)
      if (stageHome.isDirectory && !File(homeDir, ".dsh").exists()) {
        copyIfMissing(stageHome, homeDir)
      }

      stage.deleteRecursively()
      old.deleteRecursively()
      if (newFingerprint.isNotEmpty()) fingerprintFile().writeText(newFingerprint)
      Log.i(TAG, "snapshot installed (fingerprint " + newFingerprint.take(12) + ")")
      true
    } catch (t: Throwable) {
      val old = File(context.filesDir, "usr-old")
      if (old.exists() && !usrDir.exists()) old.renameTo(usrDir) // 回滚
      stage.deleteRecursively()
      Log.e(TAG, "snapshot install failed; kept old runtime", t)
      false
    }
  }

  /**
   * 递归拷贝骨架，目标已存在则跳过（绝不覆盖）。
   * 符号链接按原样重建、绝不跟随：home 骨架的 profiles/node_modules 是
   * 指向 /data/data/com.termux/files/usr/... 的绝对链接（悬空，靠 termux-exec
   * 的 LD_PRELOAD 在运行时把 Termux 路径重映射到真实 usr），跟随会抛
   * NoSuchFileException。
   */
  private fun copyIfMissing(src: File, dst: File) {
    val srcPath = src.toPath()
    if (java.nio.file.Files.isSymbolicLink(srcPath)) {
      if (!java.nio.file.Files.exists(dst.toPath())) {
        dst.parentFile?.mkdirs()
        java.nio.file.Files.createSymbolicLink(
          dst.toPath(),
          java.nio.file.Files.readSymbolicLink(srcPath),
        )
      }
      return
    }
    if (src.isDirectory) {
      dst.mkdirs()
      src.listFiles()?.forEach { copyIfMissing(it, File(dst, it.name)) }
    } else if (!dst.exists()) {
      dst.parentFile?.mkdirs()
      src.copyTo(dst, overwrite = false)
    }
  }

  /**
   * 确保私有 DSH_HOME 数据布局就绪（幂等）。运行时用户数据全部回私有
   * app data（files/home/.dsh）；公共 Documents/dshdata 仅作用户主动导出仓库。
   */
  fun ensurePrivateDshData(): File {
    val dshData = dshDataDir
    val privateDsh = File(homeDir, ".dsh")
    privateDsh.mkdirs()
    File(privateDsh, ".private-layout").writeText("private")
    try {
      dshData.mkdirs()
      File(dshData, ".nomedia").writeText("")
      File(dshData, "exports").mkdirs()
    } catch (t: Throwable) {
      Log.w(TAG, "public export repo setup failed", t)
    }
    return privateDsh
  }

  /**
   * 启动 dsh web 引擎（内嵌快照）。进程级 CAS + 冷却窗口防双启动。
   * @return true 表示引擎进程已启动（或已在启动中）。
   */
  fun startEngine(port: Int = RuntimeConfig.ENGINE_PORT): Boolean {
    // LD_PRELOAD 依赖快照内的 termux-exec 库：缺失时所有子进程 exec 会失败，
    // 且叠加冷却窗口 = 引擎静默停摆——启动前显式断言，缺失即 loud fail。
    val preload = File(usrDir, RuntimeConfig.PRELOAD_LIB.removePrefix("usr/"))
    if (!preload.exists()) {
      Log.e(TAG, "engine start failed: termux-exec preload missing at " + preload.absolutePath)
      return false
    }
    val now = System.currentTimeMillis()
    if (!STARTING.compareAndSet(false, true)) return true // 已有并发启动在途
    if (now - lastStartAttemptAt < RuntimeConfig.START_COOLDOWN_MS) {
      STARTING.set(false)
      return true
    }
    return try {
      val args = arrayOf(
        nodeBin.absolutePath, "--expose-internals", dshBin.absolutePath,
        "web", "--port", port.toString(),
      )
      val env = mapOf(
        "PATH" to (usrDir.absolutePath + "/bin:/system/bin"),
        "LD_LIBRARY_PATH" to (usrDir.absolutePath + "/lib"),
        "HOME" to homeDir.absolutePath,
        "DSH_HOME" to ensurePrivateDshData().absolutePath,
        "TMPDIR" to File(homeDir, "tmp").apply { mkdirs() }.absolutePath,
        "LD_PRELOAD" to preload.absolutePath,
        "TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE" to "force",
        "TERMUX_EXEC__EXECVE_CALL__INTERCEPT" to "1",
        "TERMUX__ROOTFS" to usrDir.parentFile!!.absolutePath,
        "TERMUX__PREFIX" to usrDir.absolutePath,
        "TERMUX_APP__DATA_DIR" to context.filesDir.parentFile!!.absolutePath,
        "TERMUX_APP__LEGACY_DATA_DIR" to "/data/data/${context.packageName}",
        "TERMUX_VERSION" to "0.118.3",
        RuntimeConfig.ENV_PICK_TOKEN to (pickToken ?: ""),
        RuntimeConfig.ENV_ENGINE_TOKEN to (engineToken ?: ""),
      )
      val proc = startWithArgs(args, env)
      engineProcess = proc
      lastStartAttemptAt = now
      true
    } catch (t: Throwable) {
      Log.e(TAG, "engine start failed", t)
      false
    } finally {
      STARTING.set(false)
    }
  }

  /**
   * 启动引擎进程：直连 exec 被拒（Android 15+ / targetSdk 35+）时回退
   * /system/bin/linker64 加载（等同 JNI 库加载机制，app-data 始终允许）。
   */
  private fun startWithArgs(args: Array<String>, env: Map<String, String>): Process {
    val log = File(context.filesDir, "engine.log")
    fun build(argv: List<String>): ProcessBuilder =
      ProcessBuilder(argv).also { b ->
        b.environment().putAll(env)
        b.redirectErrorStream(true)
        b.redirectOutput(log)
      }
    return try {
      build(args.toList()).start()
    } catch (e: java.io.IOException) {
      if (e.message?.contains("Permission denied") != true) throw e
      Log.w(TAG, "direct exec denied, falling back to linker64: " + e.message)
      build(listOf("/system/bin/linker64") + args.toList()).start()
    }
  }

  /** 停止引擎进程（尽力而为）。 */
  fun stopEngine() {
    engineProcess?.destroy()
    engineProcess = null
    // 手动停止后重置冷却：用户回前台应立即允许重新启动。
    lastStartAttemptAt = 0
  }

  /**
   * 当前引擎进程是否存活（供看门狗做进程级判定，比 HTTP 探测更直接、更快）。
   * 经 companion 的 engineProcess 跨实例可见（MainActivity 与 Service 各自
   * new EngineManager），用公开的 Process.isAlive()，无反射。
   */
  fun isEngineProcessAlive(): Boolean = engineProcess?.isAlive == true

  companion object {
    private const val TAG = "dsh-engine"

    /** 进程级启动 CAS：跨 EngineManager 实例可见（双启动竞态防护）。 */
    val STARTING = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 上次真实启动时刻（epoch ms）；看门狗冷却窗口基准。 */
    @Volatile
    var lastStartAttemptAt: Long = 0

    /** 引擎 node 进程（companion 级，跨实例共享，看门狗据此判活）。 */
    @Volatile
    var engineProcess: Process? = null
  }
}
