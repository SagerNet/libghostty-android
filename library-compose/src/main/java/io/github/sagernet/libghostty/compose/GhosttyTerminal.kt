package io.github.sagernet.libghostty.compose

import android.graphics.Typeface
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.github.sagernet.libghostty.GhosttyTerminalSession
import io.github.sagernet.libghostty.GhosttyTerminalView
import io.github.sagernet.libghostty.GhosttyTheme

@Composable
public fun GhosttyTerminal(
    session: GhosttyTerminalSession?,
    state: GhosttyTerminalState = rememberGhosttyTerminalState(),
    modifier: Modifier = Modifier,
    theme: GhosttyTheme? = null,
    darkColorScheme: Boolean = isSystemInDarkTheme(),
    typeface: Typeface = Typeface.MONOSPACE,
    fontSizeSp: Float = GhosttyTerminalView.DEFAULT_FONT_SIZE_SP,
    focusOnAttach: Boolean = true,
    onFontSizeChanged: (Float) -> Unit = {},
    onFinishedSessionKey: () -> Unit = {},
    configure: (GhosttyTerminalView) -> Unit = {},
) {
    val currentOnFontSizeChanged = rememberUpdatedState(onFontSizeChanged)
    val currentOnFinishedSessionKey = rememberUpdatedState(onFinishedSessionKey)
    // Pinch zoom moves the view's font size away from the parameter; reapply
    // only when the parameter itself changes, so recomposition does not undo
    // the gesture.
    val appliedFontSize = remember { FloatHolder() }

    AndroidView(
        factory = { context ->
            GhosttyTerminalView(context).also { view ->
                view.listener = object : GhosttyTerminalView.Listener {
                    override fun onFontSizeChanged(view: GhosttyTerminalView, fontSizeSp: Float) {
                        currentOnFontSizeChanged.value(fontSizeSp)
                    }

                    override fun onFinishedSessionKey(view: GhosttyTerminalView) {
                        currentOnFinishedSessionKey.value()
                    }
                }
                if (focusOnAttach) view.post { view.showIme() }
            }
        },
        modifier = modifier,
        update = { view ->
            state.view = view
            view.extraKeysState = state.extraKeys
            view.uiHandler = state
            view.session = session
            view.typeface = typeface
            if (appliedFontSize.value != fontSizeSp) {
                appliedFontSize.value = fontSizeSp
                view.fontSizeSp = fontSizeSp
            }
            configure(view)
        },
        onRelease = { view ->
            view.session = null
            if (state.view === view) state.view = null
        },
    )

    LaunchedEffect(session, theme, darkColorScheme) {
        if (session != null) {
            theme?.let(session::setTheme)
            session.setColorScheme(darkColorScheme)
        }
    }
}

private class FloatHolder {
    var value = Float.NaN
}
