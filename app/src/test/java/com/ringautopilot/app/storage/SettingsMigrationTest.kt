package com.ringautopilot.app.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsMigrationTest {
    @Test
    fun `legacy saved SSID enables Wi-Fi presence`() {
        assertTrue(migratedWifiPresenceEnabled(
            hasPersistedValue = false,
            persistedValue = false,
            homeWifiSsid = "Home",
        ))
    }

    @Test
    fun `fresh install starts with Wi-Fi presence disabled`() {
        assertFalse(migratedWifiPresenceEnabled(
            hasPersistedValue = false,
            persistedValue = true,
            homeWifiSsid = "",
        ))
    }

    @Test
    fun `explicit detector choice wins over legacy inference`() {
        assertFalse(migratedWifiPresenceEnabled(true, false, "Home"))
        assertTrue(migratedWifiPresenceEnabled(true, true, ""))
    }
}
