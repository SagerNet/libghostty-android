-keepclasseswithmembernames class io.github.sagernet.libghostty.GhosttyVt {
    native <methods>;
}
-keepclassmembers class io.github.sagernet.libghostty.GhosttyTerminalSession {
    private boolean clipboardHasText();
    private byte[] readClipboardForProgram(boolean);
}
