package com.youloft.linkup.net

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object LinkUpEndpoints {
    /** Hardcoded in 1.6.6 as ag.a.f1732c; the shipped build points here, not at production. */
    /** 重建网关（阿里云 ECS 101.133.235.110:3000，Caddy/systemd 管理，pm2 gallery 暂停腾出端口）。 */
    const val API = "http://101.133.235.110:3000/"

    /**
     * Business API answers here today (ag.a.f1733d, the host 1.6.6 treats as the H5/deep-link origin).
     * Unlike the test host this gateway does not enforce the Signature middleware - unsigned GETs pass.
     */
    const val PRODUCTION = "https://link.tttick.com/"

    /** Only the 1.5.x background-location tracker still talks here, with the old be2524844ec4bb25 key. */
    const val TRACKER_LEGACY = "https://dmp.51wnl-cq.com/"
}

class LinkUpClient(
    baseUrl: String = LinkUpEndpoints.API,
    context: DeviceContext,
    baseClient: OkHttpClient = OkHttpClient(),
) {
    val api: LinkUpApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(baseClient.newBuilder().addInterceptor(SignatureInterceptor(context)).build())
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(LinkUpApi::class.java)
}
