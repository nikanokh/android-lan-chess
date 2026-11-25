package com.example

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class ChessLanApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Prevent Ktor CIO embedded server from initializing NIO LinuxWatchService on Android
        runCatching { System.setProperty("io.ktor.development", "false") }
    }
}
