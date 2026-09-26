package com.bitwarden.network.retrofit

import com.bitwarden.network.core.NetworkResultCallAdapterFactory
import com.bitwarden.network.interceptor.AuthTokenManager
import com.bitwarden.network.interceptor.BaseUrlInterceptor
import com.bitwarden.network.interceptor.BaseUrlInterceptors
import com.bitwarden.network.interceptor.CookieInterceptor
import com.bitwarden.network.interceptor.HeadersInterceptor
import com.bitwarden.network.interceptor.PermissionInterceptor
import com.bitwarden.network.ssl.CertificateProvider
import com.bitwarden.network.ssl.configureSsl
import com.bitwarden.network.util.HEADER_KEY_AUTHORIZATION
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Primary implementation of [Retrofits].
 */
@Suppress("LongParameterList")
internal class RetrofitsImpl(
    authTokenManager: AuthTokenManager,
    baseUrlInterceptors: BaseUrlInterceptors,
    cookieInterceptor: CookieInterceptor,
    headersInterceptor: HeadersInterceptor,
    json: Json,
    private val permissionInterceptor: PermissionInterceptor,
    private val certificateProvider: CertificateProvider,
    private val logHttpBody: Boolean = false,
) : Retrofits {
    //region Authenticated Retrofits

    override val authenticatedApiRetrofit: Retrofit by lazy {
        createAuthenticatedRetrofit(
            baseUrlInterceptor = baseUrlInterceptors.apiInterceptor,
        )
    }

    override val authenticatedEventsRetrofit: Retrofit by lazy {
        createAuthenticatedRetrofit(
            baseUrlInterceptor = baseUrlInterceptors.eventsInterceptor,
        )
    }

    //endregion Authenticated Retrofits

    //region Unauthenticated Retrofits

    override val unauthenticatedApiRetrofit: Retrofit by lazy {
        createUnauthenticatedRetrofit(
            baseUrlInterceptor = baseUrlInterceptors.apiInterceptor,
        )
    }

    override val unauthenticatedIdentityRetrofit: Retrofit by lazy {
        createUnauthenticatedRetrofit(
            baseUrlInterceptor = baseUrlInterceptors.identityInterceptor,
        )
    }

    //endregion Unauthenticated Retrofits

    //region Fill-Assist Retrofit

    override val fillAssistRetrofit: Retrofit by lazy {
        createExternalRetrofit(
            baseUrlInterceptor = baseUrlInterceptors.fillAssistInterceptor,
        )
    }

    //endregion Fill-Assist Retrofit

    //region Static Retrofit

    override fun createStaticRetrofit(isAuthenticated: Boolean, baseUrl: String): Retrofit {
        val baseClient = if (isAuthenticated) authenticatedOkHttpClient else baseOkHttpClient
        return baseRetrofitBuilder
            .baseUrl(baseUrl)
            .client(
                baseClient
                    .newBuilder()
                    .addInterceptor(loggingInterceptor)
                    .addInterceptor(permissionInterceptor)
                    .build(),
            )
            .build()
    }

    //endregion Static Retrofit

    //region Helper properties and functions
    private val loggingInterceptor: HttpLoggingInterceptor by lazy {
        HttpLoggingInterceptor { message -> Timber.tag("BitwardenNetworkClient").d(message) }
            .apply {
                redactHeader(name = HEADER_KEY_AUTHORIZATION)
                setLevel(
                    level = HttpLoggingInterceptor.Level.BODY
                        .takeIf { logHttpBody }
                        ?: HttpLoggingInterceptor.Level.BASIC,
                )
            }
    }

    // Explicit fast-fail timeouts: the pre-provisioned self-hosted server is only
    // reachable on the WireGuard mesh, and an unreachable mesh black-holes packets
    // (no RST), so connects hang at the kernel TCP level far past OkHttp's nominal
    // defaults. Short budgets make those requests error quickly so the existing
    // cached/NoNetwork fallback paths surface the vault offline.
    private val baseOkHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(headersInterceptor)
        .addNetworkInterceptor(cookieInterceptor)
        .configureSsl(certificateProvider = certificateProvider)
        .build()

    // For requests to external (non-Bitwarden) URLs. CookieInterceptor must be excluded because
    // it treats all 302s as Bitwarden load-balancer auth redirects, which is only correct for
    // Bitwarden's own infrastructure.
    private val externalOkHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(headersInterceptor)
        .configureSsl(certificateProvider = certificateProvider)
        .build()

    private val authenticatedOkHttpClient: OkHttpClient by lazy {
        baseOkHttpClient
            .newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(authTokenManager)
            .authenticator(authTokenManager)
            .build()
    }

    private val baseRetrofit: Retrofit by lazy {
        baseRetrofitBuilder
            .baseUrl("https://api.bitwarden.com")
            .build()
    }

    private val baseRetrofitBuilder: Retrofit.Builder by lazy {
        Retrofit.Builder()
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .addCallAdapterFactory(NetworkResultCallAdapterFactory())
            .client(baseOkHttpClient)
    }

    private fun createAuthenticatedRetrofit(
        baseUrlInterceptor: BaseUrlInterceptor,
    ): Retrofit =
        baseRetrofit
            .newBuilder()
            .client(
                authenticatedOkHttpClient
                    .newBuilder()
                    .addInterceptor(baseUrlInterceptor)
                    .addInterceptor(loggingInterceptor)
                    .addInterceptor(permissionInterceptor)
                    .build(),
            )
            .build()

    private fun createUnauthenticatedRetrofit(
        baseUrlInterceptor: BaseUrlInterceptor,
    ): Retrofit =
        baseRetrofit
            .newBuilder()
            .client(
                baseOkHttpClient
                    .newBuilder()
                    .addInterceptor(baseUrlInterceptor)
                    .addInterceptor(loggingInterceptor)
                    .addInterceptor(permissionInterceptor)
                    .build(),
            )
            .build()

    private fun createExternalRetrofit(
        baseUrlInterceptor: BaseUrlInterceptor,
    ): Retrofit =
        baseRetrofit
            .newBuilder()
            .client(
                externalOkHttpClient
                    .newBuilder()
                    .addInterceptor(baseUrlInterceptor)
                    .addInterceptor(loggingInterceptor)
                    .addInterceptor(permissionInterceptor)
                    .build(),
            )
            .build()

    //endregion Helper properties and functions
}
