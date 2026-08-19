package io.github.sagernet.libghostty.compose

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import io.github.sagernet.libghostty.GhosttyProgressState
import io.github.sagernet.libghostty.GhosttyTerminalSession

@Composable
public fun GhosttyProgressIndicator(
    session: GhosttyTerminalSession,
    modifier: Modifier = Modifier,
) {
    val progress by session.progress.collectAsState()
    val current = progress ?: return
    val color = if (current.state == GhosttyProgressState.ERROR) {
        MaterialTheme.colorScheme.error
    } else {
        ProgressIndicatorDefaults.linearColor
    }
    val percent = current.percent
    if (percent != null && current.state != GhosttyProgressState.INDETERMINATE) {
        LinearProgressIndicator(
            progress = { percent / 100f },
            color = color,
            modifier = modifier,
        )
    } else {
        LinearProgressIndicator(
            color = color,
            modifier = modifier,
        )
    }
}
