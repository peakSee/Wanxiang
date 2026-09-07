package top.wanxiang.app.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Singleton
class HttpClientProvider @Inject constructor() {

    fun create(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun createKtorClient(okHttpClient: OkHttpClient): HttpClient = HttpClient(OkHttp) {
        expectSuccess = false
        followRedirects = true
        engine {
            // Keep the Android-tested OkHttp configuration while exposing the
            // streaming API through Ktor.
            preconfigured = okHttpClient
        }
    }
}
