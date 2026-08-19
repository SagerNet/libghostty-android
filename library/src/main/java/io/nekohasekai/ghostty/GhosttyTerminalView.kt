package io.nekohasekai.ghostty

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.util.AttributeSet
import android.util.Log
import android.util.LruCache
import android.util.TypedValue
import android.view.ActionMode
import android.view.Choreographer
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Magnifier
import android.widget.OverScroller
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import android.provider.Settings as AndroidSettings

class GhosttyTerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onFontSizeChanged(view: GhosttyTerminalView, fontSizeSp: Float) {}

        fun onFinishedSessionKey(view: GhosttyTerminalView) {}
    }

    var listener: Listener? = null

    var extraKeysState: TerminalExtraKeysState? = null

    var uiHandler: TerminalUiHandler = object : TerminalUiHandler {}

    var session: GhosttyTerminalSession? = null
        set(value) {
            if (field === value) return
            if (selectionActive || actionMode != null) {
                clearSelection()
            }
            field?.detach(this)
            kittyPlacements = emptyList()
            kittyImageCache.evictAll()
            composingText = null
            passwordInput = false
            field = value
            value?.attach(this)
            if (value != null) {
                updateGridGeometry(force = true)
            }
            scheduleFrame()
        }

    var typeface: Typeface = Typeface.MONOSPACE
        set(value) {
            if (field == value) return
            field = value
            rebuildPaints()
            if (selectionActive || actionMode != null) clearSelection()
            updateGridGeometry(force = true)
        }

    var fontSizeSp = DEFAULT_FONT_SIZE_SP
        set(value) {
            val clamped = value.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
            if (clamped == field) return
            field = clamped
            rebuildPaints()
            if (selectionActive || actionMode != null) clearSelection()
            updateGridGeometry(force = true)
        }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val italicPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boldItalicPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint()
    private val cursorPaint = Paint()
    private var cellWidth = 1f
    private var cellHeight = 1
    private var baseline = 0f
    private val underlineThickness = max(1f, resources.displayMetrics.density)

    private var cols = 80
    private var rows = 24
    private var bitmap: Bitmap? = null
    private var bitmapCanvas: Canvas? = null
    private var snapshotBuffer: ByteBuffer? = null

    private class KittyImagePlacement(
        val bitmap: Bitmap,
        val source: Rect,
        val dest: RectF,
        val belowText: Boolean,
    )

    private var kittyPlacements: List<KittyImagePlacement> = emptyList()
    private val kittyImagePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val kittyImageCache = object : LruCache<Long, Bitmap>(KITTY_IMAGE_CACHE_BYTES) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount
    }

    private var gridForeground = IntArray(0)
    private var gridBackground = IntArray(0)
    private var gridTextOffset = IntArray(0)
    private var gridTextLength = IntArray(0)
    private var gridFlags = IntArray(0)
    private var gridRowChars = emptyArray<CharArray>()
    private var rowSelectionStart = IntArray(0)
    private var rowSelectionEnd = IntArray(0)
    private var rowSelectionEndWide = BooleanArray(0)

    private var selectionActive = false
    private var actionMode: ActionMode? = null
    private var draggingHandle = HANDLE_NONE
    private var dragAnchorCol = 0
    private var dragAnchorRow = 0
    private var dragBiasRows = 0
    private var dragPastSlop = false
    private var longPressX = 0f
    private var longPressY = 0f
    private var lastPressCol = 0
    private var lastPressRow = 0
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val handleDrawableStart = resolveHandleDrawable(android.R.attr.textSelectHandleLeft)
    private val handleDrawableEnd = resolveHandleDrawable(android.R.attr.textSelectHandleRight)
    private val magnifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Magnifier(this) else null

    private fun showMagnifier(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) magnifier?.show(x, y)
    }

    private fun dismissMagnifier() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) magnifier?.dismiss()
    }

    private var defaultBackground = 0xFF000000.toInt()
    private var defaultForeground = 0xFFFFFFFF.toInt()
    private var cursorColor = 0
    private var cursorX = -1
    private var cursorY = -1
    private var cursorStyle = CURSOR_STYLE_BLOCK
    private var cursorVisible = false
    private var cursorBlinks = false

    private var combiningAccent = 0
    private var secondaryMouseButton = 0

    private val accessibilityManager = context.getSystemService(AccessibilityManager::class.java)
    private var accessibilityTextPending = false
    private val accessibilityTextRunnable = Runnable {
        accessibilityTextPending = false
        if (accessibilityManager?.isEnabled == true) {
            session?.withTerminal { handle ->
                contentDescription = GhosttyVt.nativeViewportText(handle)?.toString(Charsets.UTF_8)
            }
        }
    }

    private var animatorDurationScale = 1f

    private var blinkPhaseOn = true
    private var blinkScheduled = false
    private val blinkRunnable = object : Runnable {
        override fun run() {
            if (!blinkScheduled) return
            blinkPhaseOn = !blinkPhaseOn
            invalidate()
            postDelayed(this, BLINK_INTERVAL_MS)
        }
    }

    private var framePending = false
    private val frameCallback = Choreographer.FrameCallback {
        framePending = false
        renderFrame()
    }
    private val syncRetryRunnable = Runnable { scheduleFrame() }

    private var passwordInput = false
    private var composingText: String? = null

    private var scrollAccumulator = 0f
    private val scroller = OverScroller(context)
    private var flingLastY = 0
    private var gestureDownCol = 0
    private var gestureDownRow = 0
    private var scrollbarTotal = 0L
    private var scrollbarOffset = 0L
    private var scrollbarLen = 0L
    private var lastScrollActivityAt = 0L
    private val scrollbarPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val jumpChipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val jumpChipArrow = Path()
    private val underlinePath = Path()
    private var jumpChipCenterX = 0f
    private var jumpChipCenterY = 0f
    private val flingRunnable = object : Runnable {
        override fun run() {
            if (!scroller.computeScrollOffset()) return
            val y = scroller.currY
            scrollAccumulator += (y - flingLastY).toFloat()
            flingLastY = y
            val deltaRows = (scrollAccumulator / cellHeight).toInt()
            if (deltaRows != 0) {
                scrollAccumulator -= deltaRows * cellHeight
                session?.withTerminal {
                    scrollContent(it, deltaRows, gestureDownCol, gestureDownRow)
                } ?: return
            }
            postOnAnimation(this)
        }
    }

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val handled = session?.withTerminal { handle ->
                    if (scrollbarTotal > 0 && scrollbarOffset + scrollbarLen < scrollbarTotal) {
                        val slop = JUMP_CHIP_RADIUS_DP * JUMP_CHIP_TAP_SLOP_SCALE * resources.displayMetrics.density
                        if (hypot(e.x - jumpChipCenterX, e.y - jumpChipCenterY) <= slop) {
                            GhosttyVt.nativeScrollToBottom(handle)
                            scheduleFrame()
                            return@withTerminal true
                        }
                    }
                    if (selectionActive || actionMode != null) {
                        clearSelection()
                        return@withTerminal true
                    }
                    if (GhosttyVt.nativeViewportState(handle) and GhosttyVt.VIEWPORT_MOUSE_TRACKING != 0) {
                        val col = (e.x / cellWidth).toInt().coerceIn(0, cols - 1)
                        val row = (e.y / cellHeight).toInt().coerceIn(0, rows - 1)
                        sendMouseReport(handle, GhosttyVt.MOUSE_ACTION_PRESS, GhosttyVt.MOUSE_BUTTON_LEFT, col, row)
                        sendMouseReport(handle, GhosttyVt.MOUSE_ACTION_RELEASE, GhosttyVt.MOUSE_BUTTON_LEFT, col, row)
                        return@withTerminal true
                    }
                    false
                } ?: false
                if (handled) return true
                requestFocus()
                context.getSystemService(InputMethodManager::class.java)
                    ?.showSoftInput(this@GhosttyTerminalView, 0)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (scaleDetector.isInProgress) return
                session?.withTerminal { handle ->
                    lastPressCol = (e.x / cellWidth).toInt().coerceIn(0, cols - 1)
                    lastPressRow = (e.y / cellHeight).toInt().coerceIn(0, rows - 1)
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    val link = GhosttyVt.nativeHyperlinkAt(handle, lastPressCol, lastPressRow)
                        ?.toString(Charsets.UTF_8)
                        ?: detectUrlAt(lastPressRow, lastPressCol)
                    if (link != null) {
                        showLinkMenu(link)
                        return@withTerminal
                    }
                    selectionActive = GhosttyVt.nativeSelectWord(handle, lastPressCol, lastPressRow)
                    scheduleFrame()
                    if (selectionActive) {
                        draggingHandle = HANDLE_END
                        dragAnchorCol = lastPressCol
                        dragAnchorRow = lastPressRow
                        dragBiasRows = 0
                        dragPastSlop = false
                        longPressX = e.x
                        longPressY = e.y
                    } else {
                        startTerminalActionMode()
                    }
                }
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (scaleDetector.isInProgress) return false
                return session?.withTerminal { handle ->
                    if (e2.isFromSource(InputDevice.SOURCE_MOUSE) &&
                        GhosttyVt.nativeViewportState(handle) and GhosttyVt.VIEWPORT_MOUSE_TRACKING != 0
                    ) {
                        val col = (e2.x / cellWidth).toInt().coerceIn(0, cols - 1)
                        val row = (e2.y / cellHeight).toInt().coerceIn(0, rows - 1)
                        sendMouseReport(handle, GhosttyVt.MOUSE_ACTION_MOTION, GhosttyVt.MOUSE_BUTTON_LEFT, col, row)
                        return@withTerminal true
                    }
                    scrollAccumulator += distanceY
                    val deltaRows = (scrollAccumulator / cellHeight).toInt()
                    if (deltaRows != 0) {
                        scrollAccumulator -= deltaRows * cellHeight
                        scrollContent(handle, deltaRows, gestureDownCol, gestureDownRow)
                    }
                    true
                } ?: false
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                if (scaleDetector.isInProgress) return false
                val state = session?.withTerminal { GhosttyVt.nativeViewportState(it) } ?: return false
                flingLastY = 0
                if (state and (GhosttyVt.VIEWPORT_ALTERNATE_SCREEN or GhosttyVt.VIEWPORT_MOUSE_TRACKING) != 0) {
                    val range = rows / 2 * cellHeight
                    scroller.fling(
                        0,
                        0,
                        0,
                        -(velocityY * FLING_TRACKING_VELOCITY_SCALE).toInt(),
                        0,
                        0,
                        -range,
                        range,
                    )
                } else {
                    scroller.fling(0, 0, 0, -velocityY.toInt(), 0, 0, Int.MIN_VALUE, Int.MAX_VALUE)
                }
                postOnAnimation(flingRunnable)
                return true
            }
        },
    )

    private var pinchScale = 1f
    private var pinchLastScale = 1f
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pinchScale = 1f
                pinchLastScale = 1f
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                pinchScale *= detector.scaleFactor
                val steps = ((pinchScale - pinchLastScale) / PINCH_SCALE_STEP).toInt()
                if (steps != 0) {
                    pinchLastScale += steps * PINCH_SCALE_STEP
                    fontSizeSp += steps
                    listener?.onFontSizeChanged(this@GhosttyTerminalView, fontSizeSp)
                }
                return true
            }
        },
    )

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        scaleDetector.isQuickScaleEnabled = false
        readAnimatorDurationScale()
        rebuildPaints()
    }

    private fun resolveHandleDrawable(attr: Int): Drawable? {
        val value = TypedValue()
        if (!context.theme.resolveAttribute(attr, value, true)) return null
        if (value.resourceId == 0) return null
        return context.getDrawable(value.resourceId)
    }

    private fun rebuildPaints() {
        textPaint.typeface = typeface
        boldPaint.typeface = Typeface.create(typeface, Typeface.BOLD)
        italicPaint.typeface = Typeface.create(typeface, Typeface.ITALIC)
        boldItalicPaint.typeface = Typeface.create(typeface, Typeface.BOLD_ITALIC)
        val sizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            fontSizeSp,
            resources.displayMetrics,
        )
        for (paint in arrayOf(textPaint, boldPaint, italicPaint, boldItalicPaint)) {
            paint.textSize = sizePx
        }
        cellWidth = textPaint.measureText("M")
        cellHeight = ceil(textPaint.fontMetrics.descent - textPaint.fontMetrics.ascent).toInt()
        baseline = -textPaint.fontMetrics.ascent
    }

    internal fun onSessionOutput() {
        scheduleFrame()
    }

    internal fun onSessionUpdated() {
        scheduleFrame()
    }

    private fun sendMouseReport(handle: Long, action: Int, button: Int, col: Int, row: Int) {
        val bytes = GhosttyVt.nativeEncodeMouse(handle, action, button, col, row, 0) ?: return
        session?.sendRawInput(bytes)
    }

    private fun scrollContent(handle: Long, deltaRows: Int, col: Int, row: Int) {
        val state = GhosttyVt.nativeViewportState(handle)
        if (state and GhosttyVt.VIEWPORT_MOUSE_TRACKING != 0) {
            val button = if (deltaRows > 0) GhosttyVt.MOUSE_BUTTON_WHEEL_DOWN else GhosttyVt.MOUSE_BUTTON_WHEEL_UP
            repeat(abs(deltaRows)) {
                sendMouseReport(handle, GhosttyVt.MOUSE_ACTION_PRESS, button, col, row)
            }
            return
        }
        if (state and GhosttyVt.VIEWPORT_ALTERNATE_SCREEN != 0) {
            val keyCode = if (deltaRows > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
            repeat(abs(deltaRows)) {
                val bytes = GhosttyVt.nativeEncodeKey(handle, keyCode, 1, 0, 0, false, null)
                if (bytes != null && bytes.isNotEmpty()) session?.sendRawInput(bytes)
            }
            return
        }
        GhosttyVt.nativeScroll(handle, deltaRows)
        lastScrollActivityAt = SystemClock.uptimeMillis()
        scheduleFrame()
    }

    private fun sendToSession(bytes: ByteArray, send: GhosttyTerminalSession.(ByteArray) -> Unit) {
        val activeSession = session ?: return
        if (bytes.isEmpty()) return
        if (activeSession.isFinished) {
            listener?.onFinishedSessionKey(this)
            return
        }
        if (selectionActive) clearSelection()
        if (scrollbarOffset + scrollbarLen < scrollbarTotal) {
            activeSession.withTerminal { handle ->
                GhosttyVt.nativeScrollToBottom(handle)
                scheduleFrame()
            }
        }
        holdBlinkSolid()
        activeSession.send(bytes)
    }

    fun sendText(text: CharSequence) {
        if (text.isEmpty()) return
        val meta = extraKeysState?.currentMetaState() ?: 0
        if (meta != 0 && text.length == 1) {
            val char = text[0]
            // ghostty's ctrlSeq (src/input/key_encode.zig) maps a one-byte
            // utf8 argument to its C0 byte when the logical key has none.
            val bytes = session?.withTerminal { handle ->
                GhosttyVt.nativeEncodeKey(
                    handle,
                    charToKeyCode(char),
                    1,
                    meta,
                    char.lowercaseChar().code,
                    false,
                    char.toString().toByteArray(Charsets.UTF_8),
                )
            }
            if (bytes != null && bytes.isNotEmpty()) {
                extraKeysState?.consumeModifiers()
                sendToSession(bytes, GhosttyTerminalSession::sendRawInput)
                return
            }
        }
        sendToSession(text.toString().toByteArray(Charsets.UTF_8), GhosttyTerminalSession::sendTypedInput)
    }

    fun sendKey(keyCode: Int) {
        val meta = extraKeysState?.currentMetaState() ?: 0
        val bytes = session?.withTerminal { handle ->
            GhosttyVt.nativeEncodeKey(handle, keyCode, 1, meta, 0, false, null)
        } ?: return
        if (bytes.isEmpty()) return
        if (meta != 0) extraKeysState?.consumeModifiers()
        sendToSession(bytes, GhosttyTerminalSession::sendRawInput)
    }

    private fun charToKeyCode(char: Char): Int {
        val lower = char.lowercaseChar()
        return when (lower) {
            in 'a'..'z' -> KeyEvent.KEYCODE_A + (lower - 'a')
            in '0'..'9' -> KeyEvent.KEYCODE_0 + (lower - '0')
            ' ' -> KeyEvent.KEYCODE_SPACE
            '[' -> KeyEvent.KEYCODE_LEFT_BRACKET
            ']' -> KeyEvent.KEYCODE_RIGHT_BRACKET
            '-' -> KeyEvent.KEYCODE_MINUS
            '=' -> KeyEvent.KEYCODE_EQUALS
            '\\' -> KeyEvent.KEYCODE_BACKSLASH
            '/' -> KeyEvent.KEYCODE_SLASH
            '.' -> KeyEvent.KEYCODE_PERIOD
            ',' -> KeyEvent.KEYCODE_COMMA
            ';' -> KeyEvent.KEYCODE_SEMICOLON
            '\'' -> KeyEvent.KEYCODE_APOSTROPHE
            '`' -> KeyEvent.KEYCODE_GRAVE
            else -> KeyEvent.KEYCODE_UNKNOWN
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val density = resources.displayMetrics.density
        jumpChipCenterX = w - JUMP_CHIP_CENTER_OFFSET_DP * density
        jumpChipCenterY = h - JUMP_CHIP_CENTER_OFFSET_DP * density
        updateGridGeometry(force = false)
    }

    private fun updateGridGeometry(force: Boolean) {
        val activeSession = session ?: return
        if (width <= 0 || height <= 0 || !activeSession.terminalAlive) return
        val newCols = max(2, floor(width / cellWidth).toInt())
        val newRows = max(2, height / cellHeight)
        val changed = newCols != cols || newRows != rows
        cols = newCols
        rows = newRows
        if (changed || force || bitmap == null) {
            activeSession.resize(cols, rows, cellWidth.roundToInt(), cellHeight)
            allocateGridBuffers()
        }
        scheduleFrame()
    }

    private fun allocateGridBuffers() {
        val widthPx = max(1, ceil(cols * cellWidth).toInt())
        val heightPx = max(1, rows * cellHeight)
        val currentBitmap = bitmap
        if (currentBitmap == null || currentBitmap.width != widthPx || currentBitmap.height != heightPx) {
            bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888).also {
                bitmapCanvas = Canvas(it)
                bitmapCanvas?.drawColor(defaultBackground)
            }
        }
        val capacity = HEADER_BYTES +
            rows * (ROW_HEADER_BYTES + cols * CELL_RECORD_BYTES + cols * TEXT_UNITS_PER_CELL * 2 + 4)
        if ((snapshotBuffer?.capacity() ?: 0) < capacity) {
            snapshotBuffer = ByteBuffer.allocateDirect(capacity).order(ByteOrder.LITTLE_ENDIAN)
        }
        if (gridForeground.size != rows * cols) {
            gridForeground = IntArray(rows * cols)
            gridBackground = IntArray(rows * cols)
            gridTextOffset = IntArray(rows * cols)
            gridTextLength = IntArray(rows * cols)
            gridFlags = IntArray(rows * cols)
        }
        if (gridRowChars.size != rows) {
            gridRowChars = Array(rows) { CharArray(0) }
        }
        if (rowSelectionStart.size != rows) {
            rowSelectionStart = IntArray(rows) { -1 }
            rowSelectionEnd = IntArray(rows) { -1 }
            rowSelectionEndWide = BooleanArray(rows)
        }
    }

    private fun scheduleFrame() {
        if (framePending || !isAttachedToWindow) return
        framePending = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun renderFrame() {
        val buffer = snapshotBuffer ?: return
        val canvas = bitmapCanvas ?: return
        session?.withTerminal { handle ->
            renderFrameLocked(handle, buffer, canvas)
        }
    }

    private fun renderFrameLocked(handle: Long, buffer: ByteBuffer, canvas: Canvas) {
        val rowCount = GhosttyVt.nativeSnapshot(handle, buffer)
        if (rowCount == SNAPSHOT_SYNC_DEFERRED) {
            removeCallbacks(syncRetryRunnable)
            postDelayed(syncRetryRunnable, SYNC_OUTPUT_RETRY_MS)
            return
        }
        if (rowCount < 0) return
        GhosttyVt.nativeScrollbar(handle)?.let { scrollbar ->
            scrollbarTotal = scrollbar[0]
            scrollbarOffset = scrollbar[1]
            scrollbarLen = scrollbar[2]
        }

        buffer.position(0)
        val version = buffer.int
        if (version != SNAPSHOT_VERSION) {
            Log.e(TAG, "snapshot version $version != $SNAPSHOT_VERSION; dropping frame")
            return
        }
        val dirtyKind = buffer.int
        val snapshotCols = buffer.int
        buffer.int
        defaultBackground = buffer.int
        defaultForeground = buffer.int
        cursorX = buffer.int
        cursorY = buffer.int
        cursorStyle = buffer.int
        cursorVisible = buffer.int != 0
        buffer.int
        cursorBlinks = buffer.int != 0
        cursorColor = buffer.int
        val truncated = buffer.int != 0
        passwordInput = buffer.int != 0
        syncBlinkTimer()

        if (dirtyKind == DIRTY_FULL) canvas.drawColor(defaultBackground)
        repeat(rowCount) { drawRowRecord(buffer, canvas, snapshotCols) }
        updateKittyPlacements(handle)
        syncSelectionUi()
        invalidate()

        if (accessibilityManager?.isEnabled == true && !accessibilityTextPending) {
            accessibilityTextPending = true
            postDelayed(accessibilityTextRunnable, ACCESSIBILITY_TEXT_DELAY_MS)
        }

        if (truncated) {
            snapshotBuffer = ByteBuffer.allocateDirect(buffer.capacity() * 2)
                .order(ByteOrder.LITTLE_ENDIAN)
            scheduleFrame()
        }
    }

    private fun drawRowRecord(buffer: ByteBuffer, canvas: Canvas, snapshotCols: Int) {
        val rowIndex = buffer.int
        val selectionStart = buffer.int
        val selectionEnd = buffer.int
        val textUnits = buffer.int
        val rowInGrid = rowIndex < rows
        if (rowInGrid) {
            rowSelectionStart[rowIndex] = selectionStart
            rowSelectionEnd[rowIndex] = selectionEnd
            rowSelectionEndWide[rowIndex] = false
        }
        val cellsPosition = buffer.position()
        val textPosition = cellsPosition + snapshotCols * CELL_RECORD_BYTES

        buffer.position(textPosition)
        if (rowInGrid && gridRowChars[rowIndex].size < textUnits) {
            gridRowChars[rowIndex] = CharArray(textUnits)
        }
        if (rowInGrid) {
            val chars = gridRowChars[rowIndex]
            for (i in 0 until textUnits) chars[i] = buffer.char
        }
        val nextRecord = textPosition + textUnits * 2 + if (textUnits % 2 != 0) 2 else 0

        val selectionBackground = session?.selectionBackground
        val selectionForeground = session?.selectionForeground

        buffer.position(cellsPosition)
        val colCount = if (rowInGrid) minOf(snapshotCols, cols) else 0
        val gridBase = rowIndex * cols
        for (col in 0 until snapshotCols) {
            val foreground = buffer.int
            val background = buffer.int
            val textOffset = buffer.short.toInt() and 0xFFFF
            val textLength = buffer.short.toInt() and 0xFFFF
            val flags = buffer.short.toInt() and 0xFFFF
            val underlineStyle = buffer.short.toInt() and 0x7
            if (col >= colCount) continue
            if (col == selectionEnd && flags and FLAG_WIDE != 0) {
                rowSelectionEndWide[rowIndex] = true
            }
            var effectiveForeground = if (flags and FLAG_FG_DEFAULT != 0) defaultForeground else foreground
            var effectiveBackground = if (flags and FLAG_BG_NONE != 0) defaultBackground else background
            if (flags and FLAG_INVERSE != 0) {
                val swap = effectiveForeground
                effectiveForeground = effectiveBackground
                effectiveBackground = swap
            }
            if (selectionStart >= 0 && col >= selectionStart && col <= selectionEnd) {
                if (selectionBackground != null) {
                    effectiveBackground = selectionBackground
                    if (selectionForeground != null) effectiveForeground = selectionForeground
                } else {
                    val swap = effectiveForeground
                    effectiveForeground = effectiveBackground
                    effectiveBackground = swap
                }
            }
            gridForeground[gridBase + col] = effectiveForeground
            gridBackground[gridBase + col] = effectiveBackground
            gridTextOffset[gridBase + col] = textOffset
            gridTextLength[gridBase + col] = textLength
            gridFlags[gridBase + col] = flags or (underlineStyle shl 16)
        }
        buffer.position(nextRecord)
        if (!rowInGrid) return

        val top = (rowIndex * cellHeight).toFloat()
        val bottom = top + cellHeight

        fillPaint.color = defaultBackground
        canvas.drawRect(0f, top, canvas.width.toFloat(), bottom, fillPaint)
        for (col in 0 until colCount) {
            val background = gridBackground[gridBase + col]
            if (background == defaultBackground) continue
            val cellSpan = if (gridFlags[gridBase + col] and FLAG_WIDE != 0) cellWidth * 2 else cellWidth
            val x = col * cellWidth
            fillPaint.color = background
            canvas.drawRect(x, top, x + cellSpan, bottom, fillPaint)
        }

        val rowChars = gridRowChars[rowIndex]
        for (col in 0 until colCount) {
            val flags = gridFlags[gridBase + col]
            val textLength = gridTextLength[gridBase + col]
            if (textLength == 0 || flags and FLAG_SPACER != 0 || flags and FLAG_INVISIBLE != 0) continue
            val paint = stylePaint(flags)
            paint.color = gridForeground[gridBase + col]
            if (flags and FLAG_FAINT != 0) paint.alpha = FAINT_ALPHA
            val x = col * cellWidth
            canvas.drawText(rowChars, gridTextOffset[gridBase + col], textLength, x, top + baseline, paint)
            val runWidth = if (flags and FLAG_WIDE != 0) cellWidth * 2 else cellWidth
            if (flags and FLAG_UNDERLINE != 0) {
                drawUnderline(canvas, x, bottom, runWidth, flags ushr 16, paint)
            }
            if (flags and FLAG_STRIKETHROUGH != 0) {
                val middle = top + cellHeight / 2f
                canvas.drawRect(
                    x,
                    middle - underlineThickness / 2,
                    x + runWidth,
                    middle + underlineThickness / 2,
                    paint,
                )
            }
        }
    }

    private fun updateKittyPlacements(handle: Long) {
        val data = GhosttyVt.nativeKittyPlacements(handle)
        if (data == null) {
            if (kittyPlacements.isNotEmpty()) kittyPlacements = emptyList()
            return
        }
        val list = ArrayList<KittyImagePlacement>(data.size / GhosttyVt.KITTY_PLACEMENT_LONGS)
        var i = 0
        while (i + GhosttyVt.KITTY_PLACEMENT_LONGS <= data.size) {
            val imageBitmap = kittyImageBitmap(handle, data[i].toInt(), data[i + 1])
            if (imageBitmap != null) {
                val left = data[i + 2] * cellWidth + data[i + 4]
                val top = (data[i + 3] * cellHeight + data[i + 5]).toFloat()
                val sourceX = data[i + 8].toInt()
                val sourceY = data[i + 9].toInt()
                list.add(
                    KittyImagePlacement(
                        imageBitmap,
                        Rect(sourceX, sourceY, sourceX + data[i + 10].toInt(), sourceY + data[i + 11].toInt()),
                        RectF(left, top, left + data[i + 6], top + data[i + 7]),
                        data[i + 12] < 0,
                    ),
                )
            }
            i += GhosttyVt.KITTY_PLACEMENT_LONGS
        }
        kittyPlacements = list
    }

    private fun kittyImageBitmap(handle: Long, imageId: Int, generation: Long): Bitmap? {
        kittyImageCache.get(generation)?.let { return it }
        val pixels = GhosttyVt.nativeKittyImage(handle, imageId) ?: return null
        if (pixels.size < 2) return null
        val width = pixels[0]
        val height = pixels[1]
        if (width <= 0 || height <= 0 || pixels.size < 2 + width * height) return null
        val imageBitmap = Bitmap.createBitmap(pixels, 2, width, width, height, Bitmap.Config.ARGB_8888)
        kittyImageCache.put(generation, imageBitmap)
        return imageBitmap
    }

    private fun drawKittyImages(canvas: Canvas, belowText: Boolean) {
        if (kittyPlacements.none { it.belowText == belowText }) return
        val saved = canvas.save()
        canvas.clipRect(0, 0, ceil(cols * cellWidth).toInt(), rows * cellHeight)
        for (placement in kittyPlacements) {
            if (placement.belowText != belowText) continue
            canvas.drawBitmap(placement.bitmap, placement.source, placement.dest, kittyImagePaint)
        }
        canvas.restoreToCount(saved)
    }

    private fun overdrawGlyphsForBelowTextImages(canvas: Canvas) {
        if (gridForeground.size != rows * cols) return
        for (placement in kittyPlacements) {
            if (!placement.belowText) continue
            val dest = placement.dest
            val startRow = floor(dest.top / cellHeight).toInt().coerceIn(0, rows - 1)
            val endRow = floor((dest.bottom - 1) / cellHeight).toInt().coerceIn(0, rows - 1)
            val startCol = floor(dest.left / cellWidth).toInt().coerceIn(0, cols - 1)
            val endCol = floor((dest.right - 1) / cellWidth).toInt().coerceIn(0, cols - 1)
            for (row in startRow..endRow) {
                val rowChars = gridRowChars[row]
                val gridBase = row * cols
                val top = (row * cellHeight).toFloat()
                for (col in startCol..endCol) {
                    val flags = gridFlags[gridBase + col]
                    val textLength = gridTextLength[gridBase + col]
                    if (textLength == 0 || flags and FLAG_SPACER != 0 || flags and FLAG_INVISIBLE != 0) continue
                    val textOffset = gridTextOffset[gridBase + col]
                    if (textOffset + textLength > rowChars.size) continue
                    val paint = stylePaint(flags)
                    paint.color = gridForeground[gridBase + col]
                    if (flags and FLAG_FAINT != 0) paint.alpha = FAINT_ALPHA
                    canvas.drawText(rowChars, textOffset, textLength, col * cellWidth, top + baseline, paint)
                }
            }
        }
    }

    private fun stylePaint(flags: Int): Paint = when {
        flags and FLAG_BOLD != 0 && flags and FLAG_ITALIC != 0 -> boldItalicPaint
        flags and FLAG_BOLD != 0 -> boldPaint
        flags and FLAG_ITALIC != 0 -> italicPaint
        else -> textPaint
    }

    private fun drawUnderline(canvas: Canvas, x: Float, bottom: Float, runWidth: Float, style: Int, paint: Paint) {
        val thickness = underlineThickness
        when (style) {
            UNDERLINE_DOUBLE -> {
                canvas.drawRect(x, bottom - 3 * thickness, x + runWidth, bottom - 2 * thickness, paint)
                canvas.drawRect(x, bottom - thickness, x + runWidth, bottom, paint)
            }
            UNDERLINE_CURLY -> {
                val amplitude = max(2f, thickness * 1.5f)
                val halfPeriod = max(2f, thickness * 2)
                underlinePath.reset()
                underlinePath.moveTo(x, bottom - thickness / 2)
                var waveX = x
                var crest = true
                while (waveX < x + runWidth) {
                    waveX = minOf(waveX + halfPeriod, x + runWidth)
                    underlinePath.lineTo(waveX, bottom - thickness / 2 - if (crest) amplitude else 0f)
                    crest = !crest
                }
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = thickness
                canvas.drawPath(underlinePath, paint)
                paint.style = Paint.Style.FILL
            }
            UNDERLINE_DOTTED -> {
                val step = thickness * 3
                var dotX = x
                while (dotX < x + runWidth) {
                    canvas.drawRect(dotX, bottom - thickness, minOf(dotX + thickness, x + runWidth), bottom, paint)
                    dotX += step
                }
            }
            UNDERLINE_DASHED -> {
                val dash = runWidth / 4
                var dashX = x
                while (dashX < x + runWidth) {
                    canvas.drawRect(dashX, bottom - thickness, minOf(dashX + dash, x + runWidth), bottom, paint)
                    dashX += dash * 2
                }
            }
            else -> canvas.drawRect(x, bottom - thickness, x + runWidth, bottom, paint)
        }
    }

    private data class SelectionBounds(
        val top: Int,
        val topCol: Int,
        val bottom: Int,
        val bottomCol: Int,
    )

    private fun selectionBounds(): SelectionBounds? {
        var top = -1
        var bottom = -1
        for (row in rowSelectionStart.indices) {
            if (rowSelectionStart[row] >= 0) {
                if (top < 0) top = row
                bottom = row
            }
        }
        if (top < 0) return null
        return SelectionBounds(top, rowSelectionStart[top], bottom, rowSelectionEnd[bottom])
    }

    private fun selectionEndExtraCols(row: Int): Int = if (row < rowSelectionEndWide.size && rowSelectionEndWide[row]) 1 else 0

    private fun syncSelectionUi() {
        if (!selectionActive) return
        if (selectionBounds() == null) {
            clearSelection()
        } else {
            actionMode?.invalidateContentRect()
        }
    }

    private fun clearSelection() {
        session?.withTerminal { GhosttyVt.nativeClearSelection(it) }
        selectionActive = false
        draggingHandle = HANDLE_NONE
        actionMode?.finish()
        scheduleFrame()
    }

    private fun selectAllContent() {
        val selected = session?.withTerminal { GhosttyVt.nativeSelectAll(it) } ?: return
        if (selected) {
            selectionActive = true
            scheduleFrame()
            actionMode?.invalidate()
        }
    }

    private fun copySelection() {
        val bytes = session?.withTerminal { GhosttyVt.nativeSelectionText(it) } ?: return
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(null, String(bytes, Charsets.UTF_8)))
        clearSelection()
    }

    fun pasteFromClipboard() {
        val activeSession = session ?: return
        if (!activeSession.terminalAlive) return
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = clipboard.primaryClip?.takeIf { it.itemCount > 0 } ?: return
        val text = clip.getItemAt(0).coerceToText(context)?.toString()
        if (text.isNullOrEmpty()) return
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (GhosttyVt.nativeIsPasteSafe(bytes)) {
            sendPaste(bytes)
            return
        }
        uiHandler.confirmUnsafePaste(context) { sendPaste(bytes) }
    }

    private fun sendPaste(bytes: ByteArray) {
        val encoded = session?.withTerminal { GhosttyVt.nativeEncodePaste(it, bytes) } ?: return
        if (selectionActive) clearSelection() else actionMode?.finish()
        sendToSession(encoded, GhosttyTerminalSession::sendRawInput)
    }

    private fun startTerminalActionMode() {
        val current = actionMode
        if (current != null) {
            current.invalidate()
            current.invalidateContentRect()
            return
        }
        actionMode = startActionMode(
            object : ActionMode.Callback2() {
                override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                    menu.add(Menu.NONE, MENU_COPY, 0, android.R.string.copy)
                    menu.add(Menu.NONE, MENU_PASTE, 1, android.R.string.paste)
                    menu.add(Menu.NONE, MENU_SELECT_ALL, 2, android.R.string.selectAll)
                    return true
                }

                override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                    menu.findItem(MENU_COPY)?.isVisible = selectionActive
                    return true
                }

                override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                    when (item.itemId) {
                        MENU_COPY -> copySelection()
                        MENU_PASTE -> pasteFromClipboard()
                        MENU_SELECT_ALL -> selectAllContent()
                        else -> return false
                    }
                    return true
                }

                override fun onDestroyActionMode(mode: ActionMode) {
                    actionMode = null
                }

                override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                    outRect.set(selectionContentRect())
                }
            },
            ActionMode.TYPE_FLOATING,
        )
    }

    private fun selectionContentRect(): Rect {
        val bounds = selectionBounds()
        return if (bounds != null) {
            val singleRow = bounds.top == bounds.bottom
            Rect(
                if (singleRow) (bounds.topCol * cellWidth).toInt() else 0,
                bounds.top * cellHeight,
                if (singleRow) {
                    ((bounds.bottomCol + 1 + selectionEndExtraCols(bounds.bottom)) * cellWidth).toInt()
                } else {
                    width
                },
                (bounds.bottom + 1) * cellHeight,
            )
        } else {
            Rect(
                (lastPressCol * cellWidth).toInt(),
                lastPressRow * cellHeight,
                ((lastPressCol + 1) * cellWidth).toInt(),
                (lastPressRow + 1) * cellHeight,
            )
        }
    }

    private fun hitTestHandle(x: Float, y: Float): Int {
        val bounds = selectionBounds() ?: return HANDLE_NONE
        val slop = HANDLE_HIT_SLOP_DP * resources.displayMetrics.density
        val handleHalfHeight = (handleDrawableEnd?.intrinsicHeight ?: 0) / 2f
        val startX = bounds.topCol * cellWidth
        val startY = (bounds.top + 1) * cellHeight + handleHalfHeight
        val endX = (bounds.bottomCol + 1 + selectionEndExtraCols(bounds.bottom)) * cellWidth
        val endY = (bounds.bottom + 1) * cellHeight + handleHalfHeight
        val distanceToStart = hypot(x - startX, y - startY)
        val distanceToEnd = hypot(x - endX, y - endY)
        return when {
            distanceToEnd <= slop && distanceToEnd <= distanceToStart -> {
                dragAnchorCol = bounds.topCol
                dragAnchorRow = bounds.top
                dragBiasRows = 1
                HANDLE_END
            }
            distanceToStart <= slop -> {
                dragAnchorCol = bounds.bottomCol
                dragAnchorRow = bounds.bottom
                dragBiasRows = 1
                HANDLE_START
            }
            else -> HANDLE_NONE
        }
    }

    private fun dragSelection(x: Float, y: Float) {
        session?.withTerminal { handle ->
            val col = (x / cellWidth).toInt().coerceIn(0, cols - 1)
            val row = ((y / cellHeight).toInt() - dragBiasRows).coerceIn(0, rows - 1)
            val edgeDelta = when {
                row == 0 -> -1
                row == rows - 1 -> 1
                else -> 0
            }
            if (edgeDelta != 0) {
                val before = GhosttyVt.nativeScrollbar(handle)?.get(1)
                GhosttyVt.nativeScroll(handle, edgeDelta)
                val after = GhosttyVt.nativeScrollbar(handle)?.get(1)
                if (after != null && after != before) {
                    dragAnchorRow = (dragAnchorRow - edgeDelta).coerceIn(0, rows - 1)
                    lastScrollActivityAt = SystemClock.uptimeMillis()
                }
            }
            GhosttyVt.nativeSetSelection(handle, dragAnchorCol, dragAnchorRow, col, row)
        } ?: return
        actionMode?.hide(ACTION_MODE_DRAG_HIDE_MS)
        showMagnifier(x, y)
        scheduleFrame()
    }

    private fun endHandleDrag() {
        draggingHandle = HANDLE_NONE
        dismissMagnifier()
        val current = actionMode
        if (current != null) {
            current.invalidateContentRect()
        } else {
            startTerminalActionMode()
        }
    }

    private fun drawSelectionHandles(canvas: Canvas) {
        if (!selectionActive) return
        val bounds = selectionBounds() ?: return
        val startDrawable = handleDrawableStart
        val endDrawable = handleDrawableEnd
        if (startDrawable == null || endDrawable == null) return
        val startX = bounds.topCol * cellWidth
        val startTop = (bounds.top + 1) * cellHeight
        // Platform handle hotspots: 3/4 of the width for the left handle,
        // 1/4 for the right (frameworks/base Editor.HandleView).
        val startLeft = (startX - startDrawable.intrinsicWidth * 3 / 4f).toInt()
        startDrawable.setBounds(
            startLeft,
            startTop,
            startLeft + startDrawable.intrinsicWidth,
            startTop + startDrawable.intrinsicHeight,
        )
        startDrawable.draw(canvas)
        val endX = (bounds.bottomCol + 1 + selectionEndExtraCols(bounds.bottom)) * cellWidth
        val endTop = (bounds.bottom + 1) * cellHeight
        val endLeft = (endX - endDrawable.intrinsicWidth / 4f).toInt()
        endDrawable.setBounds(
            endLeft,
            endTop,
            endLeft + endDrawable.intrinsicWidth,
            endTop + endDrawable.intrinsicHeight,
        )
        endDrawable.draw(canvas)
    }

    private fun readAnimatorDurationScale() {
        animatorDurationScale = AndroidSettings.Global.getFloat(
            context.contentResolver,
            AndroidSettings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
    }

    private fun wantsBlink(): Boolean = cursorBlinks && cursorVisible && isAttachedToWindow && hasWindowFocus() &&
        animatorDurationScale != 0f

    private fun syncBlinkTimer() {
        val wants = wantsBlink()
        if (wants && !blinkScheduled) {
            blinkScheduled = true
            postDelayed(blinkRunnable, BLINK_INTERVAL_MS)
        } else if (!wants && blinkScheduled) {
            blinkScheduled = false
            removeCallbacks(blinkRunnable)
            if (!blinkPhaseOn) {
                blinkPhaseOn = true
                invalidate()
            }
        }
    }

    private fun holdBlinkSolid() {
        if (!blinkPhaseOn) {
            blinkPhaseOn = true
            invalidate()
        }
        if (blinkScheduled) {
            removeCallbacks(blinkRunnable)
            postDelayed(blinkRunnable, BLINK_INTERVAL_MS)
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        readAnimatorDurationScale()
        syncBlinkTimer()
        session?.sendFocus(hasWindowFocus)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncBlinkTimer()
        scheduleFrame()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        syncBlinkTimer()
        if (framePending) {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            framePending = false
        }
        scroller.abortAnimation()
        removeCallbacks(flingRunnable)
        removeCallbacks(syncRetryRunnable)
        removeCallbacks(accessibilityTextRunnable)
        accessibilityTextPending = false
        dismissMagnifier()
        actionMode?.finish()
    }

    override fun isOpaque(): Boolean = true

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(defaultBackground)
        bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        drawKittyImages(canvas, belowText = true)
        overdrawGlyphsForBelowTextImages(canvas)
        drawKittyImages(canvas, belowText = false)
        drawCursor(canvas)
        drawComposingText(canvas)
        drawPasswordIndicator(canvas)
        drawSelectionHandles(canvas)
        drawScrollIndicator(canvas)
        drawJumpToBottomChip(canvas)
    }

    private fun drawComposingText(canvas: Canvas) {
        val composing = composingText
        if (composing.isNullOrEmpty() || cursorX < 0 || cursorY < 0) return
        val textWidth = textPaint.measureText(composing)
        var left = cursorX * cellWidth
        val viewRight = cols * cellWidth
        if (left + textWidth > viewRight) left = max(0f, viewRight - textWidth)
        val top = (cursorY * cellHeight).toFloat()
        fillPaint.color = defaultForeground
        canvas.drawRect(left, top, left + textWidth, top + cellHeight, fillPaint)
        textPaint.color = defaultBackground
        canvas.drawText(composing, left, top + baseline, textPaint)
    }

    private fun drawPasswordIndicator(canvas: Canvas) {
        if (!passwordInput || cursorX < 0 || cursorY < 0) return
        val size = cellHeight * 0.5f
        var left = (cursorX + 1) * cellWidth + size * 0.25f
        if (left + size > cols * cellWidth) left = max(0f, cursorX * cellWidth - size * 1.25f)
        val top = cursorY * cellHeight + (cellHeight - size) / 2f
        cursorPaint.color = if (cursorColor != 0) cursorColor else defaultForeground
        cursorPaint.style = Paint.Style.STROKE
        cursorPaint.strokeWidth = underlineThickness
        val shackleRadius = size * 0.28f
        canvas.drawArc(
            left + size / 2 - shackleRadius,
            top,
            left + size / 2 + shackleRadius,
            top + shackleRadius * 2,
            180f,
            180f,
            false,
            cursorPaint,
        )
        cursorPaint.style = Paint.Style.FILL
        canvas.drawRoundRect(
            left,
            top + shackleRadius,
            left + size,
            top + size,
            underlineThickness,
            underlineThickness,
            cursorPaint,
        )
    }

    private fun drawScrollIndicator(canvas: Canvas) {
        if (scrollbarTotal <= scrollbarLen) return
        val elapsed = SystemClock.uptimeMillis() - lastScrollActivityAt
        if (elapsed > SCROLLBAR_FADE_END_MS) return
        val alpha = if (elapsed <= SCROLLBAR_FADE_START_MS) {
            255
        } else {
            (255 * (SCROLLBAR_FADE_END_MS - elapsed) / (SCROLLBAR_FADE_END_MS - SCROLLBAR_FADE_START_MS)).toInt()
        }
        val density = resources.displayMetrics.density
        val barWidth = 3 * density
        val right = width - 2 * density
        val trackHeight = height.toFloat()
        val top = trackHeight * scrollbarOffset / scrollbarTotal
        val thumbHeight = max(16 * density, trackHeight * scrollbarLen / scrollbarTotal)
        scrollbarPaint.color = SCROLLBAR_COLOR
        scrollbarPaint.alpha = alpha
        canvas.drawRoundRect(
            right - barWidth,
            top,
            right,
            (top + thumbHeight).coerceAtMost(trackHeight),
            barWidth / 2,
            barWidth / 2,
            scrollbarPaint,
        )
        if (elapsed > SCROLLBAR_FADE_START_MS) invalidate()
    }

    private fun drawJumpToBottomChip(canvas: Canvas) {
        if (scrollbarTotal <= 0 || scrollbarOffset + scrollbarLen >= scrollbarTotal) return
        val density = resources.displayMetrics.density
        val radius = JUMP_CHIP_RADIUS_DP * density
        jumpChipPaint.style = Paint.Style.FILL
        jumpChipPaint.color = JUMP_CHIP_BACKGROUND
        canvas.drawCircle(jumpChipCenterX, jumpChipCenterY, radius, jumpChipPaint)
        val span = radius * JUMP_CHIP_ARROW_SPAN_SCALE
        jumpChipArrow.reset()
        jumpChipArrow.moveTo(jumpChipCenterX - span, jumpChipCenterY - span * 0.5f)
        jumpChipArrow.lineTo(jumpChipCenterX, jumpChipCenterY + span * 0.5f)
        jumpChipArrow.lineTo(jumpChipCenterX + span, jumpChipCenterY - span * 0.5f)
        jumpChipPaint.style = Paint.Style.STROKE
        jumpChipPaint.strokeWidth = JUMP_CHIP_STROKE_DP * density
        jumpChipPaint.color = JUMP_CHIP_ARROW_COLOR
        canvas.drawPath(jumpChipArrow, jumpChipPaint)
    }

    private fun drawCursor(canvas: Canvas) {
        if (!cursorVisible || cursorX < 0 || cursorY < 0) return
        if (cursorBlinks && blinkScheduled && !blinkPhaseOn) return
        cursorPaint.color = if (cursorColor != 0) cursorColor else defaultForeground
        when (cursorStyle) {
            CURSOR_STYLE_BAR -> {
                cursorPaint.style = Paint.Style.FILL
                val x = cursorX * cellWidth
                canvas.drawRect(x, (cursorY * cellHeight).toFloat(), x + cellWidth / 4, ((cursorY + 1) * cellHeight).toFloat(), cursorPaint)
            }
            CURSOR_STYLE_UNDERLINE -> {
                cursorPaint.style = Paint.Style.FILL
                val x = cursorX * cellWidth
                val bottom = ((cursorY + 1) * cellHeight).toFloat()
                canvas.drawRect(x, bottom - cellHeight / 4f, x + cellWidth, bottom, cursorPaint)
            }
            CURSOR_STYLE_BLOCK_HOLLOW -> {
                cursorPaint.style = Paint.Style.STROKE
                cursorPaint.strokeWidth = underlineThickness
                val x = cursorX * cellWidth
                canvas.drawRect(x, (cursorY * cellHeight).toFloat(), x + cellWidth, ((cursorY + 1) * cellHeight).toFloat(), cursorPaint)
            }
            else -> drawBlockCursor(canvas)
        }
    }

    private fun drawBlockCursor(canvas: Canvas) {
        cursorPaint.style = Paint.Style.FILL
        if (cursorY >= rows || cursorX >= cols || gridForeground.size != rows * cols) {
            val x = cursorX * cellWidth
            canvas.drawRect(x, (cursorY * cellHeight).toFloat(), x + cellWidth, ((cursorY + 1) * cellHeight).toFloat(), cursorPaint)
            return
        }
        val gridBase = cursorY * cols
        var startCol = cursorX
        if (gridFlags[gridBase + startCol] and FLAG_SPACER != 0 && startCol > 0 &&
            gridFlags[gridBase + startCol - 1] and FLAG_WIDE != 0
        ) {
            startCol--
        }
        val flags = gridFlags[gridBase + startCol]
        val span = if (flags and FLAG_WIDE != 0) 2 else 1
        val x = startCol * cellWidth
        val top = (cursorY * cellHeight).toFloat()
        val bottom = top + cellHeight
        canvas.drawRect(x, top, x + span * cellWidth, bottom, cursorPaint)
        val textLength = gridTextLength[gridBase + startCol]
        if (textLength == 0 || flags and FLAG_INVISIBLE != 0) return
        val paint = stylePaint(flags)
        paint.color = gridBackground[gridBase + startCol]
        canvas.drawText(
            gridRowChars[cursorY],
            gridTextOffset[gridBase + startCol],
            textLength,
            x,
            top + baseline,
            paint,
        )
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (vertical != 0f) {
                val handled = session?.withTerminal { handle ->
                    val col = (event.x / cellWidth).toInt().coerceIn(0, cols - 1)
                    val row = (event.y / cellHeight).toInt().coerceIn(0, rows - 1)
                    scrollContent(handle, if (vertical > 0f) -WHEEL_SCROLL_ROWS else WHEEL_SCROLL_ROWS, col, row)
                    true
                } ?: false
                if (handled) return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE) && handleSecondaryMouse(event)) return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            scroller.abortAnimation()
            gestureDownCol = (event.x / cellWidth).toInt().coerceIn(0, cols - 1)
            gestureDownRow = (event.y / cellHeight).toInt().coerceIn(0, rows - 1)
        }
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress) {
            if (draggingHandle != HANDLE_NONE) {
                draggingHandle = HANDLE_NONE
                dismissMagnifier()
                actionMode?.invalidateContentRect()
            }
            gestureDetector.onTouchEvent(event)
            return true
        }
        if (selectionActive) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    draggingHandle = hitTestHandle(event.x, event.y)
                    if (draggingHandle != HANDLE_NONE) {
                        dragPastSlop = true
                        actionMode?.hide(ACTION_MODE_DRAG_HIDE_MS)
                        return true
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN -> if (draggingHandle != HANDLE_NONE) {
                    draggingHandle = HANDLE_NONE
                    dismissMagnifier()
                    actionMode?.invalidateContentRect()
                }
                MotionEvent.ACTION_MOVE -> if (draggingHandle != HANDLE_NONE) {
                    if (!dragPastSlop) {
                        if (hypot(event.x - longPressX, event.y - longPressY) < touchSlop) return true
                        dragPastSlop = true
                    }
                    dragSelection(event.x, event.y)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    if (draggingHandle != HANDLE_NONE) {
                        endHandleDrag()
                        return true
                    }
            }
        }
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    private fun handleSecondaryMouse(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val button = when {
                    event.buttonState and MotionEvent.BUTTON_SECONDARY != 0 -> GhosttyVt.MOUSE_BUTTON_RIGHT
                    event.buttonState and MotionEvent.BUTTON_TERTIARY != 0 -> GhosttyVt.MOUSE_BUTTON_MIDDLE
                    else -> return false
                }
                val col = (event.x / cellWidth).toInt().coerceIn(0, cols - 1)
                val row = (event.y / cellHeight).toInt().coerceIn(0, rows - 1)
                val tracked = session?.withTerminal { handle ->
                    if (GhosttyVt.nativeViewportState(handle) and GhosttyVt.VIEWPORT_MOUSE_TRACKING == 0) {
                        return@withTerminal false
                    }
                    sendMouseReport(handle, GhosttyVt.MOUSE_ACTION_PRESS, button, col, row)
                    true
                } ?: false
                if (tracked) {
                    secondaryMouseButton = button
                    return true
                }
                if (button == GhosttyVt.MOUSE_BUTTON_RIGHT) {
                    lastPressCol = col
                    lastPressRow = row
                    startTerminalActionMode()
                } else {
                    pasteFromClipboard()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (secondaryMouseButton == 0) return false
                session?.withTerminal { handle ->
                    val col = (event.x / cellWidth).toInt().coerceIn(0, cols - 1)
                    val row = (event.y / cellHeight).toInt().coerceIn(0, rows - 1)
                    sendMouseReport(handle, GhosttyVt.MOUSE_ACTION_MOTION, secondaryMouseButton, col, row)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (secondaryMouseButton == 0) return false
                val releasedButton = secondaryMouseButton
                secondaryMouseButton = 0
                session?.withTerminal { handle ->
                    val col = (event.x / cellWidth).toInt().coerceIn(0, cols - 1)
                    val row = (event.y / cellHeight).toInt().coerceIn(0, rows - 1)
                    sendMouseReport(handle, GhosttyVt.MOUSE_ACTION_RELEASE, releasedButton, col, row)
                }
                return true
            }
        }
        return false
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // IMEs send each keystroke of a visible-password field as it is typed
        // instead of batching autocorrect, and still compose CJK input.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return TerminalInputConnection()
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && (selectionActive || actionMode != null)) {
            if (event.action == KeyEvent.ACTION_DOWN) clearSelection()
            return true
        }
        return super.onKeyPreIme(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = handleKeyEvent(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = handleKeyEvent(event) || super.onKeyUp(keyCode, event)

    private fun handleKeyEvent(event: KeyEvent): Boolean {
        val activeSession = session ?: return false
        if (!activeSession.terminalAlive) return false
        if (activeSession.isFinished) {
            if (event.isSystem) return false
            if (event.action == KeyEvent.ACTION_DOWN) listener?.onFinishedSessionKey(this)
            return true
        }
        @Suppress("DEPRECATION")
        if (event.action == KeyEvent.ACTION_MULTIPLE && event.characters != null) {
            sendText(event.characters)
            return true
        }
        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount > 0) 2 else 1
            KeyEvent.ACTION_UP -> 0
            else -> return false
        }
        if (action != 0 && event.isShiftPressed &&
            (event.keyCode == KeyEvent.KEYCODE_PAGE_UP || event.keyCode == KeyEvent.KEYCODE_PAGE_DOWN)
        ) {
            val deltaRows = if (event.keyCode == KeyEvent.KEYCODE_PAGE_UP) -rows else rows
            activeSession.withTerminal { GhosttyVt.nativeScroll(it, deltaRows) }
            lastScrollActivityAt = SystemClock.uptimeMillis()
            scheduleFrame()
            return true
        }
        val isModifierKey = when (event.keyCode) {
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT,
            KeyEvent.KEYCODE_CAPS_LOCK,
            -> true
            else -> false
        }
        val stickyMeta = if (action == 1 && !isModifierKey) extraKeysState?.currentMetaState() ?: 0 else 0
        val metaState = event.metaState or stickyMeta
        var unicodeMask = KeyEvent.META_CTRL_MASK or KeyEvent.META_META_MASK
        if (metaState and KeyEvent.META_ALT_RIGHT_ON == 0) {
            unicodeMask = unicodeMask or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        }
        var unicode = event.getUnicodeChar(metaState and unicodeMask.inv())
        var composing = false
        if (unicode and KeyCharacterMap.COMBINING_ACCENT != 0) {
            if (action == 1) combiningAccent = unicode and KeyCharacterMap.COMBINING_ACCENT_MASK
            composing = true
            unicode = 0
        } else if (combiningAccent != 0 && action == 1 && unicode != 0) {
            val combined = KeyCharacterMap.getDeadChar(combiningAccent, unicode)
            if (combined > 0) unicode = combined
            combiningAccent = 0
        }
        val utf8 = if (unicode != 0) String(Character.toChars(unicode)).toByteArray(Charsets.UTF_8) else null
        val unshifted = event.getUnicodeChar(0)
        val bytes = activeSession.withTerminal { handle ->
            GhosttyVt.nativeEncodeKey(
                handle,
                event.keyCode,
                action,
                metaState,
                unshifted,
                composing,
                utf8,
            )
        } ?: return false
        if (bytes.isEmpty()) return false
        if (stickyMeta != 0) extraKeysState?.consumeModifiers()
        sendToSession(bytes, GhosttyTerminalSession::sendRawInput)
        return true
    }

    private fun detectUrlAt(row: Int, col: Int): String? {
        if (row >= rows || gridForeground.size != rows * cols || row >= gridRowChars.size) return null
        val rowChars = gridRowChars[row]
        val builder = StringBuilder(cols)
        val columnStart = IntArray(cols)
        val gridBase = row * cols
        for (c in 0 until cols) {
            columnStart[c] = builder.length
            val flags = gridFlags[gridBase + c]
            if (flags and FLAG_SPACER != 0) continue
            val textLength = gridTextLength[gridBase + c]
            val textOffset = gridTextOffset[gridBase + c]
            if (textLength == 0 || textOffset + textLength > rowChars.size) {
                builder.append(' ')
            } else {
                builder.append(rowChars, textOffset, textLength)
            }
        }
        val pressIndex = columnStart[col.coerceIn(0, cols - 1)]
        for (match in urlRegex.findAll(builder)) {
            if (pressIndex >= match.range.first && pressIndex <= match.range.last) {
                return match.value.trimEnd('.', ',', ';', ':', ')', ']', '>', '"', '\'')
            }
        }
        return null
    }

    private fun showLinkMenu(url: String) {
        uiHandler.showLinkMenu(
            context,
            url,
            openLink = {
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } catch (e: ActivityNotFoundException) {
                    Log.w(TAG, "no activity for $url", e)
                }
            },
            copyUrl = {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText(null, url))
            },
        )
    }

    private fun updateComposingText(text: String?) {
        val newText = text?.takeIf { it.isNotEmpty() }
        if (composingText == newText) return
        composingText = newText
        invalidate()
    }

    private inner class TerminalInputConnection : BaseInputConnection(this@GhosttyTerminalView, false) {
        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            updateComposingText(null)
            sendText(text)
            return super.commitText("", newCursorPosition)
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            updateComposingText(text.toString())
            return super.setComposingText(text, newCursorPosition)
        }

        override fun finishComposingText(): Boolean {
            updateComposingText(null)
            return super.finishComposingText()
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            repeat(beforeLength) {
                sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
            }
            return super.deleteSurroundingText(beforeLength, afterLength)
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (handleKeyEvent(event)) return true
            return super.sendKeyEvent(event)
        }
    }

    private companion object {
        const val TAG = "GhosttyTerminalView"

        const val DEFAULT_FONT_SIZE_SP = 14f
        const val MIN_FONT_SIZE_SP = 8f
        const val MAX_FONT_SIZE_SP = 48f
        const val PINCH_SCALE_STEP = 0.1f
        const val BLINK_INTERVAL_MS = 600L
        const val FAINT_ALPHA = 160
        const val WHEEL_SCROLL_ROWS = 3
        const val FLING_TRACKING_VELOCITY_SCALE = 0.25f
        const val ACTION_MODE_DRAG_HIDE_MS = 300L
        const val ACCESSIBILITY_TEXT_DELAY_MS = 500L
        const val KITTY_IMAGE_CACHE_BYTES = 64 * 1024 * 1024

        const val SNAPSHOT_VERSION = 2
        const val HEADER_BYTES = 60
        const val ROW_HEADER_BYTES = 16
        const val CELL_RECORD_BYTES = 16
        const val TEXT_UNITS_PER_CELL = 4
        const val DIRTY_FULL = 2
        const val SNAPSHOT_SYNC_DEFERRED = -2

        const val CURSOR_STYLE_BAR = 0
        const val CURSOR_STYLE_BLOCK = 1
        const val CURSOR_STYLE_UNDERLINE = 2
        const val CURSOR_STYLE_BLOCK_HOLLOW = 3

        const val FLAG_BOLD = 1 shl 0
        const val FLAG_ITALIC = 1 shl 1
        const val FLAG_FAINT = 1 shl 2
        const val FLAG_UNDERLINE = 1 shl 3
        const val FLAG_STRIKETHROUGH = 1 shl 4
        const val FLAG_INVERSE = 1 shl 5
        const val FLAG_INVISIBLE = 1 shl 6
        const val FLAG_WIDE = 1 shl 7
        const val FLAG_SPACER = 1 shl 8
        const val FLAG_FG_DEFAULT = 1 shl 9
        const val FLAG_BG_NONE = 1 shl 10

        const val UNDERLINE_DOUBLE = 2
        const val UNDERLINE_CURLY = 3
        const val UNDERLINE_DOTTED = 4
        const val UNDERLINE_DASHED = 5

        const val HANDLE_NONE = 0
        const val HANDLE_START = 1
        const val HANDLE_END = 2
        const val HANDLE_HIT_SLOP_DP = 24f

        const val MENU_COPY = 1
        const val MENU_PASTE = 2
        const val MENU_SELECT_ALL = 3

        val urlRegex = Regex("""https?://\S+""")

        const val SYNC_OUTPUT_RETRY_MS = 32L

        const val SCROLLBAR_FADE_START_MS = 800L
        const val SCROLLBAR_FADE_END_MS = 1400L
        const val SCROLLBAR_COLOR = 0xB3AAAAAA.toInt()
        const val JUMP_CHIP_RADIUS_DP = 18f
        const val JUMP_CHIP_CENTER_OFFSET_DP = 32f
        const val JUMP_CHIP_TAP_SLOP_SCALE = 1.5f
        const val JUMP_CHIP_ARROW_SPAN_SCALE = 0.45f
        const val JUMP_CHIP_STROKE_DP = 2f
        const val JUMP_CHIP_BACKGROUND = 0xCC2A2A2E.toInt()
        const val JUMP_CHIP_ARROW_COLOR = 0xFFE0E0E0.toInt()
    }
}
