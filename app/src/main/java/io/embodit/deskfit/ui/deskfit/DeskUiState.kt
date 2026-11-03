package io.embodit.deskfit.ui.deskfit

import io.embodit.deskfit.domain.model.BlockReason
import io.embodit.deskfit.domain.model.DeskPick
import io.embodit.deskfit.domain.model.Judgement
import io.embodit.deskfit.domain.model.LanguagePref
import io.embodit.deskfit.domain.model.LengthUnit
import io.embodit.deskfit.domain.model.Phase
import io.embodit.deskfit.domain.model.PostureKind
import io.embodit.deskfit.domain.model.SavedResult
import io.embodit.deskfit.domain.model.SnackKind
import io.embodit.deskfit.domain.model.Vec3
import io.embodit.deskfit.domain.usecase.EyeSampleResult

/** A snackbar on screen (one at a time; design: at most one). */
data class SnackUi(val id: Int, val kind: SnackKind, val shownAtMs: Long)

/**
 * Everything the panels and the scene render. Heights of 3D things are absolute Stage y; the rules
 * work with heights above [floorY]. Immutable; changed only by [DeskViewModel].
 */
data class DeskUiState(
    val phase: Phase = Phase.STARTING,
    val language: LanguagePref = LanguagePref.SYSTEM,
    val unit: LengthUnit = LengthUnit.CM,
    val menuOpen: Boolean = false,
    val trackingLost: Boolean = false,
    val blocked: BlockReason? = null,
    val snack: SnackUi? = null,
    val lastResult: SavedResult? = null,
    /** QA hook: transients (S4 / S5 / S12) stay on their picture. */
    val hold: Boolean = false,
    // ---- room
    val floorY: Float? = null,
    val desk: DeskPick? = null,
    /** S11: the desk is being set by hand (auto TABLE planes are ignored until "start over"). */
    val manualDesk: Boolean = false,
    /** S11a: absolute y of the glass plate. */
    val manualPlateY: Float = 0f,
    /** S11 reached from [桌子不对]: the first sub-step has a back key. */
    val manualHasBack: Boolean = false,
    /** Until when the recognised desk's outline flashes (S3). */
    val deskFlashUntilMs: Long = 0L,
    // ---- measurement
    val samplingStartMs: Long = 0L,
    val eyeLockedAtMs: Long = 0L,
    /** Sitting eye height above the floor, metres. */
    val eyeH: Float? = null,
    /** Head position at the end of sampling (y = 0) and its horizontal forward. */
    val headXZ: Vec3? = null,
    val headFwd: Vec3? = null,
    val posture: PostureKind? = null,
    /** Foot of the screen pole on the desk (absolute Stage metres). */
    val pole: Vec3? = null,
    /** Last pole of this session (30 % ghost while measuring again), cleared on resume. */
    val ghostPole: Vec3? = null,
    /** Absolute y of the bar on the pole and of the forearm bar. */
    val barY: Float = 0f,
    val forearmY: Float = 0f,
    val judgement: Judgement? = null,
    /** S11b: the hand-set desk edge, set by a tap. */
    val manualEdgeTapped: Boolean = false,
) {
    val deskH: Float?
        get() {
            val f = floorY ?: return null
            val d = desk ?: return null
            return d.topY - f
        }

    val isResult: Boolean
        get() = phase == Phase.RESULT

    val allGood: Boolean
        get() = judgement?.allGood == true

    /** The state id the QA hooks and the log name: S10 / S14 / S15 / S17 are derived. */
    val stateId: String
        get() =
            when {
                blocked != null && phase == Phase.BLOCKED -> "S16"
                trackingLost -> "S15"
                menuOpen -> "S14"
                phase == Phase.RESULT && allGood -> "S10"
                phase == Phase.SIT && lastResult != null -> "S17"
                else -> phase.stateId
            }
}

/** Everything that can happen: panel controls, scene callbacks, QA hooks. */
sealed interface DeskEvent {
    // scene
    data object SceneReady : DeskEvent

    data class PlanesChanged(val floorY: Float?, val desk: DeskPick?, val revision: Int) : DeskEvent

    data class TrackingChanged(val lost: Boolean) : DeskEvent

    data class BlockChanged(val reason: BlockReason?) : DeskEvent

    data class SampleFinished(val result: EyeSampleResult, val forward: Vec3) : DeskEvent

    /** A tap on a desk plane ([planeId]) or on the manual plane (null), [point] in Stage metres. */
    data class DeskTapped(val planeId: String?, val point: Vec3) : DeskEvent

    data object GhostTapped : DeskEvent

    data object ElsewhereTapped : DeskEvent

    /** The bar on the pole / the forearm bar / the manual plate was released at absolute y. */
    data class BarMoved(val kind: BarKind, val y: Float) : DeskEvent

    // step panel
    data object PrimaryClicked : DeskEvent

    data object SecondaryClicked : DeskEvent

    data object BackClicked : DeskEvent

    data object MoreClicked : DeskEvent

    data object MenuDismissed : DeskEvent

    data class LanguageSelected(val language: LanguagePref) : DeskEvent

    data class UnitSelected(val unit: LengthUnit) : DeskEvent

    data object RestartClicked : DeskEvent

    data object ExitClicked : DeskEvent

    data object BlockedRetryClicked : DeskEvent

    data object BlockedExitClicked : DeskEvent

    data class SnackClosed(val id: Int) : DeskEvent

    // QA hooks (debug builds)
    data class DebugState(val id: String) : DeskEvent

    data class DebugEye(val metres: Float) : DeskEvent

    data class DebugTap(val x: Float, val z: Float) : DeskEvent

    data class DebugBar(val kind: BarKind, val metresAboveFloor: Float) : DeskEvent

    data class DebugHold(val on: Boolean) : DeskEvent

    data object DebugReset : DeskEvent
}

enum class BarKind { SCREEN, FOREARM, MANUAL_PLATE }

sealed interface DeskUiEffect {
    data object ExitApp : DeskUiEffect
}
