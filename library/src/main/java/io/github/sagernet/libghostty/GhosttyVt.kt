package io.github.sagernet.libghostty

import java.nio.ByteBuffer

internal object GhosttyVt {
    init {
        System.loadLibrary("ghostty_android")
    }

    const val EVENT_BELL = 1
    const val EVENT_TITLE = 1 shl 1
    const val EVENT_CLIPBOARD = 1 shl 2
    const val EVENT_PWD = 1 shl 3
    const val EVENT_NOTIFICATION = 1 shl 4
    const val EVENT_PROGRESS = 1 shl 5
    const val EVENT_COLORS = 1 shl 6

    const val PROGRESS_STATE_REMOVE = 0
    const val PROGRESS_STATE_SET = 1
    const val PROGRESS_STATE_ERROR = 2
    const val PROGRESS_STATE_INDETERMINATE = 3
    const val PROGRESS_STATE_PAUSE = 4

    external fun nativeCreate(
        cols: Int,
        rows: Int,
        maxScrollbackLines: Long,
        xtversion: String,
        terminfoName: String?,
        cursorStyle: Int,
        cursorBlink: Boolean,
        owner: GhosttyTerminalSession?,
    ): Long

    external fun nativeFree(handle: Long)

    external fun nativeWrite(handle: Long, data: ByteArray, offset: Int, length: Int): ByteArray?

    external fun nativeResize(handle: Long, cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int)

    external fun nativeTakeEventFlags(handle: Long): Int

    external fun nativeGetTitle(handle: Long): ByteArray?

    external fun nativeTakeClipboard(handle: Long): ByteArray?

    external fun nativeGetPwd(handle: Long): ByteArray?

    external fun nativeTakeNotification(handle: Long): ByteArray?

    external fun nativeGetProgress(handle: Long): IntArray?

    external fun nativeSetColorScheme(handle: Long, dark: Boolean): ByteArray?

    external fun nativeEncodeFocus(handle: Long, gained: Boolean): ByteArray?

    external fun nativeHyperlinkAt(handle: Long, col: Int, row: Int): ByteArray?

    external fun nativeSetTheme(
        handle: Long,
        foreground: Long,
        background: Long,
        cursor: Long,
        palette: LongArray?,
    )

    external fun nativeParseColor(color: String): Int

    external fun nativeScroll(handle: Long, deltaRows: Int)

    external fun nativeScrollToBottom(handle: Long)

    external fun nativeScrollbar(handle: Long): LongArray?

    const val VIEWPORT_ALTERNATE_SCREEN = 1
    const val VIEWPORT_MOUSE_TRACKING = 2

    external fun nativeViewportState(handle: Long): Int

    external fun nativeSnapshot(handle: Long, buffer: ByteBuffer): Int

    const val KITTY_PLACEMENT_LONGS = 13

    external fun nativeKittyPlacements(handle: Long): LongArray?

    external fun nativeKittyImage(handle: Long, imageId: Int): IntArray?

    external fun nativeEncodeKey(
        handle: Long,
        keyCode: Int,
        action: Int,
        metaState: Int,
        unshiftedCodepoint: Int,
        composing: Boolean,
        utf8: ByteArray?,
    ): ByteArray?

    const val MOUSE_ACTION_PRESS = 0
    const val MOUSE_ACTION_RELEASE = 1
    const val MOUSE_ACTION_MOTION = 2
    const val MOUSE_BUTTON_LEFT = 1
    const val MOUSE_BUTTON_RIGHT = 2
    const val MOUSE_BUTTON_MIDDLE = 3
    const val MOUSE_BUTTON_WHEEL_UP = 4
    const val MOUSE_BUTTON_WHEEL_DOWN = 5

    external fun nativeEncodeMouse(
        handle: Long,
        action: Int,
        button: Int,
        col: Int,
        row: Int,
        metaState: Int,
    ): ByteArray?

    external fun nativeSelectWord(handle: Long, col: Int, row: Int): Boolean

    external fun nativeSelectAll(handle: Long): Boolean

    external fun nativeSetSelection(
        handle: Long,
        anchorCol: Int,
        anchorRow: Int,
        col: Int,
        row: Int,
    ): Boolean

    external fun nativeClearSelection(handle: Long)

    external fun nativeSelectionText(handle: Long): ByteArray?

    external fun nativeViewportText(handle: Long): ByteArray?

    external fun nativeTranscriptText(handle: Long): ByteArray?

    external fun nativeGetBackgroundColor(handle: Long): Int

    external fun nativeIsPasteSafe(data: ByteArray): Boolean

    external fun nativeEncodePaste(handle: Long, data: ByteArray): ByteArray?
}
