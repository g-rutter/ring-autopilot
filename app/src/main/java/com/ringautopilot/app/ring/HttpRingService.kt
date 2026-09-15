package com.ringautopilot.app.ring

import android.content.Context
import android.provider.Settings
import com.ringautopilot.app.model.RingMode
import com.ringautopilot.app.model.RingEvent
import com.ringautopilot.app.model.RingEventType
import com.ringautopilot.app.storage.SettingsRepository
import com.ringautopilot.app.storage.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64

/** Minimal native client for the current unofficial Ring refresh-token API flow. */
class HttpRingService(
    context: Context,
    private val settingsRepository: SettingsRepository,
    private val tokenStore: TokenStore,
) : RingService, RingEventSource {
    private val hardwareId = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ANDROID_ID,
    ).orEmpty()
    private val mutableMode = MutableStateFlow(RingMode.UNAVAILABLE)
    private var accessToken: String? = null

    override val mode: StateFlow<RingMode> = mutableMode.asStateFlow()

    override suspend fun refreshMode(): Result<RingMode> = runCatching {
        val locationId = locationId()
        val response = request("https://prd-api-us.prd.rings.solutions/api/v1/mode/location/$locationId")
        modeFrom(response).also { mutableMode.value = it }
    }

    override suspend fun setMode(mode: RingMode): Result<Unit> = runCatching {
        require(mode == RingMode.AWAY || mode == RingMode.DISARMED) {
            "Only Away and Disarmed can be selected"
        }
        val locationId = locationId()
        val value = if (mode == RingMode.DISARMED) "disarmed" else "away"
        val response = request(
            "https://prd-api-us.prd.rings.solutions/api/v1/mode/location/$locationId",
            method = "POST",
            body = JSONObject().put("mode", value).toString(),
        )
        check(modeFrom(response) == mode) { "Ring did not confirm mode $value" }
        mutableMode.value = mode
    }

    override suspend fun pollEvents(sinceEpochMillis: Long): Result<List<RingEvent>> = runCatching {
        val locationId = locationId()
        val devices = request("https://api.ring.com/clients_api/ring_devices")
        val result = mutableListOf<RingEvent>()
        listOf("doorbots", "stickup_cams", "authorized_doorbots").forEach { key ->
            val cameras = devices.optJSONArray(key) ?: return@forEach
            for (index in 0 until cameras.length()) {
                val camera = cameras.optJSONObject(index) ?: continue
                val cameraId = camera.optLong("id").toString()
                val cameraName = camera.optString("description").ifBlank { "Ring camera" }
                val events = request(
                    "https://api.ring.com/clients_api/locations/$locationId/events?limit=25&" +
                        "doorbot_id=$cameraId",
                ).optJSONArray("events") ?: continue
                for (eventIndex in 0 until events.length()) {
                    val event = events.optJSONObject(eventIndex) ?: continue
                    val occurredAt = runCatching {
                        java.time.Instant.parse(event.optString("created_at")).toEpochMilli()
                    }.getOrNull() ?: continue
                    if (occurredAt <= sinceEpochMillis) continue
                    val type = when {
                        event.optString("kind") == "ding" -> RingEventType.DOORBELL
                        event.optJSONObject("cv_properties")?.isNull("person_detected") == false -> RingEventType.PERSON
                        else -> RingEventType.MOTION
                    }
                    result += RingEvent(cameraId, cameraName, type, occurredAt)
                }
            }
        }
        result.sortedBy(RingEvent::occurredAtEpochMillis)
    }

    private suspend fun locationId(): String {
        settingsRepository.settings.value.ringLocationId.takeIf { it.isNotBlank() }?.let { return it }
        val locations = request("https://api.ring.com/devices/v1/locations")
            .optJSONArray("user_locations")
        val first = locations?.optJSONObject(0)
            ?: error("No Ring locations found for this account")
        val id = first.optString("location_id")
        check(id.isNotBlank()) { "Ring returned a location without an ID" }
        settingsRepository.updateRingLocationId(id)
        return id
    }

    private suspend fun request(url: String, method: String = "GET", body: String? = null): JSONObject =
        withContext(Dispatchers.IO) {
            requestBlocking(url, method, body, retryAuth = true)
        }

    private fun requestBlocking(
        url: String,
        method: String,
        body: String?,
        retryAuth: Boolean,
    ): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "android:com.ringapp")
            setRequestProperty("hardware_id", hardwareId)
            accessToken?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            body?.let { connection.outputStream.use { output -> output.write(it.toByteArray()) } }
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_UNAUTHORIZED && retryAuth) {
                accessToken = null
                authenticate()
                return requestBlocking(url, method, body, retryAuth = false)
            }
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) error("Ring request failed ($status): ${text.take(240)}")
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun authenticate() {
        val stored = kotlinx.coroutines.runBlocking { tokenStore.readRefreshToken() }
            ?: error("Ring refresh token is not configured")
        val rawToken = decodeWrappedToken(stored)
        val form = listOf(
            "client_id" to "ring_official_android",
            "scope" to "client",
            "grant_type" to "refresh_token",
            "refresh_token" to rawToken,
        ).joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
        val auth = postJson("https://oauth.ring.com/oauth/token", form, "application/x-www-form-urlencoded")
        accessToken = auth.getString("access_token")
        auth.optString("refresh_token").takeIf { it.isNotBlank() }?.let { updated ->
            val wrapped = Base64.getEncoder().encodeToString(
                JSONObject().put("rt", updated).put("hid", hardwareId).toString().toByteArray(),
            )
            kotlinx.coroutines.runBlocking { tokenStore.writeRefreshToken(wrapped) }
        }
        postJson(
            "https://api.ring.com/clients_api/session",
            JSONObject().put("device", JSONObject()
                .put("hardware_id", hardwareId)
                .put("metadata", JSONObject().put("api_version", 11).put("device_model", "ring-autopilot"))
                .put("os", "android")).toString(),
            "application/json",
            authorization = accessToken,
        )
    }

    private fun postJson(url: String, body: String, contentType: String, authorization: String? = null): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", contentType)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "android:com.ringapp")
            setRequestProperty("hardware_id", hardwareId)
            authorization?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        return try {
            connection.outputStream.use { it.write(body.toByteArray()) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) error("Ring authentication failed ($status): ${text.take(240)}")
            JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun decodeWrappedToken(token: String): String = runCatching {
        JSONObject(String(Base64.getDecoder().decode(token))).getString("rt")
    }.getOrElse { token }

    private fun modeFrom(response: JSONObject): RingMode = when (response.optString("mode").lowercase()) {
        "away" -> RingMode.AWAY
        "disarmed", "none" -> RingMode.DISARMED
        else -> RingMode.UNKNOWN
    }
}
