package io.github.sagernet.libghostty

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Threading: [feedOutput] and [finish] may be called from any thread. All other methods and
 * property writes must happen on the main thread. State flows and [EventListener] methods are
 * updated on the main thread, except [exitStatus], which is updated on the thread that calls
 * [finish]. [Transport] methods may be called from any thread that calls [feedOutput], and from
 * the main thread; implementations must be thread safe and must not block.
 *
 * Lifecycle: [close] is idempotent; after it, every method is a no-op.
 */
public class GhosttyTerminalSession(
    context: Context,
    options: Options = Options(),
) {

    public class Options(
        public val columns: Int = 80,
        public val rows: Int = 24,
        public val maxScrollbackLines: Long = 10_000,
        public val reportedVersion: String = "libghostty-android",
        public val terminfoName: String? = "xterm-256color",
        public val defaultCursorStyle: GhosttyCursorStyle = GhosttyCursorStyle.BLOCK,
        public val defaultCursorBlink: Boolean = true,
    ) {
        init {
            require(columns >= 1) { "columns must be >= 1" }
            require(rows >= 1) { "rows must be >= 1" }
            require(maxScrollbackLines >= 0) { "maxScrollbackLines must be >= 0" }
        }
    }

    public interface EventListener {
        public fun onBell(session: GhosttyTerminalSession) {}

        public fun onNotification(session: GhosttyTerminalSession, title: String, body: String) {}
    }

    public interface Transport {
        public fun sendInput(data: ByteArray)

        public fun sendResize(columns: Int, rows: Int, widthPixels: Int, heightPixels: Int)

        public fun close()
    }

    private val context = context.applicationContext

    private val mainHandler = Handler(Looper.getMainLooper())

    internal val terminalAccess = Any()

    internal var handle = GhosttyVt.nativeCreate(
        options.columns,
        options.rows,
        options.maxScrollbackLines,
        options.reportedVersion,
        options.terminfoName,
        options.defaultCursorStyle.nativeValue,
        options.defaultCursorBlink,
    )
        private set

    internal inline fun <T> withTerminal(block: (Long) -> T): T? = synchronized(terminalAccess) {
        if (handle == 0L) null else block(handle)
    }

    internal val terminalAlive: Boolean
        get() = synchronized(terminalAccess) { handle != 0L }

    public var eventListener: EventListener? = null

    public var systemClipboardWriteEnabled: Boolean = true

    public var transport: Transport? = null
        set(value) {
            field = value
            if (value != null && columns > 0 && rows > 0) {
                sentColumns = 0
                sentRows = 0
                sentCellWidthPixels = 0
                sentCellHeightPixels = 0
                sendTransportResize()
            }
        }

    private val _title = MutableStateFlow<String?>(null)
    public val title: StateFlow<String?> = _title.asStateFlow()

    private val _workingDirectory = MutableStateFlow<String?>(null)
    public val workingDirectory: StateFlow<String?> = _workingDirectory.asStateFlow()

    private val _progress = MutableStateFlow<GhosttyProgress?>(null)
    public val progress: StateFlow<GhosttyProgress?> = _progress.asStateFlow()

    private val _backgroundColor = MutableStateFlow<Int?>(null)
    public val backgroundColor: StateFlow<Int?> = _backgroundColor.asStateFlow()

    private val _exitStatus = MutableStateFlow<Int?>(null)
    public val exitStatus: StateFlow<Int?> = _exitStatus.asStateFlow()

    public val isFinished: Boolean
        get() = _exitStatus.value != null

    public var selectionBackground: Int? = null
        private set

    public var selectionForeground: Int? = null
        private set

    public var columns: Int = 0
        private set

    public var rows: Int = 0
        private set

    public val hasAttachedView: Boolean
        get() = attachedView != null

    private var clipboardReadApproved = false
    private var attachedView: GhosttyTerminalView? = null
    private var cellWidthPixels = 0
    private var cellHeightPixels = 0

    private var sentColumns = 0
    private var sentRows = 0
    private var sentCellWidthPixels = 0
    private var sentCellHeightPixels = 0
    private var resizeSendScheduled = false
    private val resizeSendRunnable = Runnable {
        resizeSendScheduled = false
        sendTransportResize()
    }

    init {
        _backgroundColor.value = withTerminal { GhosttyVt.nativeGetBackgroundColor(it) }
            ?.takeIf { it != 0 }
    }

    internal fun attach(view: GhosttyTerminalView) {
        check(attachedView == null || attachedView === view) {
            "session is already attached to another view"
        }
        attachedView = view
    }

    internal fun detach(view: GhosttyTerminalView) {
        if (attachedView === view) {
            attachedView = null
        }
    }

    public fun feedOutput(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        val response = withTerminal { GhosttyVt.nativeWrite(it, data, offset, length) }
        if (response != null && response.isNotEmpty()) {
            // timg abandons its kitty graphics probe when the DA1 reply does not
            // arrive within a small time budget.
            sendRawInput(response)
        }
        mainHandler.post {
            withTerminal { drainEvents(it) }
            attachedView?.scheduleFrame()
        }
    }

    private fun drainEvents(handle: Long) {
        val flags = GhosttyVt.nativeTakeEventFlags(handle)
        if (flags == 0) return
        if (flags and GhosttyVt.EVENT_TITLE != 0) {
            _title.value = GhosttyVt.nativeGetTitle(handle)?.toString(Charsets.UTF_8)
        }
        if (flags and GhosttyVt.EVENT_BELL != 0) {
            attachedView?.onSessionBell()
            eventListener?.onBell(this)
        }
        if (flags and GhosttyVt.EVENT_CLIPBOARD != 0) {
            val text = GhosttyVt.nativeTakeClipboard(handle)?.toString(Charsets.UTF_8)
            if (systemClipboardWriteEnabled && !text.isNullOrEmpty()) {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText(null, text))
            }
        }
        if (flags and GhosttyVt.EVENT_PWD != 0) {
            _workingDirectory.value = GhosttyVt.nativeGetPwd(handle)
                ?.toString(Charsets.UTF_8)
                ?.let(::pwdToPath)
        }
        if (flags and GhosttyVt.EVENT_NOTIFICATION != 0) {
            while (true) {
                val packed = GhosttyVt.nativeTakeNotification(handle) ?: break
                val titleLength = ByteBuffer.wrap(packed).order(ByteOrder.LITTLE_ENDIAN).int
                eventListener?.onNotification(
                    this,
                    String(packed, 4, titleLength, Charsets.UTF_8),
                    String(packed, 4 + titleLength, packed.size - 4 - titleLength, Charsets.UTF_8),
                )
            }
        }
        if (flags and GhosttyVt.EVENT_CLIPBOARD_READ != 0) {
            val location = GhosttyVt.nativeTakeClipboardRead(handle)
            if (location >= 0) handleClipboardRead(location)
        }
        if (flags and GhosttyVt.EVENT_PROGRESS != 0) {
            val progress = GhosttyVt.nativeGetProgress(handle)
            if (progress != null) {
                _progress.value = when (progress[0]) {
                    GhosttyVt.PROGRESS_STATE_SET -> GhosttyProgressState.SET
                    GhosttyVt.PROGRESS_STATE_ERROR -> GhosttyProgressState.ERROR
                    GhosttyVt.PROGRESS_STATE_INDETERMINATE -> GhosttyProgressState.INDETERMINATE
                    GhosttyVt.PROGRESS_STATE_PAUSE -> GhosttyProgressState.PAUSE
                    else -> null
                }?.let { GhosttyProgress(it, progress[1].takeIf { percent -> percent >= 0 }) }
            }
        }
        if (flags and GhosttyVt.EVENT_COLORS != 0) {
            _backgroundColor.value = GhosttyVt.nativeGetBackgroundColor(handle).takeIf { it != 0 }
        }
    }

    private fun pwdToPath(raw: String): String? {
        if (raw.isEmpty()) return null
        if (!raw.startsWith("file://")) return raw
        return Uri.parse(raw).path?.takeIf { it.isNotEmpty() }
    }

    public fun setColorScheme(dark: Boolean) {
        val report = withTerminal { GhosttyVt.nativeSetColorScheme(it, dark) }
        if (report != null && report.isNotEmpty()) sendRawInput(report)
    }

    private fun handleClipboardRead(location: Int) {
        if (clipboardReadApproved) {
            respondClipboardRead(location, readClipboardText())
            return
        }
        val view = attachedView
        if (view == null) {
            respondClipboardRead(location, null)
            return
        }
        view.uiHandler.requestClipboardRead(view.context) { approved ->
            if (approved) {
                clipboardReadApproved = true
                respondClipboardRead(location, readClipboardText())
            } else {
                respondClipboardRead(location, null)
            }
        }
    }

    internal fun readClipboardText(): String? = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(context)
        ?.toString()

    private fun respondClipboardRead(location: Int, text: String?) {
        val kind = when (location) {
            GhosttyVt.CLIPBOARD_LOCATION_SELECTION -> 's'
            GhosttyVt.CLIPBOARD_LOCATION_PRIMARY -> 'p'
            else -> 'c'
        }
        val encoded = Base64.encodeToString(text.orEmpty().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        sendRawInput("\u001b]52;$kind;$encoded\u0007".toByteArray(Charsets.ISO_8859_1))
    }

    public fun sendFocus(gained: Boolean) {
        if (isFinished) return
        val report = withTerminal { GhosttyVt.nativeEncodeFocus(it, gained) }
        if (report != null && report.isNotEmpty()) sendRawInput(report)
    }

    public fun sendTypedInput(data: ByteArray) {
        sendRawInput(sanitizeInput(data))
    }

    public fun sendRawInput(data: ByteArray) {
        val currentTransport = transport ?: return
        try {
            currentTransport.sendInput(data)
        } catch (e: Exception) {
            Log.e(TAG, "transport send input failed", e)
            if (!isFinished) finish(-1)
        }
    }

    public fun screenText(): String? = withTerminal { GhosttyVt.nativeViewportText(it) }
        ?.toString(Charsets.UTF_8)

    public fun selectionText(): String? = withTerminal { GhosttyVt.nativeSelectionText(it) }
        ?.toString(Charsets.UTF_8)

    public fun transcriptText(): String? = withTerminal { GhosttyVt.nativeTranscriptText(it) }
        ?.toString(Charsets.UTF_8)

    public fun resize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        resizeTerminal(columns, rows, cellWidthPixels, cellHeightPixels)
        scheduleTransportResize()
    }

    // Resizing to a size the terminal is only going to hold for the length of a
    // keyboard animation must not reach the transport: a remote shell redrawing
    // for a size that is already gone leaves its prompt mid-line.
    internal fun resizeTerminal(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        if (columns == this.columns && rows == this.rows &&
            cellWidthPixels == this.cellWidthPixels && cellHeightPixels == this.cellHeightPixels
        ) {
            return
        }
        this.columns = columns
        this.rows = rows
        this.cellWidthPixels = cellWidthPixels
        this.cellHeightPixels = cellHeightPixels
        withTerminal { GhosttyVt.nativeResize(it, columns, rows, cellWidthPixels, cellHeightPixels) }
    }

    private fun scheduleTransportResize() {
        if (transport == null) return
        // zsh erases its end-of-line mark by printing COLUMNS-1 spaces and a
        // carriage return, so a prompt printed before the shell learns the
        // real width leaves the mark on screen.
        if (columns != sentColumns) {
            if (resizeSendScheduled) {
                resizeSendScheduled = false
                mainHandler.removeCallbacks(resizeSendRunnable)
            }
            sendTransportResize()
            return
        }
        if (resizeSendScheduled) mainHandler.removeCallbacks(resizeSendRunnable)
        resizeSendScheduled = true
        mainHandler.postDelayed(resizeSendRunnable, RESIZE_SEND_DEBOUNCE_MS)
    }

    private fun sendTransportResize() {
        val currentTransport = transport ?: return
        if (columns == sentColumns && rows == sentRows &&
            cellWidthPixels == sentCellWidthPixels && cellHeightPixels == sentCellHeightPixels
        ) {
            return
        }
        sentColumns = columns
        sentRows = rows
        sentCellWidthPixels = cellWidthPixels
        sentCellHeightPixels = cellHeightPixels
        try {
            currentTransport.sendResize(columns, rows, columns * cellWidthPixels, rows * cellHeightPixels)
        } catch (e: Exception) {
            Log.e(TAG, "transport send resize failed", e)
            if (!isFinished) finish(-1)
        }
    }

    public fun setTheme(theme: GhosttyTheme) {
        selectionBackground = theme.selectionBackground
        selectionForeground = theme.selectionForeground
        withTerminal {
            GhosttyVt.nativeSetTheme(
                it,
                theme.foreground?.toUInt()?.toLong() ?: -1L,
                theme.background?.toUInt()?.toLong() ?: -1L,
                theme.cursorColor?.toUInt()?.toLong() ?: -1L,
                LongArray(theme.palette.size) { index ->
                    theme.palette[index]?.toUInt()?.toLong() ?: -1L
                },
            )
            _backgroundColor.value = GhosttyVt.nativeGetBackgroundColor(it).takeIf { color -> color != 0 }
        }
        attachedView?.scheduleFrame()
    }

    public fun finish(exitCode: Int = 0) {
        _exitStatus.value = exitCode
    }

    public fun close() {
        try {
            transport?.close()
        } catch (e: Exception) {
            Log.e(TAG, "transport close failed", e)
        }
        transport = null
        mainHandler.post {
            withTerminal {
                GhosttyVt.nativeFree(it)
                handle = 0
            }
        }
    }

    private fun sanitizeInput(data: ByteArray): ByteArray {
        var i = 0
        while (i < data.size) {
            val byte = data[i].toInt()
            if (byte == 0x0A || (byte == 0x1B && isBracketedPasteMarker(data, i))) break
            i++
        }
        if (i == data.size) return data
        val result = ByteArray(data.size)
        data.copyInto(result, endIndex = i)
        var writePosition = i
        while (i < data.size) {
            val byte = data[i]
            if (byte.toInt() == 0x1B && isBracketedPasteMarker(data, i)) {
                i += 6
                continue
            }
            result[writePosition++] = if (byte.toInt() == 0x0A) 0x0D else byte
            i++
        }
        return if (writePosition == result.size) result else result.copyOf(writePosition)
    }

    private fun isBracketedPasteMarker(data: ByteArray, i: Int): Boolean = i + 5 < data.size &&
        data[i + 1] == '['.code.toByte() &&
        data[i + 2] == '2'.code.toByte() &&
        data[i + 3] == '0'.code.toByte() &&
        (data[i + 4] == '0'.code.toByte() || data[i + 4] == '1'.code.toByte()) &&
        data[i + 5] == '~'.code.toByte()

    private companion object {
        const val TAG = "GhosttyTerminalSession"
        const val RESIZE_SEND_DEBOUNCE_MS = 100L
    }
}
