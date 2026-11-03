package io.embodit.deskfit.ui.deskfit.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.deskfit.R
import io.embodit.deskfit.domain.model.SnackKind
import io.embodit.deskfit.ui.deskfit.DeskEvent
import io.embodit.deskfit.ui.deskfit.SnackUi
import io.embodit.deskfit.ui.theme.DeskColors
import io.embodit.deskfit.ui.theme.DeskType
import kotlin.math.abs

/** Unavailable dialog 400 x 236 dp at 0.6, 0.5 m ahead (design `panels.blockedDialog`). */
internal val DialogPanelWidth = 400.dp
internal val DialogPanelHeight = 236.dp

/** Snackbar 240 - 560 x 56 dp at 0.75, head-locked 14 degrees up, 0.5 m (design `panels.snackbar`). */
internal val SnackPanelWidth = 560.dp
internal val SnackPanelHeight = 56.dp
private const val FADE_IN_MS = 150

/**
 * "Spatial features unavailable": a pre-built attachment panel to the AlertDialog metrics (Thick, 32 dp
 * padding, 32 dp corners). `AlertDialog` itself composes nothing in a Stage (Spatial Ruler finding).
 */
@Composable
internal fun DialogPanel(blocked: Boolean, onEvent: (DeskEvent) -> Unit) {
    val shape = RoundedCornerShape(32.dp)
    Box(modifier = Modifier.size(DialogPanelWidth, DialogPanelHeight), contentAlignment = Alignment.Center) {
        if (!blocked) return@Box
        val fade = remember { Animatable(0f) }
        LaunchedEffect(Unit) { fade.animateTo(1f, tween(FADE_IN_MS)) }
        val ink = PicoTheme.colorScheme.labelPrimary
        Column(
            modifier = Modifier.size(DialogPanelWidth, DialogPanelHeight).graphicsLayer { alpha = fade.value }.clip(shape).backgroundMaterial(enable = true, style = Material.Thick).padding(32.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(painter = painterResource(R.drawable.ic_unavailable), contentDescription = null, modifier = Modifier.size(32.dp), tint = ink)
                Text(text = rs(R.string.dialog_blocked_title), color = ink, style = PicoTheme.typography.headlineLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = rs(R.string.dialog_blocked_body), color = ink, style = PicoTheme.typography.bodyLargeMultiline, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.weight(1f))
            Row(modifier = Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                DialogButton("blocked_exit", R.drawable.ic_exit, rs(R.string.action_exit), PicoTheme.colorScheme.fillTertiary, ink) { onEvent(DeskEvent.BlockedExitClicked) }
                DialogButton("blocked_retry", R.drawable.ic_retry, rs(R.string.action_retry_blocked), PicoTheme.colorScheme.interaction, DeskColors.TapeInk) { onEvent(DeskEvent.BlockedRetryClicked) }
            }
        }
    }
}

@Composable
private fun DialogButton(name: String, iconRes: Int, text: String, container: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.probe(name, onClick),
        size = ButtonDefaults.Regular.copy(height = 48.dp, minWidth = 120.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
    ) {
        Icon(painter = painterResource(iconRes), contentDescription = null, modifier = Modifier.size(20.dp), tint = content)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = text, color = content, maxLines = 1)
    }
}

/**
 * The snackbar: a head-locked panel to the SpatialUI snackbar metrics (28 dp corners, Thick, icon 24 +
 * one sentence). The capsule takes the width of its message; the scene squeezes the panel to that width
 * and the capsule is drawn stretched by the inverse (PanelRig finding 4), so no empty margin takes the pointer.
 */
@Composable
internal fun SnackPanel(snack: SnackUi?, applied: Float, onWidth: (Float) -> Unit) {
    Box(modifier = Modifier.size(SnackPanelWidth, SnackPanelHeight), contentAlignment = Alignment.Center) {
        if (snack == null) return@Box
        val density = LocalDensity.current
        val panelPx = with(density) { SnackPanelWidth.toPx() }
        val ink = PicoTheme.colorScheme.labelPrimary
        val fade = remember(snack.id) { Animatable(0f) }
        LaunchedEffect(snack.id) { fade.animateTo(1f, tween(FADE_IN_MS)) }
        val (icon, text) =
            when (snack.kind) {
                SnackKind.SCANNING -> R.drawable.ic_step_screen to rs(R.string.snack_scanning)
                SnackKind.NO_FLOOR -> R.drawable.ic_arrow_down to rs(R.string.snack_no_floor)
                SnackKind.NO_DESK -> R.drawable.ic_warning to rs(R.string.snack_no_desk)
                SnackKind.TAP_DESK -> R.drawable.ic_step_screen to rs(R.string.snack_tap_desk)
                SnackKind.TAP_UNDER -> R.drawable.ic_step_screen to rs(R.string.snack_tap_under)
                SnackKind.UNSTABLE -> R.drawable.ic_warning to rs(R.string.snack_unstable)
                SnackKind.LOST -> R.drawable.ic_unavailable to rs(R.string.snack_lost)
            }
        Row(
            modifier =
                Modifier
                    .widthIn(min = 240.dp, max = SnackPanelWidth)
                    .height(SnackPanelHeight)
                    .onSizeChanged { onWidth(it.width / density.density) }
                    .graphicsLayer {
                        val own = size.width / panelPx
                        scaleX = 1f / applied
                        alpha = if (abs(applied - own) <= own * 0.12f) fade.value else 0f
                    }
                    .clip(RoundedCornerShape(28.dp))
                    .backgroundMaterial(enable = true, style = Material.Thick)
                    .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp), tint = ink)
            Text(text = text, color = ink, style = DeskType.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 4.dp))
        }
    }
}
