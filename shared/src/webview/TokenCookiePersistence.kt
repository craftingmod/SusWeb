package alyac.kotlin.susweb.webview

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Only s_token records can cross the persistence boundary. Other cookies stay in memory. */
internal class TokenCookiePersistence(
    private val tokens: CookiePersistence,
    private val legacy: LegacyCookiePersistence? = null,
    private val cleanupLegacyCookies: suspend () -> Unit = {},
) : CookiePersistence {
    private val mutex = Mutex()
    private var migrated = false

    override suspend fun read(): String = mutex.withLock {
        migrate()
        val secret = CookiePayload.cookies(tokens.read()).filter(CookiePayload::isToken)
        CookiePayload.encode(secret)
    }

    override suspend fun write(value: String) = mutex.withLock {
        migrate()
        val all = CookiePayload.cookies(value)
        val records = all.filter(CookiePayload::isToken)
        val secure = CookiePayload.encode(records)
        val current = tokens.read()
        if (current != secure && (current != null || records.isNotEmpty())) tokens.write(secure)
    }

    private suspend fun migrate() {
        if (migrated) return
        // Delete the previous DataStore file without opening another DataStore instance.
        // Failures remain retryable; no new non-token data is written anywhere.
        cleanupLegacyCookies()
        val old = legacy?.readLegacy()
        if (old != null) {
            val all = CookiePayload.cookies(old)
            // An existing destination, including an empty jar, wins over legacy credentials.
            if (tokens.read() == null)
                tokens.write(CookiePayload.encode(all.filter(CookiePayload::isToken)))
            // Discard non-token cookies; retire the legacy jar after the token commit succeeds.
            legacy.deleteLegacy()
        }
        migrated = true
    }
}

internal object CookiePayload {
    fun cookies(value: String?): List<JsonElement> {
        if (value == null) return emptyList()
        val root = Json.parseToJsonElement(value).jsonObject
        check(root.getValue("version").jsonPrimitive.int == 1) {
            "Unsupported cookie storage version"
        }
        return root.getValue("cookies").jsonArray.toList()
    }

    fun isToken(value: JsonElement): Boolean =
        value.jsonObject.getValue("name").jsonPrimitive.content == "s_token"

    fun encode(cookies: List<JsonElement>): String = buildJsonObject {
        put("version", 1)
        put("cookies", JsonArray(cookies))
    }
        .toString()
}
