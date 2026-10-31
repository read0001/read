package app.openread

import android.app.Application
import android.os.Bundle
import androidx.room.Room
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.openread.data.AppDb
import app.openread.data.MIGRATION_1_2
import app.openread.data.MIGRATION_2_3
import app.openread.data.MIGRATION_3_4
import app.openread.ui.update.UpdateController
import app.openread.data.AppUpdate
import app.openread.data.Engine
import app.openread.data.ContentFilter
import app.openread.data.Backup
import app.openread.data.Library
import app.openread.data.NetworkState
import app.openread.data.Prefs
import app.openread.data.SourceRepo
import app.openread.data.SubscriptionUpdater
import app.openread.engine.Http
import app.openread.work.SubscriptionWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** Hand-wired dependencies: small enough that a DI framework would only add ceremony. */
class AppContainer(app: Application) {
    /** For work that must finish even if the screen that started it is gone, such as saving reading progress. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val prefs = Prefs(app)
    val db: AppDb = Room.databaseBuilder(app, AppDb::class.java, "openread.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).fallbackToDestructiveMigration(true).build()
    val http = Http(timeoutSec = 15)
    val sources = SourceRepo(db.sources())
    val engine = Engine(http, sources, db.health())
    val filter = ContentFilter(db, prefs, appScope)
    val library = Library(app, db, engine, sources, filter)
    val network = NetworkState(app)
    val backup = Backup(app, db, prefs, sources)
    val subscription = SubscriptionUpdater(app, prefs, sources, http)
    val appUpdate = AppUpdate(app, prefs, http)
    /** Outlives the activity, so a download keeps going (and the dialog comes back) when the app is left and reopened. */
    @Volatile var inForeground = false
    val updates = UpdateController(this, app)
}

class App : Application() {
    lateinit var container: AppContainer
        private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        container = AppContainer(this)
        scope.launch {
            container.subscription.seedIfEmpty()
            // Like the TV app: refresh on launch when the copy is older than 12 hours; the periodic job covers the rest.
            if (container.subscription.isStale()) container.subscription.update()
        }
        scheduleSubscriptionRefresh()
        scheduleShelfUpdates()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: android.app.Activity) { started++; container.inForeground = true }
            override fun onActivityStopped(activity: android.app.Activity) { started--; container.inForeground = started > 0 }
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
    }

    /** 每 6 小时在 Wi-Fi 等不计流量的网络下检查一次书架更新，有新章节就发通知（设置里可以关）。 */
    private fun scheduleShelfUpdates() {
        val request = PeriodicWorkRequestBuilder<app.openread.work.ShelfUpdateWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build())
            .setInitialDelay(1, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("shelf-updates", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Every 12 hours, on any network, swap in the latest verified sources even if the app is never opened. */
    private fun scheduleSubscriptionRefresh() {
        val request = PeriodicWorkRequestBuilder<SubscriptionWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("subscription", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

val android.content.Context.container: AppContainer get() = (applicationContext as App).container
