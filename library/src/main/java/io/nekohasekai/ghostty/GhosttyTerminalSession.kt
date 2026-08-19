package io.nekohasekai.ghostty

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.HapticFeedbackConstants

class GhosttyTerminalSession(
    context: Context,
    options: Options = Options(),
) {

    class Options(
        val columns: Int = 80,
        val rows: Int = 24,
        val maxScrollbackLines: Long = 10_000,
        val reportedVersion: String = "libghostty-android",
    )

    interface Listener {
        fun onTitleChanged(session: GhosttyTerminalSession) {}

        fun onBell(session: GhosttyTerminalSession) {}

        fun onWorkingDirectoryChanged(session: GhosttyTerminalSession) {}

        fun onNotification(session: GhosttyTerminalSession, title: String, body: String) {}

        fun onProgressChanged(session: GhosttyTerminalSession) {}

        fun onFinished(session: GhosttyTerminalSession) {}
    }

    interface Transport {
        fun sendInput(data: ByteArray)

        fun sendResize(columns: Int, rows: Int, widthPixels: Int, heightPixels: Int)

        fun close()
    }

    private val context = context.applicationContext

    private val mainHandler = Handler(Looper.getMainLooper())

    internal val terminalAccess = Any()

    internal var handle = if (GhosttyVt.available) {
        GhosttyVt.nativeCreate(
            options.columns,
            options.rows,
            options.maxScrollbackLines,
            options.reportedVersion,
        )
    } else {
        0L
    }
        private set

    /**
     * Runs [block] with the live native handle under [terminalAccess], or
     * returns null after free. Serializes the UI thread against
     * [feedOutput], which processes output on the transport's thread.
     */
    internal inline fun <T> withTerminal(block: (Long) -> T): T? = synchronized(terminalAccess) {
        if (handle == 0L) null else block(handle)
    }

    internal val terminalAlive: Boolean
        get() = synchronized(terminalAccess) { handle != 0L }

    /** Whether a terminal view currently displays this session. */
    val hasAttachedView: Boolean
        get() = attachedView != null

    var listener: Listener? = null

    var transport: Transport? = null
        set(value) {
            field = value
            if (value != null && lastColumns > 0 && lastRows > 0) {
                try {
                    value.sendResize(
                        lastColumns,
                        lastRows,
                        lastColumns * lastCellWidthPixels,
                        lastRows * lastCellHeightPixels,
                    )
                } catch (e: Exception) {
                    onTransportFailure("send resize", e)
                }
            }
        }

    @Volatile
    var isFinished = false
        private set

    var title: String? = null
        private set

    /** Working directory reported via OSC 7 as a local path, or null. */
    var workingDirectory: String? = null
        private set

    /** GhosttyVt.PROGRESS_STATE_* of the latest OSC 9;4 report. */
    var progressState = GhosttyVt.PROGRESS_STATE_REMOVE
        private set

    /** Progress percentage 0..100, or -1 when the report omitted it. */
    var progressPercent = -1
        private set

    var exitCode = 0
        private set

    var selectionBackground: Int? = null
        private set

    var selectionForeground: Int? = null
        private set

    private var clipboardReadApproved = false
    private var attachedView: GhosttyTerminalView? = null
    private var lastColumns = 0
    private var lastRows = 0
    private var lastCellWidthPixels = 0
    private var lastCellHeightPixels = 0

    internal fun attach(view: GhosttyTerminalView) {
        attachedView = view
    }

    internal fun detach(view: GhosttyTerminalView) {
        if (attachedView === view) {
            attachedView = null
        }
    }

    // Runs on the transport's thread: query replies (kitty a=q, DA, XTWINOPS)
    // must go back without waiting for the main looper — feature probes like
    // timg's give up within a small time budget.
    fun feedOutput(data: ByteArray) {
        val response = withTerminal { GhosttyVt.nativeWrite(it, data) }
        if (response != null && response.isNotEmpty()) {
            sendRawInput(response)
        }
        mainHandler.post {
            withTerminal { drainEvents(it) }
            attachedView?.onSessionOutput()
        }
    }

    private fun drainEvents(handle: Long) {
        val flags = GhosttyVt.nativeTakeEventFlags(handle)
        if (flags == 0) return
        if (flags and GhosttyVt.EVENT_TITLE != 0) {
            title = GhosttyVt.nativeGetTitle(handle)?.toString(Charsets.UTF_8)
            listener?.onTitleChanged(this)
        }
        if (flags and GhosttyVt.EVENT_BELL != 0) {
            attachedView?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            listener?.onBell(this)
        }
        if (flags and GhosttyVt.EVENT_CLIPBOARD != 0) {
            val text = GhosttyVt.nativeTakeClipboard(handle)?.toString(Charsets.UTF_8)
            if (!text.isNullOrEmpty()) {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText(null, text))
            }
        }
        if (flags and GhosttyVt.EVENT_PWD != 0) {
            workingDirectory = GhosttyVt.nativeGetPwd(handle)
                ?.toString(Charsets.UTF_8)
                ?.let(::pwdToPath)
            listener?.onWorkingDirectoryChanged(this)
        }
        if (flags and GhosttyVt.EVENT_NOTIFICATION != 0) {
            while (true) {
                val packed = GhosttyVt.nativeTakeNotification(handle) ?: break
                if (packed.size < 4) continue
                val titleLength = (packed[0].toInt() and 0xFF) or
                    ((packed[1].toInt() and 0xFF) shl 8) or
                    ((packed[2].toInt() and 0xFF) shl 16) or
                    ((packed[3].toInt() and 0xFF) shl 24)
                if (titleLength < 0 || 4 + titleLength > packed.size) continue
                listener?.onNotification(
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
            if (progress != null && progress.size == 2) {
                progressState = progress[0]
                progressPercent = progress[1]
                listener?.onProgressChanged(this)
            }
        }
    }

    // OSC 7 delivers a file:// URI; OSC 9 / OSC 1337 deliver a bare path.
    private fun pwdToPath(raw: String): String? {
        if (raw.isEmpty()) return null
        if (!raw.startsWith("file://")) return raw
        val withoutScheme = raw.removePrefix("file://")
        val slash = withoutScheme.indexOf('/')
        if (slash < 0) return null
        return runCatching { java.net.URLDecoder.decode(withoutScheme.substring(slash), "UTF-8") }
            .getOrNull()
    }

    /**
     * Report the UI color scheme for CSI ? 996 n queries; sends the mode-2031
     * change report when the running program enabled it.
     */
    fun setColorScheme(dark: Boolean) {
        val report = withTerminal { GhosttyVt.nativeSetColorScheme(it, dark) }
        if (report != null && report.isNotEmpty()) sendRawInput(report)
    }

    // OSC 52 read: the remote program asked for the clipboard contents.
    // Denied (or view-less) requests answer with an empty payload so the
    // program completes instead of waiting.
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

    private fun readClipboardText(): String? = context.getSystemService(ClipboardManager::class.java)
        ?.primaryClip
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

    /** Report window focus to the remote shell when mode 1004 is set. */
    fun sendFocus(gained: Boolean) {
        if (isFinished) return
        val report = withTerminal { GhosttyVt.nativeEncodeFocus(it, gained) }
        if (report != null && report.isNotEmpty()) sendRawInput(report)
    }

    /** Send keyboard/IME text; LF becomes CR and bracketed-paste markers are stripped. */
    fun sendTypedInput(data: ByteArray) {
        transportSend(sanitizeInput(data))
    }

    /**
     * Send protocol bytes (key/mouse-encoder output, VT query responses,
     * encoded paste) byte-exact.
     */
    fun sendRawInput(data: ByteArray) {
        transportSend(data)
    }

    private fun transportSend(data: ByteArray) {
        val currentTransport = transport ?: return
        try {
            currentTransport.sendInput(data)
        } catch (e: Exception) {
            onTransportFailure("send input", e)
        }
    }

    private fun onTransportFailure(operation: String, e: Exception) {
        Log.e(TAG, "transport $operation failed", e)
        if (!isFinished) {
            finish(-1, null, e.message ?: "transport failed")
        }
    }

    fun resize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        // A pixel-only change (font-size change landing on the same grid)
        // still needs a window change: programs derive the cell pixel size
        // for Kitty graphics from the pixel fields of TIOCGWINSZ.
        val changed = columns != lastColumns || rows != lastRows ||
            cellWidthPixels != lastCellWidthPixels || cellHeightPixels != lastCellHeightPixels
        lastColumns = columns
        lastRows = rows
        lastCellWidthPixels = cellWidthPixels
        lastCellHeightPixels = cellHeightPixels
        withTerminal { GhosttyVt.nativeResize(it, columns, rows, cellWidthPixels, cellHeightPixels) }
        if (!changed) return
        val currentTransport = transport ?: return
        try {
            currentTransport.sendResize(columns, rows, columns * cellWidthPixels, rows * cellHeightPixels)
        } catch (e: Exception) {
            onTransportFailure("send resize", e)
        }
    }

    fun setTheme(theme: GhosttyTheme) {
        if (handle == 0L) return
        selectionBackground = theme.selectionBackground
            ?.let { GhosttyVt.nativeParseColor(it) }
            ?.takeIf { it != 0 }
        selectionForeground = theme.selectionForeground
            ?.let { GhosttyVt.nativeParseColor(it) }
            ?.takeIf { it != 0 }
        withTerminal {
            GhosttyVt.nativeSetTheme(it, theme.foreground, theme.background, theme.cursorColor, theme.palette)
        }
        attachedView?.onSessionUpdated()
    }

    /**
     * Mark the host-managed process as exited: an exit notice is written into
     * the terminal and [Listener.onFinished] fires. The session stays
     * displayable until [close].
     */
    fun finish(exitCode: Int = 0, signal: String? = null, errorMessage: String? = null) {
        this.exitCode = exitCode
        isFinished = true
        mainHandler.post {
            val exitText = buildString {
                append("\r\n[")
                append(context.getString(R.string.ghostty_session_ended))
                if (!errorMessage.isNullOrEmpty()) {
                    append(": ").append(errorMessage)
                } else if (exitCode != 0) {
                    append(" (exit ").append(exitCode).append(")")
                }
                if (!signal.isNullOrEmpty()) {
                    append(" (signal ").append(signal).append(")")
                }
                append(" - ")
                append(context.getString(R.string.ghostty_press_any_key))
                append("]")
            }
            if (withTerminal { GhosttyVt.nativeWrite(it, exitText.toByteArray()) } != null) {
                attachedView?.onSessionOutput()
            }
            listener?.onFinished(this)
        }
    }

    fun close() {
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
    }
}
