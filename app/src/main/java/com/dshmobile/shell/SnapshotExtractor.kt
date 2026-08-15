package com.dshmobile.shell

import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/**
 * 快照解压（tar.gz）：JDK 自带 GZIP 解压 + commons-compress 的 tar 解析。
 * 相比原项目的 xz + 三方 xz 依赖，这里零额外压缩依赖、离线可复现。
 *
 * 保留符号链接与可执行位；文件统一 owner-only 权限（dsh 凭据提供者
 * 会对 world-readable 密钥文件 fail loud）。解压后对可执行文件打
 * security.android.exec 标记（Android 15+ 对 app-data ELF 的 exec 限制）。
 */
object SnapshotExtractor {

  /**
   * 解压 gzip 压缩的 tar 流。
   * @param input 原始 gzip 流。
   * @param totalBytes 预期解压后大小（进度显示用；0 = 未知）。
   * @param dest 目标根目录（filesDir；归档内为 usr/ + home/）。
   * @param onProgress 回调（已解压字节, 总字节）。
   */
  fun extract(input: InputStream, totalBytes: Long, dest: File, onProgress: (Long, Long) -> Unit) {
    val gz = GZIPInputStream(input)
    val tar = TarArchiveInputStream(gz)
    val execFiles = mutableListOf<String>()
    var done = 0L
    var entry: TarArchiveEntry? = tar.nextTarEntry
    while (entry != null) {
      val target = File(dest, entry.name)
      when {
        entry.isDirectory -> target.mkdirs()
        entry.isSymbolicLink -> {
          target.parentFile?.mkdirs()
          // deleteIfExists 不跟随链接：覆盖重解压时旧 symlink 可能悬空
          // （File.exists() 跟随链接对 dangling 返回 false 会漏删）。
          java.nio.file.Files.deleteIfExists(target.toPath())
          java.nio.file.Files.createSymbolicLink(target.toPath(), java.nio.file.Paths.get(entry.linkName))
        }
        else -> {
          target.parentFile?.mkdirs()
          target.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            var n = tar.read(buf)
            while (n >= 0) {
              out.write(buf, 0, n)
              n = tar.read(buf)
            }
          }
          target.setReadable(false, false)
          target.setReadable(true, true)
          target.setWritable(true, true)
          target.setExecutable(entry.mode and 0x40 != 0, true)
          if (entry.mode and 0x40 != 0) execFiles.add(target.absolutePath)
        }
      }
      done += entry.size
      if (done % (1024 * 1024) < entry.size) onProgress(done, totalBytes)
      entry = tar.nextTarEntry
    }
    tar.close()
    stampExecAttribute(execFiles)
  }

  /** 对解压出的可执行文件打 security.android.exec 标记（内核不支持则静默忽略）。 */
  private fun stampExecAttribute(files: List<String>) {
    if (files.isEmpty()) return
    try {
      // 参数数组直传（不经 shell），文件名里的引号/元字符不会被解释。
      val base = listOf("/system/bin/setfattr", "-n", "security.android.exec", "-v", "1")
      // 并发批次（每批最多 64 个），避免一次 spawn 过多进程。
      files.chunked(64).forEach { batch ->
        val procs = batch.map { f -> ProcessBuilder(base + f).redirectErrorStream(true).start() }
        for (p in procs) {
          val finished = p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
          if (!finished) p.destroyForcibly()
        }
      }
    } catch (_: Throwable) {
      // 无该属性的内核（模拟器/旧版）无需处理。
    }
  }
}
