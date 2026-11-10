package com.youqu.vod.direct

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 封面图解密（等价于 PHP 的 image.php）。
 *
 * 上游返回：base64( AES-128-ECB( key="976f97d638360cde", PKCS7 ) 加密的字节 )
 * 解密后是字符串 "data:<mime>;base64,<图片字节的base64>"
 * 再解一层 base64 得到真实 JPEG/PNG 字节。
 */
object ImageDecryptor {

    data class Image(val mime: String, val bytes: ByteArray)

    private val MIME_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9.+-]*/[A-Za-z0-9][A-Za-z0-9.+-]*$")
    private val DATA_URI = Regex("^data:([^;]+);base64,(.*)$", RegexOption.DOT_MATCHES_ALL)

    fun decrypt(rawBody: ByteArray): Image? {
        return try {
            // 1) 响应体本身是 base64 文本
            val cipher = Base64.decode(String(rawBody, Charsets.UTF_8).trim(), Base64.DEFAULT)
            if (cipher.isEmpty()) return null

            // 2) AES-128-ECB / PKCS7（Java 里 PKCS5Padding 等价于 PKCS7）
            val key = SecretKeySpec(DirectConfig.IMAGE_AES_KEY.toByteArray(Charsets.US_ASCII), "AES")
            val plain = Cipher.getInstance("AES/ECB/PKCS5Padding").run {
                init(Cipher.DECRYPT_MODE, key)
                doFinal(cipher)
            }

            // 3) data URI
            val match = DATA_URI.find(String(plain, Charsets.UTF_8).trim()) ?: return null
            val mime = match.groupValues[1].takeIf { MIME_REGEX.matches(it) }
                ?: "application/octet-stream"
            val bytes = Base64.decode(match.groupValues[2].trim(), Base64.DEFAULT)
            if (bytes.isEmpty()) return null
            Image(mime, bytes)
        } catch (e: Exception) {
            null
        }
    }
}
