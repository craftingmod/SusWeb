package alyac.kotlin.susweb.ruliweb

import alyac.kotlin.susweb.BASE_URL
import alyac.kotlin.susweb.PUSH_URL
import alyac.kotlin.susweb.USER_AGENT
import alyac.kotlin.susweb.session.MY_PAGE_URL
import alyac.kotlin.susweb.session.SessionRepository
import androidx.compose.runtime.staticCompositionLocalOf
import com.fleeksoft.ksoup.Ksoup
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.cookies.cookies
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.parameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Shares the app's session and cookie jar with all Ruliweb requests. */
internal class RuliwebClient(
    internal val httpClient: HttpClient,
    private val session: SessionRepository,
) {
    /** Reads the current token, including one restored from encrypted storage. */
    suspend fun getToken(): String? = session.getToken()

    internal suspend fun checkSession(token: String, clientMode: String): SessionResponse {
        require(token.isNotBlank())
        require(clientMode == "d" || clientMode == "m")
        val response =
            httpClient.submitForm(
                url = PUSH_URL,
                formParameters =
                    parameters {
                        append("push_id", "")
                        append("s_token", token)
                        append("client_mode", clientMode)
                    },
            ) {
                headers {
                    append("Referer", "${BASE_URL}/")
                    append(HttpHeaders.UserAgent, USER_AGENT)
                }
            }

        check(response.status.value in 200..299) { "Session check HTTP ${response.status.value}" }
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val result =
            SessionResponse(
                success = body.getValue("success").jsonPrimitive.boolean,
                message = body.getValue("message").jsonPrimitive.content,
                unreadNotify =
                    body["unread_notify"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.int,
            )
        println("[SusWeb][Session] getPush success=${result.success} message=${result.message}")
        return result
    }

    /** Fetch profile details only after the server accepts the current token. */
    internal suspend fun getAuthenticatedProfile(clientMode: String): RuliProfile? {
        val token = getToken()?.takeIf { it.isNotBlank() } ?: return null
        if (!checkSession(token, clientMode).success) return null
        return getProfile()
    }

    suspend fun getProfile(): RuliProfile {
        val token = getToken()
        require(!token.isNullOrBlank()) { "Token is blank" }
        // HttpCookies already sends matching stored tokens. Adding the same cookie manually
        // would import it again without its original domain, expiry, and security metadata.
        val hasTokenCookie =
            httpClient.cookies(MY_PAGE_URL).any {
                it.name == "s_token" && it.value == token
            }
        val response =
            httpClient.get(
                urlString = MY_PAGE_URL,
            ) {
                headers {
                    if (!hasTokenCookie) append(HttpHeaders.Cookie, "s_token=$token")
                    append(HttpHeaders.UserAgent, USER_AGENT)
                }
            }

        check(response.status.value in 200..299) { "Session check HTTP ${response.status.value}" }

        val htmlBody = Ksoup.parse(response.bodyAsText())

        val result =
            RuliProfile(
                nickname = htmlBody.selectFirst(profileNickSel)?.text() ?: "",
                memberId = htmlBody.selectFirst(profileMemberIdSel)?.text()?.toIntOrNull() ?: -1,
                profileImage = htmlBody.selectFirst(profileImageSel)?.attr("src") ?: "",
            )

        return result
    }
}

internal val LocalRuliwebClient =
    staticCompositionLocalOf<RuliwebClient> {
        error("RuliwebClient has not been provided")
    }

internal fun createRuliwebHttpClient(cookieStorage: CookiesStorage) = HttpClient {
    // A redirect must not forward a session form to another endpoint.
    followRedirects = false
    install(HttpCookies) { storage = cookieStorage }
    install(HttpTimeout) {
        requestTimeoutMillis = 15_000
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 15_000
    }
}
