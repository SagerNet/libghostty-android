package io.github.sagernet.libghostty.compose

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sagernet.libghostty.GhosttyExtraKeysState.StickyState
import io.github.sagernet.libghostty.GhosttyTerminalView
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

public sealed interface GhosttyExtraKey {
    public data object Esc : GhosttyExtraKey

    public data object Tab : GhosttyExtraKey

    public data object Ctrl : GhosttyExtraKey

    public data object Alt : GhosttyExtraKey

    public data object ArrowLeft : GhosttyExtraKey

    public data object ArrowUp : GhosttyExtraKey

    public data object ArrowDown : GhosttyExtraKey

    public data object ArrowRight : GhosttyExtraKey

    public data class Symbol(public val text: String) : GhosttyExtraKey

    public data object Paste : GhosttyExtraKey

    public data object Divider : GhosttyExtraKey
}

@Immutable
public class GhosttyExtraKeysColors(
    public val container: Color,
    public val keyContainer: Color,
    public val keyContent: Color,
    public val armedKeyContainer: Color,
    public val armedKeyContent: Color,
    public val lockedKeyContainer: Color,
    public val lockedKeyContent: Color,
)

public object GhosttyExtraKeysDefaults {

    public val keys: List<GhosttyExtraKey> = listOf(
        GhosttyExtraKey.Esc,
        GhosttyExtraKey.Tab,
        GhosttyExtraKey.Ctrl,
        GhosttyExtraKey.Alt,
        GhosttyExtraKey.Divider,
        GhosttyExtraKey.ArrowLeft,
        GhosttyExtraKey.ArrowUp,
        GhosttyExtraKey.ArrowDown,
        GhosttyExtraKey.ArrowRight,
        GhosttyExtraKey.Divider,
        GhosttyExtraKey.Symbol("|"),
        GhosttyExtraKey.Symbol("/"),
        GhosttyExtraKey.Symbol("~"),
        GhosttyExtraKey.Symbol("-"),
        GhosttyExtraKey.Symbol("_"),
        GhosttyExtraKey.Symbol("`"),
        GhosttyExtraKey.Symbol("'"),
        GhosttyExtraKey.Symbol("\""),
        GhosttyExtraKey.Paste,
    )

    @Composable
    public fun colors(): GhosttyExtraKeysColors = GhosttyExtraKeysColors(
        container = MaterialTheme.colorScheme.surfaceContainerHigh,
        keyContainer = MaterialTheme.colorScheme.surfaceContainerHighest,
        keyContent = MaterialTheme.colorScheme.onSurface,
        armedKeyContainer = MaterialTheme.colorScheme.primaryContainer,
        armedKeyContent = MaterialTheme.colorScheme.onPrimaryContainer,
        lockedKeyContainer = MaterialTheme.colorScheme.primary,
        lockedKeyContent = MaterialTheme.colorScheme.onPrimary,
    )
}

@Composable
public fun GhosttyExtraKeysBar(
    state: GhosttyTerminalState,
    modifier: Modifier = Modifier,
    keys: List<GhosttyExtraKey> = GhosttyExtraKeysDefaults.keys,
    colors: GhosttyExtraKeysColors = GhosttyExtraKeysDefaults.colors(),
) {
    val view = state.view
    val ctrlState by state.extraKeys.ctrl.collectAsState()
    val altState by state.extraKeys.alt.collectAsState()

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = colors.container,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (key in keys) when (key) {
                GhosttyExtraKey.Esc -> KeyButton(view, colors, label = "ESC") {
                    view?.sendKey(KeyEvent.KEYCODE_ESCAPE)
                }

                GhosttyExtraKey.Tab -> KeyButton(view, colors, label = "TAB") {
                    view?.sendKey(KeyEvent.KEYCODE_TAB)
                }

                GhosttyExtraKey.Ctrl -> KeyButton(
                    view,
                    colors,
                    label = "CTRL",
                    containerColor = stickyContainerColor(ctrlState, colors),
                    contentColor = stickyContentColor(ctrlState, colors),
                ) {
                    state.extraKeys.toggleCtrl()
                }

                GhosttyExtraKey.Alt -> KeyButton(
                    view,
                    colors,
                    label = "ALT",
                    containerColor = stickyContainerColor(altState, colors),
                    contentColor = stickyContentColor(altState, colors),
                ) {
                    state.extraKeys.toggleAlt()
                }

                GhosttyExtraKey.ArrowLeft -> ArrowKeyButton(view, colors, "◀") {
                    view?.sendKey(KeyEvent.KEYCODE_DPAD_LEFT)
                }

                GhosttyExtraKey.ArrowUp -> ArrowKeyButton(view, colors, "▲") {
                    view?.sendKey(KeyEvent.KEYCODE_DPAD_UP)
                }

                GhosttyExtraKey.ArrowDown -> ArrowKeyButton(view, colors, "▼") {
                    view?.sendKey(KeyEvent.KEYCODE_DPAD_DOWN)
                }

                GhosttyExtraKey.ArrowRight -> ArrowKeyButton(view, colors, "▶") {
                    view?.sendKey(KeyEvent.KEYCODE_DPAD_RIGHT)
                }

                is GhosttyExtraKey.Symbol -> KeyButton(view, colors, label = key.text) {
                    view?.sendText(key.text)
                }

                GhosttyExtraKey.Paste -> KeyButton(view, colors, icon = pasteIcon) {
                    view?.pasteFromClipboard()
                }

                GhosttyExtraKey.Divider -> KeyDivider(colors)
            }
        }
    }
}

private val keyShape = RoundedCornerShape(8.dp)

private fun stickyContainerColor(state: StickyState, colors: GhosttyExtraKeysColors): Color = when (state) {
    StickyState.INACTIVE -> colors.keyContainer
    StickyState.ARMED -> colors.armedKeyContainer
    StickyState.LOCKED -> colors.lockedKeyContainer
}

private fun stickyContentColor(state: StickyState, colors: GhosttyExtraKeysColors): Color = when (state) {
    StickyState.INACTIVE -> colors.keyContent
    StickyState.ARMED -> colors.armedKeyContent
    StickyState.LOCKED -> colors.lockedKeyContent
}

@Composable
private fun KeyButton(
    view: GhosttyTerminalView?,
    colors: GhosttyExtraKeysColors,
    label: String? = null,
    icon: ImageVector? = null,
    containerColor: Color = colors.keyContainer,
    contentColor: Color = colors.keyContent,
    onPress: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        modifier = if (icon != null) {
            Modifier.size(36.dp)
        } else {
            Modifier.defaultMinSize(minWidth = 42.dp).height(36.dp)
        }
            .clip(keyShape)
            .indication(interactionSource, ripple())
            .ghosttyExtraKey(view, interactionSource = interactionSource, onPress = onPress),
        shape = keyShape,
        color = containerColor,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 4.dp)) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = contentColor,
                )
            } else if (label != null) {
                Text(
                    text = label,
                    fontSize = if (label.length > 1) 10.sp else 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = contentColor,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ArrowKeyButton(
    view: GhosttyTerminalView?,
    colors: GhosttyExtraKeysColors,
    label: String,
    onPress: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        modifier = Modifier
            .size(36.dp)
            .clip(keyShape)
            .indication(interactionSource, ripple())
            .ghosttyExtraKey(view, repeatable = true, interactionSource = interactionSource, onPress = onPress),
        shape = keyShape,
        color = colors.keyContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                fontSize = 12.sp,
                color = colors.keyContent,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun KeyDivider(colors: GhosttyExtraKeysColors) {
    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .size(5.dp)
            .clip(CircleShape)
            .background(colors.keyContent.copy(alpha = 0.3f)),
    )
}

private val pasteIcon: ImageVector = ImageVector.Builder(
    name = "Paste",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addPath(
    pathData = addPathNodes(
        "M19,2h-4.18C14.4,0.84 13.3,0 12,0c-1.3,0 -2.4,0.84 -2.82,2L5,2C3.9,2 3,2.9 3,4v16c0,1.1 " +
            "0.9,2 2,2h14c1.1,0 2,-0.9 2,-2L21,4c0,-1.1 -0.9,-2 -2,-2zM12,2c0.55,0 1,0.45 1,1s-0.45,1 " +
            "-1,1 -1,-0.45 -1,-1 0.45,-1 1,-1zM19,20L5,20L5,4h2v3h10L17,4h2v16z",
    ),
    fill = SolidColor(Color.Black),
).build()

/**
 * Handles presses on an extra keys button: fires [onPress] on touch down, repeats it while held
 * when [repeatable] is set, and returns focus to [view] on release so the button does not take
 * the input target away from the terminal. The gesture consumes the touch, so the button must not
 * also be clickable; pass an [interactionSource] to drive `Modifier.indication` from it instead.
 */
internal fun Modifier.ghosttyExtraKey(
    view: GhosttyTerminalView?,
    repeatable: Boolean = false,
    interactionSource: MutableInteractionSource? = null,
    onPress: () -> Unit,
): Modifier = composed {
    val currentView = rememberUpdatedState(view)
    val currentOnPress = rememberUpdatedState(onPress)
    pointerInput(repeatable, interactionSource) {
        coroutineScope {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                val press = PressInteraction.Press(down.position)
                if (interactionSource != null) launch { interactionSource.emit(press) }
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
                val up = waitForUpOrCancellation()
                repeatJob?.cancel()
                if (interactionSource != null) {
                    launch {
                        interactionSource.emit(
                            if (up != null) PressInteraction.Release(press) else PressInteraction.Cancel(press),
                        )
                    }
                }
                currentView.value?.requestFocus()
            }
        }
    }
}

private const val REPEAT_START_DELAY_MS = 400L
private const val REPEAT_INTERVAL_MS = 80L
