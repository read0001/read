package app.openread.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
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
    private var session: MediaSession? = null

    // ---- 音频焦点：别的 App 要放声音（来电、导航、视频）时暂停；只是临时打断的，结束后接着读 ----
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private var resumeOnGain = false
    private var hasFocus = false
    private val focusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> { hasFocus = false; resumeOnGain = false; ReadAloud.pause() }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        // 来电等临时打断：这期间用户手动点继续，要重新要焦点（要不到就不读，免得盖住通话）
                        hasFocus = false
                        resumeOnGain = ReadAloud.state.value.playing
                        ReadAloud.pause()
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        hasFocus = true
                        if (resumeOnGain) { resumeOnGain = false; ReadAloud.resume() }
                    }
                }
            }
            .build()
    }

    /** 要音频焦点；要不到（比如正在通话）返回 false。 */
    private fun requestFocus(): Boolean {
        if (!hasFocus) hasFocus = audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasFocus
    }

    private fun abandonFocus() {
        if (hasFocus) audio.abandonAudioFocusRequest(focusRequest)
        hasFocus = false
    }

    // 拔掉耳机、断开蓝牙：暂停，免得外放出来
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) { resumeOnGain = false; ReadAloud.pause() }
        }
    }

    /** 耳机线控、蓝牙耳机、锁屏的播放键：播放 / 暂停 / 停止 / 上一章 / 下一章。 */
    private fun createSession() = MediaSession(this, "OpenRead").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() = ReadAloud.resume()
            override fun onPause() = ReadAloud.pause()
            override fun onStop() = ReadAloud.stop()
            override fun onSkipToNext() = ReadAloud.skipChapter(1)
            override fun onSkipToPrevious() = ReadAloud.skipChapter(-1)
        })
        isActive = true
    }

    private fun updateSession(s: ReadAloud.State) {
        val ms = session ?: return
        ms.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(if (s.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build(),
        )
        ms.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, s.chapter).putString(MediaMetadata.METADATA_KEY_ARTIST, s.book).build())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "听书", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
        // Must be called promptly after the service starts, or the system stops it.
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(ReadAloud.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        session = createSession()
        ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        watch = scope.launch {
            // The notification shows the book, chapter and whether it is playing; the sentence changing is not its business.
            ReadAloud.state.distinctUntilChanged { a, b -> a.active == b.active && a.playing == b.playing && a.book == b.book && a.chapter == b.chapter }.collectLatest { s ->
                if (!s.active) {
                    abandonFocus()
                    ServiceCompat.stopForeground(this@ReadAloudService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    // 开始（或继续）读时要焦点；被别的 App 永久拿走后，用户再点播放会重新要
                    if (s.playing && !requestFocus()) ReadAloud.pause()
                    updateSession(s)
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
            ACTION_NEXT -> ReadAloud.skipChapter(1)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        abandonFocus()
        runCatching { unregisterReceiver(noisy) }
        session?.release()
        session = null
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
        val next = PendingIntent.getService(this, 3, intent(ACTION_NEXT), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(s.chapter.ifEmpty { "听书" })
            .setContentText(if (s.playing) "正在朗读 · ${s.book}" else "已暂停 · ${s.book}")
            .setContentIntent(open)
            .setOngoing(s.playing)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(if (s.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (s.playing) "暂停" else "继续", toggle)
            .addAction(android.R.drawable.ic_media_next, "下一章", next)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stop)
            .build()
    }

    private fun intent(action: String) = Intent(this, ReadAloudService::class.java).setAction(action)

    companion object {
        private const val CHANNEL = "read_aloud"
        private const val NOTIFICATION_ID = 1002
        private const val ACTION_TOGGLE = "app.openread.tts.TOGGLE"
        private const val ACTION_STOP = "app.openread.tts.STOP"
        private const val ACTION_NEXT = "app.openread.tts.NEXT"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ReadAloudService::class.java))
        }
    }
}
