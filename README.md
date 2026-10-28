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
Nucleus 2.5.18 with the Tao backend and Material 3 window chrome. Versions are
pinned in `libs.versions.toml`. The initial page is `https://nucleusframework.dev`;
change `HOME_URL` in `shared/src/App.kt` to use another page. The toolbar provides
back, forward, reload, and home actions, with loading and error feedback.

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
`nucleusApplication` explicitly selects Tao and disables its default single-instance
lock to preserve the previous ability to run multiple app instances. The direct
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
