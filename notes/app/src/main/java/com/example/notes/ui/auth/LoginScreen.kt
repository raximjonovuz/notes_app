package com.example.notes.ui.auth

import androidx.compose.runtime.Composable

/**
 * Standard LoginScreen delegating to the buttery-smooth AnimatedLoginFlow.
 */
@Composable
fun LoginScreen(
    onAuthenticated: () -> Unit,
    onBack: () -> Unit,
    isEntryPoint: Boolean = false
) {
    AnimatedLoginFlow(
        onAuthenticated = onAuthenticated,
        onBack = onBack,
        isEntryPoint = isEntryPoint,
        startAsSplash = false
    )
}
