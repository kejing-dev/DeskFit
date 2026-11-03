package io.embodit.deskfit.domain.usecase

import io.embodit.deskfit.domain.model.Vec3
import kotlin.math.abs
import kotlin.math.hypot

/** One head-pose sample during the 1.4 s window: Stage position and the floor height under it. */
data class EyeSample(val tMs: Long, val position: Vec3, val floorY: Float)

/** Outcome of the sampling window. */
sealed interface EyeSampleResult {
    /** Eye height above the floor, the horizontal head position and how long the stable tail was. */
    data class Stable(val eyeH: Float, val headXZ: Vec3, val tailMs: Long, val count: Int) : EyeSampleResult

    data class Unstable(val tailMs: Long, val count: Int) : EyeSampleResult
}

/**
 * Design `rules.eye`: discard non-finite samples, the origin pose (0, 0, 0) and samples less than
 * 0.3 m above the floor; from the last sample backwards take the run whose vertical difference to
 * the last sample is <= 3 cm and horizontal difference <= 5 cm; the tail must span >= 0.5 s and
 * hold >= 8 samples; the eye height is the mean of (y - floorY) over the tail. Pure: unit-tested.
 */
class EyeSampleUseCase {

    fun valid(position: Vec3, floorY: Float): Boolean =
        position.isFinite() && position.length > ORIGIN_EPS_M && position.y - floorY >= MIN_ABOVE_FLOOR_M

    fun evaluate(samples: List<EyeSample>): EyeSampleResult {
        val good = samples.filter { valid(it.position, it.floorY) }
        if (good.isEmpty()) return EyeSampleResult.Unstable(0L, 0)
        val last = good.last()
        var first = good.size - 1
        while (first > 0) {
            val s = good[first - 1]
            val dy = abs(s.position.y - last.position.y)
            val dxz = hypot(s.position.x - last.position.x, s.position.z - last.position.z)
            if (dy > TAIL_VERTICAL_M || dxz > TAIL_HORIZONTAL_M) break
            first--
        }
        val tail = good.subList(first, good.size)
        val span = last.tMs - tail.first().tMs
        if (span < MIN_TAIL_MS || tail.size < MIN_TAIL_SAMPLES) return EyeSampleResult.Unstable(span, tail.size)
        var sumH = 0f
        var sumX = 0f
        var sumZ = 0f
        for (s in tail) {
            sumH += s.position.y - s.floorY
            sumX += s.position.x
            sumZ += s.position.z
        }
        val n = tail.size.toFloat()
        return EyeSampleResult.Stable(sumH / n, Vec3(sumX / n, 0f, sumZ / n), span, tail.size)
    }

    companion object {
        const val WINDOW_MS = 1400L
        const val MIN_TAIL_MS = 500L
        const val MIN_TAIL_SAMPLES = 8
        const val TAIL_VERTICAL_M = 0.03f
        const val TAIL_HORIZONTAL_M = 0.05f
        const val MIN_ABOVE_FLOOR_M = 0.3f
        private const val ORIGIN_EPS_M = 1e-4f
    }
}
