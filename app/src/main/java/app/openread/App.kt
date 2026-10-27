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
import app.openread.ui.update.UpdateController
import app.openread.data.AppUpdate
import app.openread.data.Engine
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
    val db: AppDb = Room.databaseBuilder(app, AppDb::class.java, "openread.db").addMigrations(MIGRATION_1_2).fallbackToDestructiveMigration(true).build()
    val http = Http(timeoutSec = 15)
    val sources = SourceRepo(db.sources())
    val engine = Engine(http, sources, db.health())
    val library = Library(app, db, engine, sources)
    val network = NetworkState(app)
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

    /** Every 12 hours, on any network, swap in the latest verified sources even if the app is never opened. */
    private fun scheduleSubscriptionRefresh() {
        val request = PeriodicWorkRequestBuilder<SubscriptionWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("subscription", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

val android.content.Context.container: AppContainer get() = (applicationContext as App).container
