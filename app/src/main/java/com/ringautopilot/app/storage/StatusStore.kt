package com.ringautopilot.app.storage

import android.content.Context
import android.content.SharedPreferences
import com.ringautopilot.app.model.ControlMode
import com.ringautopilot.app.model.RingMode
import com.ringautopilot.app.automation.PendingChange
import com.ringautopilot.app.automation.PendingChangeStore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class LastCheck(val timeMillis: Long, val summary: String, val problem: Boolean)

/** A small shared snapshot for the dashboard and launcher widget. */
class StatusStore(context: Context) : PendingChangeStore {
    private val preferences = context.getSharedPreferences("ring_status", Context.MODE_PRIVATE)

    fun lastCheck(): LastCheck? = readCheck("check")

    fun lastAutomationCheck(): LastCheck? = readCheck("automation_check")

    private fun readCheck(prefix: String): LastCheck? {
        val time = preferences.getLong("${prefix}_time", 0)
        if (time == 0L) return null
        return LastCheck(time, preferences.getString("${prefix}_summary", "Completed").orEmpty(),
            preferences.getBoolean("${prefix}_problem", false))
    }

    fun observeLastCheck(): Flow<LastCheck?> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "check_time" || key == "check_summary" || key == "check_problem") {
                trySend(lastCheck())
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(lastCheck())
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun observeLastAutomationCheck(): Flow<LastCheck?> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key?.startsWith("automation_check_") == true) trySend(lastAutomationCheck())
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(lastAutomationCheck())
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun saveCheck(summary: String, problem: Boolean, automated: Boolean = false) {
        val now = System.currentTimeMillis()
        preferences.edit().putLong("check_time", now)
            .putString("check_summary", summary).putBoolean("check_problem", problem).apply()
        if (automated) preferences.edit().putLong("automation_check_time", now)
            .putString("automation_check_summary", summary)
            .putBoolean("automation_check_problem", problem).apply()
    }

    fun cameraMode(): RingMode = preferences.getString("camera_mode", null)
        ?.let { saved -> RingMode.entries.firstOrNull { it.name == saved } } ?: RingMode.UNKNOWN

    fun saveCameraMode(mode: RingMode) {
        preferences.edit().putString("camera_mode", mode.name).apply()
    }

    fun controlMode(): ControlMode = preferences.getString("control_mode", null)
        ?.let { saved -> ControlMode.entries.firstOrNull { it.name == saved } } ?: ControlMode.AUTO

    fun saveControlMode(mode: ControlMode) {
        preferences.edit().putString("control_mode", mode.name).apply()
    }

    override fun pendingChange(): PendingChange? {
        val mode = preferences.getString("pending_mode", null)
            ?.let { name -> RingMode.entries.firstOrNull { it.name == name } } ?: return null
        val dueAt = preferences.getLong("pending_due_at", 0)
        return if (dueAt > 0) PendingChange(mode, dueAt) else null
    }

    override fun savePendingChange(change: PendingChange) {
        preferences.edit().putString("pending_mode", change.desiredMode.name)
            .putLong("pending_due_at", change.dueAtMillis).commit()
    }

    override fun clearPendingChange() {
        preferences.edit().remove("pending_mode").remove("pending_due_at").commit()
    }
}
