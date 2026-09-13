package com.dshmobile.shell

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

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

      // home 处理：
      //  - profiles（dsh 配置 + 插件）以快照为准整体更新——升级 dsh 时必须更新，
      //    否则旧插件配新 dsh 会装配失败；用户手动 patch 的 profile 需在新版上重打。
      //  - 其余骨架仅补齐缺失项；用户数据（sessions/storages/凭据/settings）在
      //    .dsh 下、不在 profiles 内，天然不受影响。
      homeDir.mkdirs()
      val stageHome = File(stage, RuntimeConfig.HOME_DIR)
      if (stageHome.isDirectory) {
        val stageProfiles = File(stageHome, ".dsh/profiles")
        if (stageProfiles.isDirectory) {
          val destProfiles = File(homeDir, ".dsh/profiles")
          destProfiles.deleteRecursively()
          copyTree(stageProfiles, destProfiles)
        }
        copyIfMissing(stageHome, homeDir)
      }
      // 新快照又带着「它自己宿主 App 的包名」回来，作废重定向标记，
      // 让下一次启动引擎前重新改写成本 App 的路径。
      retargetMarker().delete()

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
      // 符号链接必须「删了再建」（幂等）：悬空链接下 Files.exists() 返回 false，
      // 但链接文件本身已存在（例如前一步 copyTree 刚建过），直接 create 会抛
      // FileAlreadyExistsException。
      dst.parentFile?.mkdirs()
      java.nio.file.Files.deleteIfExists(dst.toPath())
      java.nio.file.Files.createSymbolicLink(
        dst.toPath(),
        java.nio.file.Files.readSymbolicLink(srcPath),
      )
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

  /** 递归拷贝并覆盖目标（用于以快照为准整体替换 profiles）；符号链接按原样重建。 */
  private fun copyTree(src: File, dst: File) {
    val srcPath = src.toPath()
    if (java.nio.file.Files.isSymbolicLink(srcPath)) {
      dst.parentFile?.mkdirs()
      java.nio.file.Files.deleteIfExists(dst.toPath())
      java.nio.file.Files.createSymbolicLink(
        dst.toPath(),
        java.nio.file.Files.readSymbolicLink(srcPath),
      )
      return
    }
    if (src.isDirectory) {
      dst.mkdirs()
      src.listFiles()?.forEach { copyTree(it, File(dst, it.name)) }
    } else {
      dst.parentFile?.mkdirs()
      java.nio.file.Files.deleteIfExists(dst.toPath())
      src.copyTo(dst, overwrite = true)
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

  // ---------------------------------------------------------------------------
  // dsh web 浏览器鉴权（launch token → 会话 Cookie）
  // ---------------------------------------------------------------------------

  /** 引擎日志文件（进程输出重定向目标）。 */
  fun engineLogFile(): File = File(context.filesDir, RuntimeConfig.ENGINE_LOG)

  /**
   * 解析本次 dsh web 打印的一次性 launch token。
   *
   * dsh web 自带浏览器鉴权：启动时打印 `dsh web: http://127.0.0.1:3080/?token=…`，
   * 用该地址 GET 一次会种下 30 天签名的 HttpOnly Cookie 并 303 到 `/`。token 是
   * 进程级随机值（源码里是 PROCESS_LAUNCH_TOKENS WeakMap，无 flag/env 可覆盖），
   * 所以只能从日志里取。
   *
   * 取最后一条：日志每次启动被截断，正常只有一条；防御性地容忍残留。
   */
  fun launchToken(): String? {
    val text = try {
      val f = engineLogFile()
      if (!f.exists()) return null
      f.readText()
    } catch (_: Throwable) {
      return null
    }
    return TOKEN_PATTERN.findAll(text).lastOrNull()?.groupValues?.get(1)
  }

  /**
   * 有界等待 launch token（冷启动 20-45s，token 在 Web 服务就绪时才打印）。
   * 只在后台线程调用。
   */
  fun awaitLaunchToken(timeoutMs: Long): String? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
      launchToken()?.let { return it }
      if (System.currentTimeMillis() >= deadline) return null
      try {
        Thread.sleep(200)
      } catch (_: InterruptedException) {
        return null
      }
    }
  }

  /**
   * WebView 入口地址：优先带 launch token——首帧必须由它换取会话 Cookie。
   *
   * 取不到 token 时退回干净 URL：旧版 dsh 无鉴权本就该这样；新版则依赖
   * Cookie 罐里已有的会话（token 每次进程启动都轮换，但 Cookie 的签名密钥
   * 持久化在 credentials 里，30 天内跨重启仍然有效）。
   */
  fun webEntryUrl(): String {
    val token = launchToken() ?: return RuntimeConfig.ENGINE_URL
    return RuntimeConfig.ENGINE_URL + "/?token=" + token
  }

  /**
   * 用 launch token 走一次原生握手，换回 dsh 的会话 Cookie。
   *
   * 原生 HTTP 通道（会话导出等 `/api` 请求）没有 WebView 的 Cookie 罐，而
   * dsh 的 `/api` 走 requestRejection、只认 Cookie（不认 `?token=`），所以必须
   * 先换 Cookie 再手动带上。旧版 dsh（无 token）返回 null，调用方按无鉴权处理。
   *
   * 注意不要跟随 303：Cookie 只在跳转响应的 Set-Cookie 上，跟随后拿不到。
   */
  fun engineCookie(force: Boolean = false): String? {
    if (!force) cachedCookie?.let { return it }
    val token = launchToken() ?: return null
    return try {
      val conn = URL(RuntimeConfig.ENGINE_URL + "/?token=" + token).openConnection() as HttpURLConnection
      conn.instanceFollowRedirects = false
      conn.connectTimeout = 3_000
      conn.readTimeout = 3_000
      conn.requestMethod = "GET"
      val code = conn.responseCode
      val setCookie = conn.headerFields.entries
        .firstOrNull { it.key?.equals("Set-Cookie", ignoreCase = true) == true }
        ?.value?.firstOrNull()
      conn.disconnect()
      if (code != HTTP_SEE_OTHER || setCookie.isNullOrBlank()) null
      else setCookie.substringBefore(';').also { cachedCookie = it }
    } catch (t: Throwable) {
      Log.w(TAG, "launch token exchange failed", t)
      null
    }
  }

  // ---------------------------------------------------------------------------
  // 快照宿主包名重定向
  // ---------------------------------------------------------------------------

  /**
   * 重定向标记：<filesDir>/.runtime-retarget-v<rev>-<ownPkg>。
   * 带逻辑版本号——升级判定规则后老标记自动失效，会重跑一遍。
   */
  private fun retargetMarker(): File =
    File(context.filesDir, ".runtime-retarget-v" + RETARGET_REVISION + "-" + context.packageName)

  /**
   * 把快照里「按快照宿主 App 包名写死」的路径改写成本 App 的实际值。
   *
   * 快照是在某个具体 App 的数据目录里做出来的，所以路径被烘进了很多地方：
   *  - `home/.dsh/profiles/<profile>/cordis.patch.yml` → shell-termux 的 bashPath/prefix/home…
   *  - `home/.dsh/profiles/<profile>/node_modules/@dsh-android/…` → IME 包名、shared_prefs、DSH_HOME 兜底
   *  - `usr/bin/…` → 脚本 shebang（#!/data/user/0/&lt;宿主包名&gt;/files/usr/bin/bash）
   *  - `usr/lib/…` → libtool .la、Makefile、ruby/perl/python 元数据
   *
   * v0.1.0 用的快照恰好构建于 `com.dshmobile.shell`，与本 App 同名；v0.2.x 换成
   * 上游 v0.14.0-preview 快照后，其宿主包名是 `com.dsharnessmobile.shell` ——
   * 本机并不存在该目录，于是 shell-termux 的 `accessSync(bashPath, X_OK)` 失败，
   * **所有 bash 能力直接不可用**；usr/bin 里 186 个脚本的 shebang 也一并失效。
   *
   * 幂等且只需跑一次（标记文件兜底）。二进制按「文件头魔数 + NUL 密度（带文本
   * 扩展名豁免）」判定后跳过，不碰 ELF/.so/.node/字体/图片。
   *
   * @param force 忽略标记强制重跑。
   * @return 实际改写的文件数；0 表示无需改写或此前已完成。
   */
  fun retargetRuntimePaths(force: Boolean = false): Int {
    val marker = retargetMarker()
    if (!force && marker.exists()) return 0
    val own = context.packageName
    val replacements = FOREIGN_HOST_PACKAGES.map { it to own } +
      // 顺带把 /data/data/<pkg>/files 归一化到本机真实路径
      // （/data/data 是 /data/user/0 的绑定挂载，但这种写法在部分系统上不可靠）。
      listOf("/data/data/$own/files" to context.filesDir.absolutePath)
    val probes = FOREIGN_HOST_PACKAGES.map { it.toByteArray(Charsets.US_ASCII) }

    var changed = 0
    var occurrences = 0
    try {
      for (root in listOf(usrDir, File(homeDir, ".dsh/profiles"))) {
        if (!root.isDirectory) continue
        // Files.walk 默认不跟随符号链接：node_modules 里既有悬空链接（指向
        // /data/data/com.termux/...），也有指向 .pnpm 的链接，跟随会重复遍历
        // 甚至成环；不跟随也能覆盖 .pnpm 实体目录。
        java.nio.file.Files.walk(root.toPath()).use { stream ->
          val iterator = stream.iterator()
          while (iterator.hasNext()) {
            val path = iterator.next()
            if (!java.nio.file.Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue
            val count = rewriteIfStale(path.toFile(), replacements, probes) ?: continue
            changed++
            occurrences += count
          }
        }
      }
      marker.parentFile?.mkdirs()
      marker.writeText("ok\n")
      Log.i(TAG, "runtime paths retargeted: $changed files, $occurrences occurrences")
    } catch (t: Throwable) {
      Log.e(TAG, "runtime path retarget failed", t)
    }
    return changed
  }

  /**
   * 命中旧包名则改写并返回替换次数，否则返回 null（未改动）。
   * 先做字节级预筛，避免为绝大多数无命中的文件付出 UTF-8 解码与分配成本。
   */
  private fun rewriteIfStale(
    file: File,
    replacements: List<Pair<String, String>>,
    probes: List<ByteArray>,
  ): Int? {
    val length = file.length()
    if (length <= 0L || length > MAX_RETARGET_FILE_BYTES) return null
    val bytes = try {
      file.readBytes()
    } catch (_: Throwable) {
      return null
    }
    if (probes.none { bytes.containsAscii(it) }) return null
    if (looksBinary(bytes, file.name)) return null

    var text = String(bytes, Charsets.UTF_8)
    var total = 0
    for ((from, to) in replacements) {
      val count = text.split(from).size - 1
      if (count > 0) {
        text = text.replace(from, to)
        total += count
      }
    }
    if (total == 0) return null
    return try {
      file.writeBytes(text.toByteArray(Charsets.UTF_8))
      total
    } catch (_: Throwable) {
      null
    }
  }

  /** 字节序列包含判定（needle 均为 ASCII，可安全按字节比较）。 */
  private fun ByteArray.containsAscii(needle: ByteArray): Boolean {
    if (needle.isEmpty() || size < needle.size) return false
    val first = needle[0]
    outer@ for (i in 0..size - needle.size) {
      if (this[i] != first) continue
      for (j in 1 until needle.size) if (this[i + j] != needle[j]) continue@outer
      return true
    }
    return false
  }

  /**
   * 二进制判定：改写二进制会把长度不同的替换写坏（后面所有字节都要平移）。
   *
   * 只看「前 8KB 有没有 NUL」是不够的——`@dsh-android/dsh-android-manage/lib/index.js`
   * 内嵌了 NUL 字节但确实是文本，会被误判跳过。所以：
   *  1. 先看文件头魔数（ELF/PE/gzip/xz/zip/png/字体/静态库…）→ 直接判二进制；
   *  2. 再看 NUL，但带文本扩展名的文件豁免。
   */
  private fun looksBinary(bytes: ByteArray, name: String): Boolean {
    if (startsWithAny(bytes, BINARY_MAGICS)) return true
    val probeLength = minOf(bytes.size, BINARY_PROBE_BYTES)
    var hasNul = false
    for (i in 0 until probeLength) {
      if (bytes[i] == 0.toByte()) {
        hasNul = true
        break
      }
    }
    return hasNul && !isTextExtension(name)
  }

  private fun startsWithAny(bytes: ByteArray, prefixes: List<ByteArray>): Boolean {
    for (prefix in prefixes) {
      if (bytes.size < prefix.size) continue
      var same = true
      for (i in prefix.indices) {
        if (bytes[i] != prefix[i]) {
          same = false
          break
        }
      }
      if (same) return true
    }
    return false
  }

  /** 取扩展名（跳过 .revbak/.bak/.orig 之类备份后缀）判断是否为文本类。 */
  private fun isTextExtension(name: String): Boolean {
    var core = name.lowercase()
    for (suffix in BACKUP_SUFFIXES) {
      if (core.endsWith(suffix)) {
        core = core.dropLast(suffix.length)
        break
      }
    }
    val ext = core.substringAfterLast('.', "")
    return ext.isNotEmpty() && ext in TEXT_EXTENSIONS
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
      // 必须在引擎读配置前完成：快照里的宿主包名路径不改写，shell-termux 的
      // bashPath 就指向不存在的目录，bash 全线不可用。幂等（标记兜底），
      // 且此刻持有 STARTING，不会被并发的看门狗重入。
      retargetRuntimePaths()
      val args = arrayOf(
        nodeBin.absolutePath, "--expose-internals", dshBin.absolutePath,
        // --no-open：dsh web 默认会拉起系统默认浏览器（Android 上必然失败，
        // 还会多 fork 一个 node 打开器并打印一条无害但误导的错误）。壳自己
        // 用 WebView 承载界面，直接关掉这个 handoff。
        "web", "--port", port.toString(), "--no-open",
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
      // 新进程 = 新 launch token：丢掉上一轮的 Cookie 缓存，强制重新换取。
      cachedCookie = null
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
    val log = engineLogFile()
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

    /** dsh 会话 Cookie 缓存（companion 级：Activity 与 Service 各自 new EngineManager）。 */
    @Volatile
    var cachedCookie: String? = null

    /** dsh web 启动行里的 launch token（`http://127.0.0.1:<port>/?token=<b64url>`）。 */
    private val TOKEN_PATTERN = Regex("""127\.0\.0\.1:\d+/\?token=([A-Za-z0-9_-]+)""")

    /** 换 Cookie 成功的响应码（dsh 用 303 See Other 跳回干净 URL）。 */
    private const val HTTP_SEE_OTHER = 303

    /**
     * 快照宿主 App 的包名——快照按这些包名把数据目录烘进了配置、插件与脚本。
     * 本 App 的包名不同，必须重定向（见 retargetRuntimePaths）。
     * 已知来源：上游 kelai141/dsh-mobile-apk 的 v0.14.0-preview 快照。
     */
    private val FOREIGN_HOST_PACKAGES = listOf("com.dsharnessmobile.shell")

    /** 重定向时单个文件的大小上限：超过即认为是二进制/大资源，直接跳过。 */
    private const val MAX_RETARGET_FILE_BYTES = 10L * 1024 * 1024

    /** 二进制探测窗口：前 8KB 出现 NUL 即判定为二进制。 */
    private const val BINARY_PROBE_BYTES = 8192

    /**
     * 重定向逻辑版本。改判定规则时必须递增：标记文件带版本号，老标记不会
     * 让新逻辑被跳过（升级 App 后仍会重跑一遍）。
     */
    private const val RETARGET_REVISION = 2

    /** 常见二进制文件头。 */
    private val BINARY_MAGICS: List<ByteArray> = listOf(
      byteArrayOf(0x7F, 0x45, 0x4C, 0x46), // ELF（含 .so / .node / 可执行）
      byteArrayOf(0x4D, 0x5A), // PE / EXE
      byteArrayOf(0x1F, 0x8B.toByte()), // gzip
      byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0x00), // xz
      byteArrayOf(0x42, 0x5A, 0x68), // bzip2
      byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte()), // zstd
      byteArrayOf(0x50, 0x4B, 0x03, 0x04), // zip / apk / jar
      byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte()), // 7z
      byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), // png
      byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), // jpeg
      byteArrayOf(0x47, 0x49, 0x46, 0x38), // gif
      byteArrayOf(0x52, 0x49, 0x46, 0x46), // riff（webp/wav/avi）
      byteArrayOf(0x25, 0x50, 0x44, 0x46), // pdf
      byteArrayOf(0x00, 0x61, 0x73, 0x6D), // wasm
      byteArrayOf(0x00, 0x01, 0x00, 0x00), // ttf
      byteArrayOf(0x4F, 0x54, 0x54, 0x4F), // otf
      byteArrayOf(0x77, 0x4F, 0x46, 0x32), // woff2
      byteArrayOf(0x77, 0x4F, 0x46, 0x46), // woff
      "!<arch>".toByteArray(Charsets.US_ASCII), // ar 静态库
    )

    /** 备份后缀：判扩展名前先剥掉。 */
    private val BACKUP_SUFFIXES = listOf(".revbak", ".bak", ".orig", ".tmp", ".save")

    /** 文本类扩展名（含 NUL 也仍然按文本处理）。 */
    private val TEXT_EXTENSIONS = setOf(
      "js", "mjs", "cjs", "jsx", "ts", "tsx", "json", "json5", "map", "lock",
      "yml", "yaml", "toml", "ini", "cfg", "conf", "properties", "env", "list", "mod", "sum",
      "sh", "bash", "zsh", "fish", "ps1", "bat", "cmd",
      "py", "rb", "pl", "pm", "php", "lua", "tcl", "awk", "sed", "r",
      "txt", "md", "markdown", "rst", "log", "csv", "tsv",
      "html", "htm", "xml", "svg", "css", "scss", "less", "vue",
      "la", "pc", "inc", "cmake", "ac", "m4", "h", "c", "cc", "cpp", "hpp",
      "java", "kt", "gradle", "patch", "diff", "tmpl", "tpl", "template",
      "service", "desktop", "rule", "spec", "proto", "sql", "gql", "thrift", "idl",
      "sample", "example", "dist", "in",
    )
  }
}
