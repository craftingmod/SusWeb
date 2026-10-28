package alyac.kotlin.susweb.webview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.KSafeConfig

private object AndroidCookieVault {
    private var instance: CookiePersistence? = null

    @Synchronized
    fun get(context: android.content.Context): CookiePersistence =
        instance
            ?: run {
                val appContext = context.applicationContext
                val tokens = KSafeCookiePersistence {
                    KSafe(
                        context = appContext,
                        fileName = "cookies",
                        config = KSafeConfig(appNamespace = "alyac.kotlin.susweb"),
                    )
                }
                TokenCookiePersistence(tokens, tokens) {
                    for (name in listOf("cookies.preferences_pb", "cookies.preferences_pb.tmp")) {
                        val file = appContext.noBackupFilesDir.resolve(name)
                        check(!file.exists() || file.delete()) {
                            "Could not remove legacy cookie storage"
                        }
                    }
                }
            }
                .also { instance = it }
}

@Composable
internal actual fun rememberCookiePersistence(): CookiePersistence {
    val context = LocalContext.current.applicationContext
    return remember(context) { AndroidCookieVault.get(context) }
}
