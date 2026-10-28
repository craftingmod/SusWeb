This is a Kotlin Multiplatform project targeting Android, Desktop (JVM), built with the [Kotlin Toolchain](https://kotlin-toolchain.org/dev/).

- [/androidApp](./androidApp) contains the Android application.
- [/desktopApp](./desktopApp) contains the desktop (JVM) application.
- [/shared](./shared) holds the code shared across your applications — Compose UI, business logic, and platform-specific implementations. [src](./shared/src) is for common code; the sibling `src@<platform>` folders (for example `src@android`) hold code compiled only for the platform named in the folder.

The `kotlin` (macOS/Linux) and `kotlin.bat` (Windows) scripts in the project root are self-bootstrapping wrappers for the Kotlin Toolchain: they download the pinned toolchain version on first use, so no separate installation is required. Build the whole project with `./kotlin build`.

### Running

Use the run configurations provided by the run widget in your IDE's toolbar. You can also run each module from the command line:

- Android app: `./kotlin run -m androidApp`
- Desktop app: `./kotlin run -m desktopApp`

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
