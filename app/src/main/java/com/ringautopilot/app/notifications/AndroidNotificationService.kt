package com.ringautopilot.app.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ringautopilot.app.R
import com.ringautopilot.app.logging.Diagnostics
import com.ringautopilot.app.model.EventSummary
import com.ringautopilot.app.model.RingMode

class AndroidNotificationService(private val context: Context) : NotificationService {
    init {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Ring automation",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    override fun notifyModeChanged(mode: RingMode) {
        show(
            id = MODE_NOTIFICATION_ID,
            title = "Ring mode changed",
            body = "Ring switched to ${mode.displayName}",
        )
    }

    override fun notifyEventSummary(summary: EventSummary) {
        show(
            id = summary.cameraName.hashCode(),
            title = summary.cameraName,
            body = "${summary.count} ${summary.type.name.lowercase()} events in the latest window",
        )
    }

    private fun show(id: Int, title: String, body: String) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Diagnostics.warn("notification_suppressed", mapOf("reason" to "permission_denied"))
            return
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .build()

        try { NotificationManagerCompat.from(context).notify(id, notification) }
        catch (error: SecurityException) {
            Diagnostics.warn("notification_failed", mapOf("reason" to "permission_denied"), error)
        }
        catch (error: RuntimeException) {
            Diagnostics.warn("notification_failed", mapOf("reason" to "unexpected_error"), error)
        }
    }

    private val RingMode.displayName: String
        get() = name.lowercase().replaceFirstChar(Char::uppercase)

    private companion object {
        const val CHANNEL_ID = "ring_automation"
        const val MODE_NOTIFICATION_ID = 1001
    }
}
