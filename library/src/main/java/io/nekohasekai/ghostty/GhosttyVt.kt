package io.nekohasekai.ghostty

import java.nio.ByteBuffer

/**
 * JNI bindings for libghostty-vt (see library/src/main/cpp/ghostty_jni.cpp).
 *
 * All functions must be called from the main thread; the native side keeps
 * per-handle state without locking.
 */
internal object GhosttyVt {
    // Loading is not expected to fail; the flag exists to degrade the
    // terminal feature instead of crashing if it ever does.
    val available = try {
        System.loadLibrary("ghostty_android")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    // Event flags returned by nativeTakeEventFlags; mirror ghostty_jni.cpp.
    const val EVENT_BELL = 1
    const val EVENT_TITLE = 1 shl 1
    const val EVENT_CLIPBOARD = 1 shl 2
    const val EVENT_PWD = 1 shl 3
    const val EVENT_NOTIFICATION = 1 shl 4
    const val EVENT_PROGRESS = 1 shl 5
    const val EVENT_CLIPBOARD_READ = 1 shl 6

    // GhosttyClipboardLocation values reported by nativeTakeClipboardRead.
    const val CLIPBOARD_LOCATION_STANDARD = 0
    const val CLIPBOARD_LOCATION_SELECTION = 1
    const val CLIPBOARD_LOCATION_PRIMARY = 2

    // GhosttyTerminalProgressState values reported by nativeGetProgress.
    const val PROGRESS_STATE_REMOVE = 0
    const val PROGRESS_STATE_SET = 1
    const val PROGRESS_STATE_ERROR = 2
    const val PROGRESS_STATE_INDETERMINATE = 3
    const val PROGRESS_STATE_PAUSE = 4

    /**
     * Create a terminal session; [xtversion] is the XTVERSION reply. Returns
     * 0 on failure.
     */
    external fun nativeCreate(cols: Int, rows: Int, maxScrollbackLines: Long, xtversion: String): Long

    external fun nativeFree(handle: Long)

    /**
     * Feed remote shell output bytes through the VT parser. Returns
     * query-response bytes the terminal wants written back to the remote
     * shell (DSR etc.), or null.
     */
    external fun nativeWrite(handle: Long, data: ByteArray): ByteArray?

    external fun nativeResize(handle: Long, cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int)

    /** EVENT_* flags for effects observed since the last call; clears on read. */
    external fun nativeTakeEventFlags(handle: Long): Int

    /** Terminal title set via OSC 0/2 as UTF-8, or null when unset. */
    external fun nativeGetTitle(handle: Long): ByteArray?

    /** Latest OSC 52 clipboard write as UTF-8, or null when none; clears on read. */
    external fun nativeTakeClipboard(handle: Long): ByteArray?

    /**
     * The pwd set via OSC 7 / OSC 9 / OSC 1337 as raw UTF-8 (OSC 7 delivers
     * a file:// URI), or null when unset.
     */
    external fun nativeGetPwd(handle: Long): ByteArray?

    /**
     * One queued desktop notification (OSC 9 / OSC 777) as
     * [4-byte LE title length][title][body] UTF-8, or null when the queue is
     * empty. Call repeatedly to drain.
     */
    external fun nativeTakeNotification(handle: Long): ByteArray?

    /**
     * Latest progress report (OSC 9;4) as [state, percent]; state values are
     * PROGRESS_STATE_*, percent is -1 when omitted.
     */
    external fun nativeGetProgress(handle: Long): IntArray?

    /**
     * Record the UI color scheme for CSI ? 996 n queries. Returns the
     * unsolicited mode-2031 report bytes to send to the remote shell when the
     * scheme changed while reporting is enabled, or null.
     */
    external fun nativeSetColorScheme(handle: Long, dark: Boolean): ByteArray?

    /**
     * Focus gained/lost report bytes for the remote shell, or null when mode
     * 1004 is not set.
     */
    external fun nativeEncodeFocus(handle: Long, gained: Boolean): ByteArray?

    /**
     * Pending OSC 52 read request as a CLIPBOARD_LOCATION_* value, or -1
     * when none; clears on read.
     */
    external fun nativeTakeClipboardRead(handle: Long): Int

    /** OSC 8 hyperlink URI at a viewport cell as UTF-8, or null. */
    external fun nativeHyperlinkAt(handle: Long, col: Int, row: Int): ByteArray?

    /**
     * Set the terminal's default colors. Colors use ghostty config syntax
     * (hex with or without '#', X11 names); null clears back to the built-in
     * default, invalid strings are logged and skipped. [palette] overrides
     * entries by index on top of ghostty's default 256-color palette.
     */
    external fun nativeSetTheme(
        handle: Long,
        foreground: String?,
        background: String?,
        cursor: String?,
        palette: Array<String?>?,
    )

    /** Parse a ghostty-syntax color to ARGB, or 0 when invalid. */
    external fun nativeParseColor(color: String): Int

    /** Scroll the viewport by [deltaRows]; negative is up (into scrollback). */
    external fun nativeScroll(handle: Long, deltaRows: Int)

    external fun nativeScrollToBottom(handle: Long)

    /** Viewport scrollbar as [total, offset, len] rows, or null. */
    external fun nativeScrollbar(handle: Long): LongArray?

    // nativeViewportState bits; mirror ghostty_jni.cpp.
    const val VIEWPORT_ALTERNATE_SCREEN = 1
    const val VIEWPORT_MOUSE_TRACKING = 2
    const val VIEWPORT_MOUSE_SGR = 4

    /** VIEWPORT_* bitmask routing scroll gestures. */
    external fun nativeViewportState(handle: Long): Int

    /**
     * Update the render state and flatten dirty rows into [buffer] (direct,
     * little-endian; layout documented in ghostty_jni.cpp). Returns the number
     * of row records written, -1 on error/too-small buffer, or -2 while
     * mode 2026 (synchronized output) defers rendering — retry shortly.
     */
    external fun nativeSnapshot(handle: Long, buffer: ByteBuffer): Int

    // nativeKittyPlacements record size in longs; layout in ghostty_jni.cpp.
    const val KITTY_PLACEMENT_LONGS = 13

    /**
     * Visible Kitty graphics placements for the current viewport as flat
     * records of [KITTY_PLACEMENT_LONGS] values each, sorted by z ascending
     * (layout documented in ghostty_jni.cpp), or null when there are none.
     */
    external fun nativeKittyPlacements(handle: Long): LongArray?

    /**
     * One stored Kitty image as [width, height, pixels...] ARGB ints, or
     * null when the image does not exist or is not fully transmitted yet.
     */
    external fun nativeKittyImage(handle: Long, imageId: Int): IntArray?

    /**
     * Encode a key event ([action]: 0 release / 1 press / 2 repeat) into the
     * escape-sequence bytes to send to the remote shell, or null when the key
     * encodes to nothing under the terminal's active modes.
     */
    external fun nativeEncodeKey(
        handle: Long,
        keyCode: Int,
        action: Int,
        metaState: Int,
        unshiftedCodepoint: Int,
        composing: Boolean,
        utf8: ByteArray?,
    ): ByteArray?

    // nativeEncodeMouse actions/buttons; mirror GhosttyMouseAction and
    // GhosttyMouseButton in ghostty/vt/mouse/event.h.
    const val MOUSE_ACTION_PRESS = 0
    const val MOUSE_ACTION_RELEASE = 1
    const val MOUSE_ACTION_MOTION = 2
    const val MOUSE_BUTTON_LEFT = 1
    const val MOUSE_BUTTON_RIGHT = 2
    const val MOUSE_BUTTON_MIDDLE = 3
    const val MOUSE_BUTTON_WHEEL_UP = 4
    const val MOUSE_BUTTON_WHEEL_DOWN = 5

    /**
     * Encode a mouse event at a viewport cell into the report bytes to send
     * to the remote shell, or null when the terminal's tracking mode produces
     * no report.
     */
    external fun nativeEncodeMouse(
        handle: Long,
        action: Int,
        button: Int,
        col: Int,
        row: Int,
        metaState: Int,
    ): ByteArray?

    /** Select the word at a viewport cell; false when there is nothing there. */
    external fun nativeSelectWord(handle: Long, col: Int, row: Int): Boolean

    external fun nativeSelectAll(handle: Long): Boolean

    /** Install a linear selection between two viewport cells (inclusive). */
    external fun nativeSetSelection(
        handle: Long,
        anchorCol: Int,
        anchorRow: Int,
        col: Int,
        row: Int,
    ): Boolean

    external fun nativeClearSelection(handle: Long)

    /** Active selection as UTF-8 (plain, unwrapped, trimmed), or null if none. */
    external fun nativeSelectionText(handle: Long): ByteArray?

    /** Whole viewport as UTF-8 (plain, unwrapped, trimmed), or null on failure. */
    external fun nativeViewportText(handle: Long): ByteArray?

    /** Conservative check: newlines / bracketed-paste escapes are unsafe. */
    external fun nativeIsPasteSafe(data: ByteArray): Boolean

    /** Encode clipboard bytes for the remote shell (control-byte strip + mode-2004 wrap). */
    external fun nativeEncodePaste(handle: Long, data: ByteArray): ByteArray?
}
