package com.flue.launcher.surface.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.flue.launcher.ui.theme.LauncherTheme

/**
 * Gives existing settings layouts the calibrated content viewport and shared back gesture.
 * Their lists still own scrolling, rotary input, search positioning and row presentation.
 * The background and back gesture cover the entire window, including the calibrated margins.
 * Only system insets extending into the viewport are passed to the existing content layout.
 */
@Composable
fun SettingsPageFrame(
    onBack: () -> Unit,
    content: @Composable BoxWithConstraintsScope.(PaddingValues) -> Unit,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(LauncherTheme.style.screenBackground)
            .fflueSwipeBack(onBack = onBack),
    ) {
        val frame = rememberSafeAreaFrame(maxWidth, maxHeight)
        val system = WindowInsets.safeDrawing.asPaddingValues()
        val direction = LocalLayoutDirection.current
        val horizontal = frame.left.dp
        val vertical = frame.top.dp
        val remainingSystemInsets = PaddingValues(
            start = (system.calculateStartPadding(direction) - horizontal).coerceAtLeast(0.dp),
            top = (system.calculateTopPadding() - vertical).coerceAtLeast(0.dp),
            end = (system.calculateEndPadding(direction) - horizontal).coerceAtLeast(0.dp),
            bottom = (system.calculateBottomPadding() - vertical).coerceAtLeast(0.dp),
        )
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(horizontal = horizontal, vertical = vertical)
                .clipToBounds(),
        ) {
            content(remainingSystemInsets)
        }
    }
}
