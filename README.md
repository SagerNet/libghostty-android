# libghostty-android

Android bindings for [libghostty-vt](https://github.com/ghostty-org/ghostty/tree/main/src/lib),
with a terminal view, session management, theme catalog, and font handling.

```kotlin
dependencies {
    implementation("io.github.sagernet:libghostty-android:0.1.0")

    // optional
    implementation("io.github.sagernet:libghostty-android-extras:0.1.0")
    implementation("io.github.sagernet:libghostty-android-compose:0.1.0")
}
```

libghostty-android-compose requires minSdk 23; libghostty-android-compose-legacy is the same
module built against Compose 1.7 for minSdk 21.

## License

MIT
