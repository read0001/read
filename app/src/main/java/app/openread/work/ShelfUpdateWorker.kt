package app.openread.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.openread.MainActivity
import app.openread.R
import app.openread.container
import app.openread.data.isLocal

/**
 * 后台检查书架更新（只在 Wi-Fi 等不计流量的网络下跑，见 App.scheduleShelfUpdates）：每本书读一遍当前书源的目录，
 * 这次新出了章节的书发一条通知。只读目录，不做换源核对。设置里关掉“书架更新提醒”就什么也不做。
 */
class ShelfUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        if (!c.prefs.current().notifyUpdates) return Result.success()
        val before = c.library.shelfSnapshot().associate { it.bookUrl to it.totalChapters }
        if (before.isEmpty()) return Result.success()
        runCatching { c.library.checkUpdates() }.getOrElse { return Result.retry() }
        // 这次才多出章节的书（以前就有更新、还没读的，不再重复提醒）
        val fresh = c.library.shelfSnapshot().filter { b -> !b.isLocal && (before[b.bookUrl] ?: Int.MAX_VALUE) in 1 until b.totalChapters }
        if (fresh.isNotEmpty()) notify(applicationContext, fresh.map { "《${it.name}》${it.latestChapter.orEmpty()}" })
        return Result.success()
    }

    companion object {
        private const val CHANNEL = "shelf_updates"
        private const val ID = 1003

        fun notify(context: Context, lines: List<String>) {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "书架更新", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val style = NotificationCompat.InboxStyle().also { st -> lines.take(6).forEach { st.addLine(it) } }
            nm.notify(
                ID,
                NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle("${lines.size} 本书有新章节").setContentText(lines.first()).setStyle(style)
                    .setContentIntent(open).setAutoCancel(true).build(),
            )
        }
    }
}
