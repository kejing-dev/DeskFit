package io.embodit.deskfit.domain.usecase

import io.embodit.deskfit.domain.model.Judgement
import io.embodit.deskfit.domain.model.LengthUnit
import io.embodit.deskfit.domain.model.RowVerdict
import io.embodit.deskfit.domain.model.Vec3
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The verdicts of the design (`rules.chair`, `eyeAfter`, `screenHeight`, `screenDistance`,
 * `screenSide`, `allGood`). All heights are metres above the floor; deltas are rounded to the
 * display unit (1 cm or 1 in) so that the tape, the plate and the panel say the same number.
 */
class JudgeUseCase {

    /**
     * @param eyeH sitting eye height
     * @param deskH desk top height
     * @param forearmH height of the forearm bar
     * @param topH height of the screen's top edge (the bar on the pole)
     * @param distance horizontal distance head -> pole, metres
     * @param sideDeg angle between (pole - head) and the desk edge's inward normal; null without an edge
     */
    fun judge(eyeH: Float, deskH: Float, forearmH: Float, topH: Float, distance: Float, sideDeg: Float?, unit: LengthUnit): Judgement {
        // chair: elbow ~ keyboard height (desk + 2.5 cm), within 3 cm
        val chairNeed = deskH + KEYBOARD_M - forearmH
        val chairOk = abs(chairNeed) <= CHAIR_TOLERANCE_M
        val chairDelta = if (chairOk) 0f else round(chairNeed, unit)
        val chairNote =
            when {
                chairOk -> null
                chairDelta > 0f && chairDelta <= NOTE_STEP_M -> RowVerdict.Note.FOOTREST
                chairDelta > NOTE_STEP_M -> RowVerdict.Note.TRAY
                chairDelta < -NOTE_STEP_M -> RowVerdict.Note.LOW_DESK
                else -> null
            }
        val chair = RowVerdict(chairOk, chairDelta, chairNote)

        // eye after the chair change (only when it changes), then the top-edge band
        val eyeAfter = eyeH + chairDelta
        val bandLow = eyeAfter - BAND_M
        val target = eyeAfter - TARGET_BELOW_M
        val heightOk = topH >= bandLow - EPS && topH <= eyeAfter + EPS
        val heightDelta = if (heightOk) 0f else round(target - topH, unit)
        val heightNote =
            when {
                !chairOk -> RowVerdict.Note.AFTER_CHAIR
                heightDelta > NOTE_STEP_M -> RowVerdict.Note.LAPTOP
                else -> null
            }
        val screenHeight = RowVerdict(heightOk, heightDelta, heightNote)

        // distance 50 - 100 cm
        val distanceOk = distance >= DIST_MIN_M - EPS && distance <= DIST_MAX_M + EPS
        val distanceDelta =
            when {
                distanceOk -> 0f
                distance < DIST_MIN_M -> round(DIST_PUSH_TO_M - distance, unit)
                else -> -round(distance - DIST_PULL_TO_M, unit)
            }
        val screenDistance = RowVerdict(distanceOk, distanceDelta)

        val offCentre = sideDeg != null && sideDeg > SIDE_MAX_DEG
        return Judgement(chair, screenHeight, screenDistance, offCentre, eyeAfter)
    }

    /** Angle in degrees between the horizontal direction head -> pole and [inward]; null when degenerate. */
    fun sideAngleDeg(pole: Vec3, headXZ: Vec3, inward: Vec3?): Float? {
        if (inward == null) return null
        val dx = pole.x - headXZ.x
        val dz = pole.z - headXZ.z
        val len = hypot(dx, dz)
        if (len < 1e-4f) return null
        val n = Vec3(inward.x, 0f, inward.z).normalized()
        val cos = ((dx * n.x + dz * n.z) / len).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cos).toDouble()).toFloat()
    }

    /** Rounds a length to whole display units (1 cm / 1 in), keeping the sign. */
    fun round(metres: Float, unit: LengthUnit): Float {
        val step = if (unit == LengthUnit.CM) 0.01f else INCH_M
        return (metres / step).roundToInt() * step
    }

    companion object {
        const val KEYBOARD_M = 0.025f
        const val CHAIR_TOLERANCE_M = 0.03f
        const val NOTE_STEP_M = 0.15f
        const val BAND_M = 0.05f
        const val TARGET_BELOW_M = 0.02f
        const val DIST_MIN_M = 0.50f
        const val DIST_MAX_M = 1.00f
        const val DIST_PUSH_TO_M = 0.55f
        const val DIST_PULL_TO_M = 0.95f
        const val SIDE_MAX_DEG = 35f
        const val INCH_M = 0.0254f
        private const val EPS = 1e-4f

        /** `rules.forearmDefault`: sitting eye height minus sitting elbow height, population mean. */
        const val EYE_TO_ELBOW_M = 0.53f

        /** Default bar height on a fresh pole: desk + 45 cm (S7). */
        const val BAR_DEFAULT_ABOVE_DESK_M = 0.45f
        const val BAR_MIN_ABOVE_DESK_M = 0.05f
        const val BAR_MAX_ABOVE_DESK_M = 0.90f
        const val FOREARM_RANGE_M = 0.25f
    }
}
