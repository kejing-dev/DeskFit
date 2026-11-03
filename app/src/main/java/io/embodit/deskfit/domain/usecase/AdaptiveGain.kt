package io.embodit.deskfit.domain.usecase

/**
 * Drag gain of the Spatial Ruler (reused for the bars): 1 : 1 while the hand moves; after 400 ms
 * without a raw step of a pixel or more the gain drops to 1 : 4 ("fine"), and a quick move (over
 * 4 deg/s, here: over 4 cm/s of bar travel) lets it go at once.
 */
class AdaptiveGain(
    private val fastPerS: Float = FAST_M_PER_S,
    private val fine: Float = FINE,
    private val noMotionMs: Long = NO_MOTION_MS,
) {
    var gain = 1f
        private set
    private var motionMs = NEVER

    fun begin(nowMs: Long) {
        gain = 1f
        motionMs = nowMs
    }

    /** One drag event: the raw speed of the hand (m/s) and whether it moved at all. Returns the gain for this event. */
    fun sample(speedPerS: Float, moved: Boolean, nowMs: Long): Float {
        if (moved) motionMs = nowMs
        if (speedPerS > fastPerS && gain < 1f) gain = 1f
        return gain
    }

    /** Time passes (a frame): fine engages once the hand has rested for [noMotionMs]. */
    fun tick(nowMs: Long): Float {
        if (gain == 1f && motionMs != NEVER && nowMs - motionMs >= noMotionMs) gain = fine
        return gain
    }

    val fineOn: Boolean
        get() = gain < 1f

    companion object {
        const val NEVER = Long.MIN_VALUE / 4
        const val NO_MOTION_MS = 400L
        const val FAST_M_PER_S = 0.04f
        const val FINE = 0.25f
        const val MOTION_M = 0.0004f
    }
}
