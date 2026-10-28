package alyac.kotlin.susweb.session

import alyac.kotlin.susweb.BASE_URL
import alyac.kotlin.susweb.webview.PersistentCookiesStorage
import alyac.kotlin.susweb.webview.SESSION_COOKIE_URLS
import alyac.kotlin.susweb.webview.WebViewCookieSync
import androidx.compose.runtime.staticCompositionLocalOf
import dev.nucleusframework.webview.cookie.Cookie as WebViewCookie
import dev.nucleusframework.webview.cookie.CookieManager
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.Url
import io.ktor.util.date.GMTDate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Inject the app's single instance into screens and ViewModels. Token presence is not
 * authentication.
 */
interface SessionRepository {
    suspend fun getToken(): String?

    /**
     * Tokens are persisted through KSafe, including tokens without an expiry. An optional expiry is
     * an absolute Unix timestamp in milliseconds. Native WebView updates on its next sync.
     */
    suspend fun saveToken(token: String, expiresAtMillis: Long? = null)

    /**
     * Clears every stored token scope; the native profile applies deletion on its next sync/open.
     */
    suspend fun clearToken()
}

val LocalSessionRepository =
    staticCompositionLocalOf<SessionRepository> {
        error("SessionRepository has not been provided")
    }

internal class CookieSessionRepository(internal val cookieStorage: PersistentCookiesStorage) :
    SessionRepository {
    private val mutex = Mutex()
    private val sync = WebViewCookieSync(cookieStorage)
    private var pending: TokenUpdate? = null

    private data class TokenUpdate(val cookie: Cookie?)

    override suspend fun getToken(): String? = mutex.withLock { token() }

    override suspend fun saveToken(token: String, expiresAtMillis: Long?) {
        require(token.isNotBlank()) { "Token must not be blank" }
        mutex.withLock {
            val cookie =
                Cookie(
                    "s_token",
                    token,
                    CookieEncoding.RAW,
                    domain = "ruliweb.com",
                    path = "/",
                    secure = true,
                    httpOnly = true,
                    expires = expiresAtMillis?.let(::GMTDate),
                )
            pending = TokenUpdate(cookieStorage.replaceToken(cookie))
        }
    }

    override suspend fun clearToken() = mutex.withLock {
        cookieStorage.replaceToken(null)
        pending = TokenUpdate(null)
    }

    /** Call for each newly attached profile, before accepting an empty native snapshot. */
    internal suspend fun restoreProfile(manager: CookieManager) = mutex.withLock {
        applyPending(manager)
        sync.restoreToken(manager)
    }

    /**
     * Serializes native import with app token edits, so an old WebView cannot undo a save/clear.
     */
    internal suspend fun syncProfile(manager: CookieManager): String? = mutex.withLock {
        applyPending(manager)
        sync.sync(SESSION_COOKIE_URLS.associateWith { manager.getCookies(it) })
        token()
    }

    private suspend fun token(): String? {
        for (url in SESSION_COOKIE_URLS) {
            cookieStorage
                .get(Url(url))
                .firstOrNull { it.name == "s_token" && it.value.isNotBlank() }
                ?.let {
                    return it.value
                }
        }
        return null
    }

    private suspend fun applyPending(manager: CookieManager) {
        val update = pending ?: return
        // Remove only s_token, including Android's metadata-free host and parent-domain forms.
        for (url in SESSION_COOKIE_URLS) {
            for (cookie in manager.getCookies(url).filter { it.name == "s_token" }) {
                for (domain in listOf(cookie.domain, Url(url).host, ".ruliweb.com").distinct()) {
                    manager.setCookie(
                        url,
                        cookie.copy(
                            value = "",
                            domain = domain,
                            path = cookie.path ?: "/",
                            maxAge = 0,
                            expiresDate = 0,
                            isSessionOnly = false,
                        ),
                    )
                }
            }
        }
        val saved = update.cookie
        if (saved != null) {
            manager.setCookie(
                BASE_URL,
                WebViewCookie(
                    "s_token",
                    saved.value,
                    domain = ".ruliweb.com",
                    path = "/",
                    expiresDate = saved.expires?.timestamp,
                    isSessionOnly = saved.expires == null,
                    isSecure = true,
                    isHttpOnly = true,
                ),
            )
        }
        val native =
            SESSION_COOKIE_URLS.flatMap { manager.getCookies(it) }
                .filter { it.name == "s_token" && it.value.isNotBlank() }
        check(
            if (saved == null) native.isEmpty()
            else native.isNotEmpty() && native.all { it.value == saved.value },
        ) {
            "Session change is not available in WebView yet"
        }
        pending = null
    }
}
