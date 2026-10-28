package alyac.kotlin.susweb.webview

import androidx.compose.runtime.Composable
import eu.anifantakis.lib.ksafe.KSafe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The cookie jar owns its format; this boundary lets common tests run without an OS vault. */
internal interface CookiePersistence {
    suspend fun read(): String?

    suspend fun write(value: String)
}

internal interface LegacyCookiePersistence {
    suspend fun readLegacy(): String?

    suspend fun deleteLegacy()
}

internal class KSafeCookiePersistence(private val factory: () -> KSafe) :
    CookiePersistence, LegacyCookiePersistence {
    private val ksafe by lazy(factory)

    override suspend fun read(): String? =
        withContext(Dispatchers.IO) {
            check(ksafe.protectionInfo.isEncryptionOperational) {
                "Cookie encryption is unavailable"
            }
            ksafe.get<String?>(KEY, null)
        }

    override suspend fun write(value: String) =
        withContext(Dispatchers.IO) {
            require(CookiePayload.cookies(value).all { CookiePayload.isToken(it) })
            check(ksafe.protectionInfo.isEncryptionOperational) {
                "Cookie encryption is unavailable"
            }
            // CookiesStorage.addCookie is suspend: await the encrypted disk commit before
            // returning.
            ksafe.put(KEY, value)
        }

    override suspend fun readLegacy(): String? =
        withContext(Dispatchers.IO) {
            check(ksafe.protectionInfo.isEncryptionOperational) {
                "Cookie encryption is unavailable"
            }
            ksafe.get<String?>(LEGACY_KEY, null)
        }

    override suspend fun deleteLegacy() = withContext(Dispatchers.IO) { ksafe.delete(LEGACY_KEY) }

    private companion object {
        const val KEY = "http_session_token_v1"
        const val LEGACY_KEY = "http_cookies_v1"
    }
}

@Composable internal expect fun rememberCookiePersistence(): CookiePersistence
