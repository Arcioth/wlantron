package com.arcioth.wlantron

import java.net.URI
import java.net.URLEncoder

/**
 * FortiGate captive-portal steps shared with the desktop program.
 * No Android types in this file, so the JVM tests can run it.
 */
object Portal {
    private val probes = listOf(
        ProbeSpec("http://detectportal.firefox.com/canonical.html", 200, exact = null, contains = "support.mozilla.org/kb/captive-portal"),
        ProbeSpec("http://detectportal.firefox.com/success.txt", 200, exact = "success", contains = null),
        ProbeSpec("http://captive.apple.com/hotspot-detect.html", 200, exact = null, contains = "Success"),
        ProbeSpec("http://connectivitycheck.gstatic.com/generate_204", 204, exact = null, contains = null),
    )

    fun signIn(
        http: HttpCall,
        username: String,
        password: String,
        log: (String) -> Unit,
        hints: List<String> = emptyList(),
    ): Outcome {
        log("Checking the portal.")
        val first = probe(http, hints)
        when (first) {
            is Probe.Online -> return Outcome.AlreadyOnline
            is Probe.Down -> return Outcome.Failed(first.detail)
            is Probe.Portal -> {
                log("Portal session ${first.url}")
                val posted = submit(http, first.url, first.html, username, password, log)
                if (!posted.ok) return Outcome.Failed(posted.message)
            }
        }
        repeat(5) {
            if (probe(http, hints) is Probe.Online) return Outcome.SignedIn
            Thread.sleep(800)
        }
        return Outcome.Failed("The portal did not open internet access. Try again.")
    }

    fun probe(http: HttpCall, hints: List<String> = emptyList()): Probe {
        val errors = mutableListOf<String>()
        for (url in hints) {
            val found = tryHit(http, url, errors) ?: continue
            return found
        }
        val nonce = System.currentTimeMillis().toString()
        for (spec in probes) {
            val sep = if ('?' in spec.url) "&" else "?"
            val url = "${spec.url}${sep}nocache=$nonce"
            val result = try {
                http.exchange("GET", url, emptyMap(), null, 4000)
            } catch (exc: Exception) {
                errors += "${spec.url}: ${exc.message ?: exc.javaClass.simpleName}"
                continue
            }
            if (result.status == spec.expect) {
                val text = result.body.toString(Charsets.UTF_8).trim()
                val bytes = result.body.toString(Charsets.UTF_8)
                val matched = when {
                    spec.exact != null -> text == spec.exact
                    spec.contains != null -> spec.contains in bytes
                    else -> true
                }
                if (matched) return Probe.Online
            }
            classifyHit(url, result)?.let { return it }
        }
        val detail = errors.take(2).joinToString("; ").ifBlank { "all probes timed out" }
        return Probe.Down(detail)
    }

    private fun tryHit(http: HttpCall, url: String, errors: MutableList<String>): Probe? {
        val result = try {
            http.exchange("GET", url, emptyMap(), null, 3000)
        } catch (exc: Exception) {
            errors += "${url.substringBefore("?nocache")}: ${exc.message ?: exc.javaClass.simpleName}"
            return null
        }
        return classifyHit(url, result)
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
            val parts = classifyForm(form, current)
            val fields = parts.fields.toMutableList()
            if (parts.needsUser || parts.needsPass) {
                log("Signing in as $username")
                fields += parts.userField to username
                fields += parts.passField to password
            } else {
                log("Accepting the firewall disclaimer")
                if (fields.none { it.first == "answer" }) fields += "answer" to "1"
            }
            val postUrl = joinUrl(current, form.action.ifBlank { "/" })
            val origin = originOf(current)
            val headers = mutableMapOf(
                "Content-Type" to "application/x-www-form-urlencoded",
                "Origin" to origin,
                "Referer" to current,
            )
            if (cookies.isNotEmpty()) headers["Cookie"] = cookies.joinToString("; ")
            val posted = try {
                http.exchange(form.method.ifBlank { "POST" }, postUrl, headers, formBody(fields), 7000)
            } catch (exc: Exception) {
                log("Portal closed the connection after POST. Checking if the session opened.")
                HttpResult(200, emptyMap(), emptyList(), ByteArray(0))
            }
            for (cookie in posted.cookies) if (cookie !in cookies) cookies += cookie
            val text = posted.body.toString(Charsets.UTF_8)
            if (AUTH_FAIL.containsMatchIn(text)) {
                return PostResult(false, "FortiGate rejected the username or password.")
            }
            val next = extractRedirect(postUrl, posted.status, posted.headers, posted.body)
            if (next != null && "/fgtauth?" in next && next != current) {
                current = next
                html = null
                return@repeat
            }
            if ("Firewall Disclaimer" in text && "<form" in text.lowercase()) {
                current = postUrl
                html = posted.body
                return@repeat
            }
            return PostResult(true, "")
        }
        return PostResult(true, "")
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
    private val REFRESH = Regex(
        """<meta[^>]+http-equiv=["']?refresh["']?[^>]+content=["'][^"']*url=([^"'\s>]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val LOCATION_JS = Regex("""(?:window\.)?location(?:\.href)?\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val FORM = Regex("""<form\b([^>]*)>""", RegexOption.IGNORE_CASE)
    private val INPUT = Regex("""<input\b([^>]*)/?>""", RegexOption.IGNORE_CASE)
    private val ATTR = Regex("""([A-Za-z_:][-A-Za-z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+))""")
}

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

data class ProbeSpec(
    val url: String,
    val expect: Int,
    val exact: String?,
    val contains: String?,
)

sealed class Probe {
    data object Online : Probe()
    data class Portal(val url: String, val html: ByteArray?) : Probe()
    data class Down(val detail: String) : Probe()
}

sealed class Outcome {
    data object AlreadyOnline : Outcome()
    data object SignedIn : Outcome()
    data class Failed(val message: String) : Outcome()
}

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
