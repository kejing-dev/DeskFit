package io.embodit.deskfit.ui.deskfit.scene

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.OpacityControllerComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import io.embodit.deskfit.domain.model.Quat4
import io.embodit.deskfit.domain.model.Vec3
import io.embodit.deskfit.platform.DeskLog
import io.embodit.deskfit.ui.theme.DeskColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// domain <-> SDK types (reused from Spatial Ruler). The scene works in Stage metres below the
// calibrated stage root.
// ---------------------------------------------------------------------------------------------

internal fun Vec3.sdk(): Vector3 = Vector3(x, y, z)

internal fun Vector3.domain(): Vec3 = Vec3(x, y, z)

internal fun Quat4.sdk(): Quat = Quat(x, y, z, w)

internal fun Quat.domain(): Quat4 = Quat4(x, y, z, w)

internal fun Color.color4(): Color4 = Color4(red, green, blue, alpha)

internal val Entity.transformOrNull: TransformComponent?
    get() = components[TransformComponent::class.java]

internal fun Entity.setLocal(position: Vec3? = null, rotation: Quat4? = null, scale: Vec3? = null) {
    val t = transformOrNull ?: return
    if (position != null) t.setPosition(position.sdk())
    if (rotation != null) t.setQuaternion(rotation.sdk())
    if (scale != null) t.setScaleVector(scale.sdk())
}

internal fun Entity.setUniformScale(k: Float) {
    transformOrNull?.setScaleVector(Vector3(k, k, k))
}

internal fun Entity.setVisible(visible: Boolean) {
    components[ModelComponent::class.java]?.isVisible = visible
}

internal fun Entity.setMaterial(material: Material) {
    components[ModelComponent::class.java]?.materials?.set(0, material)
}

internal fun Entity.setOpacity(opacity: Float) {
    val existing = components[OpacityControllerComponent::class.java]
    if (existing != null) existing.opacity = opacity.coerceIn(0f, 1f)
    else components.set(OpacityControllerComponent(opacity.coerceIn(0f, 1f)))
}

/** An invisible box that takes system taps and drags (bars, the manual plate, the manual plane). */
internal fun touchBox(parent: Entity, name: String, size: Vec3, at: Vec3 = Vec3.ZERO): Entity {
    val e = Entity()
    runCatching { e.setName(name) }
    e.components.set(CollisionComponent(collisionShape = listOf(ShapeResource.createBox(size.sdk())), physicsMaterial = PhysicsMaterialResource()))
    e.components.set(InteractableComponent())
    parent.addChild(e)
    e.setLocal(position = at)
    return e
}

/**
 * Resources shared by entities that come and go. The SDK releases a resource with its last user;
 * shared ones are made global and closed when the scene goes away (Spatial Ruler finding).
 */
internal class SharedResources {
    private val owned = ArrayList<Resource>()

    fun <T : Resource> keep(resource: T): T {
        resource.toGlobal()
        owned += resource
        return resource
    }

    fun close() {
        owned.forEach { runCatching { it.close() } }
        owned.clear()
    }
}

/**
 * Scene-lifetime materials. Unlit colours are exact only with tone mapping off (`#FFC72C` arrives as
 * `#D1B132` otherwise); steel is lit with a little emission so that it does not go dark.
 */
internal class SceneMaterials(private val shared: SharedResources) {
    val yellow: Material = unlit(DeskColors.TapeYellow)
    val yellowTwoSided: Material = unlit(DeskColors.TapeYellow, twoSided = true)
    val edge: Material = unlit(DeskColors.BladeEdge, twoSided = true)
    val red: Material = unlit(DeskColors.MeterRed, twoSided = true)
    val white: Material = unlit(DeskColors.LabelPrimaryLight, twoSided = true)
    val stale: Material = unlit(DeskColors.StaleGrey, twoSided = true)
    val staleTwoSided: Material = unlit(DeskColors.StaleGrey, twoSided = true)

    /** The sampling ring track: white 30 %. */
    val ringTrack: Material = sheer(DeskColors.LabelPrimaryLight, 0.30f)

    /** Distance arcs: white 70 %, 3 mm. */
    val arcLine: Material = sheer(DeskColors.LabelPrimaryLight, 0.70f)

    /** The 50 - 100 cm sector: passable 12 %. */
    val arcFill: Material = sheer(DeskColors.Passable, 0.12f)

    /** Green target band on the pole: passable 35 %. */
    val targetBand: Material = sheer(DeskColors.Passable, 0.35f)

    /** Eye tick: white 90 %; the faded one at the old eye height: 30 %. */
    val eyeTick: Material = sheer(DeskColors.LabelPrimaryLight, 0.90f)
    val eyeTickFaint: Material = sheer(DeskColors.LabelPrimaryLight, 0.30f)

    /** Manual desk plate: white 14 % glass; grid lines: white 40 %. */
    val plateGlass: Material = sheer(DeskColors.LabelPrimaryLight, 0.14f)
    val gridLine: Material = sheer(DeskColors.LabelPrimaryLight, 0.40f)

    /** Desk outline flash (yellow) fades through opacity. */
    val flash: Material = sheer(DeskColors.TapeYellow, 0.9f)

    /** Reticle on the desk: never hidden by depth. */
    val reticleYellow: Material = unlit(DeskColors.TapeYellow, twoSided = true, depthTest = false)

    /** Printed tape face: palette texture (blade yellow, ink, red, rim). */
    val tapeFace: Material = paletted(listOf(DeskColors.TapeYellow, DeskColors.TapeInk, DeskColors.MeterRed, DeskColors.BladeEdge))
    val tapeFaceStale: Material = paletted(listOf(DeskColors.StaleGrey, DeskColors.TapeInk, DeskColors.StaleGrey, DeskColors.BladeEdge))

    val steel: Material =
        shared.keep(
            PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(DeskColors.Steel.color4())
                setMetallic(0.6f)
                setRoughness(0.32f)
                setEmissiveColor(Color4(DeskColors.Steel.red * 0.2f, DeskColors.Steel.green * 0.2f, DeskColors.Steel.blue * 0.2f, 1f))
            }
        )

    private fun unlit(color: Color, twoSided: Boolean = false, depthTest: Boolean = true): UnlitMaterial {
        val material =
            UnlitMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(color.color4())
                runCatching { setApplyToneMapping(false) }
                if (twoSided) setCullingMode(MaterialCullingMode.NONE)
                if (!depthTest) {
                    setDepthTest(false)
                    setDepthWrite(false)
                }
            }
        return shared.keep(material)
    }

    /** A see-through unlit material: opaque colour, the material's opacity carries the share. */
    private fun sheer(color: Color, opacity: Float): UnlitMaterial {
        val material =
            UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                setBaseColor(color.copy(alpha = 1f).color4())
                setOpacity(opacity)
                runCatching { setApplyToneMapping(false) }
                setCullingMode(MaterialCullingMode.NONE)
                setDepthWrite(false)
            }
        return shared.keep(material)
    }

    private fun paletted(colors: List<Color>): Material =
        try {
            val cell = PALETTE_CELL_PX
            val bitmap = Bitmap.createBitmap(cell * colors.size, cell, Bitmap.Config.ARGB_8888)
            for (i in colors.indices) for (x in 0 until cell) for (y in 0 until cell) bitmap.setPixel(i * cell + x, y, colors[i].toArgb())
            val material =
                UnlitMaterial.create(BlendingMode.OPAQUE).apply {
                    setBaseColor(Color4(1f, 1f, 1f, 1f))
                    setBaseColorTexture(TextureResource.create(bitmap))
                    runCatching { setApplyToneMapping(false) }
                    setCullingMode(MaterialCullingMode.NONE)
                }
            shared.keep(material)
        } catch (t: Throwable) {
            DeskLog.e("SCENE tape palette failed: $t", t)
            unlit(colors[0], twoSided = true)
        }

    companion object {
        const val PALETTE_COLUMNS = 4
        private const val PALETTE_CELL_PX = 8
    }
}

/** Axis-aligned rectangle on a face: x0..x1, y0..y1 (metres). */
data class FaceRect(val x0: Float, val x1: Float, val y0: Float, val y1: Float)

/** Programmatic meshes (`MeshModel`: positions, indices, normals). */
internal object MeshFactory {
    private val FRONT = Vector3(0f, 0f, 1f)
    private val UP = Vector3(0f, 1f, 0f)

    /** Flat rectangles in the local XY plane, facing +Z, each sampling one palette column. */
    fun faceModel(rects: List<Pair<FaceRect, Int>>): MeshModel? {
        if (rects.isEmpty()) return null
        val positions = ArrayList<Vector3>(rects.size * 4)
        val normals = ArrayList<Vector3>(rects.size * 4)
        val uvs = ArrayList<Vector2>(rects.size * 4)
        val indices = ArrayList<Int>(rects.size * 6)
        for ((r, column) in rects) {
            val uv = Vector2((column + 0.5f) / SceneMaterials.PALETTE_COLUMNS, 0.5f)
            val base = positions.size
            positions += Vector3(r.x0, r.y0, 0f)
            positions += Vector3(r.x1, r.y0, 0f)
            positions += Vector3(r.x1, r.y1, 0f)
            positions += Vector3(r.x0, r.y1, 0f)
            repeat(4) {
                normals += FRONT
                uvs += uv
            }
            indices += base
            indices += base + 1
            indices += base + 2
            indices += base
            indices += base + 2
            indices += base + 3
        }
        return MeshModel(positions = positions, triangleIndices = indices, normals = normals, uv0 = uvs)
    }

    fun rectModel(rects: List<FaceRect>): MeshModel? = faceModel(rects.map { it to 0 })

    /** Strip x 0..1, y -0.5..0.5 facing +Z: scaled to any length and width. */
    fun unitBand(name: String): MeshResource =
        MeshResource.createWithMeshModel(
            model = rectModel(listOf(FaceRect(0f, 1f, -0.5f, 0.5f)))!!,
            bounds = BoundingBox(center = Vector3(0.5f, 0f, 0f), halfExtent = Vector3(0.5f, 0.5f, 0.002f)),
            name = name,
        )

    /** The unit strip cut into [dashes] equal dashes. */
    fun unitDashes(name: String, dashes: Int, fill: Float = 0.5f): MeshResource {
        val period = 1f / dashes
        val rects = List(dashes) { i -> FaceRect(i * period + period * (1f - fill) / 2f, i * period + period * (1f + fill) / 2f, -0.5f, 0.5f) }
        return MeshResource.createWithMeshModel(
            model = rectModel(rects)!!,
            bounds = BoundingBox(center = Vector3(0.5f, 0f, 0f), halfExtent = Vector3(0.5f, 0.5f, 0.002f)),
            name = name,
        )
    }

    /**
     * Printed tape piece of [length] along +X, [halfWidth] across: ticks every [tickStep] rising from
     * the lower rim (1 cm 34 %, 5 cm 56 %, 10 cm 80 % of the width), rim along both edges. Columns:
     * 0 blade, 1 ink, 3 rim.
     */
    fun tapePiece(name: String, length: Float, halfWidth: Float, tickStep: Float, tickWidth: Float, edge: Float): MeshResource {
        val rects = ArrayList<Pair<FaceRect, Int>>()
        val yLo = -halfWidth + edge
        val yHi = halfWidth - edge
        rects += FaceRect(0f, length, -halfWidth, yLo) to 3
        rects += FaceRect(0f, length, yHi, halfWidth) to 3
        var cursor = 0f
        var k = 0
        while (true) {
            val s = k * tickStep
            if (s > length + 1e-5f) break
            val x0 = (s - tickWidth / 2f).coerceAtLeast(0f)
            val x1 = (s + tickWidth / 2f).coerceAtMost(length)
            val share = when { k % 10 == 0 -> 0.80f; k % 5 == 0 -> 0.56f; else -> 0.34f }
            val top = (-halfWidth + share * 2f * halfWidth).coerceIn(yLo, yHi)
            if (x0 > cursor) rects += FaceRect(cursor, x0, yLo, yHi) to 0
            if (x1 > x0) {
                rects += FaceRect(x0, x1, yLo, top) to 1
                if (top < yHi) rects += FaceRect(x0, x1, top, yHi) to 0
            }
            cursor = x1
            k++
        }
        if (cursor < length) rects += FaceRect(cursor, length, yLo, yHi) to 0
        return MeshResource.createWithMeshModel(
            model = faceModel(rects)!!,
            bounds = BoundingBox(center = Vector3(length / 2f, 0f, 0f), halfExtent = Vector3(length / 2f + 0.001f, halfWidth, 0.002f)),
            name = name,
        )
    }

    /** Flat ring (or [dashes] arcs of it) in the local XZ plane, facing +Y. */
    fun ring(inner: Float, outer: Float, name: String, dashes: Int = 0, dashFill: Float = 0.6f, segments: Int = 48): MeshResource {
        val positions = ArrayList<Vector3>()
        val normals = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        fun arc(a0: Double, a1: Double, steps: Int) {
            for (i in 0 until steps) {
                val s = a0 + (a1 - a0) * i / steps
                val e = a0 + (a1 - a0) * (i + 1) / steps
                val base = positions.size
                positions += Vector3((cos(s) * inner).toFloat(), 0f, (sin(s) * inner).toFloat())
                positions += Vector3((cos(s) * outer).toFloat(), 0f, (sin(s) * outer).toFloat())
                positions += Vector3((cos(e) * outer).toFloat(), 0f, (sin(e) * outer).toFloat())
                positions += Vector3((cos(e) * inner).toFloat(), 0f, (sin(e) * inner).toFloat())
                repeat(4) { normals += UP }
                indices += base
                indices += base + 2
                indices += base + 1
                indices += base
                indices += base + 3
                indices += base + 2
            }
        }
        if (dashes <= 0) {
            arc(0.0, 2 * PI, segments)
        } else {
            val slot = 2 * PI / dashes
            for (d in 0 until dashes) arc(d * slot, d * slot + slot * dashFill, 3)
        }
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = normals)
        val bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(outer, 0.001f, outer))
        return MeshResource.createWithMeshModel(model = model, bounds = bounds, name = name)
    }

    /**
     * Annular sector in the local XZ plane facing +Y, centred on +X, from -[halfAngle] to +[halfAngle]
     * (radians): the distance arcs (thin) and the 50 - 100 cm fill (wide).
     */
    fun sector(inner: Float, outer: Float, halfAngle: Float, name: String, segments: Int = 36): MeshResource {
        val positions = ArrayList<Vector3>()
        val normals = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        for (i in 0 until segments) {
            val s = -halfAngle + 2f * halfAngle * i / segments
            val e = -halfAngle + 2f * halfAngle * (i + 1) / segments
            val base = positions.size
            // +X at angle 0, turning towards -Z for positive angles (right-handed about +Y)
            positions += Vector3(cos(s) * inner, 0f, -sin(s) * inner)
            positions += Vector3(cos(s) * outer, 0f, -sin(s) * outer)
            positions += Vector3(cos(e) * outer, 0f, -sin(e) * outer)
            positions += Vector3(cos(e) * inner, 0f, -sin(e) * inner)
            repeat(4) { normals += UP }
            indices += base
            indices += base + 1
            indices += base + 2
            indices += base
            indices += base + 2
            indices += base + 3
        }
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = normals)
        val bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(outer, 0.001f, outer))
        return MeshResource.createWithMeshModel(model = model, bounds = bounds, name = name)
    }

    /** Solid disc in the local XZ plane, facing +Y. */
    fun disc(radius: Float, name: String, segments: Int = 40): MeshResource {
        val positions = ArrayList<Vector3>(segments + 1)
        val normals = ArrayList<Vector3>(segments + 1)
        val indices = ArrayList<Int>(segments * 3)
        positions += Vector3(0f, 0f, 0f)
        normals += UP
        for (i in 0 until segments) {
            val a = 2 * PI * i / segments
            positions += Vector3((cos(a) * radius).toFloat(), 0f, (sin(a) * radius).toFloat())
            normals += UP
        }
        for (i in 0 until segments) {
            indices += 0
            indices += 1 + (i + 1) % segments
            indices += 1 + i
        }
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = normals)
        val bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(radius, 0.001f, radius))
        return MeshResource.createWithMeshModel(model = model, bounds = bounds, name = name)
    }

    /** A plus sign in the local XZ plane, facing +Y (the desk reticle's cross). */
    fun cross(halfLength: Float, halfWidth: Float, name: String): MeshResource {
        val positions = ArrayList<Vector3>()
        val normals = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        fun quad(x0: Float, x1: Float, z0: Float, z1: Float) {
            val base = positions.size
            positions += Vector3(x0, 0f, z0)
            positions += Vector3(x1, 0f, z0)
            positions += Vector3(x1, 0f, z1)
            positions += Vector3(x0, 0f, z1)
            repeat(4) { normals += UP }
            indices += base
            indices += base + 2
            indices += base + 1
            indices += base
            indices += base + 3
            indices += base + 2
        }
        quad(-halfLength, halfLength, -halfWidth, halfWidth)
        quad(-halfWidth, halfWidth, -halfLength, halfLength)
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = normals)
        val bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(halfLength, 0.001f, halfLength))
        return MeshResource.createWithMeshModel(model = model, bounds = bounds, name = name)
    }
}

/**
 * A straight strip between two Stage points that turns its face to the viewer: the shared unit band
 * scaled to length and width (reused from Spatial Ruler). Showing one builds no mesh.
 */
internal class Strip(parent: Entity, mesh: MeshResource, material: Material, name: String) {
    val entity: Entity = ModelEntity(mesh, material)
    private var on = true

    init {
        runCatching { entity.setName(name) }
        parent.addChild(entity)
        hide()
    }

    /** From [from] to [to], [width] wide, its face along [faceNormal] (made square to the strip). */
    fun draw(from: Vec3, to: Vec3, width: Float, faceNormal: Vec3) {
        val full = to - from
        val length = full.length
        if (length < 1e-4f) {
            hide()
            return
        }
        val dir = full / length
        var z = faceNormal.rejectFrom(dir)
        if (z.length < 1e-4f) z = Vec3.UP.rejectFrom(dir)
        if (z.length < 1e-4f) z = Vec3.RIGHT.rejectFrom(dir)
        z = z.normalized()
        entity.setLocal(position = from, rotation = Quat4.fromBasis(dir, z.cross(dir).normalized(), z), scale = Vec3(length, width, 1f))
        if (!on) {
            entity.enabled = true
            on = true
        }
    }

    fun setOpacity(value: Float) = entity.setOpacity(value)

    fun setMaterial(value: Material) = entity.setMaterial(value)

    fun hide() {
        if (on) {
            entity.enabled = false
            on = false
        }
    }

    fun destroy() {
        runCatching { entity.destroy(true) }
    }
}
