package com.arcioth.wlantron

import java.io.File
import java.net.SocketTimeoutException
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalTest {
    @Test
    fun allowlistedProbeIsNotInternet() {
        val http = HttpCall { _, url, _, _, _ ->
            when {
                "example.com" in url -> HttpResult(
                    302,
                    mapOf("location" to "http://10.1.1.1/fgtauth?abc"),
                    emptyList(),
                    ByteArray(0),
                )
                "generate_204" in url -> HttpResult(204, emptyMap(), emptyList(), ByteArray(0))
                else -> HttpResult(200, emptyMap(), emptyList(), "support.mozilla.org/kb/captive-portal".toByteArray())
            }
        }
        val state = Portal.probe(http)
        assertTrue(state is Probe.Portal)
        assertEquals("http://10.1.1.1/fgtauth?abc", (state as Probe.Portal).url)
    }

    @Test
    fun exampleDomainMeansOnline() {
        val http = HttpCall { _, url, _, _, _ ->
            if ("example.com" in url && url.startsWith("https")) {
                HttpResult(200, emptyMap(), emptyList(), "<html><body>Example Domain</body></html>".toByteArray())
            } else if ("example.com" in url) {
                HttpResult(301, mapOf("location" to "https://example.com/"), emptyList(), ByteArray(0))
            } else {
                HttpResult(204, emptyMap(), emptyList(), ByteArray(0))
            }
        }
        assertTrue(Portal.probe(http) is Probe.Online)
    }

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


    // --- Credentials go only to a FortiGate auth page -------------------------------------

    @Test
    fun routerLoginGetsNoPassword() {
        val sent = mutableListOf<String>()
        val http = HttpCall { _, url, _, body, _ ->
            if (body != null) sent += url
            ok(200, ROUTER_LOGIN)
        }
        val posted = Portal.submit(http, "http://192.168.1.1/login.html", null, "cadet", "secret-test") {}
        assertFalse(posted.ok)
        assertTrue(sent.isEmpty())
        assertTrue(posted.message, "192.168.1.1" in posted.message)
        assertTrue(posted.message, "Your password was not sent." in posted.message)
    }

    @Test
    fun mozillaPageGetsNoPost() {
        val sent = mutableListOf<String>()
        val http = HttpCall { _, url, _, body, _ ->
            if (body != null) sent += url
            ok(200, """<html><form action="/search" method="get"><input name="q"></form></html>""")
        }
        val posted = Portal.submit(http, "https://support.mozilla.org/kb/captive-portal", null, "cadet", "secret-test") {}
        assertFalse(posted.ok)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun crossOriginActionGetsNoPassword() {
        val sent = mutableListOf<String>()
        val http = HttpCall { _, url, _, body, _ ->
            if (body != null) sent += url
            ok(200, "")
        }
        val form = LOGIN.replace("""action="/"""", """action="http://203.0.113.9/x"""")
        val posted = Portal.submit(http, "http://10.0.0.1/fgtauth?abc", form.toByteArray(), "cadet", "secret-test") {}
        assertFalse(posted.ok)
        assertTrue(sent.isEmpty())
    }

    // --- One credential POST per tap, success only after a POST ----------------------------

    @Test
    fun secondPasswordFormMeansRejected() {
        val posts = mutableListOf<String>()
        val http = HttpCall { method, _, _, body, _ ->
            if (method == "POST") {
                posts += body!!.toString(Charsets.UTF_8)
                ok(302, headers = mapOf("location" to "http://10.0.0.1/fgtauth?beef"))
            } else {
                ok(200, LOGIN)
            }
        }
        val posted = Portal.submit(http, "http://10.0.0.1/fgtauth?abc", LOGIN.toByteArray(), "cadet", "secret-test") {}
        assertEquals(1, posts.count { "password=" in it })
        assertFalse(posted.ok)
        assertTrue(posted.message, "asked for the password again" in posted.message)
    }

    @Test
    fun endlessRedirectIsNotSuccess() {
        var hops = 0
        val http = HttpCall { method, _, _, _, _ ->
            if (method == "POST") {
                ok(200, "")
            } else {
                hops += 1
                ok(302, headers = mapOf("location" to "http://10.0.0.1/fgtauth?a$hops"))
            }
        }
        val posted = Portal.submit(http, "http://10.0.0.1/fgtauth?a0", null, "cadet", "secret-test") {}
        assertFalse(posted.ok)
    }

    // --- Gateway hint: fgtauth only, tried last --------------------------------------------

    @Test
    fun gatewayRedirectToRouterLoginIsNotPortal() {
        val http = HttpCall { _, url, _, _, _ ->
            if (url.startsWith("http://192.168.1.1/")) {
                ok(302, headers = mapOf("location" to "http://192.168.1.1/login.html"))
            } else {
                throw DnsFailed("DNS failed for ${URI(url).host}")
            }
        }
        val state = Portal.probe(http, listOf("http://192.168.1.1/"))
        assertFalse(state.toString(), state is Probe.Portal)
    }

    @Test
    fun onlineWinsOverGatewayRedirect() {
        val http = HttpCall { _, url, _, _, _ ->
            when {
                url.startsWith("http://10.3.248.1/") -> ok(302, headers = mapOf("location" to "https://10.3.248.1/admin"))
                "example.com" in url -> ok(200, "<html><body>Example Domain</body></html>")
                else -> ok(204)
            }
        }
        assertTrue(Portal.probe(http, listOf("http://10.3.248.1/")) is Probe.Online)
    }

    // --- A test site that answers normally is not a portal ---------------------------------

    @Test
    fun firefoxRefreshIsNotAPortal() {
        val http = HttpCall { _, url, _, _, _ ->
            when {
                "example.com" in url -> throw NoAnswer("failed to connect to example.com/93.184.215.14 (port 80) after 4000ms")
                "canonical.html" in url -> ok(200, FIREFOX_CANONICAL)
                "hotspot-detect" in url -> ok(200, "<HTML><HEAD><TITLE>Success</TITLE></HEAD><BODY>Success</BODY></HTML>")
                else -> ok(204)
            }
        }
        val state = Portal.probe(http)
        assertTrue(state.toString(), state is Probe.Down)
        assertEquals(DownKind.REACHABLE, (state as Probe.Down).kind)
    }

    // --- Typed failures and a plain diagnosis ----------------------------------------------

    @Test
    fun silentNetworkIsExplained() {
        val tried = mutableListOf<String>()
        val http = HttpCall { _, url, _, _, _ ->
            tried += url
            throw NoAnswer("failed to connect to ${URI(url).host} (port 80) after 4000ms")
        }
        val state = Portal.probe(http, listOf("http://192.168.137.1/"))
        assertTrue(state.toString(), state is Probe.Down)
        val down = state as Probe.Down
        assertEquals(DownKind.SILENT, down.kind)
        val text = Portal.explain(down, AndroidCheck.NONE, signingIn = true).joinToString("\n")
        assertTrue(text, "no web server answered" in text)
        assertTrue(text, "Your password was not sent." in text)
        assertTrue(text, "campus list" in text)
        for (url in tried) assertTrue("$url missing from: $text", url.substringBefore('?') in text)
        val check = Portal.explain(down, AndroidCheck.NONE, signingIn = false).joinToString("\n")
        assertFalse(check, "password" in check)
    }

    @Test
    fun dnsFailureDoesNotHideSilence() {
        val http = HttpCall { _, url, _, _, _ ->
            if ("example.com" in url) throw DnsFailed("DNS timed out for example.com")
            throw NoAnswer("failed to connect to ${URI(url).host} (port 80) after 4000ms")
        }
        val state = Portal.probe(http)
        assertEquals(DownKind.SILENT, (state as Probe.Down).kind)
    }

    @Test
    fun readTimeoutIsNotSilence() {
        val http = HttpCall { _, _, _, _, _ -> throw SocketTimeoutException("Read timed out") }
        val state = Portal.probe(http)
        assertTrue(state is Probe.Down)
        assertFalse(DownKind.SILENT == (state as Probe.Down).kind)
    }

    @Test
    fun unrecognizedAnswersAreNotTimeouts() {
        val http = HttpCall { _, _, _, _, _ -> ok(200, "hello") }
        val state = Portal.probe(http)
        assertTrue(state is Probe.Down)
        assertEquals(DownKind.UNRECOGNIZED, (state as Probe.Down).kind)
        val text = Portal.explain(state, AndroidCheck.UNKNOWN, signingIn = false).joinToString("\n")
        assertFalse(text, "timed out" in text)
    }

    @Test
    fun explainUsesAndroidVerdict() {
        val down = Probe.Down(
            DownKind.SILENT,
            listOf(Attempt("http://example.com/", AttemptKind.NO_ANSWER, "failed to connect", hint = false)),
        )
        val text = Portal.explain(down, AndroidCheck.SIGN_IN, signingIn = true).joinToString("\n")
        assertTrue(text, "Android's last check saw a sign-in page" in text)
        assertTrue(text, "campus list" in text)
        assertFalse(text, "got no answer from it" in text)
        assertEquals("Android's own check: no internet, and no sign-in page.", Portal.androidLine(AndroidCheck.NONE))
        assertEquals(null, Portal.androidLine(AndroidCheck.UNKNOWN))
    }

    @Test
    fun androidSignInVerdictKeepsProbing() {
        val tried = mutableListOf<String>()
        val http = HttpCall { _, url, _, _, _ ->
            tried += url
            if ("generate_204" in url) {
                ok(302, headers = mapOf("location" to "http://10.0.0.1:1000/fgtauth?aa"))
            } else {
                throw NoAnswer("failed to connect to ${URI(url).host} (port 80) after 4000ms")
            }
        }
        val state = Portal.probe(http, emptyList(), AndroidCheck.SIGN_IN)
        assertTrue(tried.toString(), state is Probe.Portal)
    }

    // --- No hang on a silent network -------------------------------------------------------

    @Test
    fun silentNetworkStopsAfterTwoHosts() {
        val tried = mutableListOf<String>()
        val http = HttpCall { _, url, _, _, _ ->
            tried += url
            throw NoAnswer("failed to connect to ${URI(url).host} (port 80) after 4000ms")
        }
        Portal.probe(http, listOf("http://192.168.137.1/"))
        assertEquals(tried.toString(), 2, tried.size)
        assertFalse(tried.any { "192.168.137.1" in it })
    }

    @Test
    fun reprobeLoopReportsProgress() {
        val lines = mutableListOf<String>()
        var posted = false
        val http = HttpCall { method, url, _, _, _ ->
            when {
                method == "POST" -> {
                    posted = true
                    ok(200, "ok")
                }
                posted -> throw NoAnswer("failed to connect to ${URI(url).host} (port 80) after 4000ms")
                "example.com" in url -> ok(303, headers = mapOf("location" to "https://10.0.0.1:1003/fgtauth?abc"))
                "fgtauth" in url -> ok(200, LOGIN)
                else -> throw NoAnswer("failed to connect")
            }
        }
        val outcome = Portal.signIn(http, "cadet", "secret-test", { lines += it }, pause = {})
        assertTrue(outcome is Outcome.Failed)
        for (n in 1..5) assertTrue(lines.toString(), lines.any { "($n of 5)" in it })
    }

    // --- Turkish locale: no "LOG İN" -------------------------------------------------------

    @Test
    fun buttonsAreNotAllCaps() {
        val layout = listOf("src/main/res/layout/activity_main.xml", "app/src/main/res/layout/activity_main.xml")
            .map(::File).first { it.isFile }
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(layout)
        val buttons = doc.getElementsByTagName("Button")
        assertTrue(buttons.length >= 3)
        for (i in 0 until buttons.length) {
            val node = buttons.item(i) as org.w3c.dom.Element
            val id = node.getAttributeNS(ANDROID_NS, "id")
            assertEquals("$id textAllCaps", "false", node.getAttributeNS(ANDROID_NS, "textAllCaps"))
        }
    }

    private fun ok(status: Int, body: String = "", headers: Map<String, String> = emptyMap()) =
        HttpResult(status, headers, emptyList(), body.toByteArray())

    private companion object {
        const val LOGIN = """<html><form action="/" method="post">
            <input type="hidden" name="magic" value="tok">
            <input type="text" name="username" value="">
            <input type="password" name="password" value="">
            </form></html>"""
        const val DISCLAIMER = """<html>Firewall Disclaimer<form method="post" action="/">
            <input type="hidden" name="magic" value="abc">
            </form></html>"""
        const val ROUTER_LOGIN = """<html><form action="/cgi-bin/login" method="post">
            <input type="text" name="username"><input type="password" name="password">
            </form></html>"""

        /** Firefox's captivedetect.canonicalContent, what detectportal.firefox.com/canonical.html serves. */
        const val FIREFOX_CANONICAL =
            """<meta http-equiv="refresh" content="0;url=https://support.mozilla.org/kb/captive-portal"/>"""
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
