package com.youqu.vod

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
// Coil 3 起 crossfade 是 coil3.request 包下的扩展函数，必须显式导入
import coil3.request.crossfade
import com.youqu.vod.core.CrashHandler
import com.youqu.vod.di.AppContainer
import com.youqu.vod.direct.DirectRuntime
import com.youqu.vod.direct.LocalGateway
import com.youqu.vod.direct.TokenProvider
import com.youqu.vod.direct.UpstreamClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class VodApplication : Application() {

    lateinit var container: AppContainer
        private set

    /** APP 内置网关：把 PHP 的 api/image/m3u8/stream 四个端点搬进客户端 */
    private var localGateway: LocalGateway? = null

    /**
     * 与内置网关共用同一个实例。设置页「强制刷新访问凭证」必须刷新它，
     * 否则新 token 只在设置页生效、网关仍在用旧凭证。
     */
    lateinit var tokenProvider: TokenProvider
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // 必须最先装：任何一处未捕获异常（含 native 播放器 JNI 崩溃）都会留下现场
        CrashHandler.install(this)
        container = AppContainer(this)

        // 启动内置网关（同步，端口绑定很快）。
        // 账号密码不进 APP：token 由服务器 token.php 下发，见 TokenProvider。
        // 启动失败时 DirectRuntime 保持为空，GatewayUrls 自动回退到原 PHP 网关。
        tokenProvider = TokenProvider(this, container.settings)
        runCatching {
            val gateway = LocalGateway(tokenProvider, UpstreamClient(tokenProvider))
            val base = gateway.start()
            if (base != null) {
                localGateway = gateway
                DirectRuntime.localBase = base
            } else {
                gateway.shutdown()
            }
        }

        // Coil 3 的全局图片加载器（封面走网关图片端点，内置网关会本地解密）
        SingletonImageLoader.setSafe {
            ImageLoader.Builder(this)
                .crossfade(true)
                .build()
        }
        // 启动时拉一次远程网关域名；失败不影响启动，沿用本地已有地址
        appScope.launch {
            container.domainRepository.ensure()
        }
    }

    override fun onTerminate() {
        runCatching { localGateway?.shutdown() }
        DirectRuntime.localBase = ""
        super.onTerminate()
    }
}
