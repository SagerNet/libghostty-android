package io.github.sagernet.libghostty

import android.os.SystemClock
import android.view.KeyEvent

public class TerminalExtraKeysState {

    public enum class StickyModifierState { INACTIVE, ARMED, LOCKED }

    public fun interface Listener {
        public fun onChanged(state: TerminalExtraKeysState)
    }

    public var listener: Listener? = null

    public var ctrlState: StickyModifierState = StickyModifierState.INACTIVE
        private set

    public var altState: StickyModifierState = StickyModifierState.INACTIVE
        private set

    private var lastCtrlTapTime = 0L
    private var lastAltTapTime = 0L

    public fun toggleCtrl() {
        val now = SystemClock.uptimeMillis()
        setStates(toggleModifier(ctrlState, now, lastCtrlTapTime), altState)
        lastCtrlTapTime = now
    }

    public fun toggleAlt() {
        val now = SystemClock.uptimeMillis()
        setStates(ctrlState, toggleModifier(altState, now, lastAltTapTime))
        lastAltTapTime = now
    }

    public fun consumeModifiers() {
        setStates(
            if (ctrlState == StickyModifierState.ARMED) StickyModifierState.INACTIVE else ctrlState,
            if (altState == StickyModifierState.ARMED) StickyModifierState.INACTIVE else altState,
        )
    }

    public fun currentMetaState(): Int {
        var metaState = 0
        if (ctrlState != StickyModifierState.INACTIVE) metaState = metaState or KeyEvent.META_CTRL_ON
        if (altState != StickyModifierState.INACTIVE) metaState = metaState or KeyEvent.META_ALT_ON
        return metaState
    }

    private fun setStates(ctrl: StickyModifierState, alt: StickyModifierState) {
        if (ctrl == ctrlState && alt == altState) return
        ctrlState = ctrl
        altState = alt
        listener?.onChanged(this)
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

    private companion object {
        const val DOUBLE_TAP_THRESHOLD_MS = 300L
    }
}
