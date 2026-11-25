package com.kayx.manager.data.network

import java.net.URL
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

    @Test fun webDavContentRangeMustBeWellFormedAndBounded() {
        assertEquals(WebDavContentRange(10L, 19L, 100L), parseWebDavContentRange("bytes 10-19/100"))
        assertEquals(null, parseWebDavContentRange("bytes 10-19/*"))
        assertEquals(null, parseWebDavContentRange("bytes 19-10/100"))
        assertEquals(null, parseWebDavContentRange("bytes 10-100/100"))
        assertEquals(null, parseWebDavContentRange("items 10-19/100"))
    }

    @Test fun webDavResumeOffsetMustFitVerifiedLength() {
        assertEquals(0L, validateWebDavResumeOffset(0L, 100L))
        assertEquals(100L, validateWebDavResumeOffset(100L, 100L))
        assertThrows(IllegalArgumentException::class.java) { validateWebDavResumeOffset(-1L, 100L) }
        assertThrows(IllegalArgumentException::class.java) { validateWebDavResumeOffset(101L, 100L) }
        assertThrows(IllegalArgumentException::class.java) { validateWebDavResumeOffset(0L, -1L) }
    }

    @Test fun weakEtagsAreNotAcceptedAsIfRangeValidators() {
        assertEquals(true, isStrongWebDavEtag("\"revision-1\""))
        assertEquals(false, isStrongWebDavEtag("W/\"revision-1\""))
        assertEquals(false, isStrongWebDavEtag(null))
    }

    @Test fun webDavRequiresValidPort() {
        assertEquals(8443, validateWebDavServerUrl("https://files.example.test:8443/dav").port)
        assertThrows(IllegalArgumentException::class.java) { validateWebDavServerUrl("https://files.example.test:99999/dav") }
    }

    @Test fun multistatusParsesSafeSameOriginEntries() {
        val xml = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/Reports/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype><d:getcontentlength>0</d:getcontentlength></d:prop></d:propstat></d:response></d:multistatus>"""
        val entries = WebDavClient().parseMultistatusForTest(xml, URL("https://files.example.test/dav/"))
        assertEquals(1, entries.size)
        assertEquals("Reports", entries.single().name)
        assertEquals(true, entries.single().directory)
    }

    @Test fun multistatusRejectsExternalEntitiesAndCrossOriginHrefs() {
        val xxe = """<!DOCTYPE x [<!ENTITY ext SYSTEM "file:///etc/passwd">]><d:multistatus xmlns:d="DAV:"><d:response><d:href>&ext;</d:href></d:response></d:multistatus>"""
        assertThrows(Exception::class.java) {
            WebDavClient().parseMultistatusForTest(xxe, URL("https://files.example.test/dav/"))
        }
        val foreign = """<d:multistatus xmlns:d="DAV:"><d:response><d:href>https://evil.example/steal</d:href></d:response></d:multistatus>"""
        assertThrows(IllegalArgumentException::class.java) {
            WebDavClient().parseMultistatusForTest(foreign, URL("https://files.example.test/dav/"))
        }
    }
}
