package app.openread.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.openread.container

/** Periodic job that refreshes the subscribed book sources. A failed fetch just retries next period. */
class SubscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        c.subscription.seedIfEmpty()
        return if (c.subscription.update().ok) Result.success() else Result.retry()
    }
}
