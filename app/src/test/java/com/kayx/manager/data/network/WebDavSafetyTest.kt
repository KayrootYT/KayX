package com.kayx.manager.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WebDavSafetyTest {
    @Test fun webDavRequiresHttpsAndRejectsEmbeddedCredentials() {
        assertEquals("https", validateWebDavServerUrl("https://files.example.test/dav").scheme)
        assertThrows(IllegalArgumentException::class.java) { validateWebDavServerUrl("http://files.example.test/dav") }
        assertThrows(IllegalArgumentException::class.java) { validateWebDavServerUrl("https://user:pass@files.example.test/dav") }
        assertThrows(IllegalArgumentException::class.java) { validateWebDavServerUrl("https:///missing-host") }
    }

    @Test fun webDavRequiresValidPort() {
        assertEquals(8443, validateWebDavServerUrl("https://files.example.test:8443/dav").port)
        assertThrows(IllegalArgumentException::class.java) { validateWebDavServerUrl("https://files.example.test:99999/dav") }
    }
}
