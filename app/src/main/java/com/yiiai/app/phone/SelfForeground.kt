package com.yiiai.app.phone

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

/**
 * YIIAI 自己的界面此刻是不是在最前面。
 *
 * 没有无障碍服务时，我们看不到「现在前台是哪个应用」，但至少知道自己有没有被盖住：
 * 打开别的应用后自己退到后面，就说明那个应用（或系统的确认框）出现了。
 */
object SelfForeground : Application.ActivityLifecycleCallbacks {

    private val resumed = AtomicInteger(0)

    val isForeground: Boolean get() = resumed.get() > 0

    fun register(app: Application) = app.registerActivityLifecycleCallbacks(this)

    override fun onActivityResumed(activity: Activity) { resumed.incrementAndGet() }
    override fun onActivityPaused(activity: Activity) { resumed.updateAndGet { (it - 1).coerceAtLeast(0) } }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}