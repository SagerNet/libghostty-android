package io.github.sagernet.libghostty

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GhosttyVtSmokeTest {

    private class Cell(
        val foreground: Int,
        val textOffset: Int,
        val textLength: Int,
        val flags: Int,
    )

    private class Row(
        val cells: List<Cell>,
        val text: CharArray,
    )

    @Test
    fun parseAndSnapshotColoredText() {
        val handle = GhosttyVt.nativeCreate(80, 24, 1000, "libghostty-android-test")
        assertNotEquals("terminal creation failed", 0L, handle)
        try {
            val payload = "\u001b[38;2;255;0;0mred\u001b[0m\r\nplain".toByteArray(Charsets.UTF_8)
            val padded = ByteArray(payload.size + 7)
            payload.copyInto(padded, 3)
            GhosttyVt.nativeWrite(handle, padded, 3, payload.size)

            val buffer = ByteBuffer.allocateDirect(512 * 1024).order(ByteOrder.LITTLE_ENDIAN)
            val rowCount = GhosttyVt.nativeSnapshot(handle, buffer)
            assertTrue("snapshot returned $rowCount", rowCount > 0)

            buffer.position(0)
            assertEquals("snapshot version", 2, buffer.int)
            buffer.int
            val cols = buffer.int
            assertEquals(80, cols)
            buffer.int
            buffer.int
            val defaultForeground = buffer.int
            buffer.position(15 * 4)

            val rows = HashMap<Int, Row>()
            repeat(rowCount) {
                val rowIndex = buffer.int
                buffer.int
                buffer.int
                val textUnits = buffer.int
                val cells = ArrayList<Cell>(cols)
                repeat(cols) {
                    val foreground = buffer.int
                    buffer.int
                    val textOffset = buffer.short.toInt() and 0xFFFF
                    val textLength = buffer.short.toInt() and 0xFFFF
                    val flags = buffer.short.toInt() and 0xFFFF
                    buffer.short
                    cells.add(Cell(foreground, textOffset, textLength, flags))
                }
                val text = CharArray(textUnits)
                for (i in 0 until textUnits) text[i] = buffer.char
                if (textUnits % 2 != 0) buffer.short
                rows[rowIndex] = Row(cells, text)
            }

            val redRow = rows[0] ?: throw AssertionError("row 0 not in snapshot")
            for ((i, expected) in "red".withIndex()) {
                val cell = redRow.cells[i]
                assertEquals("row 0 col $i text length", 1, cell.textLength)
                assertEquals("row 0 col $i char", expected, redRow.text[cell.textOffset])
                assertEquals("row 0 col $i flags", 0, cell.flags and FLAG_FG_DEFAULT)
                assertEquals("row 0 col $i color", 0xFFFF0000.toInt(), cell.foreground)
            }

            val plainRow = rows[1] ?: throw AssertionError("row 1 not in snapshot")
            for ((i, expected) in "plain".withIndex()) {
                val cell = plainRow.cells[i]
                assertEquals("row 1 col $i text length", 1, cell.textLength)
                assertEquals("row 1 col $i char", expected, plainRow.text[cell.textOffset])
                assertEquals(
                    "row 1 col $i uses the default foreground",
                    FLAG_FG_DEFAULT,
                    cell.flags and FLAG_FG_DEFAULT,
                )
            }
            assertEquals(0xFFFFFFFF.toInt(), defaultForeground)
        } finally {
            GhosttyVt.nativeFree(handle)
        }
    }

    private companion object {
        const val FLAG_FG_DEFAULT = 1 shl 9
    }
}
