package io.embodit.deskfit.platform

import android.util.Log
import io.embodit.deskfit.domain.model.Vec3
import java.util.Locale

/** One line per event under the tag `DeskFit`; stable prefixes: STATE, PLANES, DESK, EYE, TAP, DRAG, BAR, RESULT, CMD, PANEL. */
object DeskLog {
    const val TAG = "DeskFit"

    fun i(line: String) {
        Log.i(TAG, line)
    }

    fun w(line: String, error: Throwable? = null) {
        Log.w(TAG, line, error)
    }

    fun e(line: String, error: Throwable? = null) {
        Log.e(TAG, line, error)
    }

    fun pos(v: Vec3?): String = if (v == null) "null" else String.format(Locale.US, "(%.4f, %.4f, %.4f)", v.x, v.y, v.z)

    fun f(v: Float): String = String.format(Locale.US, "%.4f", v)
}
