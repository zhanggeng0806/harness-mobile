package com.dshmobile.shell

import android.content.Context
import android.content.pm.ApplicationInfo
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

/**
 * 运行时快照在线更新：拉取 manifest {url, sha256, size, signature}，
 * 下载快照 → 校验签名 → 校验 SHA-256 → 解压安装（EngineManager 原子换 usr）。
 * 引擎重启交给 EngineService 看门狗在下个轮询完成。
 *
 * 相对原项目（明文 HTTP + 裸 sha256）的改进：
 *  - 强制 HTTPS（仅 debug 构建放行 http 供局域网测试）；
 *  - ECDSA 签名校验（UpdateCrypto）覆盖 sha256，防 manifest 被替换。
 */
class UpdateManager(private val context: Context) {

  /** 程序化覆盖（测试用）；空则读用户持久化的 Prefs.manifestUrl。 */
  var manifestUrl: String? = null

  fun checkAndApply(onStatus: (String) -> Unit, onProgress: (Int) -> Unit = {}) {
    Thread {
      try {
        val url = manifestUrl ?: Prefs(context).manifestUrl
        if (url.isBlank()) {
          onStatus("未配置更新服务器地址")
          return@Thread
        }

        onStatus("检查更新…")
        val manifest = JSONObject(fetchText(url))
        val snapshotUrl = manifest.getString("url")
        val expectedSha = manifest.optString("sha256", "")
        val signature = manifest.optString("signature", "")
        val size = manifest.optLong("size", 0)

        requireSecure(snapshotUrl)

        if (!UpdateCrypto.verifySnapshotSha(expectedSha, signature)) {
          throw IllegalStateException("manifest 签名校验失败")
        }

        onStatus("下载快照（" + size / 1024 / 1024 + " MB）…")
        val tmp = File(context.filesDir, "update.tar.gz")
        download(snapshotUrl, tmp, onProgress)

        if (expectedSha.isNotEmpty()) {
          onStatus("校验 SHA-256…")
          val actual = sha256(tmp)
          if (!actual.equals(expectedSha, ignoreCase = true)) {
            tmp.delete()
            throw IllegalStateException("SHA256 不匹配: " + actual.take(12) + "…")
          }
        }

        onStatus("安装新快照…")
        val ok = EngineManager(context).installDownloaded(tmp, expectedSha) { _, _ -> }
        tmp.delete()
        if (!ok) throw IllegalStateException("快照安装失败")

        // 杀旧引擎：看门狗在数秒内用新运行时重启。
        try {
          Runtime.getRuntime().exec(arrayOf("/system/bin/pkill", "-f", "bin.js")).waitFor()
        } catch (_: Throwable) {
        }
        onStatus("更新完成，引擎已自动重启")
      } catch (t: Throwable) {
        onStatus("更新失败：" + (t.message ?: t.javaClass.simpleName))
      }
    }.start()
  }

  /** 生产强制 HTTPS；debug 构建放行 http（局域网测试服务器）。 */
  private fun requireSecure(url: String) {
    val scheme = try {
      URL(url).protocol
    } catch (_: Throwable) {
      "http"
    }
    if (scheme != "https") {
      val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
      if (!debuggable) throw IllegalStateException("更新必须走 HTTPS")
    }
  }

  private fun fetchText(url: String): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 10_000
    conn.readTimeout = 30_000
    return try {
      val code = conn.responseCode
      if (code != 200) throw IllegalStateException("manifest HTTP $code")
      conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
      conn.disconnect()
    }
  }

  private fun download(url: String, dest: File, onProgress: (Int) -> Unit) {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 10_000
    conn.readTimeout = 120_000
    try {
      val code = conn.responseCode
      if (code != 200) throw IllegalStateException("下载 HTTP $code")
      val total = conn.contentLengthLong
      conn.inputStream.use { input ->
        dest.outputStream().use { out ->
          val buf = ByteArray(64 * 1024)
          var done = 0L
          var lastPct = -1
          var n = input.read(buf)
          while (n >= 0) {
            out.write(buf, 0, n)
            done += n
            if (total > 0) {
              val pct = (done * 100 / total).toInt()
              if (pct != lastPct) {
                lastPct = pct
                onProgress(pct)
              }
            }
            n = input.read(buf)
          }
        }
      }
    } finally {
      conn.disconnect()
    }
  }

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buf = ByteArray(64 * 1024)
      var n = input.read(buf)
      while (n >= 0) {
        digest.update(buf, 0, n)
        n = input.read(buf)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }
}
