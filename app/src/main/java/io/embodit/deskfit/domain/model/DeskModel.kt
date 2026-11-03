package io.embodit.deskfit.domain.model

/**
 * The named states of the design package (`states[]` S1 - S17). S14 (more menu), S15 (tracking lost)
 * and S17 (last result shown) are flags on top of a phase: `menuOpen`, `trackingLost`, `lastResult`.
 */
enum class Phase(val stateId: String) {
    STARTING("S1"),
    SCANNING("S2"),
    SIT("S3"),
    SAMPLING("S4"),
    EYE_LOCKED("S5"),
    MARK_SCREEN("S6"),
    ALIGN_TOP("S7"),
    FOREARM("S8"),
    RESULT("S9"),
    DESK_MANUAL_A("S11"),
    DESK_MANUAL_B("S11b"),
    UNSTABLE("S12"),
    POSTURE("S13"),
    BLOCKED("S16");

    /** Which of the four step dots is current (1 - 4). */
    val step: Int
        get() =
            when (this) {
                STARTING, SCANNING, SIT, SAMPLING, EYE_LOCKED, UNSTABLE, POSTURE, DESK_MANUAL_A, DESK_MANUAL_B, BLOCKED -> 1
                MARK_SCREEN, ALIGN_TOP -> 2
                FOREARM -> 3
                RESULT -> 4
            }
}

enum class LanguagePref(val tag: String) {
    SYSTEM("system"),
    ZH("zh"),
    EN("en"),
}

enum class LengthUnit(val tag: String) {
    CM("cm"),
    IN("in"),
}

enum class BlockReason { NOT_IN_FULL_SPACE, WITHOUT_PERMISSION, DEVICE_NOT_SUPPORTED }

/** The two kinds of posture check (S13). */
enum class PostureKind { STANDING, LOW }

/** Snackbar copy keys (design `copy.snackbar`). */
enum class SnackKind(val durationMs: Long) {
    SCANNING(-1L),
    NO_FLOOR(-1L),
    NO_DESK(4000),
    TAP_DESK(3000),
    TAP_UNDER(3000),
    UNSTABLE(3000),
    LOST(-1L);

    companion object {
        const val STICKY = -1L
    }
}

/** A horizontal line segment with the horizontal direction that points from it into the desk. */
data class DeskEdge(val a: Vec3, val b: Vec3, val inward: Vec3) {
    /** Point on the edge line nearest to [p] (clamped a little beyond the ends), at the edge's height. */
    fun nearest(p: Vec3): Vec3 {
        val d = b - a
        val l2 = d.dot(d)
        if (l2 < 1e-9f) return a
        val t = ((p - a).dot(d) / l2).coerceIn(-0.5f, 1.5f)
        return a + d * t
    }

    val along: Vec3
        get() = (b - a).let { if (it.length < 1e-6f) Vec3.RIGHT else it.normalized() }
}

/**
 * The desk in use: the plane it came from (null for a manually set desk), its top as absolute Stage
 * y, and the edge nearest to the user ([edge] is null while a manual desk has no edge yet).
 */
data class DeskPick(val planeId: String?, val topY: Float, val edge: DeskEdge?, val outline: List<Vec3>) {
    val manual: Boolean
        get() = planeId == null
}

/** Verdict of one result row. [delta] is in metres (positive = raise / push back), 0 when it fits. */
data class RowVerdict(val ok: Boolean, val delta: Float, val note: Note? = null) {
    enum class Note { FOOTREST, TRAY, LOW_DESK, AFTER_CHAIR, LAPTOP }
}

/** What the result panel and the 3D references show (design `rules.chair` .. `rules.allGood`). */
data class Judgement(
    val chair: RowVerdict,
    val screenHeight: RowVerdict,
    val screenDistance: RowVerdict,
    /** Screen more than 35 degrees off the desk edge's inward normal (OSHA side limit). */
    val offCentre: Boolean,
    /** Eye height after the chair adjustment, metres above the floor. */
    val eyeAfter: Float,
) {
    val allGood: Boolean
        get() = chair.ok && screenHeight.ok && screenDistance.ok && !offCentre
}

/** One saved measurement (design `persistence`): metres above the floor unless noted. */
data class SavedResult(
    val epochMs: Long,
    val eyeH: Float,
    val deskH: Float,
    val forearmH: Float,
    val topH: Float,
    val distance: Float,
    val sideDeg: Float,
    val chairDelta: Float,
    val screenDelta: Float,
    val distanceDelta: Float,
    val offCentre: Boolean,
)

data class Settings(val language: LanguagePref, val unit: LengthUnit)
