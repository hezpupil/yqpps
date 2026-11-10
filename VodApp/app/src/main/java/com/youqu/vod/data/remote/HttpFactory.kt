package com.youqu.vod.data.remote

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * 可插拔鉴权拦截器。
 *
 * 目前网关本身负责登录与 token 维护，APP 侧不需要带 token；
 * 若后续网关改成要求 APP 携带凭证，只需让 tokenProvider 返回对应值即可，
 * 无需改动任何调用处。
 */
class AuthInterceptor(
    private val tokenProvider: () -> String? = { null }
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val token = tokenProvider()?.trim().orEmpty()
        val next = if (token.isEmpty()) {
            request
        } else {
            request.newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .build()
        }
        return chain.proceed(next)
    }
}

object HttpFactory {

    fun createMoshi(): Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    fun createOkHttp(
        debug: Boolean,
        interceptors: List<Interceptor> = listOf(AuthInterceptor())
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
        interceptors.forEach { builder.addInterceptor(it) }
        if (debug) {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
            builder.addInterceptor(logging)
        }
        return builder.build()
    }

    /** 下载专用 client：不设读超时（大文件），不加日志拦截器（避免刷屏） */
    fun createDownloadOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .build()

    fun createApi(baseUrl: String, okHttp: OkHttpClient, moshi: Moshi): ApiService {
        return Retrofit.Builder()
            // 真实请求地址由 @Url 提供，这里只作为解析基准
            .baseUrl(GatewayUrls.normalize(baseUrl))
            .client(okHttp)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(ApiService::class.java)
    }
}
