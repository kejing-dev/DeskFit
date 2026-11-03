package io.embodit.deskfit.ui.deskfit.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.CircularProgressDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.deskfit.R
import io.embodit.deskfit.domain.model.Phase
import io.embodit.deskfit.domain.model.PostureKind
import io.embodit.deskfit.domain.usecase.DeskRulesUseCase
import io.embodit.deskfit.domain.usecase.FormatUseCase
import io.embodit.deskfit.ui.deskfit.DeskEvent
import io.embodit.deskfit.ui.deskfit.DeskUiState
import io.embodit.deskfit.ui.theme.DeskColors
import io.embodit.deskfit.ui.theme.DeskType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Step panel 400 x 248 dp at 0.75 (design `panels.stepPanel`); result panel 400 x 496 dp. */
internal val StepPanelWidth = 400.dp
internal val StepPanelHeight = 248.dp
internal val PanelCorner = 32.dp
internal val KeyWidth = 56.dp
internal val KeyHeight = 48.dp
private val DotSize = 36.dp
private val format = FormatUseCase()

/**
 * The one standing panel: four step dots + More; a pictogram and one sentence (plus a hint); Back and
 * the primary button. Glass root `Material.Regular`; all text on glass uses `labelPrimary`; content on
 * the `interaction` fill is tape ink (design `palette.rule`).
 */
@Composable
internal fun StepPanel(state: DeskUiState, filled: Boolean, onEvent: (DeskEvent) -> Unit) {
    val shape = RoundedCornerShape(PanelCorner)
    val disabled = state.phase == Phase.STARTING || state.phase == Phase.SCANNING || state.phase == Phase.BLOCKED
    Column(
        modifier =
            Modifier
                .size(StepPanelWidth, StepPanelHeight)
                .clip(shape)
                .backgroundMaterial(enable = true, style = Material.Regular)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!filled) return@Column
        StepDots(current = state.phase.step, done = state.phase.step - 1, menuEnabled = !disabled, onEvent = onEvent)
        Row(modifier = Modifier.fillMaxWidth().height(104.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Pictogram(state)
            StepCopy(state, modifier = Modifier.weight(1f))
        }
        BottomRow(state, disabled, onEvent)
    }
}

/** Four 36 dp circles with 24 dp icons: current = interaction fill, done = dark fill + check, pending = labelQuaternary outline. */
@Composable
internal fun StepDots(current: Int, done: Int, menuEnabled: Boolean, onEvent: (DeskEvent) -> Unit) {
    val icons = listOf(R.drawable.ic_step_sit, R.drawable.ic_step_screen, R.drawable.ic_step_arm, R.drawable.ic_step_result)
    val names = listOf(rs(R.string.step_sit), rs(R.string.step_screen), rs(R.string.step_arm), rs(R.string.step_result))
    Row(modifier = Modifier.fillMaxWidth().height(KeyHeight), verticalAlignment = Alignment.CenterVertically) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            for (i in 1..4) {
                val isCurrent = i == current
                val isDone = i <= done
                val fill = when { isCurrent -> PicoTheme.colorScheme.interaction; isDone -> DeskColors.TapeInk.copy(alpha = 0.85f); else -> Color.Transparent }
                val tint = when { isCurrent -> DeskColors.TapeInk; isDone -> DeskColors.LabelPrimaryLight; else -> PicoTheme.colorScheme.labelQuaternary }
                Box(
                    modifier =
                        Modifier
                            .size(DotSize)
                            .clip(CircleShape)
                            .background(fill)
                            .then(if (!isCurrent && !isDone) Modifier.border(2.dp, PicoTheme.colorScheme.labelQuaternary, CircleShape) else Modifier)
                            .semantics { contentDescription = names[i - 1] },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painter = painterResource(if (isDone) R.drawable.ic_check else icons[i - 1]), contentDescription = null, modifier = Modifier.size(24.dp), tint = tint)
                }
                if (i < 4) {
                    Box(modifier = Modifier.weight(1f).padding(horizontal = 4.dp).height(2.dp).background(PicoTheme.colorScheme.labelQuaternary))
                }
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        IconButton(
            onClick = { onEvent(DeskEvent.MoreClicked) },
            enabled = menuEnabled,
            modifier = Modifier.probe("more") { onEvent(DeskEvent.MoreClicked) },
            colors = IconButtonDefaults.iconButtonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = PicoTheme.colorScheme.labelPrimary),
            size = IconButtonDefaults.iconButtonSize(width = KeyWidth, height = KeyHeight),
        ) {
            Icon(painter = painterResource(R.drawable.ic_more), contentDescription = rs(R.string.action_more), modifier = Modifier.size(24.dp), tint = PicoTheme.colorScheme.labelPrimary)
        }
    }
}

@Composable
private fun Pictogram(state: DeskUiState) {
    val ink = PicoTheme.colorScheme.labelPrimary
    Box(modifier = Modifier.size(96.dp), contentAlignment = Alignment.Center) {
        when (state.phase) {
            Phase.STARTING -> CircularProgressIndicator(modifier = Modifier.size(40.dp), color = ink, progressSize = CircularProgressDefaults.circleProgressSize(40.dp))
            Phase.SCANNING -> {
                Icon(painter = painterResource(R.drawable.pg_look_desk), contentDescription = null, modifier = Modifier.size(96.dp).alpha(0.6f), tint = ink)
                CircularProgressIndicator(modifier = Modifier.size(28.dp), color = ink, progressSize = CircularProgressDefaults.circleProgressSize(28.dp))
            }
            else -> {
                val res =
                    when (state.phase) {
                        Phase.SIT, Phase.SAMPLING, Phase.EYE_LOCKED, Phase.UNSTABLE, Phase.BLOCKED -> R.drawable.pg_sit
                        Phase.MARK_SCREEN -> R.drawable.pg_tap_under
                        Phase.ALIGN_TOP -> R.drawable.pg_bar_top
                        Phase.FOREARM -> R.drawable.pg_arm_level
                        Phase.DESK_MANUAL_A, Phase.DESK_MANUAL_B -> R.drawable.pg_desk_panel
                        Phase.POSTURE -> if (state.posture == PostureKind.STANDING) R.drawable.pg_standing else R.drawable.pg_sit
                        else -> R.drawable.pg_sit
                    }
                Icon(painter = painterResource(res), contentDescription = null, modifier = Modifier.size(96.dp), tint = ink)
            }
        }
    }
}

@Composable
private fun StepCopy(state: DeskUiState, modifier: Modifier) {
    val ink = PicoTheme.colorScheme.labelPrimary
    val eye = state.eyeH?.let { format.withUnit(it, state.unit) } ?: ""
    val copy =
        when (state.phase) {
            Phase.STARTING -> rs(R.string.copy_starting)
            Phase.SCANNING -> rs(R.string.copy_scanning)
            Phase.SIT, Phase.UNSTABLE, Phase.BLOCKED -> rs(R.string.copy_sit)
            Phase.SAMPLING -> rs(R.string.copy_sampling)
            Phase.EYE_LOCKED -> rs(R.string.copy_eye_locked, eye)
            Phase.MARK_SCREEN -> rs(R.string.copy_mark_screen)
            Phase.ALIGN_TOP -> rs(R.string.copy_align_top)
            Phase.FOREARM -> rs(R.string.copy_forearm)
            Phase.DESK_MANUAL_A -> rs(R.string.copy_manual_a)
            Phase.DESK_MANUAL_B -> rs(R.string.copy_manual_b)
            Phase.POSTURE -> if (state.posture == PostureKind.STANDING) rs(R.string.copy_posture_standing, eye) else rs(R.string.copy_posture_low)
            Phase.RESULT -> ""
        }
    val hint =
        when (state.phase) {
            Phase.SIT, Phase.UNSTABLE -> state.lastResult?.let { last ->
                val date = SimpleDateFormat("MM-dd", Locale.US).format(Date(last.epochMs))
                rs(R.string.copy_last, date, lastSummary(last, state))
            } ?: rs(R.string.hint_sit)
            Phase.ALIGN_TOP -> rs(R.string.hint_align_top)
            Phase.FOREARM -> rs(R.string.hint_forearm)
            else -> null
        }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = copy, color = ink, style = DeskType.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (hint != null) Text(text = hint, color = ink, style = DeskType.hint, maxLines = 2, overflow = TextOverflow.Ellipsis)
        // factory default interaction D1: the thumbstick turns the bar on the pole
        if (state.phase == Phase.ALIGN_TOP) Text(text = rs(R.string.hint_joystick), color = ink, style = PicoTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "椅 ↑4 · 屏 ↑6 · 距离合适" with figures only (arrows as characters are allowed in a one-line summary). */
@Composable
private fun lastSummary(last: io.embodit.deskfit.domain.model.SavedResult, state: DeskUiState): String {
    fun arrow(d: Float) = if (d > 0f) "↑" else "↓"
    val chair = if (last.chairDelta == 0f) "${rs(R.string.summary_chair)} ${rs(R.string.summary_ok)}" else "${rs(R.string.summary_chair)} ${arrow(last.chairDelta)}${format.delta(last.chairDelta, state.unit)}"
    val screen = if (last.screenDelta == 0f) "${rs(R.string.summary_screen)} ${rs(R.string.summary_ok)}" else "${rs(R.string.summary_screen)} ${arrow(last.screenDelta)}${format.delta(last.screenDelta, state.unit)}"
    val dist = if (last.distanceDelta == 0f) "${rs(R.string.summary_distance)} ${rs(R.string.summary_ok)}" else "${rs(R.string.summary_distance)} ${if (last.distanceDelta > 0f) "→" else "←"}${format.delta(last.distanceDelta, state.unit)}"
    return "$chair · $screen · $dist"
}

@Composable
private fun BottomRow(state: DeskUiState, disabled: Boolean, onEvent: (DeskEvent) -> Unit) {
    val showBack =
        when (state.phase) {
            Phase.MARK_SCREEN, Phase.ALIGN_TOP, Phase.FOREARM, Phase.DESK_MANUAL_B -> true
            Phase.DESK_MANUAL_A -> state.manualHasBack
            else -> false
        }
    val primaryText =
        when (state.phase) {
            Phase.SIT, Phase.UNSTABLE, Phase.STARTING, Phase.SCANNING, Phase.BLOCKED -> rs(R.string.action_ready)
            Phase.ALIGN_TOP, Phase.FOREARM, Phase.DESK_MANUAL_B -> rs(R.string.action_done)
            Phase.MARK_SCREEN -> rs(R.string.action_done)
            Phase.DESK_MANUAL_A -> rs(R.string.action_next)
            Phase.POSTURE -> if (state.posture == PostureKind.STANDING) rs(R.string.action_retry) else rs(R.string.action_continue)
            Phase.SAMPLING, Phase.EYE_LOCKED, Phase.RESULT -> ""
        }
    val primaryIcon = if (state.phase == Phase.POSTURE && state.posture == PostureKind.STANDING) R.drawable.ic_retry else R.drawable.ic_check
    val primaryEnabled =
        when (state.phase) {
            Phase.SIT, Phase.UNSTABLE -> !disabled && !state.trackingLost
            Phase.ALIGN_TOP, Phase.FOREARM, Phase.DESK_MANUAL_A, Phase.POSTURE -> true
            Phase.DESK_MANUAL_B -> state.manualEdgeTapped
            else -> false
        }
    val secondary: Pair<String, Boolean>? =
        when (state.phase) {
            Phase.SIT, Phase.UNSTABLE -> rs(R.string.action_wrong_desk) to true
            Phase.POSTURE ->
                if (state.posture == PostureKind.STANDING) rs(R.string.action_keep) to DeskRulesUseCase().keepAllowed(state.eyeH ?: 0f)
                else rs(R.string.action_retry) to true
            else -> null
        }
    Row(modifier = Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showBack) {
            IconButton(
                onClick = { onEvent(DeskEvent.BackClicked) },
                modifier = Modifier.probe("back") { onEvent(DeskEvent.BackClicked) },
                colors = IconButtonDefaults.iconButtonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = PicoTheme.colorScheme.labelPrimary),
                size = IconButtonDefaults.iconButtonSize(width = KeyWidth, height = KeyHeight),
            ) {
                Icon(painter = painterResource(R.drawable.ic_back), contentDescription = rs(R.string.action_back), modifier = Modifier.size(24.dp), tint = PicoTheme.colorScheme.labelPrimary)
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        if (secondary != null) {
            val (text, enabled) = secondary
            val content = if (enabled) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelQuaternary
            Button(
                onClick = { onEvent(DeskEvent.SecondaryClicked) },
                enabled = enabled,
                modifier = Modifier.probe("secondary") { onEvent(DeskEvent.SecondaryClicked) },
                size = ButtonDefaults.Regular.copy(height = KeyHeight, minWidth = 96.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = content),
            ) {
                Text(text = text, color = content, maxLines = 1)
            }
        }
        if (primaryText.isNotEmpty()) {
            val content = if (primaryEnabled) DeskColors.TapeInk else PicoTheme.colorScheme.labelQuaternary
            Button(
                onClick = { onEvent(DeskEvent.PrimaryClicked) },
                enabled = primaryEnabled,
                modifier = Modifier.probe("primary") { onEvent(DeskEvent.PrimaryClicked) },
                size = ButtonDefaults.Regular.copy(height = KeyHeight, minWidth = 132.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = if (primaryEnabled) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.fillTertiary, contentColor = content),
            ) {
                Icon(painter = painterResource(primaryIcon), contentDescription = null, modifier = Modifier.size(20.dp), tint = content)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = primaryText, color = content, maxLines = 1)
            }
        }
    }
}
