package alyac.kotlin.susweb

import alyac.kotlin.susweb.session.*
import alyac.kotlin.susweb.webview.*
import dev.nucleusframework.webview.cookie.Cookie as WebViewCookie
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.util.date.GMTDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class WebViewCookieSyncTest {
    @Test
    fun `webview cookies reach HTTP requests and logout removes them`() = runBlocking {
        val storage = AcceptAllCookiesStorage()
        val sync = WebViewCookieSync(storage)
        var expected: String? = "s_token=raw%2B+token; theme=dark"
        val client =
            HttpClient(
                MockEngine { request ->
                    assertEquals(expected, request.headers[HttpHeaders.Cookie])
                    respond("ok")
                },
            ) {
                install(HttpCookies) { this.storage = storage }
            }
        try {
            val token =
                WebViewCookie("s_token", "raw%2B+token", domain = ".ruliweb.com", path = "/")
            val theme = WebViewCookie("theme", "dark", domain = ".ruliweb.com", path = "/")
            sync.sync(mapOf(BASE_URL to listOf(token, theme), API_URL to listOf(token, theme)))
            client.get(PUSH_URL)
            sync.sync(mapOf(BASE_URL to listOf(token.copy(value = "new")), API_URL to emptyList()))
            expected = "s_token=new"
            client.get(PUSH_URL)
            sync.sync(mapOf(BASE_URL to emptyList(), API_URL to emptyList()))
            expected = null
            client.get(PUSH_URL)
            Unit
        } finally {
            client.close()
        }
    }

    @Test
    fun `conversion preserves metadata and raw values`() {
        val converted =
            WebViewCookie(
                    name = "session",
                    value = "a%2Fb+c=",
                    domain = ".ruliweb.com",
                    path = "/member",
                    expiresDate = 1_900_000_000_000,
                    maxAge = 120,
                    isSecure = true,
                    isHttpOnly = true,
                )
                .toKtorCookie(Url(USER_URL))
        assertEquals("a%2Fb+c=", converted.value)
        assertEquals(".ruliweb.com", converted.domain)
        assertEquals("/member", converted.path)
        assertEquals(GMTDate(1_900_000_000_000), converted.expires)
        assertEquals(120, converted.maxAge)
        assertTrue(converted.secure)
        assertTrue(converted.httpOnly)

        val session =
            WebViewCookie(
                    "session",
                    "value",
                    isSessionOnly = true,
                    expiresDate = 0,
                    maxAge = 0,
                )
                .toKtorCookie(Url(USER_URL))
        assertEquals("user.ruliweb.com", session.domain)
        assertEquals("/", session.path)
        assertNull(session.expires)
        assertNull(session.maxAge)
    }

    @Test
    fun `storage respects domain path security and expiration`() = runBlocking {
        val storage = AcceptAllCookiesStorage { 1_000 }
        val sync = WebViewCookieSync(storage)
        sync.sync(
            mapOf(
                USER_URL to
                    listOf(
                        WebViewCookie(
                            "private",
                            "value",
                            domain = "user.ruliweb.com",
                            path = "/member",
                            isSecure = true,
                        ),
                        WebViewCookie("expired", "value", expiresDate = 500),
                    ),
            ),
        )
        assertEquals(listOf("private"), storage.get(Url("$USER_URL/member/mypage")).map { it.name })
        assertTrue(storage.get(Url(USER_URL)).isEmpty())
        assertTrue(storage.get(Url("http://user.ruliweb.com/member/mypage")).isEmpty())
        assertTrue(storage.get(Url("$API_URL/member/mypage")).isEmpty())
        assertTrue(storage.get(Url("https://other.example/member/mypage")).isEmpty())
        storage.close()
    }

    @Test
    fun `unchanged max age is not extended by polling`() = runBlocking {
        var now = 1_000L
        val storage = AcceptAllCookiesStorage { now }
        val sync = WebViewCookieSync(storage)
        val snapshot = mapOf(BASE_URL to listOf(WebViewCookie("short", "value", maxAge = 2)))
        sync.sync(snapshot)
        now = 2_000
        sync.sync(snapshot)
        assertFalse(storage.get(Url(BASE_URL)).isEmpty())
        now = 3_001
        assertTrue(storage.get(Url(BASE_URL)).isEmpty())
        storage.close()
    }
}
