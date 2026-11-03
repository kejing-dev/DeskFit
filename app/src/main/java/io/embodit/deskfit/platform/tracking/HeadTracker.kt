package io.embodit.deskfit.platform.tracking

import android.os.SystemClock
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
import io.embodit.deskfit.domain.model.BlockReason
import io.embodit.deskfit.domain.model.Quat4
import io.embodit.deskfit.domain.model.Vec3
import io.embodit.deskfit.platform.DeskLog

/** Head pose in Stage space. */
data class HeadPose(val position: Vec3, val rotation: Quat4) {
    /** Viewing direction (-Z of the head). */
    val forward: Vec3
        get() = rotation.rotate(Vec3.AHEAD)

    val up: Vec3
        get() = rotation.rotate(Vec3.UP)

    val right: Vec3
        get() = rotation.rotate(Vec3.RIGHT)

    /** Horizontal forward (unit), falls back to -Z when looking straight up or down. */
    val forwardFlat: Vec3
        get() {
            val f = Vec3(forward.x, 0f, forward.z)
            return if (f.length < 0.05f) Vec3.AHEAD else f.normalized()
        }
}

/**
 * HMD pose source (`HMDTrackingProvider`), reused from Spatial Ruler. Read once per frame from the
 * ECS system; the last valid pose is kept. No valid pose for [LOST_AFTER_MS] = tracking lost (S15).
 */
class HeadTracker {
    private var provider: HMDTrackingProvider? = null
    private var last: HeadPose? = null
    private var lastValidMs = 0L
    private var support: DataProvider.SupportState? = null

    /** Test hook: reports lost tracking whatever the provider says. */
    @Volatile var forceLost = false

    /** Test hook: reports the Stage as unavailable. */
    @Volatile var forceBlocked = false

    fun start() {
        if (provider != null) return
        val p = HMDTrackingProvider()
        val result = runCatching { p.start() }
        support = runCatching { p.supportState }.getOrNull()
        DeskLog.i("TRACK hmd start=${result.getOrNull() ?: result.exceptionOrNull()} support=$support")
        provider = p
        lastValidMs = SystemClock.elapsedRealtime()
    }

    fun stop() {
        runCatching { provider?.stop() }
        provider = null
        last = null
    }

    /**
     * Why head tracking cannot be used right now, or null. Read live: the state is `NOT_IN_FULL_SPACE`
     * while the Stage is still opening and turns to `SUPPORTED` by itself (Spatial Ruler finding).
     */
    fun blockReason(): BlockReason? {
        if (forceBlocked) return BlockReason.WITHOUT_PERMISSION
        return when (provider?.let { p -> runCatching { p.supportState }.getOrNull() } ?: support) {
            DataProvider.SupportState.NOT_IN_FULL_SPACE -> BlockReason.NOT_IN_FULL_SPACE
            DataProvider.SupportState.WITHOUT_PERMISSION -> BlockReason.WITHOUT_PERMISSION
            DataProvider.SupportState.DEVICE_NOT_SUPPORTED -> BlockReason.DEVICE_NOT_SUPPORTED
            else -> null
        }
    }

    fun lost(nowMs: Long): Boolean = forceLost || (provider != null && last != null && nowMs - lastValidMs > LOST_AFTER_MS)

    /** Current head pose, or the last valid one, or null before the first sample. */
    fun pose(): HeadPose? {
        val p = provider ?: return last
        val pose = runCatching { p.latestData.hmdPose }.getOrNull() ?: return last
        val position = pose.position
        val rotation = pose.rotation
        val sample = HeadPose(Vec3(position.x, position.y, position.z), Quat4(rotation.x, rotation.y, rotation.z, rotation.w))
        if (!sample.position.isFinite()) return last
        last = sample
        lastValidMs = SystemClock.elapsedRealtime()
        return sample
    }

    private companion object {
        const val LOST_AFTER_MS = 1500L
    }
}
