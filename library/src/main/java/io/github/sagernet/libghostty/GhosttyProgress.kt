package io.github.sagernet.libghostty

public enum class GhosttyProgressState {
    SET,
    ERROR,
    INDETERMINATE,
    PAUSE,
}

/** [percent] is 0..100, or null when the report omitted it. */
public class GhosttyProgress(
    public val state: GhosttyProgressState,
    public val percent: Int?,
)
