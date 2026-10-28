package alyac.kotlin.susweb

import alyac.kotlin.susweb.session.*
import alyac.kotlin.susweb.webview.*
import io.ktor.http.Cookie
import io.ktor.http.Url
import io.ktor.util.date.GMTDate
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class TokenCookiePersistenceTest {
    private class Fake : CookiePersistence, LegacyCookiePersistence {
        var value: String? = null
        var old: String? = null
        var failWrite = false
        var writes = 0

        override suspend fun read() = value

        override suspend fun write(value: String) {
            check(!failWrite)
            this.value = value
            writes++
        }

        override suspend fun readLegacy() = old

        override suspend fun deleteLegacy() {
            old = null
        }
    }

    @Test
    fun `only token persists and ordinary cookie changes do not rewrite it`() = runBlocking {
        val tokens = Fake()
        val jar = PersistentCookiesStorage(TokenCookiePersistence(tokens)) { 1_000 }
        jar.addCookie(Url(API_URL), Cookie("theme", "dark", expires = GMTDate(10_000)))
        assertNull(tokens.value)
        assertEquals(0, tokens.writes)
        jar.addCookie(Url(API_URL), Cookie("s_token", "secret", expires = GMTDate(10_000)))
        jar.addCookie(Url(API_URL), Cookie("theme", "dark", expires = GMTDate(10_000)))
        assertEquals(2, jar.get(Url(API_URL)).size)
        assertEquals(listOf("s_token"), names(tokens.value))
        assertFalse(tokens.value!!.contains("dark"))
        val writes = tokens.writes
        jar.addCookie(Url(API_URL), Cookie("theme", "light", expires = GMTDate(10_000)))
        assertEquals(writes, tokens.writes)
        val restored = PersistentCookiesStorage(TokenCookiePersistence(tokens)) { 2_000 }
        assertEquals(
            mapOf("s_token" to "secret"),
            restored.get(Url(API_URL)).associate { it.name to it.value },
        )
        restored.addCookie(Url(API_URL), Cookie("s_token", "", maxAge = 0))
        assertEquals(emptyList(), names(tokens.value))
    }

    @Test
    fun `legacy jar keeps only token and failed migration remains retryable`() = runBlocking {
        val tokens = Fake()
        val oldJar = PersistentCookiesStorage(tokens) { 1_000 }
        oldJar.addCookie(Url(API_URL), Cookie("s_token", "secret", expires = GMTDate(10_000)))
        oldJar.addCookie(Url(API_URL), Cookie("theme", "dark", expires = GMTDate(10_000)))
        tokens.old = tokens.value
        tokens.value = null
        tokens.failWrite = true
        var cleaned = false
        val persistence = TokenCookiePersistence(tokens, tokens) { cleaned = true }
        assertFailsWith<IllegalStateException> { persistence.read() }
        assertTrue(cleaned)
        assertNotNull(tokens.old)
        tokens.failWrite = false
        assertEquals(listOf("s_token"), names(persistence.read()))
        assertNull(tokens.old)
        assertFalse(tokens.value!!.contains("theme"))
    }

    @Test
    fun `empty destination wins over legacy token and cleanup failures are retried`() =
        runBlocking {
            val tokens = Fake()
            val oldJar = PersistentCookiesStorage(tokens) { 1_000 }
            oldJar.addCookie(Url(API_URL), Cookie("s_token", "stale", expires = GMTDate(10_000)))
            tokens.old = tokens.value
            tokens.value = CookiePayload.encode(emptyList())
            var failCleanup = true
            val persistence = TokenCookiePersistence(tokens, tokens) { check(!failCleanup) }
            assertFailsWith<IllegalStateException> { persistence.read() }
            assertNotNull(tokens.old)
            failCleanup = false
            assertEquals(emptyList(), names(persistence.read()))
            assertNull(tokens.old)
        }

    @Test
    fun `expired token is removed and session token survives restart until logout`() = runBlocking {
        val tokens = Fake()
        var now = 1_000L
        val jar = PersistentCookiesStorage(TokenCookiePersistence(tokens)) { now }
        jar.addCookie(Url(API_URL), Cookie("s_token", "secret", expires = GMTDate(2_000)))
        now = 3_000
        assertTrue(jar.get(Url(API_URL)).isEmpty())
        assertEquals(emptyList(), names(tokens.value))
        jar.addCookie(Url(API_URL), Cookie("s_token", "session"))
        assertEquals("session", jar.get(Url(API_URL)).single().value)
        val reopened = PersistentCookiesStorage(TokenCookiePersistence(tokens)) { now }
        assertEquals("session", reopened.get(Url(API_URL)).single().value)
        assertNull(reopened.get(Url(API_URL)).single().expires)
        reopened.addCookie(Url(API_URL), Cookie("s_token", "", maxAge = 0))
        assertTrue(
            PersistentCookiesStorage(TokenCookiePersistence(tokens)) { now }
                .get(Url(API_URL))
                .isEmpty(),
        )
    }

    private fun names(value: String?) =
        CookiePayload.cookies(value).map {
            it.jsonObject.getValue("name").jsonPrimitive.content
        }
}
