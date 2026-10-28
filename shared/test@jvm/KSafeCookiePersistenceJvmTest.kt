package alyac.kotlin.susweb

import alyac.kotlin.susweb.webview.*
import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.KSafeConfig
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.Url
import io.ktor.util.date.GMTDate
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

class KSafeCookiePersistenceJvmTest {
    @Test
    fun `encrypted token without expiry survives reopening and obsolete storage is deleted`() =
        runBlocking {
            val directory = Files.createTempDirectory("susweb-cookie-test-").toFile()
            val namespace = "alyac.kotlin.susweb.test.${UUID.randomUUID()}"
            fun open() =
                KSafe(
                    fileName = "cookies",
                    baseDir = directory,
                    config = KSafeConfig(appNamespace = namespace),
                )
            val obsolete = directory.resolve("cookies.preferences_pb")
            var vault = open()
            try {
                val tokens = KSafeCookiePersistence { vault }
                val persistence =
                    TokenCookiePersistence(tokens, tokens) {
                        Files.deleteIfExists(obsolete.toPath())
                    }
                val jar = PersistentCookiesStorage(persistence) { 1_000 }
                val secret = "cookie-secret-${UUID.randomUUID()}"
                jar.addCookie(
                    Url(API_URL),
                    Cookie("s_token", secret, CookieEncoding.RAW),
                )
                jar.addCookie(
                    Url(API_URL),
                    Cookie("theme", "discard-me", expires = GMTDate(10_000)),
                )
                assertEquals(2, jar.get(Url(API_URL)).size)
                assertFalse(tokens.read()!!.contains("theme"))
                val oldTheme = buildJsonObject {
                    put("name", "theme")
                    put("value", "discard-me")
                }
                assertFailsWith<IllegalArgumentException> {
                    tokens.write(CookiePayload.encode(listOf(oldTheme)))
                }
                // Simulate an installation which still has the old whole-cookie jar and DataStore.
                vault.put(
                    "http_cookies_v1",
                    CookiePayload.encode(CookiePayload.cookies(tokens.read()) + oldTheme),
                )
                obsolete.writeText("old non-auth cookies")
                vault.close()
                vault = open()
                val restoredTokens = KSafeCookiePersistence { vault }
                val restored =
                    PersistentCookiesStorage(
                        TokenCookiePersistence(restoredTokens, restoredTokens) {
                            Files.deleteIfExists(obsolete.toPath())
                        },
                    ) {
                        2_000
                    }
                assertEquals(
                    mapOf("s_token" to secret),
                    restored.get(Url(API_URL)).associate { it.name to it.value },
                )
                assertNull(restored.get(Url(API_URL)).single().expires)
                assertFalse(obsolete.exists())
                assertNull(vault.get<String?>("http_cookies_v1", null))
                val files = directory.walkTopDown().filter { it.isFile }.toList()
                assertTrue(files.any { it.length() > 0 })
                for (file in files) assertFalse(file.readBytes().decodeToString().contains(secret))
                restored.addCookie(
                    Url(API_URL),
                    Cookie("theme", "new-theme", expires = GMTDate(10_000)),
                )
                assertFalse(obsolete.exists())
                assertFalse(restoredTokens.read()!!.contains("theme"))
                vault.delete("http_session_token_v1")
            } finally {
                vault.close()
                directory.deleteRecursively()
            }
        }
}
