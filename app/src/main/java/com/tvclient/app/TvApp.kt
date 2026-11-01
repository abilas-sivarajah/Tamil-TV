package com.tvclient.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.tvclient.app.data.remote.ApiClient

class TvApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(ApiClient.okHttp)
            .crossfade(true)
            .build()

    companion object {
        lateinit var instance: TvApp
            private set
    }
}
