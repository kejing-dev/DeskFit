package io.embodit.deskfit.domain.usecase

import io.embodit.deskfit.domain.model.DeskEdge
import io.embodit.deskfit.domain.model.DeskPick
import io.embodit.deskfit.domain.model.PlaneSurface
import io.embodit.deskfit.domain.model.PostureKind
import io.embodit.deskfit.domain.model.SurfaceKind
import io.embodit.deskfit.domain.model.Vec3
import kotlin.math.hypot

/**
 * The room rules of the design: which FLOOR is the zero point, which TABLE is the typing desk and
 * where its near edge is (`rules.desk`), which eye heights are suspicious (`rules.eyeRange`) and
 * which desk taps may raise the pole (`rules.screenTap`). Pure: unit-tested.
 */
class DeskRulesUseCase(private val hits: HitSurfaceUseCase = HitSurfaceUseCase()) {

    /** Floor height under the head: the FLOOR plane whose polygon contains the head's foot point, else the nearest FLOOR. */
    fun floorY(planes: Collection<PlaneSurface>, head: Vec3): Float? {
        val floors = planes.filter { it.kind == SurfaceKind.FLOOR }
        if (floors.isEmpty()) return null
        val foot = Vec3(head.x, 0f, head.z)
        floors.firstOrNull { f -> hits.contains(f, Vec3(foot.x, f.y, foot.z), margin = 0.05f) }?.let { return it.y }
        return floors.minByOrNull { hypot(it.centroid.x - head.x, it.centroid.z - head.z) }?.y
    }

    /** True when the plane is a desk candidate: a TABLE 50 - 110 cm above the floor whose quad comes within [reach] of the head. */
    fun qualifies(plane: PlaneSurface, floorY: Float, head: Vec3, reach: Float = REACH_M): Boolean {
        if (plane.kind != SurfaceKind.TABLE) return false
        val h = plane.y - floorY
        if (h < DESK_MIN_M || h > DESK_MAX_M) return false
        return distanceXZ(plane, head) <= reach
    }

    /** The typing desk: the qualifying TABLE nearest to the head, with its near edge; null when none qualifies. */
    fun pickDesk(planes: Collection<PlaneSurface>, floorY: Float, head: Vec3): DeskPick? {
        val best = planes.filter { qualifies(it, floorY, head) }.minByOrNull { distanceXZ(it, head) } ?: return null
        return DeskPick(best.id, best.y, nearEdge(best, head), best.vertices)
    }

    /** The polygon edge nearest to the head, with the horizontal normal that points into the polygon. */
    fun nearEdge(plane: PlaneSurface, head: Vec3): DeskEdge? {
        if (plane.vertices.size < 3) return null
        var best: Pair<Vec3, Vec3>? = null
        var bestD = Float.MAX_VALUE
        for ((a, b) in plane.edges) {
            val d = segmentDistanceXZ(a, b, head)
            if (d < bestD) {
                bestD = d
                best = a to b
            }
        }
        val (a, b) = best ?: return null
        val along = Vec3(b.x - a.x, 0f, b.z - a.z)
        if (along.length < 1e-4f) return null
        var inward = Vec3(-along.z, 0f, along.x).normalized()
        val c = plane.centroid
        val mid = (a + b) * 0.5f
        if (Vec3(c.x - mid.x, 0f, c.z - mid.z).dot(inward) < 0f) inward = -inward
        return DeskEdge(Vec3(a.x, plane.y, a.z), Vec3(b.x, plane.y, b.z), inward)
    }

    /** `rules.eyeRange`: STANDING / LOW / null (fine). Heights in metres above the floor. */
    fun posture(eyeH: Float, deskH: Float): PostureKind? =
        when {
            eyeH > STANDING_EYE_M || eyeH - deskH > STANDING_OVER_DESK_M -> PostureKind.STANDING
            eyeH < LOW_EYE_M || eyeH - deskH < LOW_OVER_DESK_M -> PostureKind.LOW
            else -> null
        }

    /** "Keep" is only offered for a standing-looking eye height up to 170 cm. */
    fun keepAllowed(eyeH: Float): Boolean = eyeH <= KEEP_MAX_EYE_M

    /** `rules.screenTap`: the tapped desk point must be 30 - 150 cm from the sampled head position, horizontally. */
    fun tapInRange(p: Vec3, headXZ: Vec3): Boolean {
        val d = hypot(p.x - headXZ.x, p.z - headXZ.z)
        return d >= TAP_MIN_M && d <= TAP_MAX_M
    }

    /** Horizontal distance from the head to the nearest point of the polygon's edges (0 when the head is over it). */
    fun distanceXZ(plane: PlaneSurface, head: Vec3): Float {
        if (hits.contains(plane, Vec3(head.x, plane.y, head.z), margin = 0f)) return 0f
        var best = Float.MAX_VALUE
        for ((a, b) in plane.edges) best = minOf(best, segmentDistanceXZ(a, b, head))
        return best
    }

    private fun segmentDistanceXZ(a: Vec3, b: Vec3, p: Vec3): Float {
        val ax = a.x
        val az = a.z
        val dx = b.x - ax
        val dz = b.z - az
        val l2 = dx * dx + dz * dz
        val t = if (l2 < 1e-9f) 0f else (((p.x - ax) * dx + (p.z - az) * dz) / l2).coerceIn(0f, 1f)
        return hypot(p.x - (ax + dx * t), p.z - (az + dz * t))
    }

    companion object {
        const val REACH_M = 1.2f
        const val DESK_MIN_M = 0.50f
        const val DESK_MAX_M = 1.10f
        const val STANDING_EYE_M = 1.50f
        const val STANDING_OVER_DESK_M = 0.68f
        const val LOW_EYE_M = 0.95f
        const val LOW_OVER_DESK_M = 0.20f
        const val KEEP_MAX_EYE_M = 1.70f
        const val TAP_MIN_M = 0.30f
        const val TAP_MAX_M = 1.50f

        /** Manual desk (S11): default plate height above the floor, and the radius of the manual plane. */
        const val MANUAL_DEFAULT_M = 0.75f
        const val MANUAL_RADIUS_M = 1.5f
    }
}
