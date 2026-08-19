package io.github.sagernet.libghostty.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.github.sagernet.libghostty.GhosttyTerminalSession
import io.github.sagernet.libghostty.GhosttyTerminalView

@Composable
public fun GhosttyTerminal(
    session: GhosttyTerminalSession?,
    modifier: Modifier = Modifier,
    configure: (GhosttyTerminalView) -> Unit = {},
) {
    AndroidView(
        factory = ::GhosttyTerminalView,
        modifier = modifier,
        update = { view ->
            view.session = session
            configure(view)
        },
    )
}
