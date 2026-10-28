package alyac.kotlin.susweb.webview

import androidx.compose.runtime.Composable
import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.KSafeConfig
import java.io.File
import java.nio.file.Files

// Process singleton; creation and vault access happen on IO at the first suspend read.
private val cookiePersistence by lazy {
    val tokens = KSafeCookiePersistence {
        KSafe(fileName = "cookies", config = KSafeConfig(appNamespace = "alyac.kotlin.susweb"))
    }
    val os = System.getProperty("os.name").lowercase()
    val home = System.getProperty("user.home")
    val directory =
        when {
            os.contains("win") ->
                File(System.getenv("APPDATA") ?: "$home/AppData/Roaming", "SusWeb")
            os.contains("mac") -> File(home, "Library/Application Support/SusWeb")
            else -> File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "susweb")
        }
    TokenCookiePersistence(tokens, tokens) {
        for (name in listOf("cookies.preferences_pb", "cookies.preferences_pb.tmp")) {
            Files.deleteIfExists(File(directory, name).toPath())
        }
    }
}

@Composable internal actual fun rememberCookiePersistence(): CookiePersistence = cookiePersistence
