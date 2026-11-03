package io.embodit.deskfit.ui.deskfit.scene

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.MeshResource
import io.embodit.deskfit.domain.model.LengthUnit
import io.embodit.deskfit.domain.model.Quat4
import io.embodit.deskfit.domain.model.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.yield

/** Meshes shared by every tape: the plain band, the printed 10 cm / 1 ft piece per unit system. */
internal class TapeMeshes private constructor(val band: MeshResource, private val metric: MeshResource, private val imperial: MeshResource) {
    fun piece(unit: LengthUnit): MeshResource = if (unit == LengthUnit.CM) metric else imperial

    companion object {
        /** A 16 mm blade, dark rim 1.6 mm, red zero end 12 mm. */
        const val WIDTH_M = 0.016f
        const val EDGE_M = 0.0016f
        const val ZERO_END_M = 0.012f
        const val INCH_M = 0.0254f

        fun pieceLength(unit: LengthUnit): Float = if (unit == LengthUnit.CM) 0.10f else 0.3048f

        suspend fun build(shared: SharedResources): TapeMeshes {
            val band = shared.keep(MeshFactory.unitBand("tape_band"))
            yield()
            val half = WIDTH_M / 2f
            val metric = shared.keep(MeshFactory.tapePiece("seg_metric", 0.10f, half, 0.01f, 0.0010f, EDGE_M))
            yield()
            val imperial = shared.keep(MeshFactory.tapePiece("seg_imperial", 0.3048f, half, INCH_M, 0.0015f, EDGE_M))
            yield()
            return TapeMeshes(band, metric, imperial)
        }
    }
}

/**
 * A tape blade between two points: printed pieces laid end to end from the zero end (red cap, the
 * hook end) and the plain band for the rest. Faces the viewer; widened with distance so that it never
 * looks thinner than about half a degree. No mesh is built while it moves (Spatial Ruler pattern).
 */
internal class Tape(parent: Entity, name: String, private val materials: SceneMaterials, private val meshes: TapeMeshes) {
    val root = Entity()
    private val plain: Entity = ModelEntity(meshes.band, materials.yellowTwoSided)
    private val zeroCap: Entity = ModelEntity(meshes.band, materials.red)
    private val pieceHolder = Entity()
    private val pieces = ArrayList<Entity>()
    private var pieceMesh: MeshResource? = null
    private var piecesShown = 0
    private var rootOn = true
    private var stale = false

    /** Frame the blade was last drawn in (X along, Y across, Z out of the face) and its width scale. */
    var rotation: Quat4 = Quat4.IDENTITY
        private set
    var across: Vec3 = Vec3.UP
        private set
    var widthScale = 1f
        private set

    init {
        runCatching { root.setName(name) }
        parent.addChild(root)
        root.addChild(plain)
        root.addChild(zeroCap)
        root.addChild(pieceHolder)
        hide()
    }

    /** Draws the blade from [zeroEnd] (red end) to [outEnd]; [viewer] decides which way the face looks. */
    fun draw(zeroEnd: Vec3, outEnd: Vec3, viewer: Vec3, unit: LengthUnit) {
        val full = outEnd - zeroEnd
        val length = full.length
        if (length < MIN_LENGTH_M) {
            hide()
            return
        }
        showRoot(true)
        val dir = full / length
        val view = viewer - (zeroEnd + dir * (length / 2f))
        val distance = view.length
        var facing = view.rejectFrom(dir)
        if (facing.length < 1e-3f) facing = Vec3.UP.rejectFrom(dir)
        if (facing.length < 1e-3f) facing = Vec3.RIGHT.rejectFrom(dir)
        facing = facing.normalized()
        val axisY = facing.cross(dir).normalized()
        rotation = Quat4.fromBasis(dir, axisY, facing)
        across = axisY
        root.setLocal(position = zeroEnd, rotation = rotation)
        val width = max(1f, distance * HALF_DEGREE_TAN / TapeMeshes.WIDTH_M).coerceAtMost(MAX_WIDTH_SCALE)
        widthScale = width

        val mesh = meshes.piece(unit)
        val pieceLength = TapeMeshes.pieceLength(unit)
        if (mesh !== pieceMesh) {
            pieces.forEach { runCatching { it.destroy(true) } }
            pieces.clear()
            piecesShown = 0
            pieceMesh = mesh
        }
        val count = minOf(MAX_PIECES, (length / pieceLength + 1e-4f).toInt())
        while (pieces.size < count) {
            val piece = ModelEntity(mesh, if (stale) materials.tapeFaceStale else materials.tapeFace)
            piece.setLocal(position = Vec3(pieces.size * pieceLength, 0f, 0f))
            pieceHolder.addChild(piece)
            pieces += piece
        }
        showPieces(count)
        pieceHolder.setLocal(scale = Vec3(1f, width, 1f))
        val from = count * pieceLength
        val rest = length - from
        plain.enabled = rest > 1e-4f
        if (rest > 1e-4f) plain.setLocal(position = Vec3(from, 0f, 0f), scale = Vec3(rest, TapeMeshes.WIDTH_M * width, 1f))
        val cap = minOf(length, TapeMeshes.ZERO_END_M)
        zeroCap.enabled = true
        zeroCap.setLocal(position = Vec3(0f, 0f, CAP_LIFT_M), scale = Vec3(cap, (TapeMeshes.WIDTH_M - 2f * TapeMeshes.EDGE_M) * width, 1f))
    }

    fun hide() {
        showRoot(false)
    }

    /** Colours off (tracking lost) or on again. */
    fun setStale(on: Boolean) {
        if (on == stale) return
        stale = on
        plain.setMaterial(if (on) materials.staleTwoSided else materials.yellowTwoSided)
        zeroCap.setMaterial(if (on) materials.staleTwoSided else materials.red)
        pieces.forEach { it.setMaterial(if (on) materials.tapeFaceStale else materials.tapeFace) }
    }

    private fun showRoot(on: Boolean) {
        if (on != rootOn) {
            root.enabled = on
            rootOn = on
        }
    }

    private fun showPieces(count: Int) {
        if (count == piecesShown) return
        for (i in pieces.indices) {
            val on = i < count
            if (on != (i < piecesShown)) pieces[i].enabled = on
        }
        piecesShown = count
    }

    fun destroy() {
        runCatching { root.destroy(true) }
    }

    companion object {
        const val MIN_LENGTH_M = 0.004f
        const val MAX_PIECES = 10
        private const val CAP_LIFT_M = 0.00015f
        private const val HALF_DEGREE_TAN = 0.0087269f
        private const val MAX_WIDTH_SCALE = 3f

        @Suppress("unused")
        private fun eq(a: Float, b: Float) = abs(a - b) < 1e-6f
    }
}
