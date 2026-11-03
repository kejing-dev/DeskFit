package io.embodit.deskfit.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.systemColorScheme

/**
 * The app theme: the complete 16-role colour scheme of the design package and the Noto Sans family.
 * Five roles carry exact design values; the eleven adaptive roles are forwarded from the system
 * (Vibrant) scheme, each named so that the contract is visible.
 */
@Composable
fun DeskFitTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val system = systemColorScheme(context)
    val colors =
        remember(system) {
            system.copy(
                fillPrimary = system.fillPrimary,
                fillSecondary = system.fillSecondary,
                fillTertiary = system.fillTertiary,
                fillLight = system.fillLight,
                labelPrimaryLight = DeskColors.LabelPrimaryLight,
                labelPrimary = system.labelPrimary,
                labelSecondary = system.labelSecondary,
                labelTertiary = system.labelTertiary,
                labelQuaternary = system.labelQuaternary,
                lightenHover = system.lightenHover,
                lightenPressed = system.lightenPressed,
                error = DeskColors.Error,
                alert = DeskColors.Alert,
                passable = DeskColors.Passable,
                interaction = DeskColors.Interaction,
                dividerLine = system.dividerLine,
            )
        }
    val family = rememberDeskFontFamily()
    val base = PicoTheme.typography
    val typography = remember(base, family) { base.withFamily(family) }
    PicoTheme(colorScheme = colors, typography = typography, content = content)
}
