package com.sagarsystemslab.nownetwork

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class NowApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        com.sagarsystemslab.nownetwork.experience.NowPush.initialize(this)
    }
}
