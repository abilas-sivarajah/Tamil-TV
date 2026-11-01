package com.tvclient.app.data.remote

import android.annotation.SuppressLint
import com.tvclient.app.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Einziger OkHttp-Client der App - wird von Scraper, Coil und Media3 geteilt,
 * damit Verbindungen und DNS-Lookups nur einmal aufgebaut werden.
 * Inklusive tolerantem SSL-TrustManager, da Streaming-Hoster und Mirror-Domains
 * oft selbstsignierte oder unvollstaendige Zertifikatsketten nutzen, die auf
 * aelteren Fire-OS-Versionen sonst abgelehnt werden.
 */
object ApiClient {

    const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private val trustAllCerts = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    val okHttp: OkHttpClient by lazy {
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAllCerts), SecureRandom())
        }

        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val req = chain.request()
                val builder = req.newBuilder()
                if (req.header("User-Agent") == null) {
                    builder.header("User-Agent", DESKTOP_USER_AGENT)
                }
                chain.proceed(builder.build())
            }
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(
                        HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC)
                    )
                }
            }
            .build()
    }

    val service: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(normalizeBaseUrl(BuildConfig.API_BASE_URL))
            .client(okHttp)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }

    private fun normalizeBaseUrl(raw: String): String =
        if (raw.endsWith("/")) raw else "$raw/"
}
