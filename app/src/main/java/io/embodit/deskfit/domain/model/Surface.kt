package io.embodit.deskfit.domain.model

/** Semantic class of a detected plane. Only FLOOR (the zero point) and TABLE (the desk) matter here. */
enum class SurfaceKind {
    FLOOR,
    TABLE,
    WALL,
    CEILING,
    OTHER;

    companion object {
        /** Maps the platform's semantic label name (`PlaneAnchor.semantics.name`, SDK 6.1.9: FLOOR / TABLE / WALL / ...). */
        fun fromSemanticName(name: String?): SurfaceKind =
            when (name) {
                "FLOOR" -> FLOOR
                "TABLE" -> TABLE
                "WALL", "VIRTUAL_WALL" -> WALL
                "CEILING" -> CEILING
                else -> OTHER
            }
    }
}

/**
 * A detected surface: a planar polygon (a quad on the emulator) with its vertices already in Stage
 * metres. Triangle winding is not trusted (wall quads wind outwards on the emulator), so every hit
 * test is two-sided and normals are derived from the vertices. (Reused from Spatial Ruler.)
 */
data class PlaneSurface(
    val id: String,
    val kind: SurfaceKind,
    val semanticName: String,
    val vertices: List<Vec3>,
    val indices: List<Int>,
) {
    /** Unit normal of the first non-degenerate triangle; its sign is arbitrary. */
    val normal: Vec3 by lazy {
        var n = Vec3.UP
        var i = 0
        while (i + 2 < indices.size) {
            val v0 = vertices[indices[i]]
            val c = (vertices[indices[i + 1]] - v0).cross(vertices[indices[i + 2]] - v0)
            if (c.length > 1e-9f) {
                n = c.normalized()
                break
            }
            i += 3
        }
        n
    }

    val centroid: Vec3 by lazy {
        if (vertices.isEmpty()) Vec3.ZERO else vertices.fold(Vec3.ZERO) { acc, v -> acc + v } / vertices.size.toFloat()
    }

    /** Height of a horizontal plane (mean vertex y). */
    val y: Float
        get() = centroid.y

    /** `SEMANTIC:uuid8`, the name used for the plane's collider entity and in logs. */
    val label: String
        get() = "$semanticName:${id.take(8)}"

    /** Boundary of the polygon as closed segments (vertex order as reported). */
    val edges: List<Pair<Vec3, Vec3>>
        get() = vertices.indices.map { vertices[it] to vertices[(it + 1) % vertices.size] }
}

/** Result of a ray / surface intersection. [normal] faces the ray origin. */
data class SurfaceHit(val point: Vec3, val normal: Vec3, val distance: Float, val plane: PlaneSurface)

/** A pointing ray in Stage space. */
data class PointerRay(val origin: Vec3, val direction: Vec3)
