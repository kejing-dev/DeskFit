package io.embodit.deskfit.ui.deskfit.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import io.embodit.deskfit.domain.model.Phase
import io.embodit.deskfit.ui.deskfit.DeskEvent
import io.embodit.deskfit.ui.deskfit.DeskUiState
import io.embodit.deskfit.ui.deskfit.scene.DeskScene
import io.embodit.deskfit.ui.deskfit.scene.PanelIds

/**
 * The Stage content: one full-size SpatialView with the 3D scene and the pre-built panel pool.
 * Every attachment panel is declared here once, with a fixed size (a panel created or resized on
 * demand takes seconds to appear on the emulator and shows stale rows); [DeskScene] parents each
 * panel entity under its holder and shows or hides the holder. Window model: single panel with
 * popups (step panel / result card standing; plates, menu, dialog, snackbar floating).
 */
@Composable
internal fun DeskStage(state: DeskUiState, scene: DeskScene, onEvent: (DeskEvent) -> Unit) {
    val context = LocalContext.current
    val bindTick = scene.bindTick.intValue
    SpatialView(
        modifier =
            Modifier
                .fillMaxSize()
                // tap and drag live in separate pointerInput blocks (two spatial recognizers must not share one)
                .pointerInput(Unit) {
                    detectSpatialTapGesture(context = context, targetedToEntity = TargetEntity.any()) { value -> scene.onSpatialTap(value) }
                }
                .pointerInput(Unit) {
                    detectSpatialDragGesture(
                        context = context,
                        targetedToEntity = TargetEntity.any(),
                        onDragStart = { start, value -> scene.onSpatialDrag("start", start, value) },
                        onDragEnd = { value -> scene.onSpatialDrag("end", null, value) },
                        onDragCancel = { value -> scene.onSpatialDrag("cancel", null, value) },
                    ) { value ->
                        scene.onSpatialDrag("move", null, value)
                    }
                },
        attachments = {
            AttachmentPanel(PanelIds.STEP) {
                CompositionLocalProvider(LocalProbePanel provides PanelIds.STEP) {
                    StepPanel(state = state, filled = scene.panelShown[PanelIds.STEP] == true, onEvent = onEvent)
                }
            }
            AttachmentPanel(PanelIds.RESULT) {
                CompositionLocalProvider(LocalProbePanel provides PanelIds.RESULT) {
                    ResultPanel(state = state, filled = scene.panelShown[PanelIds.RESULT] == true && state.phase == Phase.RESULT, onEvent = onEvent)
                }
            }
            PanelIds.plates.forEach { id ->
                AttachmentPanel(id) {
                    ReadoutPlate(plate = if (scene.panelShown[id] == true) scene.plates[id] else null, stale = scene.stale.value)
                }
            }
            PanelIds.arcs.forEach { id ->
                AttachmentPanel(id) {
                    ArcLabel(text = if (scene.panelShown[id] == true) scene.arcLabels[id] else null)
                }
            }
            AttachmentPanel(PanelIds.MENU) {
                CompositionLocalProvider(LocalProbePanel provides PanelIds.MENU) {
                    MenuPanel(state = state, filled = scene.panelShown[PanelIds.MENU] == true, onEvent = onEvent)
                }
            }
            AttachmentPanel(PanelIds.DIALOG) {
                CompositionLocalProvider(LocalProbePanel provides PanelIds.DIALOG) {
                    DialogPanel(blocked = scene.panelShown[PanelIds.DIALOG] == true && state.phase == Phase.BLOCKED, onEvent = onEvent)
                }
            }
            AttachmentPanel(PanelIds.SNACK) {
                SnackPanel(
                    snack = if (scene.panelShown[PanelIds.SNACK] == true) state.snack else null,
                    applied = scene.panelStretch[PanelIds.SNACK] ?: 1f,
                    onWidth = { scene.reportSnackWidth(it) },
                )
            }
        },
        update = { _, attachments ->
            @Suppress("UNUSED_EXPRESSION") bindTick
            scene.bindPanels(attachments)
        },
        initial = { content, attachments -> scene.attach(content, attachments) },
    )
}
