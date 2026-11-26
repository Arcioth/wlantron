package com.arcioth.wlantron

import java.net.URI
import java.net.URLEncoder

private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

/**
 * FortiGate captive-portal steps shared with the desktop program.
 * No Android types in this file, so the JVM tests can run it.
 *
 * The password only goes to a FortiGate auth page (an /fgtauth?<hex> page or a form with a
 * magic token), posted to that page's own origin, once per tap.
 */
object Portal {
    private const val INTERNET_URL = "http://example.com/"

    /**
     * These names are often allowed through before anyone has logged in. Their normal answer
     * only shows the network passes some traffic. It is not internet, and not a portal.
     */
    private val portalFinders = listOf(
        Finder("http://detectportal.firefox.com/canonical.html") {
            it.status == 200 && "support.mozilla.org/kb/captive-portal" in it.body.toString(Charsets.UTF_8)
        },
        Finder("http://captive.apple.com/hotspot-detect.html") {
            it.status == 200 && "Success" in it.body.toString(Charsets.UTF_8)
        },
        Finder("http://connectivitycheck.gstatic.com/generate_204") { it.status == 204 },
    )

    /** Two public hosts with no answer at all: the rest would only add their timeouts. */
    private const val SILENT_HOSTS_TO_STOP = 2

    fun signIn(
        http: HttpCall,
        username: String,
        password: String,
        log: (String) -> Unit,
        hints: List<String> = emptyList(),
        check: AndroidCheck = AndroidCheck.UNKNOWN,
        pause: (Long) -> Unit = { Thread.sleep(it) },
    ): Outcome {
        log("Checking the portal.")
        when (val first = probe(http, hints, check)) {
            is Probe.Online -> return Outcome.AlreadyOnline
            is Probe.Down -> return Outcome.Failed(explain(first, check, signingIn = true).joinToString("\n"))
            is Probe.Portal -> {
                log("Portal session ${first.url}")
                val posted = submit(http, first.url, first.html, username, password, log)
                if (!posted.ok) return Outcome.Failed(posted.message)
            }
        }
        for (round in 1..5) {
            log("Checking internet access ($round of 5).")
            if (probe(http, hints, check) is Probe.Online) return Outcome.SignedIn
            if (round < 5) pause(800)
        }
        return Outcome.Failed("The portal did not open internet access. Try again.")
    }

    /**
     * example.com first (only it proves internet), then the portal finders, then the Wi-Fi
     * gateway. The gateway counts only if it names a FortiGate /fgtauth?<hex> page.
     */
    fun probe(
        http: HttpCall,
        hints: List<String> = emptyList(),
        check: AndroidCheck = AndroidCheck.UNKNOWN,
    ): Probe {
        // Android saw a sign-in page: keep going, the host that finds it may be one we would skip.
        val stopAfter = if (check == AndroidCheck.SIGN_IN) Int.MAX_VALUE else SILENT_HOSTS_TO_STOP
        val attempts = mutableListOf<Attempt>()
        proveInternet(http, attempts)?.let { return it }
        var reachable = false
        val nonce = System.currentTimeMillis().toString()
        for (finder in portalFinders) {
            if (silentHosts(attempts) >= stopAfter) break
            val url = "${finder.url}?nocache=$nonce"
            val result = fetch(http, url, attempts) ?: continue
            if (finder.normal(result)) {
                reachable = true
                continue
            }
            classifyHit(url, result)?.let { return it }
        }
        if (silentHosts(attempts) < stopAfter) {
            for (hint in hints) {
                val result = fetch(http, hint, attempts, hint = true) ?: continue
                fortiRedirect(hint, result)?.let { return Probe.Portal(it, null) }
            }
        }
        return Probe.Down(downKind(attempts, reachable), attempts)
    }

    /** A normal site, not a captive-portal URL the firewall may allow before login. */
    private fun proveInternet(http: HttpCall, attempts: MutableList<Attempt>): Probe? {
        var url = INTERNET_URL
        var result = fetch(http, url, attempts) ?: return null
        val next = extractRedirect(url, result.status, result.headers, result.body)
        if (next != null) {
            if (isPortalRedirect(url, next)) return Probe.Portal(next, null)
            url = next
            result = fetch(http, url, attempts) ?: return null
            val again = extractRedirect(url, result.status, result.headers, result.body)
            if (again != null && isPortalRedirect(url, again)) return Probe.Portal(again, null)
        }
        classifyHit(url, result)?.let { return it }
        val text = result.body.toString(Charsets.UTF_8)
        if (result.status == 200 && "Example Domain" in text && "<form" !in text.lowercase()) {
            return Probe.Online
        }
        return null
    }

    private fun fetch(
        http: HttpCall,
        url: String,
        attempts: MutableList<Attempt>,
        hint: Boolean = false,
    ): HttpResult? {
        val shown = url.substringBefore("?nocache")
        return try {
            http.exchange("GET", url, emptyMap(), null, 4000).also {
                attempts += Attempt(shown, AttemptKind.ANSWERED, "HTTP ${it.status}", hint)
            }
        } catch (exc: Exception) {
            val kind = when (exc) {
                is NoAnswer -> AttemptKind.NO_ANSWER
                is DnsFailed -> AttemptKind.DNS
                else -> AttemptKind.ERROR
            }
            attempts += Attempt(shown, kind, exc.message ?: exc.javaClass.simpleName, hint)
            null
        }
    }

    private fun silentHosts(attempts: List<Attempt>): Int =
        attempts.filter { !it.hint && it.kind == AttemptKind.NO_ANSWER }
            .mapNotNull { runCatching { URI(it.url).host?.lowercase() }.getOrNull() }
            .toSet().size

    private fun downKind(attempts: List<Attempt>, reachable: Boolean): DownKind {
        val public = attempts.filter { !it.hint }
        return when {
            reachable -> DownKind.REACHABLE
            attempts.any { it.kind == AttemptKind.ANSWERED } -> DownKind.UNRECOGNIZED
            public.any { it.kind == AttemptKind.NO_ANSWER } -> DownKind.SILENT
            public.isNotEmpty() && public.all { it.kind == AttemptKind.DNS } -> DownKind.DNS
            else -> DownKind.OTHER
        }
    }

    /** http://example.com to https://example.com is not a captive portal. A private-IP or fgtauth redirect is. */
    private fun isPortalRedirect(from: String, to: String): Boolean {
        val lower = to.lowercase()
        if ("fgtauth" in lower) return true
        val toHost = runCatching { URI(to).host }.getOrNull() ?: return true
        val fromHost = runCatching { URI(from).host }.getOrNull()
        if (fromHost != null && fromHost.equals(toHost, ignoreCase = true)) return false
        if (IPV4.matches(toHost)) {
            val a = toHost.split('.').map { it.toInt() }
            val priv = a[0] == 10 || (a[0] == 192 && a[1] == 168) || (a[0] == 172 && a[1] in 16..31)
            if (priv) return true
        }
        return false
    }

    private fun classifyHit(url: String, result: HttpResult): Probe? {
        val redir = extractRedirect(url, result.status, result.headers, result.body)
        if (redir != null) return Probe.Portal(redir, null)
        val text = result.body.toString(Charsets.UTF_8)
        val lower = text.lowercase()
        if ("<form" in lower && ("magic" in lower || "fgtauth" in lower)) {
            return Probe.Portal(url, result.body)
        }
        return null
    }

    /** Only a FortiGate auth URL. A router or admin login on the gateway is not the portal. */
    private fun fortiRedirect(base: String, result: HttpResult): String? {
        val location = result.headers["location"]
        if (result.status in REDIRECTS && !location.isNullOrBlank()) {
            return joinUrl(base, location.trim()).takeIf(::isFortiAuthUrl)
        }
        return FGTAUTH.find(result.body.toString(Charsets.UTF_8))?.value
    }

    private fun isFortiAuthUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.path.orEmpty().endsWith("/fgtauth") && HEX.matches(uri.rawQuery.orEmpty())
    }

    private fun isFortiAuthPage(pageUrl: String, form: RawForm): Boolean =
        isFortiAuthUrl(pageUrl) || form.inputs.any { it.name.trim().equals("magic", ignoreCase = true) }

    private fun sameOrigin(a: String, b: String): Boolean {
        val x = runCatching { URI(a) }.getOrNull() ?: return false
        val y = runCatching { URI(b) }.getOrNull() ?: return false
        fun port(u: URI) = if (u.port > 0) u.port else if (u.scheme.equals("https", ignoreCase = true)) 443 else 80
        return x.scheme.equals(y.scheme, ignoreCase = true) &&
            x.host != null && x.host.equals(y.host, ignoreCase = true) &&
            port(x) == port(y)
    }

    /** One line for the log about Android's own captive-portal check, or null if unknown. */
    fun androidLine(check: AndroidCheck): String? = when (check) {
        AndroidCheck.INTERNET -> "Android's own check: this Wi-Fi has internet."
        AndroidCheck.SIGN_IN -> "Android's own check: this Wi-Fi has a sign-in page."
        AndroidCheck.NONE -> "Android's own check: no internet, and no sign-in page."
        AndroidCheck.UNKNOWN -> null
    }

    /** What the student reads when the probe found neither internet nor the FortiGate page. */
    fun explain(down: Probe.Down, check: AndroidCheck, signingIn: Boolean): List<String> {
        val lines = mutableListOf<String>()
        when (down.kind) {
            DownKind.SILENT -> {
                lines += "Wi-Fi is connected and names resolve, but no web server answered, not even a login page."
                if (signingIn) lines += "Your password was not sent."
                when (check) {
                    AndroidCheck.SIGN_IN ->
                        lines += "Android's last check saw a sign-in page on this Wi-Fi. A setting may also block wlantron on Wi-Fi."
                    AndroidCheck.INTERNET ->
                        lines += "Android's last check said this Wi-Fi had internet. A setting may also block wlantron on Wi-Fi."
                    else -> Unit
                }
                lines += SILENT_STEPS
            }
            DownKind.REACHABLE -> {
                lines += "The test sites answered, but example.com did not load and no login page appeared. " +
                    "Open http://example.com/ in the browser and see what this Wi-Fi shows."
                if (signingIn) lines += "Your password was not sent."
            }
            DownKind.DNS -> {
                lines += "This Wi-Fi did not answer name lookups. Rejoin HHO_WiFi and tap Check again."
                if (signingIn) lines += "Your password was not sent."
            }
            DownKind.UNRECOGNIZED -> {
                lines += "Sites answered, but none was the internet or the FortiGate login page."
                if (signingIn) lines += "Your password was not sent."
            }
            DownKind.OTHER -> {
                lines += "Could not reach the internet or the FortiGate login page."
                if (signingIn) lines += "Your password was not sent."
            }
        }
        if (down.attempts.isNotEmpty()) lines += "Details: ${down.detail}"
        return lines
    }

    private val SILENT_STEPS = listOf(
        "Likely causes, most likely first:",
        "1. This device's Wi-Fi MAC is not on the campus list. Android 10 and later use a random MAC " +
            "for each network unless you choose the device MAC.",
        "2. This Wi-Fi is a phone or laptop hotspot, not HHO_WiFi. Check the network name.",
        "3. The campus network is closed or down right now. Try again later.",
        "To check the MAC, open Settings, Wi-Fi (Kablosuz), and tap HHO_WiFi or its gear icon. " +
            "The MAC address shown there must be the one on the campus list.",
        "If the list has the device's own MAC, set Privacy (Gizlilik) or MAC address type to the device MAC " +
            "(Cihazın MAC adresini kullan; Phone MAC on Samsung). Rejoin HHO_WiFi, then tap Log in.",
        "Forgetting and rejoining HHO_WiFi does not change the random MAC.",
    )

    internal fun submit(
        http: HttpCall,
        portalUrl: String,
        inlineHtml: ByteArray?,
        username: String,
        password: String,
        log: (String) -> Unit,
    ): PostResult {
        val cookies = mutableListOf<String>()
        var current = portalUrl
        var html = inlineHtml
        var sentPassword = false
        var posted = false
        repeat(3) {
            if (html == null) {
                log("Fetching the portal form")
                val headers = if (cookies.isEmpty()) emptyMap() else mapOf("Cookie" to cookies.joinToString("; "))
                val page = try {
                    http.exchange("GET", current, headers, null, 6000)
                } catch (exc: Exception) {
                    return PostResult(false, "Could not open the portal form: ${exc.message ?: exc.javaClass.simpleName}")
                }
                for (cookie in page.cookies) if (cookie !in cookies) cookies += cookie
                val location = page.headers["location"]
                if (page.status in REDIRECTS && location != null) {
                    current = joinUrl(current, location)
                    html = null
                    return@repeat
                }
                html = page.body
            }
            val pageHtml = html!!.toString(Charsets.UTF_8)
            val form = parseForm(pageHtml)
            if (form == null) {
                val redir = extractRedirect(current, 200, emptyMap(), html!!)
                if (redir != null && redir != current) {
                    current = redir
                    html = null
                    return@repeat
                }
                return PostResult(false, "Portal page did not contain a login form.")
            }
            val postUrl = joinUrl(current, form.action.ifBlank { "/" })
            if (!isFortiAuthPage(current, form) || !sameOrigin(current, postUrl)) {
                val host = runCatching { URI(current).host }.getOrNull() ?: current
                val tail = if (sentPassword) "Nothing more was sent." else "Your password was not sent."
                return PostResult(false, "The page at $host is not the FortiGate login page. $tail")
            }
            val parts = classifyForm(form, current)
            val fields = parts.fields.toMutableList()
            if (parts.needsUser || parts.needsPass) {
                if (sentPassword) {
                    return PostResult(
                        false,
                        "FortiGate asked for the password again. The username or password may be wrong, " +
                            "or the login page expired. Check them and tap Log in again.",
                    )
                }
                log("Signing in as $username")
                fields += parts.userField to username
                fields += parts.passField to password
                sentPassword = true
            } else {
                log("Accepting the firewall disclaimer")
                if (fields.none { it.first == "answer" }) fields += "answer" to "1"
            }
            val origin = originOf(current)
            val headers = mutableMapOf(
                "Content-Type" to "application/x-www-form-urlencoded",
                "Origin" to origin,
                "Referer" to current,
            )
            if (cookies.isNotEmpty()) headers["Cookie"] = cookies.joinToString("; ")
            posted = true
            val reply = try {
                http.exchange(form.method.ifBlank { "POST" }, postUrl, headers, formBody(fields), 7000)
            } catch (exc: Exception) {
                log("Portal closed the connection after POST. Checking if the session opened.")
                HttpResult(200, emptyMap(), emptyList(), ByteArray(0))
            }
            for (cookie in reply.cookies) if (cookie !in cookies) cookies += cookie
            val text = reply.body.toString(Charsets.UTF_8)
            if (AUTH_FAIL.containsMatchIn(text)) {
                return PostResult(false, "FortiGate rejected the username or password.")
            }
            val next = extractRedirect(postUrl, reply.status, reply.headers, reply.body)
            if (next != null && "/fgtauth?" in next && next != current) {
                current = next
                html = null
                return@repeat
            }
            if ("Firewall Disclaimer" in text && "<form" in text.lowercase()) {
                current = postUrl
                html = reply.body
                return@repeat
            }
            return PostResult(true, "")
        }
        if (posted) return PostResult(true, "")
        return PostResult(false, "The portal kept redirecting and never showed a login form. Tap Log in again.")
    }

    fun extractRedirect(base: String, status: Int, headers: Map<String, String>, body: ByteArray): String? {
        val location = headers["location"]
        if (status in REDIRECTS && !location.isNullOrBlank()) return joinUrl(base, location.trim())
        val text = body.toString(Charsets.UTF_8)
        FGTAUTH.find(text)?.let { return it.value }
        REFRESH.find(text)?.let { return joinUrl(base, it.groupValues[1].trim()) }
        LOCATION_JS.find(text)?.let { return joinUrl(base, it.groupValues[1].trim()) }
        return null
    }

    fun parseForm(html: String): RawForm? {
        val form = FORM.find(html) ?: return null
        val attrs = parseAttrs(form.groupValues[1])
        val start = form.range.last
        val end = html.indexOf("</form", startIndex = start, ignoreCase = true).let { if (it < 0) html.length else it }
        val inputs = INPUT.findAll(html.substring(start, end)).map { match ->
            val ad = parseAttrs(match.groupValues[1])
            RawInput(
                name = ad["name"].orEmpty(),
                type = (ad["type"] ?: "text").lowercase(),
                value = ad["value"].orEmpty(),
            )
        }.toList()
        if (inputs.isEmpty() && attrs.isEmpty()) return null
        return RawForm(
            action = attrs["action"] ?: "/",
            method = (attrs["method"] ?: "POST").uppercase(),
            inputs = inputs,
        )
    }

    fun classifyForm(form: RawForm, currentUrl: String): ClassifiedForm {
        val fields = mutableListOf<Pair<String, String>>()
        val names = mutableSetOf<String>()
        var needsUser = false
        var needsPass = false
        var userField = "username"
        var passField = "password"
        for (input in form.inputs) {
            val name = input.name.trim()
            if (name.isEmpty() || input.type in SKIP_TYPES) continue
            names += name
            when {
                input.type == "password" || name.lowercase() in PASS_NAMES -> {
                    needsPass = true
                    passField = name
                }
                name.lowercase() in USER_NAMES -> {
                    needsUser = true
                    userField = name
                }
                else -> fields += name to input.value
            }
        }
        val query = URI(currentUrl).rawQuery.orEmpty()
        if ("magic" !in names && query.isNotEmpty() && "=" !in query) {
            fields += "magic" to query
        }
        return ClassifiedForm(fields, needsUser, needsPass, userField, passField)
    }

    fun joinUrl(base: String, relative: String): String {
        return try {
            URI(base).resolve(relative).toString()
        } catch (_: Exception) {
            relative
        }
    }

    private fun originOf(url: String): String {
        val uri = URI(url)
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return "${uri.scheme}://${uri.host}$port"
    }

    private fun formBody(fields: List<Pair<String, String>>): ByteArray {
        return fields.joinToString("&") { (key, value) ->
            URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
        }.toByteArray(Charsets.UTF_8)
    }

    private fun parseAttrs(raw: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (match in ATTR.findAll(raw)) {
            val value = match.groupValues[2].ifEmpty { match.groupValues[3].ifEmpty { match.groupValues[4] } }
            out[match.groupValues[1].lowercase()] = value
        }
        return out
    }

    private val REDIRECTS = setOf(301, 302, 303, 307, 308)
    private val SKIP_TYPES = setOf("submit", "button", "image", "reset")
    private val PASS_NAMES = setOf("password", "passwd", "ft_pd")
    private val USER_NAMES = setOf("username", "user", "ft_un")
    private val AUTH_FAIL = Regex("Authentication Failed|Invalid username|Login failed", RegexOption.IGNORE_CASE)
    private val FGTAUTH = Regex("""https?://[^\s"'<>]+/fgtauth\?[0-9a-fA-F]+""")
    private val HEX = Regex("[0-9a-fA-F]+")
    private val REFRESH = Regex(
        """<meta[^>]+http-equiv=["']?refresh["']?[^>]+content=["'][^"']*url=([^"'\s>]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val LOCATION_JS = Regex("""(?:window\.)?location(?:\.href)?\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val FORM = Regex("""<form\b([^>]*)>""", RegexOption.IGNORE_CASE)
    private val INPUT = Regex("""<input\b([^>]*)/?>""", RegexOption.IGNORE_CASE)
    private val ATTR = Regex("""([A-Za-z_:][-A-Za-z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+))""")
}

/** A portal test site and what its normal, not-intercepted answer looks like. */
private class Finder(val url: String, val normal: (HttpResult) -> Boolean)

fun interface HttpCall {
    fun exchange(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
        timeoutMs: Int,
    ): HttpResult
}

data class HttpResult(
    val status: Int,
    val headers: Map<String, String>,
    val cookies: List<String>,
    val body: ByteArray,
)

sealed class Probe {
    data object Online : Probe()
    data class Portal(val url: String, val html: ByteArray?) : Probe()
    /** Neither internet nor a FortiGate page. [attempts] holds every URL tried, in order. */
    data class Down(val kind: DownKind, val attempts: List<Attempt>) : Probe() {
        val detail: String get() = attempts.joinToString("; ") { "${it.url}: ${it.detail}" }
    }
}

sealed class Outcome {
    data object AlreadyOnline : Outcome()
    data object SignedIn : Outcome()
    data class Failed(val message: String) : Outcome()
}

class NoAnswer(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)

class DnsFailed(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)

enum class AndroidCheck { INTERNET, SIGN_IN, NONE, UNKNOWN }

enum class DownKind { SILENT, REACHABLE, DNS, UNRECOGNIZED, OTHER }

enum class AttemptKind { ANSWERED, NO_ANSWER, DNS, ERROR }

data class Attempt(val url: String, val kind: AttemptKind, val detail: String, val hint: Boolean)

data class PostResult(val ok: Boolean, val message: String)

data class RawInput(val name: String, val type: String, val value: String)

data class RawForm(val action: String, val method: String, val inputs: List<RawInput>)

data class ClassifiedForm(
    val fields: List<Pair<String, String>>,
    val needsUser: Boolean,
    val needsPass: Boolean,
    val userField: String,
    val passField: String,
)
