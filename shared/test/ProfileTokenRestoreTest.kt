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
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.parseQueryString
import io.ktor.util.date.GMTDate
import kotlin.test.*
import kotlinx.coroutines.runBlocking

class ProfileTokenRestoreTest {
    private class MemoryPersistence : CookiePersistence {
        var value: String? = null

        override suspend fun read() = value

        override suspend fun write(value: String) {
            this.value = value
        }
    }

    private class NativeCookies(private val metadata: Boolean = true) : CookieManager {
        val cookies = mutableListOf<Pair<String, WebViewCookie>>()
        var acceptWrites = true

        override suspend fun getCookies(url: String): List<WebViewCookie> =
            cookies
                .filter {
                    it.second.toKtorCookie(Url(it.first)).matches(Url(url))
                }
                .map { if (metadata) it.second else WebViewCookie(it.second.name, it.second.value) }

        override suspend fun setCookie(url: String, cookie: WebViewCookie) {
            if (acceptWrites) cookies.add(url to cookie)
        }

        override suspend fun removeAllCookies() {
            cookies.clear()
        }

        override suspend fun removeCookies(url: String) {
            cookies.removeAll { it.second.toKtorCookie(Url(it.first)).matches(Url(url)) }
        }
    }

    @Test
    fun `native login without expiry survives restart and logout stays cleared`() = runBlocking {
        for (metadata in listOf(true, false)) {
            val disk = MemoryPersistence()
            fun repository() =
                CookieSessionRepository(
                    PersistentCookiesStorage(TokenCookiePersistence(disk)) { 1_000 },
                )
            val original = repository()
            val login = NativeCookies(metadata)
            login.cookies.add(
                BASE_URL to
                    WebViewCookie(
                        "s_token",
                        "login-token",
                        domain = ".ruliweb.com",
                        path = "/",
                        isSessionOnly = true,
                        isSecure = true,
                        isHttpOnly = true,
                    ),
            )
            original.restoreProfile(login)
            assertEquals("login-token", original.syncProfile(login))

            val restarted = repository()
            val fresh = NativeCookies(metadata)
            restarted.restoreProfile(fresh)
            assertTrue(fresh.cookies.all { it.second.isSessionOnly })
            assertEquals("login-token", restarted.syncProfile(fresh))
            assertEquals("login-token", repository().getToken())

            fresh.removeAllCookies()
            assertNull(restarted.syncProfile(fresh))
            val afterLogout = repository()
            val empty = NativeCookies(metadata)
            afterLogout.restoreProfile(empty)
            assertNull(afterLogout.getToken())
            assertTrue(empty.cookies.isEmpty())
        }
    }

    @Test
    fun `stored token authenticates new profile and survives Android metadata loss and reopen`() =
        runBlocking {
            val disk = MemoryPersistence()
            val saved =
                Cookie(
                    "s_token",
                    "saved-token",
                    domain = ".ruliweb.com",
                    path = "/",
                    expires = GMTDate(10_000),
                    secure = true,
                    httpOnly = true,
                )
            PersistentCookiesStorage(disk) { 1_000 }.addCookie(Url(BASE_URL), saved)
            // Simulate both a process restart and repeated profile open/close, using fresh native
            // views.
            repeat(2) {
                val storage = PersistentCookiesStorage(disk) { 2_000 }
                val native = NativeCookies(metadata = false)
                val sync = WebViewCookieSync(storage)
                sync.restoreToken(native)
                assertTrue(native.cookies.all { it.second.name == "s_token" })
                assertEquals(10_000L, native.cookies.single().second.expiresDate)
                sync.sync(SESSION_COOKIE_URLS.associateWith { native.getCookies(it) })
                assertEquals(10_000L, storage.get(Url(API_URL)).single().expires!!.timestamp)
                val client =
                    HttpClient(
                        MockEngine { request ->
                            assertEquals(
                                "saved-token",
                                parseQueryString(
                                    (request.body as FormDataContent).bytes().decodeToString(),
                                )["s_token"],
                            )
                            assertEquals("s_token=saved-token", request.headers[HttpHeaders.Cookie])
                            respond("""{"success":true,"message":"ok"}""")
                        },
                    ) {
                        install(HttpCookies) { this.storage = storage }
                    }
                try {
                    assertTrue(
                        RuliwebClient(client, CookieSessionRepository(storage))
                            .checkSession("saved-token", "d")
                            .success,
                    )
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun `new native login wins over previously saved token`() = runBlocking {
        val disk = MemoryPersistence()
        val storage = PersistentCookiesStorage(disk) { 1_000 }
        storage.addCookie(Url(BASE_URL), Cookie("s_token", "old", expires = GMTDate(10_000)))
        val native = NativeCookies()
        native.cookies.add(BASE_URL to WebViewCookie("s_token", "new", expiresDate = 10_000))
        val sync = WebViewCookieSync(storage)
        sync.restoreToken(native)
        assertEquals("new", native.cookies.single().second.value)
        sync.sync(SESSION_COOKIE_URLS.associateWith { native.getCookies(it) })
        assertEquals(
            "new",
            PersistentCookiesStorage(disk) { 1_000 }.get(Url(BASE_URL)).single().value,
        )
    }

    @Test
    fun `pending native write preserves saved token and retries then logout removes it`() =
        runBlocking {
            val disk = MemoryPersistence()
            val storage = PersistentCookiesStorage(disk) { 1_000 }
            storage.addCookie(Url(BASE_URL), Cookie("s_token", "saved", expires = GMTDate(10_000)))
            val native = NativeCookies()
            native.acceptWrites = false
            val sync = WebViewCookieSync(storage)
            assertFailsWith<IllegalStateException> { sync.restoreToken(native) }
            assertEquals(
                "saved",
                PersistentCookiesStorage(disk) { 1_000 }.get(Url(BASE_URL)).single().value,
            )
            native.acceptWrites = true
            sync.restoreToken(native)
            sync.sync(SESSION_COOKIE_URLS.associateWith { native.getCookies(it) })
            native.removeAllCookies()
            sync.sync(SESSION_COOKIE_URLS.associateWith { native.getCookies(it) })
            val reopened = PersistentCookiesStorage(disk) { 1_000 }
            assertTrue(reopened.get(Url(BASE_URL)).isEmpty())
            WebViewCookieSync(reopened).restoreToken(native)
            assertTrue(native.cookies.isEmpty())
        }

    @Test
    fun `expired token is not restored and server rejection remains logged out`() = runBlocking {
        val disk = MemoryPersistence()
        PersistentCookiesStorage(disk) { 1_000 }
            .addCookie(Url(BASE_URL), Cookie("s_token", "expired", expires = GMTDate(2_000)))
        val native = NativeCookies()
        WebViewCookieSync(PersistentCookiesStorage(disk) { 3_000 }).restoreToken(native)
        assertTrue(native.cookies.isEmpty())
        val client = HttpClient(MockEngine { respond("""{"success":false,"message":"expired"}""") })
        try {
            assertFalse(
                RuliwebClient(
                        client,
                        CookieSessionRepository(PersistentCookiesStorage(disk) { 3_000 }),
                    )
                    .checkSession("rejected", "d")
                    .success,
            )
        } finally {
            client.close()
        }
    }
}
