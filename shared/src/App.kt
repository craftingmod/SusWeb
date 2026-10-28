package alyac.kotlin.susweb

import alyac.kotlin.susweb.ruliweb.LocalRuliwebClient
import alyac.kotlin.susweb.ruliweb.RuliProfile
import alyac.kotlin.susweb.ruliweb.RuliwebClient
import alyac.kotlin.susweb.ruliweb.SessionResponse
import alyac.kotlin.susweb.ruliweb.createRuliwebHttpClient
import alyac.kotlin.susweb.session.CookieSessionRepository
import alyac.kotlin.susweb.session.LOGIN_URL
import alyac.kotlin.susweb.session.LocalSessionRepository
import alyac.kotlin.susweb.session.MY_PAGE_URL
import alyac.kotlin.susweb.session.ruliwebClientMode
import alyac.kotlin.susweb.webview.PersistentCookiesStorage
import alyac.kotlin.susweb.webview.rememberCookiePersistence
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nucleusframework.webview.web.WebView
import dev.nucleusframework.webview.web.rememberWebViewNavigator
import dev.nucleusframework.webview.web.rememberWebViewState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import org.jetbrains.compose.reload.DevelopmentEntryPoint
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

@Composable
fun SusWebTheme(content: @Composable () -> Unit) {
    MiuixTheme(
        colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}

@Composable
@DevelopmentEntryPoint
fun App() {
    // App lifetime: closing the profile must not discard cookies imported for API requests.
    val persistence = rememberCookiePersistence()
    val repository =
        remember(persistence) { CookieSessionRepository(PersistentCookiesStorage(persistence)) }
    val cookieStorage = repository.cookieStorage
    val httpClient = remember(cookieStorage) { createRuliwebHttpClient(cookieStorage) }
    val ruliwebClient = remember(httpClient, repository) { RuliwebClient(httpClient, repository) }
    DisposableEffect(httpClient) { onDispose { httpClient.close() } }
    CompositionLocalProvider(
        LocalSessionRepository provides repository,
        LocalRuliwebClient provides ruliwebClient,
    ) {
        SusWebTheme {
            var showProfile by remember { mutableStateOf(false) }
            var profile by remember(ruliwebClient) { mutableStateOf<RuliProfile?>(null) }
            var checkingProfile by remember { mutableStateOf(true) }
            var profileError by remember { mutableStateOf(false) }
            // Recheck on startup and when returning from the login/profile WebView.
            LaunchedEffect(ruliwebClient, showProfile) {
                if (showProfile) return@LaunchedEffect
                checkingProfile = true
                profileError = false
                profile = null
                try {
                    profile = ruliwebClient.getAuthenticatedProfile(ruliwebClientMode)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    profileError = true
                } finally {
                    checkingProfile = false
                }
            }
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().safeContentPadding()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("SusWeb", modifier = Modifier.weight(1f))
                        Button(onClick = { showProfile = !showProfile }) {
                            Text(
                                when {
                                    showProfile -> "닫기"
                                    checkingProfile -> "로그인 확인 중…"
                                    profileError -> "로그인 확인"
                                    profile != null -> profile!!.nickname.ifBlank { "내 계정" }
                                    else -> "로그인"
                                },
                            )
                        }
                    }
                    if (!showProfile && profileError) {
                        Text(
                            "로그인 정보를 불러오지 못했습니다. 연결을 확인하고 로그인 확인을 눌러 주세요.",
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                    if (showProfile) {
                        ProfileWebView(
                            ruliwebClient,
                            repository,
                            Modifier.fillMaxWidth().weight(1f),
                        )
                    } else {
                        Box(
                            Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Hello World")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileWebView(
    ruliwebClient: RuliwebClient,
    repository: CookieSessionRepository,
    modifier: Modifier = Modifier,
) {
    // Desktop's cookie store is available only after the native view attaches.
    val state = rememberWebViewState("about:blank")
    val navigator = rememberWebViewNavigator()
    var session by remember { mutableStateOf<SessionResponse?>(null) }
    var checking by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf(0) }
    var loginShown by remember { mutableStateOf(false) }
    var tokenRestored by remember { mutableStateOf(false) }

    LaunchedEffect(retry) {
        checking = true
        error = null
        // about:blank can remain Initializing on Desktop; cookies only need attachment.
        snapshotFlow { state.webView != null }.first { it }
        var attempted = false
        var lastToken: String? = null
        while (isActive) {
            try {
                if (!tokenRestored) {
                    repository.restoreProfile(state.cookieManager)
                    tokenRestored = true
                }
                // Native cookie API includes HttpOnly cookies. Never log tokens.
                val token = repository.syncProfile(state.cookieManager)
                // Catch redirects/AJAX logins; call the server only on token changes or retry.
                if (!attempted || token != lastToken) {
                    attempted = true
                    lastToken = token
                    checking = true
                    error = null
                    if (token == null) {
                        session = null
                        if (!loginShown) {
                            loginShown = true
                            navigator.loadUrl(LOGIN_URL)
                        }
                    } else {
                        val result = ruliwebClient.checkSession(token, ruliwebClientMode)
                        val wasLoggedIn = session?.success == true
                        session = result
                        if (result.success) {
                            loginShown = false
                            if (!wasLoggedIn && state.lastLoadedUrl != MY_PAGE_URL) {
                                navigator.loadUrl(MY_PAGE_URL)
                            }
                        } else if (!loginShown) {
                            loginShown = true
                            navigator.loadUrl(LOGIN_URL)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A network/JSON failure does not establish that the user is logged out.
                error = "로그인 상태를 확인하지 못했습니다. 연결을 확인하고 다시 시도하세요."
            } finally {
                checking = false
            }
            delay(1_500)
        }
    }

    Column(modifier) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = navigator::navigateBack, enabled = navigator.canGoBack) { Text("뒤로") }
            Button(onClick = navigator::reload) { Text("새로고침") }
            Button(onClick = { retry++ }, enabled = !checking) { Text("로그인 확인") }
        }
        Text(
            text =
                when {
                    checking -> "로그인 상태 확인 중…"
                    error != null -> error!!
                    session?.success == true ->
                        session?.unreadNotify?.let { "로그인됨 · 읽지 않은 알림 $it" } ?: "로그인됨"
                    else -> "로그인은 선택 사항입니다. 루리웹 계정으로 로그인해 주세요."
                },
            modifier = Modifier.padding(12.dp),
        )
        if (state.isLoading || checking) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (state.errorsForCurrentRequest.any { it.isFromMainFrame }) {
            Text("페이지를 불러오지 못했습니다. 새로고침을 눌러 주세요.", modifier = Modifier.padding(12.dp))
        }
        WebView(
            state = state,
            navigator = navigator,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}
