package alyac.kotlin.susweb

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nucleusframework.webview.web.WebView
import dev.nucleusframework.webview.web.rememberWebViewNavigator
import dev.nucleusframework.webview.web.rememberWebViewState
import org.jetbrains.compose.reload.DevelopmentEntryPoint

private const val HOME_URL = "https://nucleusframework.dev"

@Composable
fun SusWebTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}

@Composable
@DevelopmentEntryPoint
fun App() {
    SusWebTheme {
        val state = rememberWebViewState(HOME_URL)
        val navigator = rememberWebViewNavigator()

        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeContentPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = navigator::navigateBack, enabled = navigator.canGoBack) {
                        Text("뒤로")
                    }
                    Button(onClick = navigator::navigateForward, enabled = navigator.canGoForward) {
                        Text("앞으로")
                    }
                    Button(onClick = navigator::reload) { Text("새로고침") }
                    Button(onClick = { navigator.loadUrl(HOME_URL) }) { Text("홈") }
                }
                Text(
                    text = state.pageTitle ?: "SusWeb",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                if (state.isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (state.errorsForCurrentRequest.isNotEmpty()) {
                    Text(
                        text = "페이지를 불러오지 못했습니다. 네트워크 연결을 확인하고 새로고침하세요.",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                WebView(
                    state = state,
                    navigator = navigator,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
}
