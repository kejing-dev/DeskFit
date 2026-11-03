package io.embodit.deskfit.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The design package's palette (`palette.colorScheme` + `palette.brandTokens`). This is the only
 * file with colour literals. Semantic roles go into `PicoTheme` through [DeskFitTheme]; brand tokens
 * are used directly by the 3D materials and the solid dark faces (readout plates).
 */
object DeskColors {
    // ---- ColorScheme roles with a fixed design value (the other eleven inherit SpatialUI Vibrant)
    val LabelPrimaryLight = Color(0xFFFFFFFF) // design-style: fixed-figma-color labelPrimaryLight: text on plateInk
    val Error = Color(0xFFFF5F52) // design-style: fixed-figma-color error: unavailable only
    val Alert = Color(0xFFFF8A1F) // design-style: fixed-figma-color alert
    val Passable = Color(0xFF66E08A) // design-style: fixed-figma-color passable: fits / all set
    val Interaction = Color(0xFFFFC72C) // design-style: fixed-figma-color interaction: adjust, the thing being handled

    // ---- brand tokens
    val TapeYellow = Color(0xFFFFC72C) // design-style: fixed-figma-color tapeYellow: bars, tape
    val TapeInk = Color(0xFF16181C) // design-style: fixed-figma-color tapeInk: content on semantic fills
    val BladeEdge = Color(0xFF0E0F12) // design-style: fixed-figma-color bladeEdge
    val MeterRed = Color(0xFFE23B2E) // design-style: fixed-figma-color meterRed: tape zero end
    val Steel = Color(0xFFC9CED6) // design-style: fixed-figma-color steel: the pole
    val PlateInk = Color(0xF0121417) // design-style: fixed-figma-color plateInk #121417 94 %: readout plates
    val StaleGrey = Color(0xFF9A9A96) // design-style: fixed-figma-color staleGrey: tracking lost
    val OnInkSecondary = Color(0xA8FFFFFF) // white 66 % on dark faces
    val IconBg = Color(0xFF5B4BDB) // design-style: fixed-figma-color iconBg: app icon only
}
