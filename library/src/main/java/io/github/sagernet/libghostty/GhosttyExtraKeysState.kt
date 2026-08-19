package io.github.sagernet.libghostty

import android.os.SystemClock
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

public class GhosttyExtraKeysState {

    public enum class StickyState { INACTIVE, ARMED, LOCKED }

    private val _ctrl = MutableStateFlow(StickyState.INACTIVE)
    public val ctrl: StateFlow<StickyState> = _ctrl.asStateFlow()

    private val _alt = MutableStateFlow(StickyState.INACTIVE)
    public val alt: StateFlow<StickyState> = _alt.asStateFlow()

    private var lastCtrlTapTime = 0L
    private var lastAltTapTime = 0L

    public fun toggleCtrl() {
        val now = SystemClock.uptimeMillis()
        _ctrl.value = toggleModifier(_ctrl.value, now, lastCtrlTapTime)
        lastCtrlTapTime = now
    }

    public fun toggleAlt() {
        val now = SystemClock.uptimeMillis()
        _alt.value = toggleModifier(_alt.value, now, lastAltTapTime)
        lastAltTapTime = now
    }

    public fun consumeModifiers() {
        if (_ctrl.value == StickyState.ARMED) _ctrl.value = StickyState.INACTIVE
        if (_alt.value == StickyState.ARMED) _alt.value = StickyState.INACTIVE
    }

    public fun currentMetaState(): Int {
        var metaState = 0
        if (_ctrl.value != StickyState.INACTIVE) metaState = metaState or KeyEvent.META_CTRL_ON
        if (_alt.value != StickyState.INACTIVE) metaState = metaState or KeyEvent.META_ALT_ON
        return metaState
    }

    private fun toggleModifier(
        current: StickyState,
        currentTimeMs: Long,
        lastTapTime: Long,
    ): StickyState = when (current) {
        StickyState.INACTIVE -> StickyState.ARMED
        StickyState.ARMED -> {
            if (currentTimeMs - lastTapTime < DOUBLE_TAP_THRESHOLD_MS) {
                StickyState.LOCKED
            } else {
                StickyState.INACTIVE
            }
        }
        StickyState.LOCKED -> StickyState.INACTIVE
    }

    private companion object {
        const val DOUBLE_TAP_THRESHOLD_MS = 300L
    }
}
