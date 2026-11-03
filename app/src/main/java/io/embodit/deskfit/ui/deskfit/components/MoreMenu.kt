package io.embodit.deskfit.ui.deskfit.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.deskfit.R
import io.embodit.deskfit.domain.model.LanguagePref
import io.embodit.deskfit.domain.model.LengthUnit
import io.embodit.deskfit.ui.deskfit.DeskEvent
import io.embodit.deskfit.ui.deskfit.DeskUiState

/** More menu 256 x 200 dp at 0.75 (design `panels.moreMenu`): language, unit, start over + exit. */
internal val MenuPanelWidth = 256.dp
internal val MenuPanelHeight = 200.dp

@Composable
internal fun MenuPanel(state: DeskUiState, filled: Boolean, onEvent: (DeskEvent) -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = Modifier.size(MenuPanelWidth, MenuPanelHeight).clip(shape).backgroundMaterial(enable = true, style = Material.Thick).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!filled) return@Column
        val ink = PicoTheme.colorScheme.labelPrimary
        SegmentControl(modifier = Modifier.fillMaxWidth().height(KeyHeight), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
            LanguagePref.entries.forEach { option ->
                SegmentItem(
                    selected = state.language == option,
                    onClick = { onEvent(DeskEvent.LanguageSelected(option)) },
                    modifier = Modifier.width(80.dp).fillMaxHeight().probe("lang_${option.tag}") { onEvent(DeskEvent.LanguageSelected(option)) },
                    textStyle = PicoTheme.typography.labelLarge,
                    colors = segmentColors(),
                    title = {
                        when (option) {
                            LanguagePref.SYSTEM -> Icon(painter = painterResource(R.drawable.ic_globe), contentDescription = rs(R.string.lang_system), modifier = Modifier.size(20.dp), tint = ink)
                            // design-style: inherited-content-color SegmentItem
                            LanguagePref.ZH -> Text(text = rs(R.string.lang_zh), maxLines = 1, softWrap = false, overflow = TextOverflow.Visible)
                            LanguagePref.EN -> {
                                val english = rs(R.string.lang_en)
                                // design-style: inherited-content-color SegmentItem
                                Text(text = rs(R.string.lang_en_short), maxLines = 1, softWrap = false, overflow = TextOverflow.Visible, modifier = Modifier.semantics { contentDescription = english })
                            }
                        }
                    },
                )
            }
        }
        SegmentControl(modifier = Modifier.fillMaxWidth().height(KeyHeight), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
            LengthUnit.entries.forEach { option ->
                SegmentItem(
                    selected = state.unit == option,
                    onClick = { onEvent(DeskEvent.UnitSelected(option)) },
                    modifier = Modifier.width(120.dp).fillMaxHeight().probe("unit_${option.tag}") { onEvent(DeskEvent.UnitSelected(option)) },
                    textStyle = PicoTheme.typography.labelLarge,
                    colors = segmentColors(),
                    // design-style: inherited-content-color SegmentItem
                    title = { Text(text = rs(if (option == LengthUnit.CM) R.string.unit_cm else R.string.unit_in), maxLines = 1, softWrap = false) },
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth().height(KeyHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onEvent(DeskEvent.RestartClicked) },
                modifier = Modifier.weight(1f).probe("menu_restart") { onEvent(DeskEvent.RestartClicked) },
                size = ButtonDefaults.Regular.copy(height = KeyHeight, minWidth = 120.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = ink),
            ) {
                Icon(painter = painterResource(R.drawable.ic_retry), contentDescription = null, modifier = Modifier.size(20.dp), tint = ink)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = rs(R.string.action_restart), color = ink, maxLines = 1)
            }
            IconButton(
                onClick = { onEvent(DeskEvent.ExitClicked) },
                modifier = Modifier.probe("menu_exit") { onEvent(DeskEvent.ExitClicked) },
                colors = IconButtonDefaults.iconButtonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = ink),
                size = IconButtonDefaults.iconButtonSize(width = KeyWidth, height = KeyHeight),
            ) {
                Icon(painter = painterResource(R.drawable.ic_exit), contentDescription = rs(R.string.action_exit), modifier = Modifier.size(24.dp), tint = ink)
            }
        }
    }
}

@Composable
private fun segmentColors() =
    SegmentControlDefaults.colors(
        itemBackgroundColor = Color.Transparent,
        itemContentColor = PicoTheme.colorScheme.labelPrimary,
        selectedItemBackgroundColor = PicoTheme.colorScheme.fillSecondary,
        selectedItemContentColor = PicoTheme.colorScheme.labelPrimary,
    )
