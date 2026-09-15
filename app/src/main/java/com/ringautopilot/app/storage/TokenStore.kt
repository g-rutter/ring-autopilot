package com.ringautopilot.app.storage

/**
 * Boundary for Ring credentials. The implementation must use Android
 * Keystore-backed encryption; never place tokens in SharedPreferences as text.
 */
interface TokenStore {
    suspend fun readRefreshToken(): String?
    suspend fun writeRefreshToken(token: String)
    suspend fun clear()
}
