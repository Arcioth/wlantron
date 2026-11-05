package com.arcioth.wlantron

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalTest {
    @Test
    fun gatewayRedirectIsThePortal() {
        val http = HttpCall { _, url, _, _, _ ->
            if ("10.3.248.1" in url) {
                HttpResult(302, mapOf("location" to "http://10.3.248.1:1000/fgtauth?abc"), emptyList(), ByteArray(0))
            } else {
                throw java.io.IOException("dns failed")
            }
        }
        val state = Portal.probe(http, listOf("http://10.3.248.1/"))
        assertTrue(state is Probe.Portal)
        assertEquals("http://10.3.248.1:1000/fgtauth?abc", (state as Probe.Portal).url)
    }

    @Test
    fun redirectAndMagicToken() {
        val loc = Portal.extractRedirect(
            "http://detectportal.firefox.com/canonical.html",
            302,
            mapOf("location" to "http://10.1.1.1:1000/fgtauth?abc"),
            ByteArray(0),
        )
        assertEquals("http://10.1.1.1:1000/fgtauth?abc", loc)

        val form = Portal.parseForm(
            """<form method="post"><input type="text" name="username">
               <input type="password" name="password"></form>""",
        )
        val parts = Portal.classifyForm(form!!, "http://10.0.0.1/fgtauth?abc123")
        assertTrue(parts.fields.any { it == ("magic" to "abc123") })
        assertTrue(parts.needsUser && parts.needsPass)
    }

    @Test
    fun loginThenDisclaimer() {
        val posts = mutableListOf<String>()
        val http = HttpCall { method, url, _, body, _ ->
            when {
                method == "POST" -> {
                    val text = body?.toString(Charsets.UTF_8).orEmpty()
                    posts += text
                    if (posts.size == 1) {
                        HttpResult(200, emptyMap(), emptyList(), DISCLAIMER.toByteArray())
                    } else {
                        HttpResult(200, emptyMap(), emptyList(), "https://10.0.0.1/keepalive?abcdef".toByteArray())
                    }
                }
                "canonical.html" in url ->
                    HttpResult(200, emptyMap(), emptyList(), "support.mozilla.org/kb/captive-portal".toByteArray())
                else -> HttpResult(500, emptyMap(), emptyList(), ByteArray(0))
            }
        }
        val posted = Portal.submit(http, "http://10.0.0.1/fgtauth?tok", LOGIN.toByteArray(), "cadet", "secret-test") {}
        assertTrue(posted.ok)
        assertEquals(2, posts.size)
        assertTrue("username=cadet" in posts[0])
        assertTrue("password=secret-test" in posts[0])
        assertTrue("magic=tok" in posts[0])
        assertTrue("answer=1" in posts[1])
    }

    @Test
    fun authFailureStops() {
        val http = HttpCall { _, _, _, _, _ ->
            HttpResult(200, emptyMap(), emptyList(), "Authentication Failed".toByteArray())
        }
        val posted = Portal.submit(
            http,
            "http://10.0.0.1/fgtauth?tok",
            LOGIN.toByteArray(),
            "cadet",
            "secret-test",
        ) {}
        assertFalse(posted.ok)
    }

    private companion object {
        const val LOGIN = """<html><form action="/" method="post">
            <input type="hidden" name="magic" value="tok">
            <input type="text" name="username" value="">
            <input type="password" name="password" value="">
            </form></html>"""
        const val DISCLAIMER = """<html>Firewall Disclaimer<form method="post" action="/">
            <input type="hidden" name="magic" value="abc">
            </form></html>"""
    }
}
