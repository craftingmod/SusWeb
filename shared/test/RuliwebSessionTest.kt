package alyac.kotlin.susweb

import alyac.kotlin.susweb.ruliweb.RuliwebClient
import alyac.kotlin.susweb.session.SessionRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class RuliwebSessionTest {
    private val session =
        object : SessionRepository {
            override suspend fun getToken(): String? = null

            override suspend fun saveToken(token: String, expiresAtMillis: Long?) = Unit

            override suspend fun clearToken() = Unit
        }

    @Test
    fun `session check sends encoded form and parses success`() = runBlocking {
        val client =
            HttpClient(
                MockEngine { request ->
                    assertEquals(PUSH_URL, request.url.toString())
                    assertEquals(HttpMethod.Post, request.method)
                    val body = request.body as FormDataContent
                    assertEquals(
                        "application/x-www-form-urlencoded",
                        body.contentType.withoutParameters().toString(),
                    )
                    val form = parseQueryString(body.bytes().decodeToString())
                    assertEquals("", form["push_id"])
                    assertEquals("token+with&reserved=한글", form["s_token"])
                    assertEquals("m", form["client_mode"])
                    respond(
                        """{"success":true,"message":"ok","unread_notify":3,"extra":1}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            )
        try {
            val result = RuliwebClient(client, session).checkSession("token+with&reserved=한글", "m")
            assertTrue(result.success)
            assertEquals(3, result.unreadNotify)
            assertEquals("ok", result.message)
        } finally {
            client.close()
        }
    }

    @Test
    fun `expired session may omit notification count`() = runBlocking {
        val client =
            HttpClient(
                MockEngine { request ->
                    assertEquals(
                        "d",
                        parseQueryString(
                            (request.body as FormDataContent).bytes().decodeToString(),
                        )["client_mode"],
                    )
                    respond("""{"success":false,"message":"expired"}""")
                },
            )
        try {
            val result = RuliwebClient(client, session).checkSession("expired", "d")
            assertFalse(result.success)
            assertNull(result.unreadNotify)
        } finally {
            client.close()
        }
    }

    @Test
    fun `http and malformed responses do not become logged out results`() = runBlocking {
        for ((body, status) in
            listOf(
                "unavailable" to HttpStatusCode.ServiceUnavailable,
                "<html>not JSON</html>" to HttpStatusCode.OK,
                """{"message":"missing success"}""" to HttpStatusCode.OK,
            )) {
            val client = HttpClient(MockEngine { respond(body, status) })
            try {
                assertFailsWith<Exception> {
                    RuliwebClient(client, session).checkSession("token", "d")
                }
            } finally {
                client.close()
            }
        }
    }
}
