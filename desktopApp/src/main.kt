package alyac.kotlin.susweb

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.NucleusBackend
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.window.material.MaterialDecoratedWindow
import dev.nucleusframework.window.material.MaterialTitleBar

fun main(args: Array<String>) =
    nucleusApplication(
        args = args,
        backend = NucleusBackend.Tao,
        enableSingleInstance = false,
    ) {
        SusWebTheme {
            MaterialDecoratedWindow(
                onCloseRequest = ::exitApplication,
                title = "SusWeb",
                state = rememberWindowState(size = DpSize(1100.dp, 800.dp)),
                minimumSize = DpSize(600.dp, 400.dp),
            ) {
                Column(Modifier.fillMaxSize()) {
                    MaterialTitleBar { Text("SusWeb") }
                    App()
                }
            }
        }
    }
