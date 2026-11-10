package com.youqu.vod.player

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 播放器画面载体：用 TextureView（避免 SurfaceView 的黑帧闪烁与层级问题），
 * 把它的 Surface 交给实现了 [PlaybackController] 的内核（通过 [PlaybackController.attachSurface]）。
 * 与具体内核解耦，IJK / ExoPlayer 共用。
 */
@Composable
fun VideoSurface(controller: PlaybackController, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        texture: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                        controller.attachSurface(Surface(texture))
                    }

                    override fun onSurfaceTextureSizeChanged(
                        texture: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) = Unit

                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        controller.attachSurface(null)
                        return true
                    }

                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                }
            }
        },
        update = { },
        modifier = modifier
    )
}
