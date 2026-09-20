package com.ringautopilot.app.logging

import android.util.Log
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** Structured, deliberately small diagnostic surface. Values are encoded before reaching logcat. */
interface DiagnosticLog {
    fun debug(event: String, fields: Map<String, Any?> = emptyMap(), error: Throwable? = null)
    fun info(event: String, fields: Map<String, Any?> = emptyMap(), error: Throwable? = null)
    fun warn(event: String, fields: Map<String, Any?> = emptyMap(), error: Throwable? = null)
    fun error(event: String, fields: Map<String, Any?> = emptyMap(), error: Throwable? = null)
}

object Diagnostics : DiagnosticLog {
    private const val TAG = "RingAutopilot"
    override fun debug(event: String, fields: Map<String, Any?>, error: Throwable?) = write(Log.DEBUG, event, fields, error)
    override fun info(event: String, fields: Map<String, Any?>, error: Throwable?) = write(Log.INFO, event, fields, error)
    override fun warn(event: String, fields: Map<String, Any?>, error: Throwable?) = write(Log.WARN, event, fields, error)
    override fun error(event: String, fields: Map<String, Any?>, error: Throwable?) = write(Log.ERROR, event, fields, error)

    private fun write(level: Int, event: String, fields: Map<String, Any?>, error: Throwable?) {
        val line = formatEvent(event, fields, error)
        try { Log.println(level, TAG, line) } catch (_: RuntimeException) { /* android.jar unit tests */ }
    }
}

fun operationId(): String = UUID.randomUUID().toString().take(8)

/** Never forward exception messages or arbitrary strings from network responses to a sink. */
fun errorReason(error: Throwable): String = when (error) {
    is CancellationException -> "cancelled"
    is HttpStatusException -> "http_error"
    is IOException -> "io_error"
    is SecurityException -> "permission_denied"
    is org.json.JSONException -> "parse_error"
    else -> "unexpected_error"
}

class HttpStatusException(val status: Int) : IOException("HTTP status $status")

private val safeToken = Regex("[a-zA-Z0-9_.-]{1,80}")
private val safeKey = Regex("[a-zA-Z][a-zA-Z0-9]{0,40}")
private val sensitiveKey = Regex("(?i).*(token|secret|key|ssid|bridge|camera|hardware|header|body|url|address|coordinates|latitude|longitude|locationId|ipAddress).*")

fun formatEvent(event: String, fields: Map<String, Any?> = emptyMap(), error: Throwable? = null): String {
    require(safeToken.matches(event))
    return buildString {
        append("event=").append(event)
        fields.forEach { (key, value) ->
            require(safeKey.matches(key))
            append(' ').append(key).append('=')
            append(when {
                sensitiveKey.matches(key) -> "redacted"
                value == null -> "none"
                value is Number || value is Boolean -> value.toString()
                value is Enum<*> -> value.name.lowercase()
                else -> value.toString().takeIf(safeToken::matches) ?: "redacted"
            })
        }
        if (error != null) append(" errorType=").append(error.javaClass.simpleName.takeIf(safeToken::matches) ?: "Exception")
    }
}
