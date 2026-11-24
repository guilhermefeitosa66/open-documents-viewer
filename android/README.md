# Open Documents Viewer (Android)

A fast, offline, read-only viewer for text documents. Part of [libre-apps](https://libre-apps.org).
No ads, no account, no network permission, no telemetry.

- **Markdown** rendered natively (CommonMark + GFM tables, task lists, strikethrough, autolinks), with a Formatted / Source toggle
- **Code** with syntax highlighting for 30+ languages (Kotlin, Java, C/C++, C#, Go, Rust, Swift, Dart, JS/TS, Python, Ruby, PHP, shell, SQL, Lua, CSS...)
- **Data and config**: JSON (pretty-printed), CSV/TSV as a table, `.env` with secret-looking values hidden, YAML, TOML, INI, XML/HTML
- **Plain text and logs**, large files included (virtualized; files over 16 MB show their first 16 MB)
- Search with match case, whole word and regex; recent files with reading position; quick switcher; pinch to zoom
- Opens from "Open with" and "Share" in any app, through the Storage Access Framework
- Themes: libre-apps (follows the system), Pastel, Dracula, Kitty and Neon (Tokyo Night)

The Linux app lives in [`../linux`](../linux) in this same repository.

## Build

```sh
./gradlew --no-daemon :app:testDebugUnitTest   # unit tests (JVM)
./gradlew --no-daemon :app:assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17 and the Android SDK (compileSdk 36). minSdk 26.

## Dependencies and licenses

| Dependency | License |
|---|---|
| Kotlin, kotlinx-coroutines | Apache-2.0 |
| AndroidX (Activity, Lifecycle, Core), Jetpack Compose, Material 3 | Apache-2.0 |
| [commonmark-java](https://github.com/commonmark/commonmark-java) and its GFM extensions | BSD-2-Clause |
| [jsoup](https://jsoup.org) (HTML parser) | MIT |
| [AndroidSVG](https://github.com/BigBadaboom/androidsvg) | Apache-2.0 |
| [snakeyaml-engine](https://bitbucket.org/snakeyaml/snakeyaml-engine) | Apache-2.0 |
| [JetBrains Mono](https://github.com/JetBrains/JetBrainsMono) (bundled font) | SIL OFL 1.1 |
| Dracula and Tokyo Night color palettes (themes) | MIT |

Theme backgrounds are original art from the libre-apps themes. Syntax highlighting is this app's own code.

## License

[Apache License 2.0](LICENSE).
