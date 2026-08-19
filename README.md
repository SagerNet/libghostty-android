# libghostty-android

Android bindings for [libghostty-vt](https://github.com/ghostty-org/ghostty/tree/main/src/lib),
with a terminal view, session management, theme catalog, and font handling.

## Artifacts

| Artifact | minSdk | Contents |
| --- | --- | --- |
| `io.github.sagernet:libghostty-android` | 21 | `GhosttyTerminalSession`, `GhosttyTerminalView` |
| `io.github.sagernet:libghostty-android-extras` | 21 | `GhosttyThemeStore`, `ImportedFontStore` |
| `io.github.sagernet:libghostty-android-compose` | 23 | `GhosttyTerminal`, `TerminalDialogs` |
| `io.github.sagernet:libghostty-android-compose-legacy` | 21 | the compose module built against Compose 1.7 |

```kotlin
dependencies {
    implementation("io.github.sagernet:libghostty-android:0.1.0")
}
```

## Usage

`Transport` carries input to the process or connection behind the terminal; `feedOutput` takes the
bytes that process writes back.

```kotlin
val session = GhosttyTerminalSession(context)
session.transport = object : GhosttyTerminalSession.Transport {
    override fun sendInput(data: ByteArray) {}
    override fun sendResize(columns: Int, rows: Int, widthPixels: Int, heightPixels: Int) {}
    override fun close() {}
}
val view = GhosttyTerminalView(context)
view.session = session
```

With Compose:

```kotlin
val dialogState = rememberTerminalDialogState()
GhosttyTerminal(session, Modifier.fillMaxSize()) { view ->
    view.uiHandler = dialogState
}
TerminalDialogs(dialogState)
```

Themes and imported fonts come from the extras module:

```kotlin
val theme = GhosttyThemeStore.loadThemeOrDefault(context, "Afterglow", isDark = true)
if (theme != null) session.setTheme(theme)
```

## License

MIT
