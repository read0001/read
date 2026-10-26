package app.openread.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.openread.MainActivity
import app.openread.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps read-aloud alive when the app is in the background or the screen is off, and carries its notification
 * (play/pause, stop). The service only watches [ReadAloud.state]; it stops itself when the session ends.
 */
class ReadAloudService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watch: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "听书", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
        // Must be called promptly after the service starts, or the system stops it.
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(ReadAloud.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        watch = scope.launch {
            // The notification shows the book, chapter and whether it is playing; the sentence changing is not its business.
            ReadAloud.state.distinctUntilChanged { a, b -> a.active == b.active && a.playing == b.playing && a.book == b.book && a.chapter == b.chapter }.collectLatest { s ->
                if (!s.active) {
                    ServiceCompat.stopForeground(this@ReadAloudService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(s))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A new read started while the service is already up: it must still be made foreground within five seconds.
        if (intent?.action == null) ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(ReadAloud.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        when (intent?.action) {
            ACTION_TOGGLE -> ReadAloud.toggle()
            ACTION_STOP -> ReadAloud.stop()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watch?.cancel()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun notification(s: ReadAloud.State): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val toggle = PendingIntent.getService(this, 1, intent(ACTION_TOGGLE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 2, intent(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(s.chapter.ifEmpty { "听书" })
            .setContentText(if (s.playing) "正在朗读 · ${s.book}" else "已暂停 · ${s.book}")
            .setContentIntent(open)
            .setOngoing(s.playing)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(if (s.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (s.playing) "暂停" else "继续", toggle)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stop)
            .build()
    }

    private fun intent(action: String) = Intent(this, ReadAloudService::class.java).setAction(action)

    companion object {
        private const val CHANNEL = "read_aloud"
        private const val NOTIFICATION_ID = 1002
        private const val ACTION_TOGGLE = "app.openread.tts.TOGGLE"
        private const val ACTION_STOP = "app.openread.tts.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ReadAloudService::class.java))
        }
    }
}
