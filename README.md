# libghostty-android

Android bindings for [libghostty-vt](https://github.com/ghostty-org/ghostty), with a terminal
view, session management, theme catalog, and font handling.

## Usage

```kotlin
dependencies {
    implementation("io.github.sagernet:libghostty-android:0.1.0")
}
```

```kotlin
val session = GhosttyTerminalSession(context)
session.transport = object : GhosttyTerminalSession.Transport {
    override fun sendInput(data: ByteArray) { /* write to the process or connection */ }
    override fun sendResize(columns: Int, rows: Int, widthPixels: Int, heightPixels: Int) {}
    override fun close() {}
}
val view = GhosttyTerminalView(context)
view.session = session
session.feedOutput(processOutput)
```

With Compose:

```kotlin
dependencies {
    implementation("io.github.sagernet:libghostty-android-compose:0.1.0")
}
```

```kotlin
val dialogState = rememberTerminalDialogState()
GhosttyTerminal(session, Modifier.fillMaxSize()) { view ->
    view.uiHandler = dialogState
}
TerminalDialogs(dialogState)
```

## License

MIT
