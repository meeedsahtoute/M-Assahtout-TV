package com.streamtube.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * Lightweight zero-block startup hook kept for compatibility.
 * Window background (#0F0F0F) and instant Home feed cache deliver interactive
 * rendering well under 400ms with zero blocking sleeps or heavy particle canvases.
 */
@Composable
fun SplashScreen(
    onMountHome: () -> Unit,
    onHomeTransitionProgress: (Float) -> Unit,
    onFinished: () -> Unit
) {
    LaunchedEffect(Unit) {
        onMountHome()
        onHomeTransitionProgress(1f)
        onFinished()
    }
}
