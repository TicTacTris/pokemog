package dev.pokemog.android

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat

internal fun captureNotificationEnabled(context: Context): Boolean {
    val manager = context.getSystemService(NotificationManager::class.java)
    val channel = manager.getNotificationChannel(OverlayScanService.CHANNEL_ID)
    return NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE) &&
        (channel?.group == null || manager.getNotificationChannelGroup(channel.group)?.isBlocked != true)
}
