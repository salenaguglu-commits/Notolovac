package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val NotolovacLightColorScheme = lightColorScheme(
    primary = CoralPrimary,
    onPrimary = Color.White,
    primaryContainer = CoralContainer,
    onPrimaryContainer = OnCoralContainer,
    secondary = SageMintSecondary,
    onSecondary = Color.White,
    secondaryContainer = SageMintContainer,
    onSecondaryContainer = OnSageMintContainer,
    tertiary = HoneyGoldTertiary,
    onTertiary = WarmInk,
    tertiaryContainer = HoneyGoldContainer,
    onTertiaryContainer = OnHoneyGoldContainer,
    background = CreamPaper,
    onBackground = WarmInk,
    surface = CreamPaper,
    onSurface = WarmInk,
    surfaceVariant = WarmSurface,
    onSurfaceVariant = MutedInk,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = CreamPaper,
    surfaceContainer = WarmSurface,
    surfaceContainerHigh = WarmCard,
    surfaceContainerHighest = Color(0xFFF5E6CE),
    outline = WarmCardBorder,
    outlineVariant = NotebookRuleLine,
    error = SoftError,
    onError = Color.White,
    errorContainer = SoftErrorContainer,
    onErrorContainer = Color(0xFF5D1313)
)

private val NotolovacShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun NotolovacTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = NotolovacLightColorScheme,
        typography = NotolovacTypography,
        shapes = NotolovacShapes,
        content = content
    )
}
