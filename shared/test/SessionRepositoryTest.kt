package alyac.kotlin.susweb

import alyac.kotlin.susweb.ruliweb.RuliwebClient
import alyac.kotlin.susweb.session.*
import alyac.kotlin.susweb.webview.*
import dev.nucleusframework.webview.cookie.Cookie as WebViewCookie
import dev.nucleusframework.webview.cookie.CookieManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.cookies.matches
import io.ktor.client.request.get
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.util.date.GMTDate
import kotlin.test.*
import kotlinx.coroutines.runBlocking

class SessionRepositoryTest {
    private class Disk : CookiePersistence {
        var value: String? = null
        var failWrites = false

        override suspend fun read() = value

        override suspend fun write(value: String) {
            check(!failWrites)
            this.value = value
        }
    }

    private class Native : CookieManager {
        val cookies = mutableMapOf<Pair<String, String>, WebViewCookie>()
        var acceptWrites = true

        override suspend fun getCookies(url: String) =
            cookies.values.filter {
                it.toKtorCookie(Url(url)).matches(Url(url))
            }

        override suspend fun setCookie(url: String, cookie: WebViewCookie) {
            if (!acceptWrites) return
            val key = (cookie.domain?.trimStart('.') ?: Url(url).host) to (cookie.path ?: "/")
            if (cookie.maxAge == 0L || cookie.expiresDate == 0L) cookies.remove(key)
            else cookies[key] = cookie.copy(domain = key.first, path = key.second)
        }

        override suspend fun removeAllCookies() {
            cookies.clear()
        }

        override suspend fun removeCookies(url: String) {
            cookies.clear()
        }
    }

    @Test
    fun `repository and Ktor share persisted token including tokens without expiry`() =
        runBlocking {
            val disk = Disk()
            val storage = PersistentCookiesStorage(TokenCookiePersistence(disk)) { 1_000 }
            val repository: SessionRepository = CookieSessionRepository(storage)
            repository.saveToken("raw%2B+token", 10_000)
            assertEquals("raw%2B+token", repository.getToken())
            val client =
                HttpClient(
                    MockEngine { request ->
                        assertEquals("s_token=raw%2B+token", request.headers[HttpHeaders.Cookie])
                        respond("ok")
                    },
                ) {
                    install(HttpCookies) { this.storage = storage }
                }
            try {
                client.get(PUSH_URL)
            } finally {
                client.close()
            }
            val reopened =
                CookieSessionRepository(
                    PersistentCookiesStorage(TokenCookiePersistence(disk)) { 2_000 },
                )
            assertEquals("raw%2B+token", reopened.getToken())
            val reopenedHttp = HttpClient(MockEngine { respond("ok") })
            try {
                val ruliweb = RuliwebClient(reopenedHttp, reopened)
                assertEquals("raw%2B+token", ruliweb.getToken())
                reopened.clearToken()
                assertNull(ruliweb.getToken())
            } finally {
                reopenedHttp.close()
            }
            repository.saveToken("temporary")
            assertEquals("temporary", repository.getToken())
            assertEquals(
                "temporary",
                CookieSessionRepository(
                        PersistentCookiesStorage(TokenCookiePersistence(disk)) { 2_000 },
                    )
                    .getToken(),
            )
        }

    @Test
    fun `webview login is visible to repository and repository save clear reach active webview`() =
        runBlocking {
            val storage = PersistentCookiesStorage(TokenCookiePersistence(Disk())) { 1_000 }
            val repository = CookieSessionRepository(storage)
            val native = Native()
            native.setCookie(
                BASE_URL,
                WebViewCookie(
                    "s_token",
                    "browser",
                    domain = ".ruliweb.com",
                    path = "/",
                    expiresDate = 10_000,
                ),
            )
            repository.restoreProfile(native)
            assertEquals("browser", repository.syncProfile(native))
            assertEquals("browser", repository.getToken())
            repository.saveToken("app", 10_000)
            assertEquals("app", repository.syncProfile(native))
            assertEquals("app", native.getCookies(BASE_URL).single().value)
            repository.clearToken()
            assertNull(repository.syncProfile(native))
            assertTrue(native.getCookies(BASE_URL).isEmpty())
            repository.restoreProfile(native)
            assertNull(repository.getToken())
        }

    @Test
    fun `clear removes every token scope and leaves other cookies intact`() = runBlocking {
        val disk = Disk()
        val storage = PersistentCookiesStorage(TokenCookiePersistence(disk)) { 1_000 }
        storage.addCookie(
            Url(BASE_URL),
            Cookie("s_token", "one", path = "/", expires = GMTDate(10_000)),
        )
        storage.addCookie(
            Url("$USER_URL/hidden"),
            Cookie("s_token", "two", path = "/hidden", expires = GMTDate(10_000)),
        )
        storage.addCookie(Url(BASE_URL), Cookie("theme", "dark"))
        CookieSessionRepository(storage).clearToken()
        assertEquals(listOf("theme"), storage.get(Url(BASE_URL)).map { it.name })
        assertTrue(storage.get(Url("$USER_URL/hidden")).isEmpty())
        assertFalse(disk.value!!.contains("one"))
        assertFalse(disk.value!!.contains("two"))
    }

    @Test
    fun `failed disk or native update does not lose desired token and remains retryable`() =
        runBlocking {
            val disk = Disk()
            val repository =
                CookieSessionRepository(
                    PersistentCookiesStorage(TokenCookiePersistence(disk)) { 1_000 },
                )
            repository.saveToken("old", 10_000)
            disk.failWrites = true
            assertFailsWith<IllegalStateException> { repository.saveToken("new", 10_000) }
            assertEquals("old", repository.getToken())
            assertFailsWith<IllegalArgumentException> { repository.saveToken(" ") }
            disk.failWrites = false
            repository.saveToken("new", 10_000)
            val native = Native()
            native.acceptWrites = false
            assertFailsWith<IllegalStateException> { repository.restoreProfile(native) }
            assertEquals("new", repository.getToken())
            native.acceptWrites = true
            repository.restoreProfile(native)
            assertEquals("new", repository.syncProfile(native))
            repository.saveToken("expired", 500)
            assertNull(repository.getToken())
            assertNull(repository.syncProfile(native))
        }
}
