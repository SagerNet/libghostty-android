package io.github.sagernet.libghostty

import kotlin.math.floor
import kotlin.math.max

public class GhosttyCellSize(
    public val widthPx: Float,
    public val heightPx: Int,
) {
    public fun columnsFor(viewWidthPx: Int): Int = max(2, floor(viewWidthPx / widthPx).toInt())

    public fun rowsFor(viewHeightPx: Int): Int = max(2, viewHeightPx / heightPx)
}
