package alyac.kotlin.susweb

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.NucleusBackend
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.window.material.MaterialDecoratedWindow
import dev.nucleusframework.window.material.MaterialTitleBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

fun main(args: Array<String>) =
    nucleusApplication(
        args = args,
        backend = NucleusBackend.Tao,
        // KSafe uses a single-process store coordinator.
        enableSingleInstance = true,
    ) {
        SusWebTheme {
            MiuixWindowTheme {
                MaterialDecoratedWindow(
                    onCloseRequest = ::exitApplication,
                    title = "SusWeb",
                    state = rememberWindowState(size = DpSize(1100.dp, 800.dp)),
                    minimumSize = DpSize(600.dp, 400.dp),
                ) {
                    SusWebTheme {
                        Column(Modifier.fillMaxSize()) {
                            MaterialTitleBar { Text("SusWeb") }
                            App()
                        }
                    }
                }
            }
        }
    }

// Nucleus owns the Tao window controls; adapt its Material chrome to Miuix colors.
@Composable
private fun MiuixWindowTheme(content: @Composable () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val base = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(
        colorScheme =
            base.copy(
                primary = colors.primary,
                onPrimary = colors.onPrimary,
                background = colors.background,
                onBackground = colors.onBackground,
                surface = colors.surface,
                onSurface = colors.onSurface,
                surfaceContainer = colors.surfaceContainer,
                surfaceContainerHigh = colors.surfaceContainerHigh,
                surfaceContainerHighest = colors.surfaceContainerHighest,
                surfaceVariant = colors.surfaceVariant,
                onSurfaceVariant = colors.onSurfaceSecondary,
                outline = colors.outline,
            ),
        content = content,
    )
}
