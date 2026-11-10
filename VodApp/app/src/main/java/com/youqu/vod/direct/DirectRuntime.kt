package com.youqu.vod.direct

/**
 * APP 内置网关的运行时开关。
 *
 * 本地网关启动成功后写入 localBase；此时所有数据/图片/播放请求走 APP 内实现（直连上游）。
 * 若启动失败（端口被占、初始化异常），localBase 保持为空，GatewayUrls 自动回退到
 * 原来部署在服务器上的 api.php / image.php / m3u8.php —— 保证老版本行为可用，便于灰度与排障。
 */
object DirectRuntime {
    @Volatile
    var localBase: String = ""

    val enabled: Boolean get() = localBase.isNotEmpty()
}
