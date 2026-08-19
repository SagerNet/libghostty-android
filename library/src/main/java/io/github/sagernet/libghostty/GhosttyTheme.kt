package io.github.sagernet.libghostty

/** Colors are ARGB values; null keeps the terminal default. [palette] holds 256 entries. */
public class GhosttyTheme(
    public val foreground: Int?,
    public val background: Int?,
    public val cursorColor: Int?,
    public val selectionBackground: Int?,
    public val selectionForeground: Int?,
    public val palette: List<Int?>,
) {
    init {
        require(palette.size == 256) { "palette must hold 256 entries" }
    }
}
