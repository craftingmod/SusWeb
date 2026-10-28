package alyac.kotlin.susweb

import alyac.kotlin.susweb.ruliweb.RuliwebClient
import alyac.kotlin.susweb.session.MY_PAGE_URL
import alyac.kotlin.susweb.session.SessionRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.util.date.GMTDate
import kotlin.test.*
import kotlinx.coroutines.runBlocking

class RuliwebProfileTest {
    private class Session(var token: String?) : SessionRepository {
        override suspend fun getToken() = token

        override suspend fun saveToken(token: String, expiresAtMillis: Long?) {
            this.token = token
        }

        override suspend fun clearToken() {
            token = null
        }
    }

    @Test
    fun `authenticated profile checks session before fetching nickname`() = runBlocking {
        val requests = mutableListOf<String>()
        val client =
            HttpClient(
                MockEngine { request ->
                    requests.add(request.url.toString())
                    when (request.url.toString()) {
                        PUSH_URL -> respond("""{"success":true,"message":"ok"}""")
                        MY_PAGE_URL ->
                            respond(
                                """
                                <div id="mypage"><div class="profile"><div class="profile_info">
                                  <div class="nick_name"><span class="info_value">nickname</span></div>
                                </div></div></div>
                                """
                                    .trimIndent(),
                            )
                        else -> error("Unexpected URL")
                    }
                },
            )
        try {
            assertEquals(
                "nickname",
                RuliwebClient(client, Session("token")).getAuthenticatedProfile("d")?.nickname,
            )
            assertEquals(listOf(PUSH_URL, MY_PAGE_URL), requests)
        } finally {
            client.close()
        }
    }

    @Test
    fun `missing token or rejected session never fetches profile`() = runBlocking {
        for (token in listOf(null, "", " ", "rejected")) {
            var requests = 0
            val client =
                HttpClient(
                    MockEngine { request ->
                        requests++
                        assertEquals(PUSH_URL, request.url.toString())
                        respond("""{"success":false,"message":"expired"}""")
                    },
                )
            try {
                assertNull(RuliwebClient(client, Session(token)).getAuthenticatedProfile("d"))
                assertEquals(if (token == "rejected") 1 else 0, requests)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `session network failure does not fetch profile or become logged out`() = runBlocking<Unit> {
        val client =
            HttpClient(
                MockEngine { request ->
                    assertEquals(PUSH_URL, request.url.toString())
                    error("Network failure")
                },
            )
        try {
            assertFailsWith<IllegalStateException> {
                RuliwebClient(client, Session("token")).getAuthenticatedProfile("d")
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `profile sends raw token once with or without matching cookie storage`() = runBlocking {
        for (mode in listOf("no plugin", "empty storage", "stored token")) {
            val token = "raw%2B+token"
            val storage = AcceptAllCookiesStorage()
            val saved =
                Cookie(
                    "s_token",
                    token,
                    CookieEncoding.RAW,
                    domain = "ruliweb.com",
                    path = "/",
                    expires = GMTDate(4_000_000_000_000),
                    secure = true,
                    httpOnly = true,
                )
            if (mode == "stored token") storage.addCookie(Url(MY_PAGE_URL), saved)
            val client =
                HttpClient(
                    MockEngine { request ->
                        assertEquals(MY_PAGE_URL, request.url.toString())
                        assertEquals(HttpMethod.Get, request.method)
                        assertEquals("s_token=$token", request.headers[HttpHeaders.Cookie])
                        assertEquals(USER_AGENT, request.headers[HttpHeaders.UserAgent])
                        respond(
                            """
                            <div id="mypage"><div class="profile">
                              <div class="profile_info">
                                <div class="nick_name"><span class="info_value">tester</span></div>
                                <div class="member_srl"><span class="info_value">123</span></div>
                              </div><img class="profile_img" src="https://example.com/avatar.png">
                            </div></div>
                            """
                                .trimIndent(),
                        )
                    },
                ) {
                    if (mode != "no plugin") install(HttpCookies) { this.storage = storage }
                }
            try {
                val profile = RuliwebClient(client, Session(token)).getProfile()
                assertEquals("tester", profile.nickname)
                assertEquals(123, profile.memberId)
                assertEquals("https://example.com/avatar.png", profile.profileImage)
                if (mode == "stored token")
                    assertEquals(saved, storage.get(Url(MY_PAGE_URL)).single())
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `missing or blank token prevents profile request`() = runBlocking {
        val client = HttpClient(MockEngine { error("Profile request must not be sent") })
        try {
            for (token in listOf(null, "", " ")) {
                assertFailsWith<IllegalArgumentException> {
                    RuliwebClient(client, Session(token)).getProfile()
                }
            }
        } finally {
            client.close()
        }
    }
}
