package io.embodit.deskfit.domain.usecase

import io.embodit.deskfit.domain.model.PlaneSurface
import io.embodit.deskfit.domain.model.SurfaceHit
import io.embodit.deskfit.domain.model.Vec3
import kotlin.math.abs

/**
 * Analytic, two-sided ray / plane-polygon intersection in Stage metres (reused from Spatial Ruler).
 * Physics ray casts cannot be used: colliders built from plane anchors are one-sided and wall quads
 * face outwards.
 */
class HitSurfaceUseCase {

    /** Nearest surface hit by the ray, or null. [direction] must be a unit vector. */
    fun nearest(planes: Collection<PlaneSurface>, origin: Vec3, direction: Vec3, maxDistance: Float = MAX_DISTANCE): SurfaceHit? {
        var best: SurfaceHit? = null
        for (plane in planes) {
            val hit = onSurface(plane, origin, direction, maxDistance) ?: continue
            val current = best
            if (current == null || hit.distance < current.distance) best = hit
        }
        return best
    }

    /** Hit of the ray with one surface, limited to its polygon. */
    fun onSurface(plane: PlaneSurface, origin: Vec3, direction: Vec3, maxDistance: Float = MAX_DISTANCE): SurfaceHit? {
        val v = plane.vertices
        val idx = plane.indices
        var best: SurfaceHit? = null
        var i = 0
        while (i + 2 < idx.size) {
            val v0 = v[idx[i]]
            val v1 = v[idx[i + 1]]
            val v2 = v[idx[i + 2]]
            i += 3
            val e1 = v1 - v0
            val e2 = v2 - v0
            val pv = direction.cross(e2)
            val det = e1.dot(pv)
            if (abs(det) < 1e-9f) continue
            val inv = 1f / det
            val tv = origin - v0
            val u = tv.dot(pv) * inv
            if (u < -EDGE_EPS || u > 1f + EDGE_EPS) continue
            val qv = tv.cross(e1)
            val w = direction.dot(qv) * inv
            if (w < -EDGE_EPS || u + w > 1f + EDGE_EPS) continue
            val t = e2.dot(qv) * inv
            if (t <= MIN_DISTANCE || t > maxDistance) continue
            val current = best
            if (current == null || t < current.distance) {
                var n = e1.cross(e2).normalized()
                if (n.dot(direction) > 0f) n = -n
                best = SurfaceHit(origin + direction * t, n, t, plane)
            }
        }
        return best
    }

    /** Hit of the ray with the unbounded plane that carries the surface (puts a collider tap back onto the plane). */
    fun onInfinitePlane(plane: PlaneSurface, origin: Vec3, direction: Vec3): SurfaceHit? {
        if (plane.vertices.isEmpty()) return null
        var n = plane.normal
        val denom = direction.dot(n)
        if (abs(denom) < 1e-6f) return null
        val t = (plane.vertices[0] - origin).dot(n) / denom
        if (t <= MIN_DISTANCE) return null
        if (denom > 0f) n = -n
        return SurfaceHit(origin + direction * t, n, t, plane)
    }

    /** Hit of a ray with the horizontal plane y = [planeY]. */
    fun onHorizontal(planeY: Float, origin: Vec3, direction: Vec3): Vec3? {
        if (abs(direction.y) < 1e-6f) return null
        val t = (planeY - origin.y) / direction.y
        if (t <= MIN_DISTANCE) return null
        return origin + direction * t
    }

    /** True when the projection of [point] lies inside the polygon (within [margin] metres). */
    fun contains(plane: PlaneSurface, point: Vec3, margin: Float = 0.002f): Boolean {
        if (plane.vertices.isEmpty()) return false
        val n = plane.normal
        val p = point - n * (point - plane.vertices[0]).dot(n)
        val v = plane.vertices
        val idx = plane.indices
        var i = 0
        while (i + 2 < idx.size) {
            val a = v[idx[i]]
            val b = v[idx[i + 1]]
            val c = v[idx[i + 2]]
            i += 3
            val sign = if ((b - a).cross(c - a).dot(n) >= 0f) 1f else -1f
            if (edgeSide(p, a, b, n) * sign >= -margin && edgeSide(p, b, c, n) * sign >= -margin && edgeSide(p, c, a, n) * sign >= -margin) return true
        }
        return false
    }

    private fun edgeSide(p: Vec3, a: Vec3, b: Vec3, n: Vec3): Float {
        val e = b - a
        val len = e.length
        if (len < 1e-9f) return 0f
        return e.cross(p - a).dot(n) / len
    }

    companion object {
        const val MAX_DISTANCE = 20f
        const val MIN_DISTANCE = 1e-4f
        private const val EDGE_EPS = 1e-5f
    }
}
