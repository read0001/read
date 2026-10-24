package app.openread.ui.update

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import app.openread.MainActivity
import app.openread.R

/** One notification, for a download that finished while the app was in the background. Silent if the user turned them off. */
object UpdateNotifier {
    private const val CHANNEL = "update"
    private const val ID = 1001

    fun downloaded(app: Application, versionName: String) {
        if (Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "应用更新", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            app, 0, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            ID,
            NotificationCompat.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle("开卷 $versionName 已下载完成")
                .setContentText("点击安装").setContentIntent(open).setAutoCancel(true).build(),
        )
    }
}
