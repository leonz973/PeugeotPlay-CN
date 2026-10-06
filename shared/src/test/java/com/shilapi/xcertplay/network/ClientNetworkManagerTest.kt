package com.shilapi.xcertplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClientNetworkManagerTest {
    @Test
    fun stripsAndroidQuotesAroundConfiguredSsids() {
        assertEquals("葡萄", unquoteSsid("\"葡萄\""))
        assertEquals("葡萄", unquoteSsid("葡萄"))
        assertNull(unquoteSsid(null))
        assertNull(unquoteSsid(CLIENT_NETWORK_UNKNOWN_SSID))
        assertEquals("\"", unquoteSsid("\""))
    }

    @Test
    fun matchesOnlyTheExpectedNetwork() {
        assertEquals(true, clientNetworkMatch("\"葡萄\"", "葡萄"))
        assertEquals(false, clientNetworkMatch("\"other\"", "葡萄"))
        assertNull(clientNetworkMatch(CLIENT_NETWORK_UNKNOWN_SSID, "葡萄"))
        assertNull(clientNetworkMatch(null, "葡萄"))
    }
}
