package app.openread

import android.app.Application
import androidx.room.Room
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.openread.data.AppDb
import app.openread.data.Engine
import app.openread.data.Library
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
    val db: AppDb = Room.databaseBuilder(app, AppDb::class.java, "openread.db").fallbackToDestructiveMigration(true).build()
    val http = Http(timeoutSec = 15)
    val sources = SourceRepo(db.sources())
    val engine = Engine(http, sources)
    val library = Library(app, db, engine, sources)
    val subscription = SubscriptionUpdater(app, prefs, sources, http)
}

class App : Application() {
    lateinit var container: AppContainer
        private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        scope.launch {
            container.subscription.seedIfEmpty()
            // Like the TV app: refresh on launch when the copy is older than 12 hours; the periodic job covers the rest.
            if (container.subscription.isStale()) container.subscription.update()
        }
        scheduleSubscriptionRefresh()
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
