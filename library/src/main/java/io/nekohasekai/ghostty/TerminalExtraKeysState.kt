package io.nekohasekai.ghostty

import android.os.SystemClock
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow

class TerminalExtraKeysState {

    enum class StickyModifierState { INACTIVE, ARMED, LOCKED }

    val ctrlState = MutableStateFlow(StickyModifierState.INACTIVE)
    val altState = MutableStateFlow(StickyModifierState.INACTIVE)

    private val isCtrlActive: Boolean get() = ctrlState.value != StickyModifierState.INACTIVE
    private val isAltActive: Boolean get() = altState.value != StickyModifierState.INACTIVE

    private var lastCtrlTapTime = 0L
    private var lastAltTapTime = 0L

    fun toggleCtrl() {
        val now = SystemClock.uptimeMillis()
        ctrlState.value = toggleModifier(ctrlState.value, now, lastCtrlTapTime)
        lastCtrlTapTime = now
    }

    fun toggleAlt() {
        val now = SystemClock.uptimeMillis()
        altState.value = toggleModifier(altState.value, now, lastAltTapTime)
        lastAltTapTime = now
    }

    fun consumeModifiers() {
        if (ctrlState.value == StickyModifierState.ARMED) {
            ctrlState.value = StickyModifierState.INACTIVE
        }
        if (altState.value == StickyModifierState.ARMED) {
            altState.value = StickyModifierState.INACTIVE
        }
    }

    fun currentMetaState(): Int {
        var metaState = 0
        if (isCtrlActive) metaState = metaState or KeyEvent.META_CTRL_ON
        if (isAltActive) metaState = metaState or KeyEvent.META_ALT_ON
        return metaState
    }

    private fun toggleModifier(
        current: StickyModifierState,
        currentTimeMs: Long,
        lastTapTime: Long,
    ): StickyModifierState = when (current) {
        StickyModifierState.INACTIVE -> StickyModifierState.ARMED
        StickyModifierState.ARMED -> {
            if (currentTimeMs - lastTapTime < DOUBLE_TAP_THRESHOLD_MS) {
                StickyModifierState.LOCKED
            } else {
                StickyModifierState.INACTIVE
            }
        }
        StickyModifierState.LOCKED -> StickyModifierState.INACTIVE
    }

    companion object {
        private const val DOUBLE_TAP_THRESHOLD_MS = 300L
    }
}
