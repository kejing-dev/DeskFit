package io.embodit.deskfit.ui.deskfit.scene

import com.pico.spatial.core.ecs.AnchorComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.anchor.AnchorTarget
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import io.embodit.deskfit.domain.model.Quat4
import io.embodit.deskfit.domain.model.Vec3
import io.embodit.deskfit.platform.DeskLog
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs

/**
 * The pre-built panel pool. Every attachment panel of the app is declared once, with a fixed size,
 * and gets a holder entity here. A panel appears by enabling its holder and moving it into place and
 * disappears by disabling the holder and taking it out of the room: nothing is created at that moment
 * (at the emulator's idle frame rate a new panel takes seconds to show up, and a resized one shows
 * stale rows).
 *
 * 1 dp of an attachment panel is 0.8 mm at scale 1 (measured in the feasibility spike).
 *
 * How a panel is hidden follows from three findings on the emulator (SDK 6.1.9):
 * 1. A panel entity that is parented under a disabled holder never appears, also not after the holder
 *    is enabled. A slot therefore stays enabled until its panel has been parented and has had a few
 *    frames to come up; until then a hidden slot is parked (out of the view, a thousandth of its size).
 * 2. A panel must not stay parked. While a parked panel hangs below a head anchor, no panel on the
 *    line of sight through that anchor gets the pointer: a pinch goes through them to whatever lies
 *    behind (seen for the body panel with the parked menu panels below the same anchor, and for a
 *    test panel in front of the parked snackbar panel's anchor, from 0.26 m to 1.2 m along the ray).
 *    With the holder disabled instead, the same panels get their presses. So a hidden slot is disabled
 *    as soon as finding 1 allows.
 * 3. What a panel draws while it is parked stays broken (black, a few rows only) when it is shown.
 *    A panel therefore draws its content after it was shown: [onVisibility] tells the Compose side,
 *    which composes a panel's content only then. "After" means after the engine has rendered the panel
 *    in its place: the report follows [SHOW_FRAMES] engine frames later. Reported in the same frame, the
 *    content was sometimes drawn first when the emulator idled at a few frames per second (Compose draws
 *    within 16 ms, the engine's next frame came half a second later), and the snackbar came up black.
 *
 * 4. A panel takes the pointer over its whole rectangle, also where it draws nothing: a pinch on the
 *    empty part of a panel is not delivered to anything, neither to the panel's content nor to the
 *    pin or surface behind it (seen with reading plates, whose pill is narrower than their panel).
 *    A panel whose content is narrower than the panel is therefore made exactly as wide as its content:
 *    the holder is squeezed along its width ([Slot.widthFactor]) and the content is drawn stretched by
 *    the inverse, so that the picture is unchanged and no empty part is left.
 *
 * Rule that follows, and that [violations] checks: **a slot that is not shown never lies on a line of
 * sight.** Hidden = holder disabled *and* stowed: a room panel behind the viewer ([stowBehind]), a
 * panel that hangs on the head or on the instrument body 40 m below it in the head's own frame.
 * Parked (finding 1, for the first frames only) = the same places, enabled.
 *
 * Facing. A panel in the room turns towards the viewer about the vertical and then pitches towards
 * the viewer, at most 60 degrees, without roll. The turn is written to the holder here, once per frame
 * ([faceViewer]), and not left to a `LookAtComponent`: with that component the panel is drawn turned,
 * but the pointer still hits it where it would be without the turn (presses on a tag 20 degrees off
 * axis landed up to 59 px beside the aimed point and matched the unturned plane to the pixel). With
 * the rotation in the holder's own transform, picture and pointer agree.
 */
internal class PanelRig(private val stageRoot: Entity, private val onVisibility: (id: String, shown: Boolean) -> Unit) {

    enum class Mount { WORLD, CHILD, HEAD }

    /**
     * How a hidden slot is taken out of sight once its panel is up (until then it is always parked).
     * [AUTO] is the product behaviour described above: the holder is disabled and stowed. The other
     * modes exist for the debug command `hidemode`, to re-check the findings on other builds: [PARK]
     * keeps hidden slots parked (reproduces finding 2), [DISABLE_HOLDER] disables the holder where it
     * stands, [DISABLE_PANEL] disables the panel entity instead of the holder.
     */
    enum class HideMode { AUTO, PARK, DISABLE_HOLDER, DISABLE_PANEL }

    /** What a slot is doing right now (rig dump and transition log). */
    enum class Rest { SHOWN, PARKED, STOWED, OFF_IN_PLACE, PANEL_OFF }

    var hideMode = HideMode.AUTO
        set(value) {
            field = value
            slots.values.forEach { it.apply() }
        }

    private val facingRule = FacePanelUseCase()
    private var frame = 0L
    private var viewer: Vec3? = null

    /** Where hidden room panels are kept: behind the viewer, far outside the room. */
    private var stow: Vec3 = Vec3(0f, -40f, 0f)
    private var stowSet = false

    inner class Slot(val id: String, val holder: Entity, val mount: Mount, val widthDp: Float, val heightDp: Float) {
        var panel: Entity? = null
            private set
        var shown = false
            private set

        /** Where the panel is when shown: Stage metres for WORLD, the anchor's frame for CHILD and HEAD. */
        var position: Vec3 = Vec3.ZERO
            private set
        var scale = 1f
            private set

        /**
         * Share of the panel's width that its content fills (finding 4): the holder is this much
         * narrower, and the content is drawn stretched by the inverse.
         */
        var widthFactor = 1f
            set(value) {
                val f = value.coerceIn(MIN_WIDTH_FACTOR, 1f)
                if (f == field) return
                field = f
                apply()
            }

        /** For head-locked slots: offset of the anchor and rotation of the panel in the head frame (bounds report). */
        var headOffset: Vec3? = null
        var headRotation: Quat4 = Quat4.IDENTITY

        /** The entity that owns the slot: destroyed with it, and disabled while the slot is hidden. */
        var switch: Entity = holder

        /** Room panels: the turn towards the viewer that was last written to the holder. */
        var facing: PanelFacing = PanelFacing(0f, 0f)
            private set
        private var faced = false

        /** The panel has been parented under the enabled holder and has had time to come up (finding 1). */
        var settled = false
            private set
        var rest = Rest.PARKED
            private set
        private var boundFrame = 0L
        private var boundMs = 0L
        private var writtenPosition: Vec3? = null
        private var writtenScale = -1f
        private var writtenFactor = -1f
        private var switchOn = true
        private var panelOn = true
        private var reported: Boolean? = null
        private var shownFrame = 0L
        private var panelName: String? = null
        private var kickFrame = -1L

        val holderEnabled: Boolean
            get() = switchOn

        val panelEnabled: Boolean
            get() = panelOn

        /** Yaw the panel had last (kept when the head is almost straight above or below it). */
        val lastYaw: Float?
            get() = if (faced) facing.yaw else null

        /** Names the panel entity (the system logs `HOVER_ENTERED <id> <name>` with it). */
        fun rename(name: String) {
            val p = panel ?: return
            if (name == panelName) return
            runCatching { p.setName(name) }
            panelName = name
        }

        internal fun bindPanel(entity: Entity, nowMs: Long) {
            // finding 1: the holder is enabled (parked if the slot is hidden) when the panel is parented
            settled = false
            apply()
            holder.addChild(entity)
            panel = entity
            panelName = null
            panelOn = true
            boundFrame = frame
            boundMs = nowMs
        }

        internal fun forgetPanel() {
            panel = null
            panelName = null
            settled = false
        }

        internal fun settle(nowMs: Long) {
            if (kickFrame >= 0 && frame > kickFrame) {
                kickFrame = -1
                apply()
            }
            if (reported != shown) report()
            if (settled || panel == null) return
            if (frame - boundFrame < SETTLE_FRAMES || nowMs - boundMs < SETTLE_MS) return
            settled = true
            apply()
        }

        /** Tells the Compose side whether the panel may draw its content (finding 3). */
        private fun report() {
            val visible = shown && frame - shownFrame >= SHOW_FRAMES
            if (reported != visible) {
                reported = visible
                onVisibility(id, visible)
            }
        }

        internal fun face(viewer: Vec3) {
            val f = facingRule.facing(position, viewer, lastYaw)
            if (faced && angle(f.yaw, facing.yaw) < FACE_STEP_RAD && abs(f.pitch - facing.pitch) < FACE_STEP_RAD) return
            facing = f
            faced = true
            holder.setLocal(rotation = f.rotation)
        }

        /** Shows the panel; [at] and [k] move a WORLD slot, null keeps the last (or home) values. */
        fun show(at: Vec3?, k: Float?) {
            if (at != null) position = at
            if (k != null) scale = k
            shown = true
            if (mount == Mount.WORLD) viewer?.let { face(it) }
            apply()
        }

        fun hide() {
            if (!shown) return
            shown = false
            apply()
        }

        internal fun home(at: Vec3, k: Float, visible: Boolean) {
            position = at
            scale = k
            shown = visible
            apply()
        }

        /**
         * Takes the slot away for one frame and brings it back: used when a pinch was seen to go
         * through a panel that should have taken it.
         */
        internal fun kick() {
            if (!shown || !settled) return
            kickFrame = frame
            if (switchOn) {
                switch.enabled = false
                switchOn = false
            }
            log("kick")
        }

        internal fun apply() {
            if (kickFrame >= 0 && frame <= kickFrame) return
            val wanted =
                when {
                    shown -> Rest.SHOWN
                    !settled -> Rest.PARKED
                    hideMode == HideMode.AUTO -> Rest.STOWED
                    hideMode == HideMode.PARK -> Rest.PARKED
                    hideMode == HideMode.DISABLE_HOLDER -> Rest.OFF_IN_PLACE
                    else -> Rest.PANEL_OFF
                }
            val away = wanted == Rest.PARKED || wanted == Rest.STOWED
            val p = if (away) awayPosition() else position
            val k = if (away) PARK_SCALE else scale
            val wantSwitch = wanted != Rest.STOWED && wanted != Rest.OFF_IN_PLACE
            // a slot that goes away is switched off before it is moved; one that comes back is moved first
            if (!wantSwitch && switchOn) {
                switch.enabled = false
                switchOn = false
            }
            if (p != writtenPosition) {
                holder.setLocal(position = p)
                writtenPosition = p
            }
            val factor = if (away) 1f else widthFactor
            if (k != writtenScale || factor != writtenFactor) {
                if (factor == 1f) holder.setUniformScale(k) else holder.setLocal(scale = Vec3(k * factor, k, k))
                writtenScale = k
                writtenFactor = factor
            }
            if (wantSwitch && !switchOn) {
                switch.enabled = true
                switchOn = true
            }
            val wantPanel = wanted != Rest.PANEL_OFF
            if (wantPanel != panelOn) {
                panel?.let { it.enabled = wantPanel }
                panelOn = wantPanel
            }
            if (wanted != rest) {
                if (wanted == Rest.SHOWN) shownFrame = frame
                rest = wanted
                log(wanted.name.lowercase(Locale.US))
            }
            report()
        }

        private fun awayPosition(): Vec3 = if (mount == Mount.WORLD) stow else BELOW_HEAD

        /** Where the holder was last put, in its parent's frame (the shown place, or the away place). */
        val placed: Vec3
            get() = writtenPosition ?: position

        /**
         * Where the engine has the holder right now, relative to the stage root (null when it cannot be
         * asked). For a room panel that is its Stage pose. The transform of a head anchor is not part of
         * what the engine reports here, so for the other mounts the caller adds the head pose.
         */
        fun enginePosition(): Vec3? =
            runCatching { holder.convertPositionTo(Vector3.ZERO, stageRoot).domain() }.getOrNull()?.takeIf { it.isFinite() }

        fun engineRotation(): Quat4? = runCatching { holder.convertRotationTo(Quat.identity(), stageRoot).domain() }.getOrNull()

        private fun log(what: String) {
            if (LOG_SLOTS && panel != null) DeskLog.i("RIG slot id=$id $what")
        }
    }

    private val slots = LinkedHashMap<String, Slot>()

    val all: Collection<Slot>
        get() = slots.values

    operator fun get(id: String): Slot? = slots[id]

    /** Once per rendered frame: lets freshly bound panels settle (finding 1), then stows the hidden ones. */
    fun onFrame(nowMs: Long) {
        frame++
        for (slot in slots.values) slot.settle(nowMs)
    }

    /**
     * Keeps the stowing place of hidden room panels behind the viewer: 30 m away, level, opposite to
     * the viewing direction. It only moves when the viewer has turned towards it (it is always at
     * least 90 degrees off the viewing direction when it is set, and is set again inside 75 degrees).
     */
    fun stowBehind(view: ViewPose) {
        val to = stow - view.position
        val range = to.length
        val ahead = if (range < 1e-3f) 1f else to.dot(view.forward) / range
        if (stowSet && range in STOW_MIN_M..STOW_MAX_M && ahead < STOW_AHEAD_COS) return
        stowSet = true
        val back = Vec3(-view.forward.x, 0f, -view.forward.z).let { if (it.length < 0.1f) Vec3(0f, 0f, 1f) else it.normalized() }
        stow = view.position + back * STOW_DISTANCE_M
        for (slot in slots.values) if (slot.mount == Mount.WORLD && !slot.shown) slot.apply()
    }

    /** Turns every shown room panel towards [viewer] (Stage metres): yaw, then pitch, never roll. */
    fun faceViewer(viewer: Vec3) {
        this.viewer = viewer
        for (slot in slots.values) if (slot.mount == Mount.WORLD && slot.shown) slot.face(viewer)
    }

    /** A panel that stands in the room; [faceViewer] keeps it turned towards the viewer. */
    fun world(id: String, widthDp: Float, heightDp: Float): Slot {
        val holder = Entity()
        runCatching { holder.setName("holder:$id") }
        stageRoot.addChild(holder)
        return Slot(id, holder, Mount.WORLD, widthDp, heightDp).also {
            it.home(Vec3.ZERO, 1f, visible = false)
            slots[id] = it
        }
    }

    /**
     * A panel that rides on the head-locked instrument body: [inFrame] is its place in the body's
     * frame, which is turned by [frameRotation] relative to the body's mount entity ([mount]).
     *
     * The holder is a child of the mount and not of the housing frame: the frame is disabled while
     * the housing is being built, and a panel parented under a disabled entity never appears.
     */
    fun onBody(id: String, mount: Entity, frameRotation: Quat4, inFrame: Vec3, scale: Float, widthDp: Float, heightDp: Float, shown: Boolean): Slot {
        val holder = Entity()
        runCatching { holder.setName("holder:$id") }
        mount.addChild(holder)
        holder.setLocal(rotation = frameRotation)
        return Slot(id, holder, Mount.CHILD, widthDp, heightDp).also {
            it.headRotation = frameRotation
            it.home(frameRotation.rotate(inFrame), scale, visible = shown)
            slots[id] = it
        }
    }

    /**
     * A panel locked to the head (`AnchorTarget.createCameraTarget()`): [offset] is in the head frame
     * (x right, y up, -z ahead). With [facing] the panel is turned to face the head; without it the
     * panel stays parallel to the image plane (the calibration target of the test harness).
     */
    fun head(id: String, offset: Vec3, scale: Float, widthDp: Float, heightDp: Float, facing: Boolean = true): Slot {
        val anchor = Entity()
        runCatching { anchor.setName("holder:$id") }
        val rotation = if (facing) facingHead(offset) else Quat4.IDENTITY
        anchor.components.set(AnchorComponent(AnchorTarget.createCameraTarget()).apply { positionOffset = offset.sdk() })
        stageRoot.addChild(anchor)
        // the anchor component owns the anchor entity's transform: rotation, position and scale of
        // the panel live on a child
        val scaler = Entity()
        anchor.addChild(scaler)
        scaler.setLocal(rotation = rotation)
        return Slot(id, scaler, Mount.HEAD, widthDp, heightDp).also {
            it.headOffset = offset
            it.headRotation = rotation
            it.switch = anchor
            it.home(Vec3.ZERO, scale, visible = false)
            slots[id] = it
        }
    }

    /** Parents freshly composed panel entities under their holders. Returns true when none is missing. */
    fun bind(attachments: SpatialViewAttachments, ids: Collection<String>, nowMs: Long): Boolean {
        var complete = true
        for (id in ids) {
            val slot = slots[id] ?: continue
            if (slot.panel != null) continue
            val panel = attachments.entity(id)
            if (panel == null) {
                complete = false
                continue
            }
            slot.bindPanel(panel, nowMs)
            slot.rename("panel:$id")
            DeskLog.i("PANEL bound id=$id")
        }
        return complete
    }

    /**
     * Removes a slot whose attachment leaves the composition (the debug calibration panel). The panel
     * entity belongs to the framework: it is let go of before the holder is destroyed.
     */
    fun remove(id: String) {
        val slot = slots.remove(id) ?: return
        runCatching { slot.holder.removeAllChildren() }
        slot.forgetPanel()
        runCatching { slot.switch.destroy(true) }
    }

    /** The framework owns the panel entities: let go of them before the holders are destroyed. */
    fun release() {
        for (slot in slots.values) {
            runCatching { slot.holder.removeAllChildren() }
            slot.forgetPanel()
        }
        slots.clear()
    }

    companion object {
        private const val LOG_SLOTS = false

        /** Metres per dp of an attachment panel at scale 1. */
        const val DP_M = 0.0008f

        /** A panel is never squeezed to less than this share of its width. */
        private const val MIN_WIDTH_FACTOR = 0.25f

        /** Out of reach: a thousandth of the size. */
        private const val PARK_SCALE = 0.001f

        /** Away place of a panel that hangs on the head or on the body: 40 m below, in the head's own frame. */
        private val BELOW_HEAD = Vec3(0f, -40f, 0f)
        private const val STOW_DISTANCE_M = 30f
        private const val STOW_MIN_M = 20f
        private const val STOW_MAX_M = 45f

        /** The stowing place moves when it is less than 75 degrees away from the viewing direction (cos 75). */
        private const val STOW_AHEAD_COS = 0.26f

        /** What counts as "inside the view" for the rule check (the emulator shows about 40 degrees each way). */
        private const val VIEW_HALF_DEG = 50f

        /** Engine frames a panel is in its place before its content is composed (finding 3). */
        private const val SHOW_FRAMES = 2L

        /** A panel counts as up after this many rendered frames and this much time under its enabled holder. */
        private const val SETTLE_FRAMES = 6L
        private const val SETTLE_MS = 600L

        /** A room panel is turned again when the direction to the viewer has changed by a quarter of a degree. */
        private const val FACE_STEP_RAD = 0.0044f

        private fun angle(a: Float, b: Float): Float {
            var d = abs(a - b)
            if (d > PI.toFloat()) d = 2f * PI.toFloat() - d
            return d
        }

        /** Rotation in the head frame that turns a panel at [offset] so that its +Z points at the head. */
        fun facingHead(offset: Vec3): Quat4 {
            val z = (-offset).normalized()
            var x = Vec3.UP.cross(z)
            if (x.length < 1e-3f) x = Vec3.RIGHT
            x = x.normalized()
            val y = z.cross(x).normalized()
            return Quat4.fromBasis(x, y, z)
        }
    }
}
