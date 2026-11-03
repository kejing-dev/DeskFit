package io.embodit.deskfit.platform.input

import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.controller.ControllerActionData
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import io.embodit.deskfit.domain.model.PointerRay
import io.embodit.deskfit.domain.model.Quat4
import io.embodit.deskfit.domain.model.Vec3
import io.embodit.deskfit.platform.DeskLog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** What the controllers deliver in one frame: the aiming ray of the active hand, its trigger and the thumbstick X of either hand. */
data class ControllerSample(val ray: PointerRay, val triggerPressed: Boolean, val thumbX: Float, val right: Boolean)

/**
 * Controller input through `ControllerTrackingProvider` (SDK 6.1.9): pose from `latestData` on the
 * main thread, trigger and thumbstick (`ControllerAction.thumbstickValue.x`) from the action
 * listener, which runs on the data thread and only copies values into atomics.
 *
 * The thumbstick turns the bar on the screen pole about its vertical axis (factory default
 * interaction D1); the ray gives the desk hover reticle and, on the trigger edge, a desk tap.
 * On the emulator the provider is `DEVICE_NOT_SUPPORTED` in eye mode and `SUPPORTED` in controller mode.
 */
class ControllerInput {
    private var provider: ControllerTrackingProvider? = null
    private val rightTrigger = AtomicBoolean(false)
    private val leftTrigger = AtomicBoolean(false)
    private val rightThumb = AtomicInteger(0)
    private val leftThumb = AtomicInteger(0)
    private var useRight = true
    private var lastSupport: DataProvider.SupportState? = null

    private val listener =
        ControllerTrackingProvider.ControllerActionListener { action: ControllerActionData ->
            rightTrigger.set(action.right.triggerPressed)
            leftTrigger.set(action.left.triggerPressed)
            rightThumb.set(java.lang.Float.floatToIntBits(action.right.thumbstickValue.x))
            leftThumb.set(java.lang.Float.floatToIntBits(action.left.thumbstickValue.x))
        }

    fun start() {
        if (provider != null) return
        val p = ControllerTrackingProvider()
        runCatching { p.addControllerActionListener(listener) }
        val result = runCatching { p.start() }
        lastSupport = runCatching { p.supportState }.getOrNull()
        DeskLog.i("TRACK controller start=${result.getOrNull() ?: result.exceptionOrNull()} support=$lastSupport")
        provider = p
    }

    fun stop() {
        provider?.let { p ->
            runCatching { p.removeControllerActionListener(listener) }
            runCatching { p.stop() }
        }
        provider = null
    }

    /** Once per frame on the main thread; null when no controller is tracked. */
    fun sample(): ControllerSample? {
        val p = provider ?: return null
        val support = runCatching { p.supportState }.getOrNull()
        if (support != lastSupport) {
            lastSupport = support
            DeskLog.i("TRACK controller support=$support")
        }
        if (support != DataProvider.SupportState.SUPPORTED) return null
        val data = runCatching { p.latestData }.getOrNull() ?: return null
        val rightDown = rightTrigger.get()
        val leftDown = leftTrigger.get()
        val rx = java.lang.Float.intBitsToFloat(rightThumb.get())
        val lx = java.lang.Float.intBitsToFloat(leftThumb.get())
        if (useRight && leftDown && !rightDown) useRight = false
        if (!useRight && rightDown && !leftDown) useRight = true
        val right = data.right
        val left = data.left
        val pickRight = if (useRight) right != null else left == null
        val pose = (if (pickRight) right else left) ?: return null
        val position = Vec3(pose.position.x, pose.position.y, pose.position.z)
        val rotation = Quat4(pose.rotation.x, pose.rotation.y, pose.rotation.z, pose.rotation.w)
        if (!position.isFinite()) return null
        val forward = rotation.rotate(Vec3.AHEAD)
        if (!forward.isFinite() || forward.length < 0.5f) return null
        // either thumbstick turns; the one further from rest wins
        val thumb = if (kotlin.math.abs(rx) >= kotlin.math.abs(lx)) rx else lx
        return ControllerSample(PointerRay(position, forward.normalized()), if (pickRight) rightDown else leftDown, thumb, pickRight)
    }
}
