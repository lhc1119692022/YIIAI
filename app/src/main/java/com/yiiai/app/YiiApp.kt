package com.yiiai.app

import android.app.Application
import com.yiiai.app.live.LiveSession
import com.yiiai.app.settings.SettingsStore

class YiiApp : Application() {

    lateinit var settings: SettingsStore
        private set

    /** 进程内唯一的通话会话。M2 起由前台服务持有其生命周期。 */
    lateinit var live: LiveSession
        private set

    override fun onCreate() {
        super.onCreate()
        com.yiiai.app.phone.SelfForeground.register(this)
        settings = SettingsStore(this)
        live = LiveSession(this, settings)
    }
}
