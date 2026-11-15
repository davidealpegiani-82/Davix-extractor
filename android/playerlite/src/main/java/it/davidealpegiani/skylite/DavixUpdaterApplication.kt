package it.davidealpegiani.skylite

import android.app.Activity
import android.app.Application

class DavixUpdaterApplication : Application(), Application.ActivityLifecycleCallbacks {
    private lateinit var updater: DavixUpdater

    override fun onCreate() {
        super.onCreate()
        updater = DavixUpdater(this, "player")
        registerActivityLifecycleCallbacks(this)
    }

    fun showDownloads(activity: Activity) {
        updater.showDownloads(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity.javaClass.name == "it.davidealpegiani.skylite.MainActivity") updater.check(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
