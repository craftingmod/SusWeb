This is a Kotlin Multiplatform project targeting Android, Desktop (JVM), built with the [Kotlin Toolchain](https://kotlin-toolchain.org/dev/).

- [/androidApp](./androidApp) contains the Android application.
- [/desktopApp](./desktopApp) contains the desktop (JVM) application.
- [/shared](./shared) holds the code shared across your applications — Compose UI, business logic, and platform-specific implementations. [src](./shared/src) is for common code; the sibling `src@<platform>` folders (for example `src@android`) hold code compiled only for the platform named in the folder.

The `kotlin` (macOS/Linux) and `kotlin.bat` (Windows) scripts in the project root are self-bootstrapping wrappers for the Kotlin Toolchain: they download the pinned toolchain version on first use, so no separate installation is required. Build the whole project with `./kotlin build`.

### Running

Use the run configurations provided by the run widget in your IDE's toolbar. You can also run each module from the command line:

- Android app: `./kotlin run -m androidApp`
- Desktop app: `./kotlin run -m desktopApp`

### Nucleus WebView

The shared screen uses `dev.nucleusframework:composewebview:1.0.3`. Desktop uses
Nucleus 2.5.18 with the Tao backend and Miuix 0.9.4 for the shared UI. The Nucleus
Material 3 window adapter uses colors from Miuix to match the title bar. Versions are
pinned in `libs.versions.toml`. The main screen displays `Hello World` and a
top-right account button. On startup and after closing the profile, the app reads
the current token and validates the session with `getPush`. Only a successful session
check calls `getProfile()` and displays the returned nickname in the account button.
Missing or rejected tokens show `로그인`; connection failures show a retry message.
The nickname remains clickable to open the profile. Login is optional. Opening the profile attaches a WebView
and restores the saved `s_token` when the native store has none. A token already
in the native store takes precedence. The app then reads that token and validates
it with a form-encoded POST to
`https://api.ruliweb.com/getPush` (`push_id` is empty; `client_mode` is `m` on Android
and `d` on Desktop). Only `success: true` establishes a logged-in session.
Missing or rejected tokens open `https://user.ruliweb.com/member/login` with
`redirect_url=https://bbs.ruliweb.com/member/mypage`; successful validation opens
that same mypage. Before checking the token, the app imports all cookies exposed by
the native WebView for the BBS, user, and API origins (including the mypage path) into Ktor's `HttpCookies`
storage. The import repeats every poll, even when the token has not changed;
changed cookies are replaced and disappeared cookies are expired in Ktor.
Cookie values remain raw; available domain, path, expiry, Secure, HttpOnly, and
SameSite metadata are retained. Native APIs may omit metadata (especially Android's
CookieManager); missing domain/path use the queried host and `/`, without guessing
a shared parent domain. Cookies scoped to paths outside the queried URLs are not
enumerated by URL-based native APIs.
The shared HTTP client and its `PersistentCookiesStorage` live for the app's lifetime,
including when the profile closes. Only `s_token`, including tokens without an expiry,
is encrypted through [KSafe 3.3.0](https://github.com/ioannisa/KSafe). Other cookies
are kept in memory only; the app no longer writes a separate DataStore cookie jar.
The token is restored lazily on the first Ktor cookie access.
Other session cookies stay in memory. Login tokens without expiry metadata retain
their session-cookie attributes when restored into WebView. `Max-Age` is converted once to an absolute deadline; restarting the
app does not extend it. Expired cookies are removed from memory and disk. Credentials
are not logged. Token restoration finishes before cookie polling begins, so an
initially empty WebView cannot erase the saved login. If the native write is not yet
visible, restoration retries without deleting the saved token. Subsequent cookie
removal still clears the saved token; restoration runs once per new profile view,
so logout does not immediately restore it. On Android, matching restored token
values retain their saved scope and expiry when native reads omit metadata.
While the profile is open, cookies are checked every 1.5 seconds. API requests run
only when the token changes, the profile reopens, or **로그인 확인** is pressed.
Network/HTTP/JSON failures show a retry message rather than treating the session
as logged out. Close the profile to return to the main screen at any time.
The profile toolbar provides back, reload, and session verification actions.

`SessionRepository` is the app-wide token API. `App` creates one instance, shares its
cookie storage with Ktor, and provides it through `LocalSessionRepository`.
Composable screens can obtain it with `val session = LocalSessionRepository.current`;
pass that same `SessionRepository` into ViewModel/repository constructors.
All three operations are suspend functions and should be called from a coroutine:

```kotlin
val token: String? = session.getToken()
session.saveToken(tokenFromLogin, expiresAtMillis = serverExpiryMillis)
session.clearToken()
```

`saveToken` replaces all old token scopes with the supplied token. An absolute expiry
is optional; tokens persist across app restarts even when it is omitted.
`clearToken` removes all token scopes while preserving unrelated
in-memory cookies. Native profile changes appear through the same API; app token
edits reach the WebView on its next poll/open, before native cookies are imported,
so stale native cookies cannot undo the edit. A failed native update is retried.
Token presence alone does not prove authentication; the profile still checks the server.

`RuliwebClient` in `shared/src/ruliweb` shares the app's HTTP client and session repository.
Shared composables can obtain it through `LocalRuliwebClient`; `getToken()` reads the
current token from the same KSafe-backed cookie store used by the profile. HTTP requests
using its client share that cookie store, so matching cookies are sent automatically.
The token persists even when it has no expiry. The client and its composition
local are currently internal to the shared module.

`createRuliwebHttpClient(cookieStorage)` and `WebViewCookieSync(cookieStorage)` use
the same Ktor `CookiesStorage`. `PersistentCookiesStorage` serializes a versioned
cookie jar through the app-owned `CookiePersistence` interface; common tests use a
fake backend. `TokenCookiePersistence` persists only records named exactly `s_token`.
`KSafeCookiePersistence` also rejects payloads containing any other cookie name.
Token cookie metadata stays with the encrypted value so domain, path, and
expiry remain available after restoration. Ordinary cookie updates do not rewrite
unchanged KSafe records. `KSafeCookiePersistence` uses suspend `get`/`put` on IO and
awaits encrypted disk commits, with no plaintext fallback implemented by the app.
Read or write errors propagate without replacing the in-memory jar; unsupported
payload versions are preserved.
Platform factories reuse an app-lifetime KSafe instance named `cookies`
with namespace `alyac.kotlin.susweb`; Android passes `applicationContext`.
The previous encrypted `http_cookies_v1` jar migrates on first access: only token
records move to KSafe's `http_session_token_v1`; other records are discarded and the
old key is deleted after the token commit succeeds. An existing destination,
including an empty jar, wins so legacy credentials cannot revive a deleted token.
The obsolete `cookies.preferences_pb` DataStore file and its `.tmp` file are removed
on first access from Android's no-backup directory, Windows `%APPDATA%/SusWeb`,
macOS `~/Library/Application Support/SusWeb`, or Linux `$XDG_DATA_HOME/susweb`
(falling back to `~/.local/share/susweb`). Cleanup or migration failures propagate
and are retried on the next access. There is no direct DataStore dependency;
KSafe may still use DataStore internally.
KSafe manages platform key custody (Android Keystore; Windows DPAPI, macOS Keychain,
or Linux Secret Service on Desktop, with its documented software fallback where no
OS vault exists). Its encryption-operability check runs before storage access.
Desktop uses single-instance mode because KSafe does not coordinate multiple processes.
When adding a trimmed JVM runtime for release packaging, include `jdk.unsupported`
to retain OS vault support; installer packaging is not configured in this project.
The toolbar wraps on narrow screens. Miuix follows the system light/dark theme;
the embedded website controls its own appearance. Miuix's optional desktop window
module is not used because Nucleus must own the Tao window hosting the WebView.

On Windows, run `bun run start` (or `./kotlin.bat run -m desktopApp`). The WebView
requires Microsoft Edge WebView2 Runtime. Android uses the system WebView and
declares the `INTERNET` permission. Linux requires WebKit2GTK; macOS uses WKWebView.
Only Windows desktop rendering has been checked locally; Android has been built
and unit tested, but its WebView has not been checked on a device.

The migration follows the [Nucleus migration guide](https://nucleusframework.dev/nucleus-migrate.md)
and [WebView setup](https://nucleusframework.dev/en/docs/webview/getting-started/).
This project keeps Kotlin Toolchain (Amper), so runtime dependencies are declared
in `module.yaml` rather than applying the guide's Gradle packaging plugin.
Installer packaging, auto-update, GraalVM, and CI are outside this migration.
`nucleusApplication` explicitly selects Tao and enables its single-instance lock
to protect the KSafe store from concurrent processes. The direct
`kotlinx-coroutines-swing` dependency has been removed: Tao provides its own main
dispatcher. Desktop WebView screens must be run in the Tao host window rather than
an AWT window or a standalone Compose preview.

### Testing

Run the project's tests with `./kotlin test`, or `./kotlin test -m <module>` for a single module.

### Kotlin formatting

Requires Bun and Java 17+ (`java` on `PATH`, falling back to `JAVA_HOME`). The first run downloads
ktfmt 0.65, verifies its SHA-256 checksum, and caches it under `build/tools/ktfmt/`.

- `bun run ktfmt`: format Kotlin files in `shared`, `androidApp`, and `desktopApp` using the Kotlin style (4-space indentation).
- `bun run ktfmt:check`: check formatting without modifying files; exit with a nonzero status if changes are needed.
- `bun run ktfmt shared/src/App.kt`: format a specific file. Explicit arguments replace the default module paths.
- `bun run ktfmt --help`: show ktfmt CLI options.

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html), [Kotlin Toolchain](https://kotlin-toolchain.org/dev/), [Compose Multiplatform](https://kotlinlang.org/compose-multiplatform/).
