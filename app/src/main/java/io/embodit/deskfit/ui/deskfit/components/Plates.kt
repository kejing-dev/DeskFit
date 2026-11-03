package io.embodit.deskfit.ui.deskfit.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import io.embodit.deskfit.R
import io.embodit.deskfit.ui.theme.DeskColors
import io.embodit.deskfit.ui.theme.DeskType

/** Readout plate 144 x 40 dp (design `panels.readoutPlate`); arc figure 56 x 24 dp. */
internal val PlateWidth = 144.dp
internal val PlateHeight = 40.dp
internal val ArcLabelWidth = 56.dp
internal val ArcLabelHeight = 24.dp

enum class PlateGlyph { EYE, UP, DOWN, CHAIR_UP, CHAIR_DOWN, CHAIR_OK }

/** What one plate shows: a glyph and figures; `ok` plates are the plate ink with a passable dot, the others the interaction fill. */
data class PlateUi(val glyph: PlateGlyph, val text: String, val ok: Boolean)

/**
 * One line, icon + figures, on a solid pill that fills its panel (no empty margin may take the
 * pointer). Adjust = interaction fill with tape ink; fits = plateInk with a passable dot and white text.
 * Not a control.
 */
@Composable
internal fun ReadoutPlate(plate: PlateUi?, stale: Boolean) {
    Box(modifier = Modifier.size(PlateWidth, PlateHeight), contentAlignment = Alignment.Center) {
        if (plate == null) return@Box
        val adjust = !plate.ok
        val fill = when { stale -> DeskColors.StaleGrey; adjust -> PicoTheme.colorScheme.interaction; else -> DeskColors.PlateInk }
        val content = if (adjust && !stale) DeskColors.TapeInk else DeskColors.LabelPrimaryLight
        Row(
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(fill).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (plate.ok && !stale) Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(PicoTheme.colorScheme.passable))
            val (icon, desc) =
                when (plate.glyph) {
                    PlateGlyph.EYE -> R.drawable.ic_eye to rs(R.string.desc_eye)
                    PlateGlyph.UP -> R.drawable.ic_arrow_up to rs(R.string.desc_up)
                    PlateGlyph.DOWN -> R.drawable.ic_arrow_down to rs(R.string.desc_down)
                    PlateGlyph.CHAIR_UP, PlateGlyph.CHAIR_DOWN, PlateGlyph.CHAIR_OK -> R.drawable.ic_row_chair to rs(R.string.desc_chair)
                }
            Icon(painter = painterResource(icon), contentDescription = desc, modifier = Modifier.size(20.dp), tint = content)
            if (plate.glyph == PlateGlyph.CHAIR_UP || plate.glyph == PlateGlyph.CHAIR_DOWN) {
                Icon(
                    painter = painterResource(if (plate.glyph == PlateGlyph.CHAIR_UP) R.drawable.ic_arrow_up else R.drawable.ic_arrow_down),
                    contentDescription = rs(if (plate.glyph == PlateGlyph.CHAIR_UP) R.string.desc_up else R.string.desc_down),
                    modifier = Modifier.size(16.dp),
                    tint = content,
                )
            }
            Text(text = plate.text, color = content, style = DeskType.label, maxLines = 1, softWrap = false)
        }
    }
}

/** The small figure at the end of a distance arc (50 / 75 / 100): figures only on plate ink. */
@Composable
internal fun ArcLabel(text: String?) {
    Box(modifier = Modifier.size(ArcLabelWidth, ArcLabelHeight), contentAlignment = Alignment.Center) {
        if (text == null) return@Box
        Box(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(DeskColors.PlateInk), contentAlignment = Alignment.Center) {
            Text(text = text, color = DeskColors.LabelPrimaryLight, style = DeskType.label, maxLines = 1, softWrap = false)
        }
    }
}

@Suppress("unused")
private val keepColorImport: Color = Color.Transparent
