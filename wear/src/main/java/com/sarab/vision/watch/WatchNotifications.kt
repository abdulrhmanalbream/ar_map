package com.sarab.vision.watch

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import com.sarab.vision.R
import com.sarab.vision.wear.shared.WatchAlert

object WatchNotifications {
    private const val CHANNEL = "sarab_group_alerts"
    fun show(context: Context, alert: WatchAlert) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "تنبيهات المجموعة", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "طلب مساعدة ورسائل المشرف والمجموعة"
            // One explicit vibration after durable deduplication, never two overlapping patterns.
            enableVibration(false)
            setSound(null, null)
        })
        if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val vibrator = context.getSystemService(Vibrator::class.java)
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 250, 100, 250, 100, 500), -1),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
        val open = PendingIntent.getActivity(context, alert.id.hashCode(),
            Intent(context, WatchActivity::class.java).putExtra("alertId", alert.id)
                .setData(Uri.parse("sarab://watch/alert/${Uri.encode(alert.id)}"))
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = when (alert.kind) { "help" -> "طلب مساعدة"; "regroup" -> "تجمّع المجموعة"; else -> "رسالة من المجموعة" }
        manager.notify(alert.id, 1, Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_watch).setContentTitle(title)
            .setContentText(alert.message).setStyle(Notification.BigTextStyle().bigText(alert.message))
            .setContentIntent(open).setAutoCancel(false).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_MESSAGE).setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, "عرض وتأكيد", open).build()).build())
    }

    fun cancel(context: Context, id: String) { context.getSystemService(NotificationManager::class.java).cancel(id, 1) }
}
