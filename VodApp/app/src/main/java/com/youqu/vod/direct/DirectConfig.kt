package com.youqu.vod.direct

import com.youqu.vod.data.local.SettingsRepository

/**
 * APP 内置网关常量。
 *
 * 【凭证边界 · 重要】
 * 上游账号密码不在这里、也不在 APP 任何位置。APP 只向服务器 token.php 换取
 * token + device；账号密码始终留在服务器 web/crawler/config.php。
 *
 * 本文件里的 AES key 是「图片内容解混淆密钥」（来自 APP SVideo 组件的公开常量），
 * 不是账号凭证，与 token 无关。
 */
object DirectConfig {

    /** 上游业务站点兜底值；token.php 会下发 base_url 覆盖它 */
    const val DEFAULT_UPSTREAM = "https://sixth.xxcjpt.com"

    /** token.php 兜底地址（当网关域名下取不到 token.php 时尝试） */
    const val FALLBACK_TOKEN_URL = "https://yq-app.yqnb.lol/token.php"

    /** 可选访问密钥，与服务器 config.php 的 token_access_key 对应；空表示不校验 */
    const val TOKEN_ACCESS_KEY = ""

    /** 图片域名白名单（image.php 原始行为） */
    const val IMAGE_HOST = "beta.100njp.com"

    /** 流媒体域名白名单（KEY / 分片） */
    val SEGMENT_HOSTS = setOf("mail.xxcjpt.com", "dev.100njp.com")

    /** KEY 相对地址时补齐的主机 */
    const val KEY_FALLBACK_HOST = "https://mail.xxcjpt.com"

    /** 图片混淆：AES-128-ECB / PKCS7，key 为 16 字节 ASCII */
    const val IMAGE_AES_KEY = "976f97d638360cde"

    /** 图片回源 UA（不带 abab 后缀） */
    const val IPHONE_UA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 16_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"

    /** 本地网关端口候选：避开原有 StreamProxy 的 18925~18929，两套可并存 */
    val PORTS = intArrayOf(19025, 19026, 19027, 19028, 19029)

    fun apiUserAgent(device: String): String = "$IPHONE_UA abab/$device"

    /** token.php 地址：跟随远程下发的网关域名，换域名无需发版 */
    fun tokenUrl(settings: SettingsRepository): String =
        settings.baseUrl.trim().trimEnd('/') + "/token.php"
}
