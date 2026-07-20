package com.jarvis.agent

import android.app.Application
import com.jarvis.agent.util.Preferences

class JarvisApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Preferences.init(this)
    }
}
