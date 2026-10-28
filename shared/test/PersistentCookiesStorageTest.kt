package alyac.kotlin.susweb

import alyac.kotlin.susweb.session.*
import alyac.kotlin.susweb.webview.*
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.Url
import io.ktor.util.date.GMTDate
import kotlin.test.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

class PersistentCookiesStorageTest {
    private class FakePersistence : CookiePersistence {
        var value: String? = null
        var failRead = false
        var failWrite = false

        override suspend fun read(): String? {
            check(!failRead)
            return value
        }

        override suspend fun write(value: String) {
            check(!failWrite)
            this.value = value
        }
    }

    @Test
    fun `persistent cookies restore with metadata while session cookies stay in memory`() =
        runBlocking {
            val disk = FakePersistence()
            val jar = PersistentCookiesStorage(disk) { 1_000 }
            val cookie =
                Cookie(
                    "auth",
                    "a%2Fb+c=",
                    CookieEncoding.RAW,
                    expires = GMTDate(10_000),
                    domain = ".ruliweb.com",
                    path = "/",
                    secure = true,
                    httpOnly = true,
                    extensions = mapOf("SameSite" to "Lax"),
                )
            jar.addCookie(Url(API_URL), cookie)
            jar.addCookie(Url(API_URL), Cookie("temporary", "secret", CookieEncoding.RAW))
            assertEquals(2, jar.get(Url(API_URL)).size)
            assertFalse(disk.value!!.contains("temporary"))
            assertFalse(disk.value!!.contains("secret"))
            val restored = PersistentCookiesStorage(disk) { 2_000 }
            assertEquals(listOf(cookie.copy(domain = "ruliweb.com")), restored.get(Url(API_URL)))
            assertTrue(restored.get(Url("http://api.ruliweb.com")).isEmpty())
            assertTrue(restored.get(Url("https://unrelated.example")).isEmpty())
        }

    @Test
    fun `max age uses original deadline across restart and expired cookies are removed`() =
        runBlocking {
            val disk = FakePersistence()
            val jar = PersistentCookiesStorage(disk) { 1_000 }
            jar.addCookie(
                Url(API_URL),
                Cookie("short", "value", maxAge = 2, expires = GMTDate(99_000)),
            )
            val restored = PersistentCookiesStorage(disk) { 2_000 }
            assertEquals(3_000, restored.get(Url(API_URL)).single().expires!!.timestamp)
            assertTrue(PersistentCookiesStorage(disk) { 3_000 }.get(Url(API_URL)).isEmpty())
            assertTrue(PersistentCookiesStorage(disk) { 1_000 }.get(Url(API_URL)).isEmpty())
        }

    @Test
    fun `replacement and deletion use name domain and path`() = runBlocking {
        val disk = FakePersistence()
        val jar = PersistentCookiesStorage(disk) { 1_000 }
        val url = Url("$API_URL/member/page")
        jar.addCookie(url, Cookie("id", "root", path = "/", expires = GMTDate(10_000)))
        jar.addCookie(url, Cookie("id", "member", path = "/member", expires = GMTDate(10_000)))
        jar.addCookie(url, Cookie("id", "updated", path = "/member", expires = GMTDate(10_000)))
        assertEquals(listOf("updated", "root"), jar.get(url).map { it.value })
        assertTrue(jar.get(Url("https://child.api.ruliweb.com/member")).isEmpty())
        jar.addCookie(url, Cookie("id", "", path = "/member", maxAge = 0))
        assertEquals(
            listOf("root"),
            PersistentCookiesStorage(disk) { 1_000 }.get(url).map { it.value },
        )
        jar.addCookie(
            url,
            Cookie("foreign", "value", domain = "other.example", expires = GMTDate(10_000)),
        )
        assertEquals(listOf("root"), jar.get(url).map { it.value })
    }

    @Test
    fun `failed reads and writes preserve durable state and can be retried`() = runBlocking {
        val disk = FakePersistence()
        val jar = PersistentCookiesStorage(disk) { 1_000 }
        val cookie = Cookie("auth", "old", expires = GMTDate(10_000))
        jar.addCookie(Url(API_URL), cookie)
        val saved = disk.value
        disk.failWrite = true
        assertFailsWith<IllegalStateException> {
            jar.addCookie(Url(API_URL), cookie.copy(value = "new"))
        }
        assertEquals(saved, disk.value)
        assertEquals("old", jar.get(Url(API_URL)).single().value)
        disk.failRead = true
        val reopened = PersistentCookiesStorage(disk) { 1_000 }
        assertFailsWith<IllegalStateException> { reopened.get(Url(API_URL)) }
        disk.failRead = false
        assertEquals("old", reopened.get(Url(API_URL)).single().value)
        disk.failWrite = false
        jar.addCookie(Url(API_URL), cookie.copy(value = "new"))
        assertEquals(
            "new",
            PersistentCookiesStorage(disk) { 1_000 }.get(Url(API_URL)).single().value,
        )
    }

    @Test
    fun `concurrent writes do not lose cookies and unknown versions are preserved`() = runBlocking {
        val disk = FakePersistence()
        val jar = PersistentCookiesStorage(disk) { 1_000 }
        (1..20)
            .map {
                async {
                    jar.addCookie(
                        Url(API_URL),
                        Cookie("cookie$it", "value", expires = GMTDate(10_000)),
                    )
                }
            }
            .awaitAll()
        assertEquals(20, PersistentCookiesStorage(disk) { 1_000 }.get(Url(API_URL)).size)
        disk.value = """{"version":2,"cookies":[]}"""
        assertFailsWith<IllegalStateException> { PersistentCookiesStorage(disk).get(Url(API_URL)) }
        assertEquals("""{"version":2,"cookies":[]}""", disk.value)
    }

    @Test
    fun `first webview snapshot deletes restored cookies absent from native store`() = runBlocking {
        val disk = FakePersistence()
        PersistentCookiesStorage(disk) { 1_000 }
            .addCookie(
                Url(API_URL),
                Cookie("auth", "old", expires = GMTDate(10_000)),
            )
        val jar = PersistentCookiesStorage(disk) { 1_000 }
        WebViewCookieSync(jar).sync(mapOf(API_URL to emptyList()))
        assertTrue(PersistentCookiesStorage(disk) { 1_000 }.get(Url(API_URL)).isEmpty())
    }
}
