package com.dshmobile.shell

import android.util.Base64
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * 在线更新的签名校验：ECDSA P-256（SHA256withECDSA），公钥固定在 APK 内。
 * manifest 携带的 `signature` 覆盖 `sha256` 值（签 "sha256=" + sha256hex），
 * 使快照完整性不再依赖"manifest 与快照同源"这一薄弱假设——即使走非 TLS
 * 通道，替换 manifest + 重算 sha256 的 MITM 也无法伪造签名。
 *
 * 仅用 JDK 内置 JCA（无三方依赖）；EC/X509/Signature 在 API 26+ 均可用。
 */
object UpdateCrypto {

  /** 固定的 ECDSA P-256 公钥（X.509/SPKI，base64）。由 scripts/gen-keys.mjs 生成。 */
  private const val PINNED_PUBLIC_KEY_B64 =
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEPqL/MZsLGc++l4FLTN/JSssAbXIF2/p7W0u0dpukSYeaoWYkCrFJeJ/9T98e1uf+CFr/iXh56W2hhk5yygRMkQ=="

  /** 是否已配置公钥（未配置则无法校验签名，仅开发态）。 */
  val hasKey: Boolean
    get() = PINNED_PUBLIC_KEY_B64.isNotBlank() && PINNED_PUBLIC_KEY_B64 != "PASTE_PUBLIC_KEY_HERE"

  /**
   * 校验 manifest 的 signature 是否覆盖 expectedSha256。
   * @param expectedSha256 快照 sha256（hex，小写）。
   * @param signatureB64 base64 的 ECDSA DER 签名。
   */
  fun verifySnapshotSha(expectedSha256: String, signatureB64: String): Boolean {
    if (!hasKey || expectedSha256.isBlank() || signatureB64.isBlank()) return false
    return try {
      val keyBytes = Base64.decode(PINNED_PUBLIC_KEY_B64, Base64.DEFAULT)
      val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(keyBytes))
      val sig = Signature.getInstance("SHA256withECDSA")
      sig.initVerify(pub)
      sig.update(("sha256=" + expectedSha256).toByteArray(Charsets.UTF_8))
      sig.verify(Base64.decode(signatureB64, Base64.DEFAULT))
    } catch (_: Throwable) {
      false
    }
  }
}
