package io.embodit.deskfit.ui.deskfit.scene

import io.embodit.deskfit.domain.model.Quat4
import io.embodit.deskfit.domain.model.Vec3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Pose of the viewer's head in Stage metres: -Z of [rotation] is the viewing direction. */
data class ViewPose(val position: Vec3, val rotation: Quat4) {
    val forward: Vec3
        get() = rotation.rotate(Vec3.AHEAD)
}

/** How a room panel is turned: yaw about the vertical and pitch towards the viewer, radians; no roll. */
data class PanelFacing(val yaw: Float, val pitch: Float) {
    val rotation: Quat4
        get() = Quat4.axisAngle(Vec3.UP, yaw) * Quat4.axisAngle(Vec3.RIGHT, -pitch)

    /** Unit vector along the panel's width (always horizontal). */
    val right: Vec3
        get() = Vec3(cos(yaw), 0f, -sin(yaw))

    val up: Vec3
        get() = Vec3(-sin(yaw) * sin(pitch), cos(pitch), -cos(yaw) * sin(pitch))

    val normal: Vec3
        get() = Vec3(sin(yaw) * cos(pitch), sin(pitch), cos(yaw) * cos(pitch))
}

/**
 * Facing rule for panels that stand in the room (Spatial Ruler): turn about the vertical towards the
 * head, then pitch towards the head, the pitch clamped to 60 degrees, never roll. With the head almost
 * straight above or below, the last yaw is kept.
 */
class FacePanelUseCase {
    fun facing(panel: Vec3, head: Vec3, lastYaw: Float?): PanelFacing {
        val to = head - panel
        val flat = hypot(to.x, to.z)
        val yaw = if (flat < KEEP_YAW_WITHIN_M && lastYaw != null) lastYaw else if (flat < 1e-4f) 0f else atan2(to.x, to.z)
        val pitch = atan2(to.y, flat).coerceIn(-MAX_PITCH_RAD, MAX_PITCH_RAD)
        return PanelFacing(yaw, pitch)
    }

    companion object {
        const val MAX_PITCH_RAD = 1.0471976f
        const val KEEP_YAW_WITHIN_M = 0.1f
    }
}
