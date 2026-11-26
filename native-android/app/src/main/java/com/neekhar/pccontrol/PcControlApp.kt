package com.neekhar.pccontrol

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.neekhar.pccontrol.core.network.PcApiClient
import com.neekhar.pccontrol.core.storage.AppPrefs
import com.neekhar.pccontrol.core.storage.LocalCache

/**
 * Application entry point holding app-scoped singletons ([prefs], [cache], [apiClient])
 * and configuring Coil 3 to share [PcApiClient.okHttpClient].
 */
class PcControlApp : Application(), SingletonImageLoader.Factory {

    lateinit var prefs: AppPrefs
        private set

    lateinit var cache: LocalCache
        private set

    lateinit var apiClient: PcApiClient
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = AppPrefs(this)
        apiClient = PcApiClient(prefs)
        cache = LocalCache(this, apiClient.json)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { apiClient.okHttpClient },
                    ),
                )
            }
            .crossfade(true)
            .build()

    companion object {
        lateinit var instance: PcControlApp
            private set
    }
}
