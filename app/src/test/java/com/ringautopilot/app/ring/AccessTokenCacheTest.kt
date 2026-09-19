package com.ringautopilot.app.ring

import org.junit.Assert.assertEquals
import org.junit.Test

class AccessTokenCacheTest {
    @Test
    fun `missing token authenticates before it is returned`() {
        val cache = AccessTokenCache()
        var authentications = 0

        val token = cache.getOrAuthenticate {
            authentications += 1
            "first-token"
        }

        assertEquals("first-token", token)
        assertEquals(1, authentications)
    }

    @Test
    fun `cached token avoids repeated authentication`() {
        val cache = AccessTokenCache()
        var authentications = 0
        val authenticate = {
            authentications += 1
            "token-$authentications"
        }

        assertEquals("token-1", cache.getOrAuthenticate(authenticate))
        assertEquals("token-1", cache.getOrAuthenticate(authenticate))
        assertEquals(1, authentications)
    }

    @Test
    fun `rejected current token is refreshed once`() {
        val cache = AccessTokenCache()
        cache.getOrAuthenticate { "expired-token" }
        var refreshes = 0

        val replacement = cache.replaceAfterRejection("expired-token") {
            refreshes += 1
            "fresh-token"
        }

        assertEquals("fresh-token", replacement)
        assertEquals("fresh-token", cache.getOrAuthenticate { error("should not authenticate") })
        assertEquals(1, refreshes)
    }

    @Test
    fun `late rejection does not replace a newer token`() {
        val cache = AccessTokenCache()
        cache.getOrAuthenticate { "expired-token" }
        cache.replaceAfterRejection("expired-token") { "fresh-token" }

        val token = cache.replaceAfterRejection("expired-token") {
            error("newer token must be retained")
        }

        assertEquals("fresh-token", token)
    }
}
