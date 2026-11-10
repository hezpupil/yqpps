package com.youqu.vod.data.remote

import com.youqu.vod.data.model.ApiEnvelope
import com.youqu.vod.data.model.EmptyData
import com.youqu.vod.data.model.HotListData
import com.youqu.vod.data.model.IndexData
import com.youqu.vod.data.model.RecentData
import com.youqu.vod.data.model.SearchData
import com.youqu.vod.data.model.ShowData
import retrofit2.http.GET
import retrofit2.http.Url

/**
 * 网关接口（api.php）。
 *
 * 所有方法都接收完整 URL（@Url），因为网关地址可以在「设置」里随时改动，
 * 这样改地址后无需重建 Retrofit 实例即可立即生效。
 */
interface ApiService {

    @GET
    suspend fun index(@Url url: String): ApiEnvelope<IndexData>

    @GET
    suspend fun recent(@Url url: String): ApiEnvelope<RecentData>

    @GET
    suspend fun hot(@Url url: String): ApiEnvelope<HotListData>

    @GET
    suspend fun search(@Url url: String): ApiEnvelope<SearchData>

    @GET
    suspend fun show(@Url url: String): ApiEnvelope<ShowData>

    @GET
    suspend fun playtime(@Url url: String): ApiEnvelope<EmptyData>

    @GET
    suspend fun deviceActive(@Url url: String): ApiEnvelope<EmptyData>

    @GET
    suspend fun login(@Url url: String): ApiEnvelope<EmptyData>
}
