package com.ringautopilot.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.ringautopilot.app.MainActivity
import com.ringautopilot.app.R
import com.ringautopilot.app.model.ControlMode
import com.ringautopilot.app.model.RingMode
import com.ringautopilot.app.storage.StatusStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class RingWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, views(context, it)) }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { WidgetAppearance.remove(context, it) }
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, RingWidgetProvider::class.java))
                .forEach { manager.updateAppWidget(it, views(context, it)) }
        }

        fun views(context: Context, id: Int): RemoteViews {
            val status = StatusStore(context)
            val check = status.lastCheck()
            val mode = status.controlMode()
            val camera = status.cameraMode()
            return RemoteViews(context.packageName, R.layout.ring_widget).apply {
                setInt(R.id.widget_root, "setBackgroundResource", WidgetAppearance.background(id, context))
                setTextViewText(R.id.widget_control, when (mode) {
                    ControlMode.AUTO -> "Auto"
                    ControlMode.AWAY, ControlMode.DISARMED -> "Manual"
                })
                setTextViewText(R.id.widget_camera, when (camera) {
                    RingMode.AWAY -> "Away"
                    RingMode.DISARMED -> "Disarmed"
                    RingMode.UNKNOWN, RingMode.UNAVAILABLE -> "Unknown"
                })
                setTextViewText(R.id.widget_health, when {
                    check?.problem == true -> "Issue"
                    check != null -> "OK"
                    mode != ControlMode.AUTO && camera != RingMode.UNKNOWN -> "Ready"
                    else -> "Pending"
                })
                setInt(R.id.widget_health, "setTextColor", if (check?.problem == true) 0xFFFFB4A9.toInt() else 0xFFD4DFE9.toInt())
                setTextViewText(R.id.widget_check_time, check?.let {
                    SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(it.timeMillis))
                } ?: "No checks")
                val open = PendingIntent.getActivity(context, id,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                setOnClickPendingIntent(R.id.widget_root, open)
                setOnClickPendingIntent(R.id.widget_open, open)
            }
        }
    }
}

object WidgetAppearance {
    private const val FILE = "ring_widget_appearance"
    private fun key(id: Int) = "opacity_$id"

    fun opacity(context: Context, id: Int): Int = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        .getInt(key(id), 90).coerceIn(10, 100)

    fun setOpacity(context: Context, id: Int, value: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(key(id), ((value.coerceIn(10, 100) / 10f).roundToInt() * 10)).apply()
    }

    fun remove(context: Context, id: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(key(id)).apply()
    }

    fun background(id: Int, context: Context): Int = when (opacity(context, id)) {
        10 -> R.drawable.widget_background_10
        20 -> R.drawable.widget_background_20
        30 -> R.drawable.widget_background_30
        40 -> R.drawable.widget_background_40
        50 -> R.drawable.widget_background_50
        60 -> R.drawable.widget_background_60
        70 -> R.drawable.widget_background_70
        80 -> R.drawable.widget_background_80
        90 -> R.drawable.widget_background_90
        else -> R.drawable.widget_background_100
    }
}
