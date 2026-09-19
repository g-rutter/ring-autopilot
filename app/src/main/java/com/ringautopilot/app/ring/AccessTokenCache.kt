package com.ringautopilot.app.ring

/** Keeps authentication atomic when several Ring requests start at the same time. */
internal class AccessTokenCache {
    @Volatile
    private var token: String? = null

    fun getOrAuthenticate(authenticate: () -> String): String =
        token ?: synchronized(this) {
            token ?: authenticate().also { token = it }
        }

    fun replaceAfterRejection(rejectedToken: String, authenticate: () -> String): String =
        synchronized(this) {
            if (token == rejectedToken) {
                token = authenticate()
            }
            checkNotNull(token)
        }
}
