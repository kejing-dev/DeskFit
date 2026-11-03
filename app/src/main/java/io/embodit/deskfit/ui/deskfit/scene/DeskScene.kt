package io.embodit.deskfit.ui.deskfit.scene

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import com.pico.spatial.ui.foundation.gesture.SpatialDragValue
import com.pico.spatial.ui.foundation.gesture.SpatialTapValue
import com.pico.spatial.ui.geometry.Offset3D
import io.embodit.deskfit.domain.model.DeskPick
import io.embodit.deskfit.domain.model.Phase
import io.embodit.deskfit.domain.model.PointerRay
import io.embodit.deskfit.domain.model.Quat4
import io.embodit.deskfit.domain.model.Vec3
import io.embodit.deskfit.domain.usecase.AdaptiveGain
import io.embodit.deskfit.domain.usecase.DeskRulesUseCase
import io.embodit.deskfit.domain.usecase.EyeSample
import io.embodit.deskfit.domain.usecase.EyeSampleUseCase
import io.embodit.deskfit.domain.usecase.FormatUseCase
import io.embodit.deskfit.domain.usecase.HitSurfaceUseCase
import io.embodit.deskfit.domain.usecase.JudgeUseCase
import io.embodit.deskfit.platform.DeskGraph
import io.embodit.deskfit.platform.DeskLog
import io.embodit.deskfit.platform.sense.PlaneTracker
import io.embodit.deskfit.platform.tracking.HeadPose
import io.embodit.deskfit.ui.deskfit.BarKind
import io.embodit.deskfit.ui.deskfit.DeskEvent
import io.embodit.deskfit.ui.deskfit.DeskUiState
import io.embodit.deskfit.ui.deskfit.components.ArcLabelHeight
import io.embodit.deskfit.ui.deskfit.components.ArcLabelWidth
import io.embodit.deskfit.ui.deskfit.components.DeskProbe
import io.embodit.deskfit.ui.deskfit.components.DialogPanelHeight
import io.embodit.deskfit.ui.deskfit.components.DialogPanelWidth
import io.embodit.deskfit.ui.deskfit.components.MenuPanelHeight
import io.embodit.deskfit.ui.deskfit.components.MenuPanelWidth
import io.embodit.deskfit.ui.deskfit.components.PlateGlyph
import io.embodit.deskfit.ui.deskfit.components.PlateHeight
import io.embodit.deskfit.ui.deskfit.components.PlateUi
import io.embodit.deskfit.ui.deskfit.components.PlateWidth
import io.embodit.deskfit.ui.deskfit.components.ResultPanelHeight
import io.embodit.deskfit.ui.deskfit.components.ResultPanelWidth
import io.embodit.deskfit.ui.deskfit.components.SnackPanelHeight
import io.embodit.deskfit.ui.deskfit.components.SnackPanelWidth
import io.embodit.deskfit.ui.deskfit.components.StepPanelHeight
import io.embodit.deskfit.ui.deskfit.components.StepPanelWidth
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** Ids of the attachment panels; the Compose side declares exactly these, once (pre-built pool). */
object PanelIds {
    const val STEP = "step"
    const val RESULT = "result"
    const val MENU = "menu"
    const val DIALOG = "dialog"
    const val SNACK = "snack"
    const val PLATE_EYE = "plateEye"
    const val PLATE_SCREEN = "plateScreen"
    const val PLATE_CHAIR = "plateChair"
    const val ARC_50 = "arc50"
    const val ARC_75 = "arc75"
    const val ARC_100 = "arc100"
    val plates = listOf(PLATE_EYE, PLATE_SCREEN, PLATE_CHAIR)
    val arcs = listOf(ARC_50, ARC_75, ARC_100)
    val pool: List<String> = listOf(STEP, RESULT) + plates + arcs + listOf(MENU, DIALOG, SNACK)
}

/** The framework instantiates the system itself, so the running scene is reached through this holder. */
internal object ActiveScene {
    @Volatile var scene: DeskScene? = null
}

/** The one per-frame ECS system of the app. */
class DeskFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        ActiveScene.scene?.onFrame(context)
    }
}

/**
 * The 3D side: the pole, the bars, the tapes, the arcs, the sampling ring, the manual desk, the tap
 * and drag targets and the placement of the pre-built panels. Compose state is rendered through
 * [render]; per-frame work happens in [onFrame] and never goes through Compose (except the three
 * panel maps below, written only when they change). Everything started in [attach] stops in [detach].
 * Main thread only. Patterns and runtime findings come from Spatial Ruler (`RulerScene`).
 */
class DeskScene(private val sink: (DeskEvent) -> Unit, private val frameHook: (Long) -> Unit) : DeskProbe.SceneProbe {

    /** Panel id -> in view (a pooled panel composes its content only then; PanelRig finding 3). */
    val panelShown = mutableStateMapOf<String, Boolean>()

    /** Panel id -> share of its width the holder was squeezed to (the content draws itself stretched back). */
    val panelStretch = mutableStateMapOf<String, Float>()

    /** What the three readout plates show (written at most 10 times a second, and only on change). */
    val plates = mutableStateMapOf<String, PlateUi>()

    /** The arc end figures ("50", "75", "100" or the inch equivalents). */
    val arcLabels = mutableStateMapOf<String, String>()

    /** Bumped to make `SpatialView.update` run again while panel entities are still missing. */
    val bindTick = mutableIntStateOf(0)

    val stale = mutableStateOf(false)

    private val main = Handler(Looper.getMainLooper())
    private val planeTracker = DeskGraph.planes
    private val headTracker = DeskGraph.head
    private val controller = DeskGraph.controller
    private val rules = DeskRulesUseCase()
    private val hits = HitSurfaceUseCase()
    private val sampler = EyeSampleUseCase()
    private val format = FormatUseCase()

    private var content: SpatialViewContent? = null
    private var stageRoot: Entity? = null
    private var contentProbe: Entity? = null
    private var shared: SharedResources? = null
    private var materials: SceneMaterials? = null
    private var tapeMeshes: TapeMeshes? = null
    private var colliders: PlaneColliders? = null
    private var rig: PanelRig? = null
    private var attached = false
    private var attachedAtMs = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var buildJob: Job? = null

    private var ui = DeskUiState()
    private var head: HeadPose = HeadPose(Vec3(0f, 1.2f, 0f), Quat4.IDENTITY)
    private var hasHead = false

    // ---- room polling
    private var lastRevision = -1
    private var lastPollMs = 0L
    private var lastEmittedFloor: Float? = null
    private var lastEmittedDesk: String? = null
    private var lastLost = false

    // ---- sampling
    private val samples = ArrayList<EyeSample>()
    private var samplingFor = 0L
    private var ringProgress = 0f
    private var ringAt: Vec3? = null
    private var ringRotation = Quat4.IDENTITY

    // ---- 3D parts (built once)
    private var ring: SampleRing? = null
    private var arcs: DistanceArcs? = null
    private var pole: PoleView? = null
    private var ghost: PoleView? = null
    private var forearm: ForearmView? = null
    private var manual: ManualDesk? = null
    private var reticle: DeskReticle? = null
    private val flashStrips = ArrayList<Strip>()
    private var band: MeshResource? = null

    // ---- drags and the joystick
    private var drag: Drag? = null
    private var barYaw = 0f
    private var lastFrameMs = 0L
    private var triggerDown = false
    private var lastTriggerMs = NEVER
    private var controllerSeenMs = NEVER
    private val synthetic = SyntheticRay()

    // ---- panel placement
    private var followRest: Vec3? = null
    private var followOutSinceMs = NEVER
    private var slideFrom: Vec3? = null
    private var slideTo: Vec3? = null
    private var slideStartMs = 0L
    private var fixedPanelAt: Vec3? = null
    private var fixedForKey = ""
    private var manualAnchor: Vec3? = null
    private var lastPlateWriteMs = 0L
    private var fpsOn = false
    private var fpsFrames = 0
    private var fpsSinceMs = 0L

    private class Drag(val kind: BarKind, val startY: Float) {
        var totalPx = Vec3.ZERO
        var y = startY
        var lastHandY = 0f
        var lastMs = 0L
        val gain = AdaptiveGain()
        var moves = 0
    }

    // =========================================================================================
    // lifecycle
    // =========================================================================================

    fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        if (attached || stageRoot != null) detach()
        val t0 = SystemClock.elapsedRealtime()
        attachedAtMs = t0
        this.content = content
        val root = Entity()
        runCatching { root.setName("stageRoot") }
        content.addEntity(root)
        val probe = Entity()
        runCatching { probe.setName("contentProbe") }
        content.addEntity(probe)
        contentProbe = probe
        // an entity added to the SpatialView is not at the Stage origin (0.256 m off in z): calibrate the root once
        val offset = root.convertPositionFrom(Vector3.ZERO, null)
        val rotation = root.convertRotationFrom(Quat.identity(), null)
        root.components[TransformComponent::class.java]?.apply {
            setPosition(offset)
            setQuaternion(rotation)
        }
        stageRoot = root
        DeskLog.i("STAGE root offset=${DeskLog.pos(offset.domain())}")

        val rig = PanelRig(root) { id, shown -> panelShown[id] = shown }
        this.rig = rig
        buildPanelSlots(rig)
        headTracker.start()
        controller.start()
        planeTracker.start(PlaneTracker.Listener { id, surface, _ -> if (surface != null) colliders?.upsert(surface) else colliders?.remove(id) })
        ActiveScene.scene = this
        bindPanels(attachments)
        buildJob =
            scope.launch {
                try {
                    build(root, t0)
                } catch (t: Throwable) {
                    if (t !is CancellationException) DeskLog.e("SCENE build failed: $t", t)
                    throw t
                }
            }
    }

    /** One mesh per rendered frame (0.4 s each at the emulator's idle fps): the build yields after every mesh. */
    private suspend fun build(root: Entity, t0: Long) {
        val shared = SharedResources()
        this.shared = shared
        val m = SceneMaterials(shared)
        materials = m
        yield()
        val tapes = TapeMeshes.build(shared)
        tapeMeshes = tapes
        band = tapes.band
        colliders = PlaneColliders(root).also {
            it.buildBackstop()
            planeTracker.all.forEach(it::upsert)
        }
        yield()
        ring = SampleRing(root, shared, m).also { it.build() }
        yield()
        arcs = DistanceArcs(root, shared, m).also { it.build() }
        yield()
        val poleMeshes = PoleView.Meshes.build(shared)
        pole = PoleView(root, "pole", poleMeshes, m, tapes, touchName = "bar")
        yield()
        ghost = PoleView(root, "ghostPole", poleMeshes, m, tapes, touchName = "ghost").also { it.setGhost() }
        yield()
        forearm = ForearmView(root, poleMeshes, m, tapes)
        yield()
        manual = ManualDesk(root, shared, m, poleMeshes, tapes.band)
        yield()
        reticle = DeskReticle(root, shared, m)
        repeat(4) { flashStrips += Strip(root, tapes.band, m.flash, "flash$it") }
        attached = true
        DeskLog.i("SCENE attached build_ms=${SystemClock.elapsedRealtime() - t0}")
        render(ui)
        sink(DeskEvent.SceneReady)
    }

    private fun buildPanelSlots(rig: PanelRig) {
        rig.world(PanelIds.STEP, StepPanelWidth.value, StepPanelHeight.value)
        rig.world(PanelIds.RESULT, ResultPanelWidth.value, ResultPanelHeight.value)
        PanelIds.plates.forEach { rig.world(it, PlateWidth.value, PlateHeight.value) }
        PanelIds.arcs.forEach { rig.world(it, ArcLabelWidth.value, ArcLabelHeight.value) }
        rig.world(PanelIds.MENU, MenuPanelWidth.value, MenuPanelHeight.value)
        rig.head(PanelIds.DIALOG, DIALOG_HEAD_OFFSET, DIALOG_SCALE, DialogPanelWidth.value, DialogPanelHeight.value)
        rig.head(PanelIds.SNACK, SNACK_HEAD_OFFSET, PANEL_SCALE, SnackPanelWidth.value, SnackPanelHeight.value)
    }

    fun bindPanels(attachments: SpatialViewAttachments) {
        val rig = rig ?: return
        val complete = rig.bind(attachments, PanelIds.pool, SystemClock.elapsedRealtime())
        if (!complete) {
            main.removeCallbacks(rebind)
            main.postDelayed(rebind, REBIND_DELAY_MS)
        }
    }

    private val rebind = Runnable { if (stageRoot != null) bindTick.intValue++ }

    fun detach() {
        if (!attached && stageRoot == null) return
        attached = false
        buildJob?.cancel()
        buildJob = null
        if (ActiveScene.scene === this) ActiveScene.scene = null
        main.removeCallbacksAndMessages(null)
        planeTracker.stop()
        headTracker.stop()
        controller.stop()
        panelShown.clear()
        panelStretch.clear()
        plates.clear()
        arcLabels.clear()
        drag = null
        samples.clear()
        runCatching { rig?.release() }
        ring?.destroy()
        arcs?.destroy()
        pole?.destroy()
        ghost?.destroy()
        forearm?.destroy()
        manual?.destroy()
        reticle?.destroy()
        flashStrips.forEach { it.destroy() }
        flashStrips.clear()
        colliders?.clear()
        runCatching { stageRoot?.destroy(true) }
        runCatching { contentProbe?.destroy(true) }
        shared?.close()
        shared = null
        stageRoot = null
        contentProbe = null
        content = null
        rig = null
        ring = null
        arcs = null
        pole = null
        ghost = null
        forearm = null
        manual = null
        reticle = null
        colliders = null
        materials = null
        tapeMeshes = null
        hasHead = false
        lastRevision = -1
        lastEmittedFloor = null
        lastEmittedDesk = null
        lastLost = false
        followRest = null
        fixedPanelAt = null
        manualAnchor = null
        DeskLog.i("SCENE detached")
    }

    // =========================================================================================
    // model -> entities
    // =========================================================================================

    fun render(state: DeskUiState) {
        val previous = ui
        ui = state
        if (!attached) return
        if (previous.samplingStartMs != state.samplingStartMs || (state.phase == Phase.SAMPLING && previous.phase != Phase.SAMPLING)) {
            samples.clear()
            samplingFor = state.samplingStartMs
            ringProgress = 0f
            ringAt = null
        }
        if (state.phase == Phase.SIT && previous.phase != Phase.SIT) {
            fixedPanelAt = null
        }
        if ((state.phase == Phase.DESK_MANUAL_A || state.phase == Phase.DESK_MANUAL_B) && previous.phase != Phase.DESK_MANUAL_A && previous.phase != Phase.DESK_MANUAL_B) {
            manualAnchor = null
        }
        if (state.pole != previous.pole) barYaw = 0f
        if (state.trackingLost != previous.trackingLost) {
            stale.value = state.trackingLost
            pole?.setStale(state.trackingLost)
            forearm?.setStale(state.trackingLost)
        }
    }

    // =========================================================================================
    // per frame
    // =========================================================================================

    fun onFrame(context: SceneUpdateContext) {
        val root = stageRoot ?: return
        if (root.scene == null || context.scene != root.scene) return
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrameMs == 0L) 0.016f else ((now - lastFrameMs) / 1000f).coerceIn(0f, 0.25f)
        lastFrameMs = now
        headTracker.pose()?.let {
            if (it.position.length > 1e-3f) {
                head = it
                hasHead = true
            }
        }
        val rig = rig ?: return
        rig.onFrame(now)
        rig.stowBehind(ViewPose(head.position, head.rotation))
        countFrame(now)
        if (!attached) return
        val state = ui
        frameHook(now)
        // q1-1: surface colliders only take the pointer while a desk tap is wanted; otherwise a panel in front of a wall gets it
        colliders?.active = state.phase == Phase.MARK_SCREEN && !state.manualDesk && !state.menuOpen
        pollTracking(now)
        pollBlock()
        pollPlanes(now)
        sampleEye(state, now)
        val ctrl = controller.sample()
        if (ctrl != null) controllerSeenMs = now
        turnBar(state, ctrl?.thumbX ?: synthetic.thumbX, dt)
        aimRay(state, ctrl?.ray ?: synthetic.ray, ctrl?.triggerPressed ?: synthetic.consumeTrigger(), now)
        drag?.gain?.tick(now)

        drawRing(state, now)
        drawArcs(state)
        drawPole(state, now)
        drawForearm(state, now)
        drawManual(state)
        drawFlash(state, now)
        placePanels(state, now)
        rig.faceViewer(head.position)
    }

    private fun pollTracking(now: Long) {
        val lost = headTracker.lost(now)
        if (lost == lastLost) return
        lastLost = lost
        sink(DeskEvent.TrackingChanged(lost))
    }

    private fun pollBlock() {
        sink(DeskEvent.BlockChanged(headTracker.blockReason()))
    }

    /** Floor height under the head and the desk pick: on every plane change and every 500 ms (the head moves). */
    private fun pollPlanes(now: Long) {
        val revision = planeTracker.revision
        if (revision == lastRevision && now - lastPollMs < PLANES_POLL_MS) return
        lastRevision = revision
        lastPollMs = now
        if (!hasHead) return
        val planes = planeTracker.all
        val floorY = rules.floorY(planes, head.position)
        val pick = if (floorY != null) rules.pickDesk(planes, floorY, head.position) else null
        val key = pick?.let { "${it.planeId}:${String.format(Locale.US, "%.3f", it.topY)}" }
        if (floorY == lastEmittedFloor && key == lastEmittedDesk) return
        lastEmittedFloor = floorY
        lastEmittedDesk = key
        DeskLog.i("PLANES n=${planes.size} floor=${floorY?.let { DeskLog.f(it) }} desk=${pick?.let { "${it.planeId?.take(8)} y=${DeskLog.f(it.topY)}" }}")
        sink(DeskEvent.PlanesChanged(floorY, pick, revision))
    }

    /** S4: head-pose samples for 1.4 s, then the pure evaluation; the ring only shows progress. */
    private fun sampleEye(state: DeskUiState, now: Long) {
        if (state.phase != Phase.SAMPLING) return
        if (ringAt == null && hasHead) {
            val f = head.forwardFlat
            ringAt = head.position + f * RING_DISTANCE_M
            ringRotation = Quat4.fromTo(Vec3.UP, (head.position - ringAt!!).normalized())
        }
        if (state.hold) {
            // QA hold: the ring stands at a third of its fill, nothing is sampled
            ringProgress = 0.35f
            return
        }
        val floorY = rules.floorY(planeTracker.all, head.position) ?: state.floorY ?: return
        if (hasHead) samples += EyeSample(now, head.position, floorY)
        val elapsed = now - state.samplingStartMs
        ringProgress = (elapsed / EyeSampleUseCase.WINDOW_MS.toFloat()).coerceIn(0f, 1f)
        if (elapsed >= EyeSampleUseCase.WINDOW_MS) {
            val result = sampler.evaluate(samples)
            samples.clear()
            sink(DeskEvent.SampleFinished(result, head.forwardFlat))
        }
    }

    /** Factory default interaction D1: the thumbstick turns the bar about the pole's vertical axis; it snaps back when near its square pose. */
    private fun turnBar(state: DeskUiState, thumbX: Float, dt: Float) {
        if (state.phase != Phase.ALIGN_TOP && state.phase != Phase.FOREARM && state.phase != Phase.RESULT) return
        if (abs(thumbX) > THUMB_DEADZONE) {
            barYaw += thumbX * YAW_RATE_RAD_S * dt
            if (barYaw > PI.toFloat()) barYaw -= 2f * PI.toFloat()
            if (barYaw < -PI.toFloat()) barYaw += 2f * PI.toFloat()
        } else if (abs(barYaw) < SNAP_YAW_RAD && barYaw != 0f) {
            // snap to the square pose (the dashed target of the design) with a short ease
            val step = SNAP_RATE_RAD_S * dt
            barYaw = if (abs(barYaw) <= step) 0f else barYaw - kotlin.math.sign(barYaw) * step
        }
    }

    /**
     * A tracked ray (controller, or the QA synthetic ray): the desk reticle in S6 / S11b and, on the
     * trigger edge, the tap. One press, one action: a system tap on a plane within 0.7 s of a trigger
     * commit is the same press and is dropped.
     */
    private fun aimRay(state: DeskUiState, ray: PointerRay?, trigger: Boolean, now: Long) {
        val reticle = reticle ?: return
        if (ray == null || (state.phase != Phase.MARK_SCREEN && state.phase != Phase.DESK_MANUAL_B) || state.menuOpen) {
            reticle.hide()
            triggerDown = trigger
            return
        }
        val hit: Pair<String?, Vec3>? =
            if (state.manualDesk) {
                val y = if (state.phase == Phase.DESK_MANUAL_B) state.manualPlateY else (state.desk?.topY ?: state.manualPlateY)
                hits.onHorizontal(y, ray.origin, ray.direction)?.takeIf { hypot(it.x - head.position.x, it.z - head.position.z) <= DeskRulesUseCase.MANUAL_RADIUS_M }?.let { null to it }
            } else {
                val floorY = state.floorY
                val planes = planeTracker.all.filter { floorY != null && rules.qualifies(it, floorY, head.position, reach = 3f) }
                hits.nearest(planes, ray.origin, ray.direction)?.let { it.plane.id to it.point }
            }
        if (hit == null) {
            reticle.hide()
        } else {
            reticle.show(hit.second + Vec3(0f, RETICLE_LIFT_M, 0f), head.position.distanceTo(hit.second))
        }
        val edge = trigger && !triggerDown
        triggerDown = trigger
        if (edge && hit != null) {
            lastTriggerMs = now
            DeskLog.i("TAP ray plane=${hit.first?.take(8)} stage=${DeskLog.pos(hit.second)}")
            sink(DeskEvent.DeskTapped(hit.first, hit.second))
        }
    }

    // ---- drawing ----------------------------------------------------------------------------

    private fun drawRing(state: DeskUiState, now: Long) {
        val ring = ring ?: return
        val at = ringAt
        when (state.phase) {
            Phase.SAMPLING -> if (at != null) ring.show(at, ringRotation, ringProgress, shake = 0f, scale = 1f) else ring.hide()
            Phase.UNSTABLE -> {
                val t = ((now - state.samplingStartMs) / 600f).coerceIn(0f, 1f)
                val a = at ?: (head.position + head.forwardFlat * RING_DISTANCE_M).also { ringAt = it; ringRotation = Quat4.fromTo(Vec3.UP, (head.position - it).normalized()) }
                ring.show(a, ringRotation, progress = 0f, shake = sin(t * 40f) * 0.006f * (1f - t), scale = 1f)
            }
            Phase.EYE_LOCKED -> {
                val t = ((now - state.eyeLockedAtMs) / 400f).coerceIn(0f, 1f)
                if (at != null && t < 1f) ring.show(at, ringRotation, 1f, 0f, scale = 1f - 0.95f * t) else ring.hide()
            }
            else -> ring.hide()
        }
    }

    /** S6 onwards: 50 / 75 / 100 cm arcs on the desk around the sampled head position, facing the desk edge's inward normal. */
    private fun drawArcs(state: DeskUiState) {
        val arcs = arcs ?: return
        val rig = rig ?: return
        val visible = state.phase.ordinal >= Phase.MARK_SCREEN.ordinal && state.phase.ordinal <= Phase.RESULT.ordinal && state.headXZ != null && state.desk != null
        if (!visible) {
            arcs.hide()
            PanelIds.arcs.forEach { rig[it]?.hide() }
            return
        }
        val desk = state.desk!!
        val headXZ = state.headXZ!!
        val inward = desk.edge?.inward ?: state.headFwd ?: Vec3.AHEAD
        val centre = Vec3(headXZ.x, desk.topY + ARC_LIFT_M, headXZ.z)
        arcs.show(centre, inward)
        val unit = state.unit
        val radii = floatArrayOf(0.5f, 0.75f, 1.0f)
        val right = Vec3(-inward.z, 0f, inward.x)
        for (i in 0..2) {
            val id = PanelIds.arcs[i]
            val label = format.whole(radii[i], unit)
            if (arcLabels[id] != label) arcLabels[id] = label
            // at the right-hand end of each arc (+35 degrees), 1 cm above the desk
            val dir = inward * cos(ARC_HALF_ANGLE) + right * sin(ARC_HALF_ANGLE)
            val at = centre + dir * radii[i] + Vec3(0f, 0.01f, 0f)
            val k = panelScale(at)
            rig[id]?.show(at, k)
        }
    }

    private fun drawPole(state: DeskUiState, now: Long) {
        val pole = pole ?: return
        val ghost = ghost ?: return
        val rig = rig ?: return
        val p = state.pole
        val headXZ = state.headXZ
        val showPole = p != null && headXZ != null && (state.phase == Phase.ALIGN_TOP || state.phase == Phase.FOREARM || state.phase == Phase.RESULT)
        val d = drag
        if (!showPole) {
            pole.hide()
            rig[PanelIds.PLATE_EYE]?.hide()
            rig[PanelIds.PLATE_SCREEN]?.hide()
        } else {
            val barY = if (d != null && d.kind == BarKind.SCREEN) d.y else state.barY
            val breathing = state.phase == Phase.ALIGN_TOP && d == null
            val j = state.judgement
            val floorY = state.floorY ?: 0f
            pole.show(
                foot = p!!,
                headXZ = headXZ!!,
                yawOffset = barYaw,
                barY = barY,
                breath = if (breathing) 1f + 0.06f * (0.5f + 0.5f * sin(now / 1200f * 2f * PI.toFloat())) else 1f,
                result = if (state.phase == Phase.RESULT && j != null) PoleView.ResultMarks(eyeAfterY = floorY + j.eyeAfter, eyeBeforeY = if (j.chair.ok) null else floorY + (state.eyeH ?: j.eyeAfter), targetY = floorY + j.eyeAfter - JudgeUseCase.TARGET_BELOW_M, heightOk = j.screenHeight.ok) else null,
                viewer = head.position,
                unit = state.unit,
            )
            // plates: eye height beside the eye tick; the screen delta on the tape
            if (state.phase == Phase.RESULT && j != null) {
                val eyeText = format.withUnit(j.eyeAfter, state.unit)
                writePlate(PanelIds.PLATE_EYE, PlateUi(PlateGlyph.EYE, eyeText, ok = true), pole.eyePlateAt(), now)
                if (!j.screenHeight.ok) {
                    val delta = j.screenHeight.delta
                    writePlate(PanelIds.PLATE_SCREEN, PlateUi(if (delta > 0f) PlateGlyph.UP else PlateGlyph.DOWN, format.withUnit(abs(delta), state.unit), ok = false), pole.tapePlateAt(), now)
                } else {
                    rig[PanelIds.PLATE_SCREEN]?.hide()
                }
            } else {
                rig[PanelIds.PLATE_EYE]?.hide()
                rig[PanelIds.PLATE_SCREEN]?.hide()
            }
        }
        val g = state.ghostPole
        if (g != null && headXZ != null && state.phase == Phase.MARK_SCREEN) {
            ghost.show(g, headXZ, 0f, g.y + JudgeUseCase.BAR_DEFAULT_ABOVE_DESK_M, 1f, null, head.position, state.unit)
        } else {
            ghost.hide()
        }
    }

    private fun drawForearm(state: DeskUiState, now: Long) {
        val view = forearm ?: return
        val rig = rig ?: return
        val desk = state.desk
        val headXZ = state.headXZ
        val show = desk?.edge != null && headXZ != null && (state.phase == Phase.FOREARM || state.phase == Phase.RESULT)
        if (!show) {
            view.hide()
            rig[PanelIds.PLATE_CHAIR]?.hide()
            return
        }
        val edge = desk!!.edge!!
        val d = drag
        val y = if (d != null && d.kind == BarKind.FOREARM) d.y else state.forearmY
        val breathing = state.phase == Phase.FOREARM && d == null
        val j = state.judgement
        val at = edge.nearest(Vec3(headXZ!!.x, edge.a.y, headXZ.z))
        view.show(
            at = at,
            along = edge.along,
            inward = edge.inward,
            barY = y,
            breath = if (breathing) 1f + 0.06f * (0.5f + 0.5f * sin(now / 1200f * 2f * PI.toFloat())) else 1f,
            keyboardY = if (state.phase == Phase.RESULT && j != null && !j.chair.ok) desk.topY + JudgeUseCase.KEYBOARD_M else null,
            viewer = head.position,
            unit = state.unit,
        )
        if (state.phase == Phase.RESULT && j != null) {
            val plate =
                if (j.chair.ok) PlateUi(PlateGlyph.CHAIR_OK, "", ok = true)
                else PlateUi(if (j.chair.delta > 0f) PlateGlyph.CHAIR_UP else PlateGlyph.CHAIR_DOWN, format.withUnit(abs(j.chair.delta), state.unit), ok = false)
            writePlate(PanelIds.PLATE_CHAIR, plate, view.plateAt(), now)
        } else {
            rig[PanelIds.PLATE_CHAIR]?.hide()
        }
    }

    private fun drawManual(state: DeskUiState) {
        val manual = manual ?: return
        when (state.phase) {
            Phase.DESK_MANUAL_A -> {
                val anchor = manualAnchor ?: (head.position + head.forwardFlat * MANUAL_PLATE_DISTANCE_M).also { manualAnchor = it }
                val d = drag
                val y = if (d != null && d.kind == BarKind.MANUAL_PLATE) d.y else state.manualPlateY
                manual.showPlate(Vec3(anchor.x, y, anchor.z), head.forwardFlat)
            }
            Phase.DESK_MANUAL_B -> {
                val anchor = manualAnchor ?: head.position
                manual.showGrid(Vec3(anchor.x, state.manualPlateY, anchor.z), state.desk?.edge)
            }
            else -> {
                manual.hide()
                // the manual desk keeps its horizontal plane as the tap target while it is the desk in use
                if (state.manualDesk && state.desk != null && state.phase == Phase.MARK_SCREEN) manual.showTapPlane(Vec3((manualAnchor ?: head.position).x, state.desk.topY, (manualAnchor ?: head.position).z))
            }
        }
    }

    /** S3: the recognised desk's outline flashes yellow for a second. */
    private fun drawFlash(state: DeskUiState, now: Long) {
        val outline = state.desk?.outline
        val until = state.deskFlashUntilMs
        if (outline == null || outline.size < 3 || now >= until || state.phase != Phase.SIT) {
            flashStrips.forEach { it.hide() }
            return
        }
        val t = ((until - now) / 1000f).coerceIn(0f, 1f)
        for (i in flashStrips.indices) {
            if (i >= outline.size) {
                flashStrips[i].hide()
                continue
            }
            val a = outline[i] + Vec3(0f, 0.002f, 0f)
            val b = outline[(i + 1) % outline.size] + Vec3(0f, 0.002f, 0f)
            flashStrips[i].draw(a, b, FLASH_WIDTH_M, Vec3.UP)
            flashStrips[i].setOpacity(0.9f * t)
        }
    }

    private fun writePlate(id: String, plate: PlateUi, at: Vec3, now: Long) {
        val rig = rig ?: return
        if (plates[id] != plate && (now - lastPlateWriteMs >= PLATE_WRITE_MS || plates[id] == null)) {
            plates[id] = plate
            lastPlateWriteMs = now
        }
        rig[id]?.show(at, panelScale(at))
    }

    /** Constant visual angle: scale = distance / 0.8, distance clamped to 0.45 - 6 m. */
    private fun panelScale(at: Vec3): Float = (head.position.distanceTo(at).coerceIn(0.45f, 6f) / 0.8f)

    // ---- panels -------------------------------------------------------------------------------

    private fun placePanels(state: DeskUiState, now: Long) {
        val rig = rig ?: return
        val step = rig[PanelIds.STEP] ?: return
        val result = rig[PanelIds.RESULT] ?: return
        val menu = rig[PanelIds.MENU]
        val dialog = rig[PanelIds.DIALOG]
        val snack = rig[PanelIds.SNACK]
        if (!hasHead) return

        val fixed = state.headXZ != null && state.phase.ordinal >= Phase.EYE_LOCKED.ordinal && state.phase.ordinal <= Phase.RESULT.ordinal
        val at =
            if (fixed) fixedPanelPosition(state)
            else lazyFollow(now, hold = state.menuOpen || drag != null || state.phase == Phase.SAMPLING)
        val k = PANEL_SCALE
        if (state.phase == Phase.RESULT) {
            step.hide()
            // same top edge as the step panel: the result panel is taller, its centre sits lower
            val drop = (ResultPanelHeight.value - StepPanelHeight.value) / 2f * PanelRig.DP_M * k
            result.show(at + Vec3(0f, -drop, 0f), k)
        } else {
            result.hide()
            step.show(at, k)
        }
        // the More menu drops down from the More key (top right of the panel), 1.5 cm towards the viewer
        if (menu != null) {
            if (state.menuOpen) {
                val host = if (state.phase == Phase.RESULT) result else step
                val f = host.facing
                val halfW = host.widthDp / 2f * PanelRig.DP_M * k
                val halfH = host.heightDp / 2f * PanelRig.DP_M * k
                val menuHalfW = MenuPanelWidth.value / 2f * PanelRig.DP_M * k
                val menuHalfH = MenuPanelHeight.value / 2f * PanelRig.DP_M * k
                val pos = host.position + f.right * (halfW - menuHalfW) + f.up * (halfH - 0.042f - menuHalfH) + f.normal * 0.015f
                menu.show(pos, k)
            } else {
                menu.hide()
            }
        }
        if (dialog != null) {
            if (state.phase == Phase.BLOCKED) dialog.show(null, null) else dialog.hide()
        }
        if (snack != null) {
            if (state.snack != null && state.phase != Phase.BLOCKED) snack.show(null, null) else snack.hide()
        }
    }

    /** S4 onwards: left 32 degrees, down 15 degrees, 0.5 m from the sampled head position, based on the desk edge's inward normal. */
    private fun fixedPanelPosition(state: DeskUiState): Vec3 {
        val headXZ = state.headXZ ?: return head.position
        val floorY = state.floorY ?: 0f
        val eyeY = floorY + (state.eyeH ?: (head.position.y - floorY))
        val desk = state.desk
        val dir = desk?.edge?.inward ?: state.headFwd ?: head.forwardFlat
        val key = "${headXZ.x},${headXZ.z},${dir.x},${dir.z},${state.phase == Phase.RESULT}"
        fixedPanelAt?.let { if (key == fixedForKey) return it }
        val left = rotateY(dir, FIXED_LEFT_RAD)
        var pos = Vec3(headXZ.x, eyeY, headXZ.z) + left * (cos(FIXED_DOWN_RAD) * PANEL_DISTANCE_M) + Vec3(0f, -sin(FIXED_DOWN_RAD) * PANEL_DISTANCE_M, 0f)
        // the taller result card decides: its lower edge must stay 10 cm above the desk
        val halfH = ResultPanelHeight.value / 2f * PanelRig.DP_M * PANEL_SCALE
        if (desk != null && pos.y - halfH < desk.topY) pos = Vec3(pos.x, desk.topY + 0.10f + halfH, pos.z)
        fixedPanelAt = pos
        fixedForKey = key
        return pos
    }

    /**
     * S1 - S3 lazy follow (Spatial Ruler body rule): the panel rests where it is; when its centre has
     * been more than 45 degrees off the view, or nearer than 0.35 m / farther than 0.9 m, for 0.5 s,
     * it slides (0.35 s) to 18 degrees left, 22 degrees down, 0.5 m.
     */
    private fun lazyFollow(now: Long, hold: Boolean): Vec3 {
        val rest = followRest
        if (rest == null) {
            val r = restPose()
            followRest = r
            return r
        }
        slideTo?.let { to ->
            val from = slideFrom ?: to
            val q = ((now - slideStartMs) / SLIDE_MS).coerceIn(0f, 1f)
            val t = if (q < 0.5f) 4f * q * q * q else 1f - (-2f * q + 2f).let { it * it * it } / 2f
            val pos = from.lerp(to, t)
            if (q >= 1f) {
                followRest = to
                slideTo = null
                slideFrom = null
            }
            return pos
        }
        val to = rest - head.position
        val dist = to.length
        val away = if (dist < 1e-3f) 0f else Math.toDegrees(acos((to / dist).dot(head.forward).coerceIn(-1f, 1f)).toDouble()).toFloat()
        val out = away > FOLLOW_WINDOW_DEG || dist < FOLLOW_NEAR_M || dist > FOLLOW_FAR_M
        if (!out || hold) {
            followOutSinceMs = NEVER
        } else if (followOutSinceMs == NEVER) {
            followOutSinceMs = now
        } else if (now - followOutSinceMs >= FOLLOW_OUT_MS) {
            slideFrom = rest
            slideTo = restPose()
            slideStartMs = now
            followOutSinceMs = NEVER
            DeskLog.i("PANEL follow angle=${String.format(Locale.US, "%.1f", away)} dist=${DeskLog.f(dist)}")
        }
        return rest
    }

    private fun restPose(): Vec3 {
        val left = rotateY(head.forwardFlat, FOLLOW_LEFT_RAD)
        return head.position + left * (cos(FOLLOW_DOWN_RAD) * PANEL_DISTANCE_M) + Vec3(0f, -sin(FOLLOW_DOWN_RAD) * PANEL_DISTANCE_M, 0f)
    }

    /** Snackbar capsule width reported by the panel: the panel is squeezed to it (PanelRig finding 4). */
    fun reportSnackWidth(widthDp: Float) {
        val slot = rig?.get(PanelIds.SNACK) ?: return
        if (widthDp <= 0f) return
        val factor = (widthDp / slot.widthDp).coerceIn(0.25f, 1f)
        slot.widthFactor = factor
        if (panelStretch[PanelIds.SNACK] != factor) panelStretch[PanelIds.SNACK] = factor
    }

    // =========================================================================================
    // system pointer: taps and drags
    // =========================================================================================

    fun onSpatialTap(value: SpatialTapValue) {
        val now = SystemClock.elapsedRealtime()
        val target = value.targetEntity
        val name = runCatching { target?.getName() }.getOrNull() ?: "none"
        val stage = toStage(value.position.x, value.position.y, value.position.z)
        if (target == null || stage == null) {
            DeskLog.i("TAP other target=$name stage=${DeskLog.pos(stage)}")
            return
        }
        if (lastTriggerMs != NEVER && now - lastTriggerMs < TRIGGER_TAP_MS && (name.startsWith("plane:") || name == "manualPlane")) {
            DeskLog.i("TAP ignored reason=trigger-commit target=$name")
            return
        }
        val planeId = colliders?.planeIdOf(target)
        when {
            planeId != null -> {
                // put the collider tap back onto the mathematical plane
                val plane = planeTracker.get(planeId)
                val origin = head.position
                val to = stage - origin
                val point = if (plane != null && to.length > 1e-3f) hits.onInfinitePlane(plane, origin, to.normalized())?.point ?: stage else stage
                DeskLog.i("TAP plane=${name.removePrefix("plane:")} stage=${DeskLog.pos(point)}")
                sink(DeskEvent.DeskTapped(planeId, point))
            }
            name == "manualPlane" -> {
                DeskLog.i("TAP manualPlane stage=${DeskLog.pos(stage)}")
                sink(DeskEvent.DeskTapped(null, stage))
            }
            name == "ghost" -> {
                DeskLog.i("TAP ghost")
                sink(DeskEvent.GhostTapped)
            }
            name == "bar" || name == "forearm" || name == "manualPlate" -> DeskLog.i("TAP $name (drag target)")
            else -> {
                DeskLog.i("TAP elsewhere target=$name stage=${DeskLog.pos(stage)}")
                sink(DeskEvent.ElsewhereTapped)
            }
        }
    }

    /**
     * A system drag on a bar: the hand's travel (summed `dragAmount`, converted to Stage metres) moves
     * the bar by its vertical component (design `rules.barDrag`: only the height is taken), through the
     * adaptive gain (1 : 1 moving, 1 : 4 after a 400 ms rest). The scene draws the bar while the drag
     * runs; the ViewModel hears the final height.
     */
    fun onSpatialDrag(phase: String, start: Offset3D?, value: SpatialDragValue) {
        val now = SystemClock.elapsedRealtime()
        val amount = value.dragAmount
        when (phase) {
            "start" -> {
                drag = null
                if (ui.menuOpen) {
                    sink(DeskEvent.MenuDismissed)
                    return
                }
                val name = runCatching { value.targetEntity?.getName() }.getOrNull() ?: "none"
                val kind =
                    when (name) {
                        "bar" -> if (ui.phase == Phase.ALIGN_TOP || ui.phase == Phase.FOREARM || ui.phase == Phase.RESULT) BarKind.SCREEN else null
                        "forearm" -> if (ui.phase == Phase.FOREARM || ui.phase == Phase.RESULT) BarKind.FOREARM else null
                        "manualPlate" -> if (ui.phase == Phase.DESK_MANUAL_A) BarKind.MANUAL_PLATE else null
                        else -> null
                    }
                if (kind == null || ui.trackingLost) {
                    DeskLog.i("DRAG start ignored target=$name")
                    return
                }
                val startY = when (kind) { BarKind.SCREEN -> ui.barY; BarKind.FOREARM -> ui.forearmY; BarKind.MANUAL_PLATE -> ui.manualPlateY }
                val d = Drag(kind, startY)
                d.lastMs = now
                d.gain.begin(now)
                drag = d
                DeskLog.i("DRAG start kind=$kind y=${DeskLog.f(startY)} stage=${DeskLog.pos(start?.let { toStage(it.x, it.y, it.z) })}")
            }
            "move" -> {
                val d = drag ?: return
                d.moves++
                d.totalPx = Vec3(d.totalPx.x + amount.x, d.totalPx.y + amount.y, d.totalPx.z + amount.z)
                val hand = handDelta(d.totalPx) ?: return
                val stepY = hand.y - d.lastHandY
                d.lastHandY = hand.y
                val dt = ((now - d.lastMs).coerceAtLeast(1L)) / 1000f
                d.lastMs = now
                val gain = d.gain.sample(abs(stepY) / dt, abs(stepY) >= AdaptiveGain.MOTION_M, now)
                d.y = clamp(d.kind, d.y + stepY * gain)
                if (d.moves <= 2 || d.moves % 20 == 0) DeskLog.i("DRAG move kind=${d.kind} hand_y=${DeskLog.f(hand.y)} gain=$gain y=${DeskLog.f(d.y)} n=${d.moves}")
            }
            "end", "cancel" -> {
                val d = drag ?: return
                drag = null
                DeskLog.i("DRAG $phase kind=${d.kind} y=${DeskLog.f(d.y)} n=${d.moves}")
                sink(DeskEvent.BarMoved(d.kind, if (phase == "cancel") d.startY else d.y))
            }
        }
    }

    private fun clamp(kind: BarKind, y: Float): Float {
        val deskY = ui.desk?.topY
        return when (kind) {
            BarKind.SCREEN -> if (deskY == null) y else y.coerceIn(deskY + JudgeUseCase.BAR_MIN_ABOVE_DESK_M, deskY + JudgeUseCase.BAR_MAX_ABOVE_DESK_M)
            BarKind.FOREARM -> if (deskY == null) y else y.coerceIn(deskY - JudgeUseCase.FOREARM_RANGE_M, deskY + JudgeUseCase.FOREARM_RANGE_M)
            BarKind.MANUAL_PLATE -> {
                val floorY = ui.floorY ?: 0f
                y.coerceIn(floorY + DeskRulesUseCase.DESK_MIN_M - 0.1f, floorY + DeskRulesUseCase.DESK_MAX_M + 0.1f)
            }
        }
    }

    /** A hand movement in view pixels -> Stage metres. */
    private fun handDelta(px: Vec3): Vec3? {
        val moved = toStage(px.x, px.y, px.z) ?: return null
        val origin = toStage(0f, 0f, 0f) ?: return null
        return moved - origin
    }

    /** View-local pixels of a spatial tap -> Stage metres, through the SpatialView content conversion. */
    private fun toStage(x: Float, y: Float, z: Float): Vec3? {
        val c = content ?: return null
        val probe = contentProbe ?: return null
        return runCatching {
                val local = c.convertPosition(Vector3(x, y, z), ViewCoordinateSpace.Local, c.localSpatialCoordinateSpace)
                probe.convertPositionTo(local, null).domain()
            }
            .onFailure { DeskLog.w("TAP convert failed: $it") }
            .getOrNull()
    }

    private fun countFrame(now: Long) {
        if (!fpsOn) return
        fpsFrames++
        if (fpsSinceMs == 0L) fpsSinceMs = now
        if (now - fpsSinceMs >= 1000L) {
            DeskLog.i("FPS app=${fpsFrames * 1000L / (now - fpsSinceMs)} frames=$fpsFrames ms=${now - fpsSinceMs}")
            fpsFrames = 0
            fpsSinceMs = now
        }
    }

    // =========================================================================================
    // QA probe
    // =========================================================================================

    override fun poseLine(): String =
        String.format(Locale.US, "POSE hmd pos=%s fwd=%s valid=%s", DeskLog.pos(head.position), DeskLog.pos(head.forward), hasHead)

    override fun planeLines(): List<String> =
        planeTracker.all.map { p -> "PLANE ${p.label} kind=${p.kind} y=${DeskLog.f(p.y)} center=${DeskLog.pos(p.centroid)} verts=${p.vertices.joinToString(" ") { DeskLog.pos(it) }}" }

    override fun panelLines(): List<String> =
        rig?.all?.map { s -> "PANEL id=${s.id} shown=${s.shown} pos=${DeskLog.pos(s.position)} scale=${DeskLog.f(s.scale)} engine=${DeskLog.pos(s.enginePosition())}" } ?: emptyList()

    /** QA: the surface tap targets that exist right now and whether they take the pointer. */
    fun collidersLine(): String = colliders?.describe() ?: "COLLIDERS none"

    override fun forceTrackingLost(lost: Boolean): String {
        headTracker.forceLost = lost
        return "TRACK force lost=$lost"
    }

    override fun setFps(on: Boolean): String {
        fpsOn = on
        fpsFrames = 0
        fpsSinceMs = 0L
        return "FPS on=$on"
    }

    override fun setSyntheticRay(values: FloatArray?): String {
        if (values == null || values.size < 6) {
            synthetic.ray = null
            return "POINTER removed"
        }
        synthetic.ray = PointerRay(Vec3(values[0], values[1], values[2]), Vec3(values[3], values[4], values[5]).normalized())
        return "POINTER set origin=${DeskLog.pos(synthetic.ray!!.origin)} dir=${DeskLog.pos(synthetic.ray!!.direction)}"
    }

    override fun triggerSynthetic(): String {
        synthetic.trigger = true
        return "POINTER trigger"
    }

    /** QA: a thumbstick value held until changed (`stick --ef x`). */
    fun setSyntheticThumb(x: Float): String {
        synthetic.thumbX = x
        return "STICK x=$x yaw=${String.format(Locale.US, "%.1f", Math.toDegrees(barYaw.toDouble()))}"
    }

    val barYawDeg: Float
        get() = Math.toDegrees(barYaw.toDouble()).toFloat()

    private class SyntheticRay {
        var ray: PointerRay? = null
        var trigger = false
        var thumbX = 0f

        fun consumeTrigger(): Boolean {
            val t = trigger
            trigger = false
            return t
        }
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE
        private const val REBIND_DELAY_MS = 150L
        private const val PLANES_POLL_MS = 500L
        private const val PLATE_WRITE_MS = 100L
        private const val TRIGGER_TAP_MS = 700L

        /** Attachment panels of the design are shown at 0.75. */
        const val PANEL_SCALE = 0.75f
        private const val DIALOG_SCALE = 0.6f
        private const val PANEL_DISTANCE_M = 0.5f
        private const val RING_DISTANCE_M = 0.45f
        private const val MANUAL_PLATE_DISTANCE_M = 0.5f
        private const val ARC_LIFT_M = 0.002f
        private const val RETICLE_LIFT_M = 0.002f
        private const val FLASH_WIDTH_M = 0.006f
        val ARC_HALF_ANGLE = Math.toRadians(35.0).toFloat()

        /** Lazy follow: 18 degrees left, 22 degrees down (S1 - S3); fixed: 32 degrees left, 15 degrees down (S4 onwards). */
        private val FOLLOW_LEFT_RAD = Math.toRadians(18.0).toFloat()
        private val FOLLOW_DOWN_RAD = Math.toRadians(22.0).toFloat()
        private val FIXED_LEFT_RAD = Math.toRadians(32.0).toFloat()
        private val FIXED_DOWN_RAD = Math.toRadians(15.0).toFloat()
        private const val FOLLOW_WINDOW_DEG = 45f
        private const val FOLLOW_NEAR_M = 0.35f
        private const val FOLLOW_FAR_M = 0.9f
        private const val FOLLOW_OUT_MS = 500L
        private const val SLIDE_MS = 350f

        /** Thumbstick: dead zone 0.2, 90 degrees per second, snap within 8 degrees of square. */
        private const val THUMB_DEADZONE = 0.2f
        private val YAW_RATE_RAD_S = Math.toRadians(90.0).toFloat()
        private val SNAP_YAW_RAD = Math.toRadians(8.0).toFloat()
        private val SNAP_RATE_RAD_S = Math.toRadians(60.0).toFloat()

        /** Dialog straight ahead at 0.5 m; snackbar 14 degrees above the view centre at 0.5 m. */
        private val DIALOG_HEAD_OFFSET = Vec3(0f, 0f, -0.5f)
        private val SNACK_HEAD_OFFSET = Vec3(0f, 0.121f, -0.485f)

        fun rotateY(v: Vec3, rad: Float): Vec3 {
            val c = cos(rad)
            val s = sin(rad)
            return Vec3(v.x * c + v.z * s, v.y, -v.x * s + v.z * c)
        }
    }
}

// =============================================================================================
// the 3D parts
// =============================================================================================

/** S4: a 6 cm ring of 4 mm tube at eye height, 0.45 m ahead, that fills clockwise over 1.4 s (24 pieces of one shared mesh). */
internal class SampleRing(private val parent: Entity, private val shared: SharedResources, private val materials: SceneMaterials) {
    private val root = Entity()
    private val pieces = ArrayList<Entity>()
    private var track: Entity? = null
    private var on = false
    private var shownPieces = -1

    fun build() {
        runCatching { root.setName("sampleRing") }
        parent.addChild(root)
        val trackMesh = shared.keep(MeshFactory.ring(INNER_M, OUTER_M, "ring_track"))
        track = ModelEntity(trackMesh, materials.ringTrack).also { root.addChild(it) }
        val piece = shared.keep(MeshFactory.sector(INNER_M, OUTER_M, (PI / PIECES).toFloat(), "ring_piece"))
        for (i in 0 until PIECES) {
            val e = ModelEntity(piece, materials.yellowTwoSided)
            // clockwise as seen from the viewer: the ring's +Y points at the head, so turn by -i steps
            e.setLocal(rotation = Quat4.axisAngle(Vec3.UP, -(2f * PI.toFloat() / PIECES) * i + PI.toFloat() / 2f))
            root.addChild(e)
            pieces += e
        }
        root.enabled = false
    }

    fun show(at: Vec3, rotation: Quat4, progress: Float, shake: Float, scale: Float) {
        root.setLocal(position = at + rotation.rotate(Vec3.RIGHT) * shake, rotation = rotation, scale = Vec3(scale, scale, scale))
        val count = (progress * PIECES + 1e-3f).toInt().coerceIn(0, PIECES)
        if (count != shownPieces) {
            for (i in pieces.indices) pieces[i].enabled = i < count
            shownPieces = count
        }
        if (!on) {
            root.enabled = true
            on = true
        }
    }

    fun hide() {
        if (on) {
            root.enabled = false
            on = false
        }
    }

    fun destroy() {
        runCatching { root.destroy(true) }
    }

    private companion object {
        const val PIECES = 24
        const val INNER_M = 0.028f
        const val OUTER_M = 0.032f
    }
}

/** S6 onwards: 50 / 75 / 100 cm arcs (3 mm, white 70 %) over 70 degrees and the passable 12 % sector between 50 and 100. */
internal class DistanceArcs(private val parent: Entity, private val shared: SharedResources, private val materials: SceneMaterials) {
    private val root = Entity()
    private var on = false

    fun build() {
        runCatching { root.setName("distanceArcs") }
        parent.addChild(root)
        val half = DeskScene.ARC_HALF_ANGLE
        root.addChild(ModelEntity(shared.keep(MeshFactory.sector(0.5f, 1.0f, half, "arc_fill")), materials.arcFill).also { it.setLocal(position = Vec3(0f, -0.0005f, 0f)) })
        for (r in floatArrayOf(0.5f, 0.75f, 1.0f)) {
            root.addChild(ModelEntity(shared.keep(MeshFactory.sector(r - 0.0015f, r + 0.0015f, half, "arc_$r")), materials.arcLine))
        }
        root.enabled = false
    }

    fun show(centre: Vec3, inward: Vec3) {
        val x = Vec3(inward.x, 0f, inward.z).normalized()
        val z = x.cross(Vec3.UP).normalized()
        root.setLocal(position = centre, rotation = Quat4.fromBasis(x, Vec3.UP, z))
        if (!on) {
            root.enabled = true
            on = true
        }
    }

    fun hide() {
        if (on) {
            root.enabled = false
            on = false
        }
    }

    fun destroy() {
        runCatching { root.destroy(true) }
    }
}

/**
 * The screen pole (design `entities3d.screenPole`): washer, 8 mm steel pole 90 cm high, a 40 x 1.5 x 1 cm
 * yellow bar with a hook towards the screen and a 40 x 6 x 3 cm touch box; in the result the green
 * target band, the target line, the eye tick(s) and the tape from the bar to the target line.
 */
internal class PoleView(parent: Entity, name: String, private val meshes: Meshes, private val materials: SceneMaterials, tapes: TapeMeshes, touchName: String) {
    class Meshes(val washer: MeshResource, val pole: MeshResource, val bar: MeshResource, val barEdge: MeshResource, val hook: MeshResource, val band: MeshResource, val line: MeshResource, val tick: MeshResource, val plate: MeshResource, val plateEdge: MeshResource) {
        companion object {
            suspend fun build(shared: SharedResources): Meshes {
                val washer = shared.keep(MeshResource.createCylinder(0.003f, 0.015f))
                yield()
                val pole = shared.keep(MeshResource.createCylinder(POLE_H, 0.004f))
                yield()
                val bar = shared.keep(MeshResource.createBox(Vector3(BAR_LEN, 0.015f, 0.010f), 0.002f))
                yield()
                val barEdge = shared.keep(MeshResource.createBox(Vector3(BAR_LEN + 0.002f, 0.017f, 0.009f), 0.002f))
                yield()
                val hook = shared.keep(MeshResource.createBox(Vector3(0.006f, 0.006f, 0.03f), 0f))
                yield()
                val band = shared.keep(MeshResource.createBox(Vector3(BAR_LEN, JudgeUseCase.BAND_M, 0.002f), 0f))
                yield()
                val line = shared.keep(MeshResource.createBox(Vector3(BAR_LEN, 0.002f, 0.003f), 0f))
                yield()
                val tick = shared.keep(MeshResource.createBox(Vector3(0.06f, 0.0015f, 0.0015f), 0f))
                yield()
                val plate = shared.keep(MeshResource.createBox(Vector3(0.60f, 0.004f, 0.40f), 0.004f))
                yield()
                val plateEdge = shared.keep(MeshResource.createBox(Vector3(1f, 0.006f, 0.006f), 0f))
                yield()
                return Meshes(washer, pole, bar, barEdge, hook, band, line, tick, plate, plateEdge)
            }
        }
    }

    class ResultMarks(val eyeAfterY: Float, val eyeBeforeY: Float?, val targetY: Float, val heightOk: Boolean)

    private val root = Entity()
    private val washer = ModelEntity(meshes.washer, materials.steel)
    private val pole = ModelEntity(meshes.pole, materials.steel)
    private val barPivot = Entity()
    private val barGroup = Entity()
    private val bar = ModelEntity(meshes.bar, materials.yellow)
    private val barEdge = ModelEntity(meshes.barEdge, materials.edge)
    private val hook = ModelEntity(meshes.hook, materials.steel)
    private val touch: Entity
    private val markPivot = Entity()
    private val band = ModelEntity(meshes.band, materials.targetBand)
    private val line = ModelEntity(meshes.line, materials.white)
    private val tickL = ModelEntity(meshes.tick, materials.eyeTick)
    private val tickR = ModelEntity(meshes.tick, materials.eyeTick)
    private val faintL = ModelEntity(meshes.tick, materials.eyeTickFaint)
    private val faintR = ModelEntity(meshes.tick, materials.eyeTickFaint)
    private val tape = Tape(parent, "${name}_tape", materials, tapes)
    private var on = false
    private var ghost = false
    private var footY = 0f
    private var lastTapeMid: Vec3 = Vec3.ZERO
    private var lastEyeTick: Vec3 = Vec3.ZERO
    private var pivotRotation = Quat4.IDENTITY

    init {
        runCatching { root.setName(name) }
        parent.addChild(root)
        root.addChild(washer)
        washer.setLocal(position = Vec3(0f, 0.0015f, 0f))
        root.addChild(pole)
        pole.setLocal(position = Vec3(0f, POLE_H / 2f, 0f))
        root.addChild(barPivot)
        barPivot.addChild(barGroup)
        barGroup.addChild(barEdge)
        barEdge.setLocal(position = Vec3(0f, 0f, -0.0008f))
        barGroup.addChild(bar)
        barGroup.addChild(hook)
        hook.setLocal(position = Vec3(-BAR_LEN / 2f + 0.003f, 0f, -0.018f))
        touch = touchBox(barGroup, touchName, Vec3(BAR_LEN, 0.06f, 0.03f))
        root.addChild(markPivot)
        markPivot.addChild(band)
        markPivot.addChild(line)
        markPivot.addChild(tickL)
        markPivot.addChild(tickR)
        markPivot.addChild(faintL)
        markPivot.addChild(faintR)
        root.enabled = false
        hide()
    }

    /** Ghost: 30 % opacity, no marks; a tap on it reuses the place. */
    fun setGhost() {
        ghost = true
        root.setOpacity(0.3f)
    }

    fun show(foot: Vec3, headXZ: Vec3, yawOffset: Float, barY: Float, breath: Float, result: ResultMarks?, viewer: Vec3, unit: io.embodit.deskfit.domain.model.LengthUnit) {
        footY = foot.y
        root.setLocal(position = foot)
        // the bar is square to the head -> pole direction; +Z of the pivot points at the head
        var fwd = Vec3(foot.x - headXZ.x, 0f, foot.z - headXZ.z)
        fwd = if (fwd.length < 1e-3f) Vec3.AHEAD else fwd.normalized()
        val right = Vec3(-fwd.z, 0f, fwd.x)
        pivotRotation = Quat4.fromBasis(right, Vec3.UP, -fwd) * Quat4.axisAngle(Vec3.UP, yawOffset)
        barPivot.setLocal(position = Vec3(0f, barY - foot.y, 0f), rotation = pivotRotation)
        barGroup.setLocal(scale = Vec3(1f, breath, breath))
        markPivot.setLocal(rotation = Quat4.fromBasis(right, Vec3.UP, -fwd))
        if (result == null || ghost) {
            band.enabled = false
            line.enabled = false
            tickL.enabled = false
            tickR.enabled = false
            faintL.enabled = false
            faintR.enabled = false
            tape.hide()
        } else {
            val bandY = result.eyeAfterY - JudgeUseCase.BAND_M / 2f - foot.y
            band.enabled = true
            band.setLocal(position = Vec3(0f, bandY, 0.001f))
            line.enabled = true
            line.setLocal(position = Vec3(0f, result.targetY - foot.y, 0.002f))
            val eyeY = result.eyeAfterY - foot.y
            tickL.enabled = true
            tickR.enabled = true
            tickL.setLocal(position = Vec3(-TICK_X, eyeY, 0f))
            tickR.setLocal(position = Vec3(TICK_X, eyeY, 0f))
            val before = result.eyeBeforeY
            if (before != null) {
                faintL.enabled = true
                faintR.enabled = true
                faintL.setLocal(position = Vec3(-TICK_X, before - foot.y, 0f))
                faintR.setLocal(position = Vec3(TICK_X, before - foot.y, 0f))
            } else {
                faintL.enabled = false
                faintR.enabled = false
            }
            val pivot = Quat4.fromBasis(right, Vec3.UP, -fwd)
            lastEyeTick = foot + pivot.rotate(Vec3(-TICK_X - 0.03f, eyeY, 0f))
            if (result.heightOk) {
                tape.hide()
                lastTapeMid = foot + pivot.rotate(Vec3(TAPE_X, barY - foot.y, 0.012f))
            } else {
                val zero = foot + pivotRotation.rotate(Vec3(TAPE_X, barY - foot.y, 0.012f))
                val end = foot + pivotRotation.rotate(Vec3(TAPE_X, result.targetY - foot.y, 0.012f))
                tape.draw(zero, end, viewer, unit)
                lastTapeMid = zero.lerp(end, 0.5f)
            }
        }
        if (!on) {
            root.enabled = true
            on = true
        }
    }

    /** Where the screen delta plate stands: beside the tape's middle, outside the bar end. */
    fun tapePlateAt(): Vec3 = lastTapeMid + pivotRotation.rotate(Vec3(0.06f + 0.06f, 0f, 0f))

    /** Where the eye plate stands: left of the eye tick. */
    fun eyePlateAt(): Vec3 = lastEyeTick + pivotRotation.rotate(Vec3(-0.06f, 0f, 0.01f))

    fun setStale(stale: Boolean) {
        bar.setMaterial(if (stale) materials.stale else materials.yellow)
        tape.setStale(stale)
    }

    fun hide() {
        tape.hide()
        if (on) {
            root.enabled = false
            on = false
        }
    }

    fun destroy() {
        tape.destroy()
        runCatching { root.destroy(true) }
    }

    companion object {
        const val POLE_H = 0.90f
        const val BAR_LEN = 0.40f
        private const val TICK_X = 0.004f + 0.03f
        private const val TAPE_X = 0.14f
    }
}

/** The forearm bar (design `entities3d.forearmBar`): 60 x 1.5 x 1 cm in the desk edge's vertical plane, with its tape to keyboard height and a plate. */
internal class ForearmView(parent: Entity, meshes: PoleView.Meshes, private val materials: SceneMaterials, tapes: TapeMeshes) {
    private val root = Entity()
    private val group = Entity()
    private val bar = ModelEntity(meshes.plateEdge, materials.yellow)
    private val edge = ModelEntity(meshes.plateEdge, materials.edge)
    private val touch: Entity
    private val tape = Tape(parent, "forearm_tape", materials, tapes)
    private var on = false
    private var rotation = Quat4.IDENTITY
    private var plateAt = Vec3.ZERO

    init {
        runCatching { root.setName("forearmBar") }
        parent.addChild(root)
        root.addChild(group)
        group.addChild(edge)
        edge.setLocal(scale = Vec3(LEN + 0.002f, 1.2f, 0.9f), position = Vec3(0f, 0f, -0.0008f))
        group.addChild(bar)
        bar.setLocal(scale = Vec3(LEN, 2.5f, 1.6f))
        touch = touchBox(group, "forearm", Vec3(LEN, 0.06f, 0.03f))
        root.enabled = false
        hide()
    }

    fun show(at: Vec3, along: Vec3, inward: Vec3, barY: Float, breath: Float, keyboardY: Float?, viewer: Vec3, unit: io.embodit.deskfit.domain.model.LengthUnit) {
        // X along the edge, +Z towards the user (away from the desk)
        val x = along.normalized()
        var z = -Vec3(inward.x, 0f, inward.z).normalized()
        z = z.rejectFrom(x).normalized()
        val y = z.cross(x).normalized()
        rotation = Quat4.fromBasis(x, y, z)
        root.setLocal(position = Vec3(at.x, barY, at.z), rotation = rotation)
        group.setLocal(scale = Vec3(1f, breath, breath))
        if (keyboardY == null) {
            tape.hide()
            plateAt = Vec3(at.x, barY, at.z) + rotation.rotate(Vec3(LEN / 2f + 0.06f, 0f, 0.01f))
        } else {
            val zero = Vec3(at.x, barY, at.z) + rotation.rotate(Vec3(TAPE_X, 0f, 0.012f))
            val end = Vec3(at.x, keyboardY, at.z) + rotation.rotate(Vec3(TAPE_X, 0f, 0.012f))
            tape.draw(zero, end, viewer, unit)
            plateAt = zero.lerp(end, 0.5f) + rotation.rotate(Vec3(0.09f, 0f, 0f))
        }
        if (!on) {
            root.enabled = true
            on = true
        }
    }

    fun plateAt(): Vec3 = plateAt

    fun setStale(stale: Boolean) {
        bar.setMaterial(if (stale) materials.stale else materials.yellow)
        tape.setStale(stale)
    }

    fun hide() {
        tape.hide()
        if (on) {
            root.enabled = false
            on = false
        }
    }

    fun destroy() {
        tape.destroy()
        runCatching { root.destroy(true) }
    }

    private companion object {
        const val LEN = 0.60f
        const val TAPE_X = 0.25f
    }
}

/** S11: the 60 x 40 cm glass plate (a), the dashed grid plane with its tap target (b) and the yellow edge line. */
internal class ManualDesk(parent: Entity, shared: SharedResources, private val materials: SceneMaterials, meshes: PoleView.Meshes, unitBand: MeshResource) {
    private val plateRoot = Entity()
    private val plate = ModelEntity(meshes.plate, materials.plateGlass)
    private val edges = ArrayList<Entity>()
    private val plateTouch: Entity
    private val gridRoot = Entity()
    private val gridStrips = ArrayList<Entity>()
    private val gridTouch: Entity
    private val edgeLine = Strip(parent, unitBand, materials.yellowTwoSided, "manualEdge")
    private var plateOn = false
    private var gridOn = false

    init {
        runCatching { plateRoot.setName("manualPlate") }
        parent.addChild(plateRoot)
        plateRoot.addChild(plate)
        for (i in 0 until 4) {
            val e = ModelEntity(meshes.plateEdge, materials.white)
            plateRoot.addChild(e)
            edges += e
        }
        edges[0].setLocal(position = Vec3(0f, 0.003f, -0.20f), scale = Vec3(0.60f, 1f, 1f))
        edges[1].setLocal(position = Vec3(0f, 0.003f, 0.20f), scale = Vec3(0.60f, 1f, 1f))
        edges[2].setLocal(position = Vec3(-0.30f, 0.003f, 0f), rotation = Quat4.axisAngle(Vec3.UP, PI.toFloat() / 2f), scale = Vec3(0.40f, 1f, 1f))
        edges[3].setLocal(position = Vec3(0.30f, 0.003f, 0f), rotation = Quat4.axisAngle(Vec3.UP, PI.toFloat() / 2f), scale = Vec3(0.40f, 1f, 1f))
        plateTouch = touchBox(plateRoot, "manualPlate", Vec3(0.60f, 0.03f, 0.40f))
        runCatching { gridRoot.setName("manualGrid") }
        parent.addChild(gridRoot)
        val dashes = shared.keep(MeshFactory.unitDashes("grid_dashes", 60))
        val span = DeskRulesUseCase.MANUAL_RADIUS_M * 2f
        val lines = 7
        for (i in 0 until lines) {
            val offset = -DeskRulesUseCase.MANUAL_RADIUS_M + span * i / (lines - 1)
            val a = ModelEntity(dashes, materials.gridLine)
            a.setLocal(position = Vec3(-DeskRulesUseCase.MANUAL_RADIUS_M, 0f, offset), rotation = Quat4.axisAngle(Vec3.RIGHT, -PI.toFloat() / 2f), scale = Vec3(span, 0.006f, 1f))
            gridRoot.addChild(a)
            gridStrips += a
            val b = ModelEntity(dashes, materials.gridLine)
            b.setLocal(position = Vec3(offset, 0f, DeskRulesUseCase.MANUAL_RADIUS_M), rotation = Quat4.axisAngle(Vec3.UP, PI.toFloat() / 2f) * Quat4.axisAngle(Vec3.RIGHT, -PI.toFloat() / 2f), scale = Vec3(span, 0.006f, 1f))
            gridRoot.addChild(b)
            gridStrips += b
        }
        gridTouch = touchBox(gridRoot, "manualPlane", Vec3(span, 0.006f, span))
        plateRoot.enabled = false
        gridRoot.enabled = false
        hide()
    }

    fun showPlate(centre: Vec3, forward: Vec3) {
        val f = Vec3(forward.x, 0f, forward.z).let { if (it.length < 1e-3f) Vec3.AHEAD else it.normalized() }
        val right = Vec3(-f.z, 0f, f.x)
        plateRoot.setLocal(position = centre, rotation = Quat4.fromBasis(right, Vec3.UP, -f))
        if (!plateOn) {
            plateRoot.enabled = true
            plateOn = true
        }
        if (gridOn) {
            gridRoot.enabled = false
            gridOn = false
        }
        edgeLine.hide()
    }

    fun showGrid(centre: Vec3, edge: io.embodit.deskfit.domain.model.DeskEdge?) {
        gridRoot.setLocal(position = centre)
        gridStrips.forEach { it.enabled = true }
        if (!gridOn) {
            gridRoot.enabled = true
            gridOn = true
        }
        if (plateOn) {
            plateRoot.enabled = false
            plateOn = false
        }
        if (edge != null) edgeLine.draw(edge.a + Vec3(0f, 0.003f, 0f), edge.b + Vec3(0f, 0.003f, 0f), 0.006f, Vec3.UP) else edgeLine.hide()
    }

    /** S6 with a manual desk: the invisible plane keeps taking taps; the grid itself is not drawn. */
    fun showTapPlane(centre: Vec3) {
        gridRoot.setLocal(position = centre)
        gridStrips.forEach { it.enabled = false }
        if (!gridOn) {
            gridRoot.enabled = true
            gridOn = true
        }
        if (plateOn) {
            plateRoot.enabled = false
            plateOn = false
        }
        edgeLine.hide()
    }

    fun hide() {
        if (plateOn) {
            plateRoot.enabled = false
            plateOn = false
        }
        if (gridOn) {
            gridRoot.enabled = false
            gridOn = false
        }
        edgeLine.hide()
    }

    fun destroy() {
        edgeLine.destroy()
        runCatching { plateRoot.destroy(true) }
        runCatching { gridRoot.destroy(true) }
    }
}

/** S6: a 2 cm yellow dot with a cross where a tracked ray meets the desk (the system pointer has no hover). */
internal class DeskReticle(parent: Entity, shared: SharedResources, materials: SceneMaterials) {
    private val root = Entity()
    private var on = false

    init {
        runCatching { root.setName("deskReticle") }
        parent.addChild(root)
        root.addChild(ModelEntity(shared.keep(MeshFactory.disc(0.005f, "reticle_dot")), materials.reticleYellow))
        root.addChild(ModelEntity(shared.keep(MeshFactory.cross(0.02f, 0.0012f, "reticle_cross")), materials.reticleYellow))
        root.enabled = false
    }

    fun show(at: Vec3, distance: Float) {
        val k = (distance / 0.7f).coerceIn(1f, 2.5f)
        root.setLocal(position = at, scale = Vec3(k, 1f, k))
        if (!on) {
            root.enabled = true
            on = true
        }
    }

    fun hide() {
        if (on) {
            root.enabled = false
            on = false
        }
    }

    fun destroy() {
        runCatching { root.destroy(true) }
    }
}

@Suppress("unused")
private fun keepImports(m: Material, p: DeskPick) = Unit
