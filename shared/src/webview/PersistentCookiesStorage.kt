package alyac.kotlin.susweb.webview

import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.client.plugins.cookies.matches
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.Url
import io.ktor.util.date.GMTDate
import io.ktor.util.date.getTimeMillis
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Cookie jar that retains login tokens across runs, including tokens without an expiry. */
internal class PersistentCookiesStorage(
    private val persistence: CookiePersistence,
    private val clock: () -> Long = { getTimeMillis() },
) : CookiesStorage {
    private val mutex = Mutex()
    private var loaded = false
    private var entries = emptyList<Entry>()

    private data class Entry(val cookie: Cookie, val hostOnly: Boolean)

    override suspend fun get(requestUrl: Url): List<Cookie> = mutex.withLock {
        load()
        val live = entries.filter {
            it.cookie.expires?.timestamp?.let { expiry -> expiry <= clock() } != true
        }
        if (live != entries) commit(live)
        live
            .filter {
                (!it.hostOnly || it.cookie.domain == requestUrl.host.lowercase()) &&
                    it.cookie.matches(requestUrl)
            }
            .map { it.cookie }
            .sortedByDescending { it.path?.length ?: 0 }
    }

    override suspend fun addCookie(requestUrl: Url, cookie: Cookie) {
        if (cookie.name.isBlank()) return
        mutex.withLock {
            load()
            val domain = cookie.domain?.trimStart('.')?.lowercase()?.takeIf { it.isNotBlank() }
            val host = requestUrl.host.lowercase()
            if (domain != null && domain != host && !host.endsWith(".$domain")) return@withLock
            val path =
                cookie.path?.takeIf { it.startsWith('/') }
                    ?: requestUrl.encodedPath.substringBeforeLast('/', "").ifEmpty { "/" }
            // Convert Max-Age once so restoring the jar cannot restart a relative lifetime.
            val expires =
                cookie.maxAge?.let { seconds ->
                    GMTDate(if (seconds <= 0) 0 else clock() + seconds.toLong() * 1_000)
                } ?: cookie.expires
            val normalized =
                cookie.copy(domain = domain ?: host, path = path, maxAge = null, expires = expires)
            val next =
                entries
                    .filterNot {
                        it.cookie.expires?.timestamp?.let { expiry -> expiry <= clock() } == true ||
                            (it.cookie.name == normalized.name &&
                                it.cookie.domain == normalized.domain &&
                                it.cookie.path == path)
                    }
                    .toMutableList()
            if (expires == null || expires.timestamp > clock())
                next.add(Entry(normalized, domain == null))
            commit(next)
        }
    }

    // Writes are awaited; the app-scoped KSafe instance is owned separately.
    override fun close() = Unit

    /** Replace all token scopes atomically; unrelated cookies remain in memory. */
    internal suspend fun replaceToken(cookie: Cookie?) = mutex.withLock {
        require(cookie == null || cookie.name == "s_token")
        load()
        val next = entries.filterNot { it.cookie.name == "s_token" }.toMutableList()
        val liveCookie = cookie?.takeIf {
            it.expires?.timestamp?.let { expiry -> expiry > clock() } != false
        }
        if (liveCookie != null) {
            next.add(Entry(liveCookie, hostOnly = false))
        }
        commit(next)
        liveCookie
    }

    private suspend fun load() {
        if (loaded) return
        val payload = persistence.read()
        val restored = payload?.let(::decode) ?: emptyList()
        val live = restored.filter {
            it.cookie.expires?.timestamp?.let { expiry -> expiry > clock() } != false
        }
        // Do not replace in-memory state if decrypting, decoding, or committing fails.
        if (live != restored) persistence.write(encode(live))
        entries = live
        loaded = true
    }

    private suspend fun commit(next: List<Entry>) {
        if (next == entries) return
        // Login tokens survive restarts even when native APIs provide no expiry metadata.
        if (encode(next) != encode(entries)) persistence.write(encode(next))
        entries = next
    }

    private fun encode(values: List<Entry>): String = buildJsonObject {
        put("version", 1)
        put(
            "cookies",
            buildJsonArray {
                for ((cookie, hostOnly) in values) {
                    if (cookie.expires == null && cookie.name != "s_token") continue
                    add(
                        buildJsonObject {
                            put("name", cookie.name)
                            put("value", cookie.value)
                            put("encoding", cookie.encoding.name)
                            put("domain", cookie.domain)
                            put("path", cookie.path)
                            put("expires", cookie.expires?.timestamp)
                            put("secure", cookie.secure)
                            put("httpOnly", cookie.httpOnly)
                            put("hostOnly", hostOnly)
                            put(
                                "extensions",
                                buildJsonObject {
                                    for ((key, value) in cookie.extensions) put(key, value)
                                },
                            )
                        },
                    )
                }
            },
        )
    }
        .toString()

    private fun decode(payload: String): List<Entry> {
        val root = Json.parseToJsonElement(payload).jsonObject
        check(root.getValue("version").jsonPrimitive.int == 1) {
            "Unsupported cookie storage version"
        }
        return root.getValue("cookies").jsonArray.map { element ->
            val item = element.jsonObject
            fun text(key: String) = item.getValue(key).jsonPrimitive.content
            Entry(
                Cookie(
                    name = text("name"),
                    value = text("value"),
                    encoding = CookieEncoding.valueOf(text("encoding")),
                    domain = text("domain"),
                    path = text("path"),
                    expires = item.getValue("expires").jsonPrimitive.longOrNull?.let(::GMTDate),
                    secure = item.getValue("secure").jsonPrimitive.boolean,
                    httpOnly = item.getValue("httpOnly").jsonPrimitive.boolean,
                    extensions =
                        item.getValue("extensions").jsonObject.mapValues {
                            it.value.jsonPrimitive.contentOrNull
                        },
                ),
                item.getValue("hostOnly").jsonPrimitive.boolean,
            )
        }
    }
}
