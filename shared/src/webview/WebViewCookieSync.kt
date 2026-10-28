package alyac.kotlin.susweb.webview

import alyac.kotlin.susweb.API_URL
import alyac.kotlin.susweb.BASE_URL
import alyac.kotlin.susweb.USER_URL
import alyac.kotlin.susweb.session.MY_PAGE_URL
import dev.nucleusframework.webview.cookie.Cookie as WebViewCookie
import dev.nucleusframework.webview.cookie.CookieManager
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.Url
import io.ktor.util.date.GMTDate

/** Restores the token on profile open, then imports native cookies into Ktor's storage. */
internal class WebViewCookieSync(private val storage: CookiesStorage) {
    private data class Key(val name: String, val domain: String, val path: String)

    private data class Entry(val url: Url, val source: WebViewCookie?, val cookie: Cookie)

    private var previous = emptyMap<Key, Entry>()
    private var initialized = false

    /** Restore once for each new native view, before an empty snapshot can mean logout. */
    suspend fun restoreToken(manager: CookieManager) {
        // A token already in this WebView may come from a more recent login.
        if (
            SESSION_COOKIE_URLS.any { url ->
                manager.getCookies(url).any { it.name == "s_token" && it.value.isNotBlank() }
            }
        )
            return
        val saved =
            SESSION_COOKIE_URLS.flatMap { url ->
                    storage
                        .get(Url(url))
                        .filter { it.name == "s_token" && it.value.isNotBlank() }
                        .map { url to it }
                }
                .distinctBy { (_, cookie) -> Triple(cookie.name, cookie.domain, cookie.path) }
        for ((url, cookie) in saved) {
            manager.setCookie(
                url,
                WebViewCookie(
                    name = cookie.name,
                    value = cookie.value,
                    domain =
                        cookie.domain?.let { domain ->
                            if (Url(url).host != domain.trimStart('.')) ".${domain.trimStart('.')}"
                            else domain
                        },
                    path = cookie.path,
                    expiresDate = cookie.expires?.timestamp,
                    isSessionOnly = cookie.expires == null,
                    isSecure = cookie.secure,
                    isHttpOnly = cookie.httpOnly,
                    sameSite =
                        cookie.extensions["SameSite"]?.let { value ->
                            WebViewCookie.HTTPCookieSameSitePolicy.entries.firstOrNull {
                                it.name.equals(value, ignoreCase = true)
                            }
                        },
                ),
            )
        }
        if (saved.isNotEmpty()) {
            // Android's native setCookie is asynchronous. Retry restoration on the next poll
            // instead of treating a not-yet-visible cookie as a logout and deleting KSafe data.
            check(
                SESSION_COOKIE_URLS.any { url ->
                    manager.getCookies(url).any { native ->
                        native.name == "s_token" && saved.any { it.second.value == native.value }
                    }
                },
            ) {
                "Restored token is not available in WebView yet"
            }
        }
    }

    /**
     * Supply a complete snapshot of all observed URLs; failed reads must not become empty lists.
     */
    suspend fun sync(snapshot: Map<String, List<WebViewCookie>>) {
        val stored = snapshot.keys.associateWith { storage.get(Url(it)) }
        if (!initialized) {
            // restoreToken must run before this first snapshot for a newly attached profile.
            // Once restored, disappearing native cookies can be treated as logout.
            previous = buildMap {
                for (urlString in snapshot.keys) {
                    val url = Url(urlString)
                    for (cookie in stored.getValue(urlString)) {
                        put(
                            Key(
                                cookie.name,
                                cookie.domain!!.trimStart('.').lowercase(),
                                cookie.path!!,
                            ),
                            Entry(url, null, cookie),
                        )
                    }
                }
            }
            initialized = true
        }
        val current = buildMap {
            for ((urlString, cookies) in snapshot) {
                val url = Url(urlString)
                for (source in cookies) {
                    if (source.name.isBlank()) continue
                    // Android returns only name/value. Retain the saved scope and deadline when
                    // the same restored token comes back without native cookie metadata.
                    val savedToken =
                        if (
                            source.name == "s_token" &&
                                source.domain == null &&
                                source.path == null &&
                                source.expiresDate == null &&
                                source.maxAge == null &&
                                !source.isSessionOnly
                        )
                            stored.getValue(urlString).firstOrNull {
                                it.name == source.name && it.value == source.value
                            }
                        else null
                    val cookie = savedToken ?: source.toKtorCookie(url)
                    val key =
                        Key(cookie.name, cookie.domain!!.trimStart('.').lowercase(), cookie.path!!)
                    put(key, Entry(url, source, cookie))
                }
            }
        }
        for ((key, entry) in previous) {
            if (key !in current) {
                // CookiesStorage has no remove API. Expire disappeared WebView cookies (logout).
                storage.addCookie(
                    entry.url,
                    entry.cookie.copy(value = "", maxAge = null, expires = GMTDate(0)),
                )
            }
        }
        for ((key, entry) in current) {
            // Re-importing unchanged Max-Age cookies would extend their lifetime on every poll.
            if (previous[key]?.source != entry.source) storage.addCookie(entry.url, entry.cookie)
        }
        previous = current
    }
}

internal val SESSION_COOKIE_URLS = listOf(BASE_URL, USER_URL, API_URL, MY_PAGE_URL)

internal fun WebViewCookie.toKtorCookie(url: Url): Cookie =
    Cookie(
        name = name,
        value = value,
        encoding = CookieEncoding.RAW,
        domain = domain?.takeIf { it.isNotBlank() } ?: url.host,
        path = path?.takeIf { it.startsWith("/") } ?: "/",
        expires = if (isSessionOnly) null else expiresDate?.let { GMTDate(it) },
        maxAge = if (isSessionOnly) null else maxAge?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt(),
        secure = isSecure == true,
        httpOnly = isHttpOnly == true,
        extensions = sameSite?.let { mapOf("SameSite" to it.name) } ?: emptyMap(),
    )
