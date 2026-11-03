package io.embodit.deskfit.ui.deskfit.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.deskfit.R
import io.embodit.deskfit.domain.model.RowVerdict
import io.embodit.deskfit.domain.usecase.FormatUseCase
import io.embodit.deskfit.ui.deskfit.DeskEvent
import io.embodit.deskfit.ui.deskfit.DeskUiState
import io.embodit.deskfit.ui.theme.DeskColors
import io.embodit.deskfit.ui.theme.DeskType

internal val ResultPanelWidth = 400.dp
internal val ResultPanelHeight = 496.dp
private val format = FormatUseCase()

/**
 * The result card (design `panels.resultPanel`): header ("chair first, then screen" or a big check
 * with "all set"), three rows (chair / screen height / screen distance) each with a verdict pill, a
 * fourth row only when the screen is off centre, a footer line (eye · desk · arm) and Back + [Measure again].
 */
@Composable
internal fun ResultPanel(state: DeskUiState, filled: Boolean, onEvent: (DeskEvent) -> Unit) {
    val shape = RoundedCornerShape(PanelCorner)
    Column(
        modifier = Modifier.size(ResultPanelWidth, ResultPanelHeight).clip(shape).backgroundMaterial(enable = true, style = Material.Regular).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!filled) return@Column
        val j = state.judgement ?: return@Column
        val ink = PicoTheme.colorScheme.labelPrimary
        StepDots(current = 4, done = 3, menuEnabled = true, onEvent = onEvent)
        Row(modifier = Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (j.allGood) {
                Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(PicoTheme.colorScheme.passable), contentAlignment = Alignment.Center) {
                    Icon(painter = painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(28.dp), tint = DeskColors.TapeInk)
                }
                Text(text = rs(R.string.result_all_good), color = ink, style = DeskType.headline, maxLines = 1)
            } else {
                Text(text = rs(R.string.result_header), color = ink, style = DeskType.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val eye = state.eyeH ?: 0f
        VerdictRow(1, R.drawable.ic_row_chair, rs(R.string.row_chair), j.chair, upDown = true, state = state)
        VerdictRow(2, R.drawable.ic_row_height, rs(R.string.row_height), j.screenHeight, upDown = true, state = state)
        VerdictRow(3, R.drawable.ic_row_distance, rs(R.string.row_distance), j.screenDistance, upDown = false, state = state)
        if (j.offCentre) {
            Row(modifier = Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "4", color = ink, style = DeskType.label, maxLines = 1)
                Icon(painter = painterResource(R.drawable.ic_center), contentDescription = rs(R.string.desc_center), modifier = Modifier.size(32.dp), tint = ink)
                Text(text = rs(R.string.row_side), color = ink, style = DeskType.title, maxLines = 1, modifier = Modifier.weight(1f))
                Pill(fill = PicoTheme.colorScheme.interaction, icon = R.drawable.ic_center, text = "", content = DeskColors.TapeInk)
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        val deskH = state.deskH ?: 0f
        val forearmH = state.floorY?.let { state.forearmY - it } ?: 0f
        Text(
            text = rs(R.string.result_footer, format.whole(eye, state.unit), format.whole(deskH, state.unit), format.withUnit(forearmH, state.unit)),
            color = ink,
            style = DeskType.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.height(18.dp),
        )
        Row(modifier = Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(
                onClick = { onEvent(DeskEvent.BackClicked) },
                modifier = Modifier.probe("back") { onEvent(DeskEvent.BackClicked) },
                colors = IconButtonDefaults.iconButtonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = ink),
                size = IconButtonDefaults.iconButtonSize(width = KeyWidth, height = KeyHeight),
            ) {
                Icon(painter = painterResource(R.drawable.ic_back), contentDescription = rs(R.string.action_back), modifier = Modifier.size(24.dp), tint = ink)
            }
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = { onEvent(DeskEvent.PrimaryClicked) },
                modifier = Modifier.probe("primary") { onEvent(DeskEvent.PrimaryClicked) },
                size = ButtonDefaults.Regular.copy(height = KeyHeight, minWidth = 148.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.interaction, contentColor = DeskColors.TapeInk),
            ) {
                Icon(painter = painterResource(R.drawable.ic_retry), contentDescription = null, modifier = Modifier.size(20.dp), tint = DeskColors.TapeInk)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = rs(R.string.action_again), color = DeskColors.TapeInk, maxLines = 1)
            }
        }
    }
}

@Composable
private fun VerdictRow(n: Int, iconRes: Int, name: String, verdict: RowVerdict, upDown: Boolean, state: DeskUiState) {
    val ink = PicoTheme.colorScheme.labelPrimary
    val note =
        when (verdict.note) {
            RowVerdict.Note.FOOTREST -> rs(R.string.note_footrest)
            RowVerdict.Note.TRAY -> rs(R.string.note_tray)
            RowVerdict.Note.LOW_DESK -> rs(R.string.note_low_desk)
            RowVerdict.Note.AFTER_CHAIR -> rs(R.string.note_after_chair)
            RowVerdict.Note.LAPTOP -> rs(R.string.note_laptop)
            null -> null
        }
    Row(modifier = Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = n.toString(), color = ink, style = DeskType.label, maxLines = 1)
        Icon(painter = painterResource(iconRes), contentDescription = null, modifier = Modifier.size(32.dp), tint = ink)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = name, color = ink, style = DeskType.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (note != null) Text(text = note, color = ink, style = DeskType.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (verdict.ok) {
            Pill(fill = PicoTheme.colorScheme.passable, icon = R.drawable.ic_check, text = rs(R.string.verdict_ok), content = DeskColors.TapeInk)
        } else {
            val amount = format.withUnit(kotlin.math.abs(verdict.delta), state.unit)
            val icon = if (upDown) (if (verdict.delta > 0f) R.drawable.ic_arrow_up else R.drawable.ic_arrow_down) else (if (verdict.delta > 0f) R.drawable.ic_push else R.drawable.ic_pull)
            val text = if (upDown) amount else (if (verdict.delta > 0f) rs(R.string.verdict_push, amount) else rs(R.string.verdict_pull, amount))
            Pill(fill = PicoTheme.colorScheme.interaction, icon = icon, text = text, content = DeskColors.TapeInk)
        }
    }
}

/** Verdict pill: 40 dp high, semantic fill, icon + figures in tape ink. */
@Composable
private fun Pill(fill: androidx.compose.ui.graphics.Color, icon: Int, text: String, content: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier.height(40.dp).clip(CircleShape).background(fill).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp), tint = content)
        if (text.isNotEmpty()) Text(text = text, color = content, style = DeskType.label, maxLines = 1)
    }
}
