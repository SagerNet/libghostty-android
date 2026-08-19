package io.github.sagernet.libghostty.compose

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import io.github.sagernet.libghostty.GhosttyTerminalView
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Handles presses on an extra keys button: fires [onPress] on touch down, repeats it while held
 * when [repeatable] is set, and returns focus to [view] on release so the button does not take
 * the input target away from the terminal.
 */
public fun Modifier.ghosttyExtraKey(
    view: GhosttyTerminalView?,
    repeatable: Boolean = false,
    onPress: () -> Unit,
): Modifier = composed {
    val currentView = rememberUpdatedState(view)
    val currentOnPress = rememberUpdatedState(onPress)
    pointerInput(repeatable) {
        coroutineScope {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                currentOnPress.value()
                val repeatJob = if (repeatable) {
                    launch {
                        delay(REPEAT_START_DELAY_MS)
                        while (isActive) {
                            currentOnPress.value()
                            delay(REPEAT_INTERVAL_MS)
                        }
                    }
                } else {
                    null
                }
                waitForUpOrCancellation()
                repeatJob?.cancel()
                currentView.value?.requestFocus()
            }
        }
    }
}

private const val REPEAT_START_DELAY_MS = 400L
private const val REPEAT_INTERVAL_MS = 80L
