package io.embodit.deskfit.platform.sense

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.pico.spatial.core.lifecycle.Cancellable
import com.pico.spatial.sense.base.AnchorUpdate
import com.pico.spatial.sense.plane.PlaneAnchor
import com.pico.spatial.sense.plane.PlaneTrackingManager
import io.embodit.deskfit.domain.model.PlaneSurface
import io.embodit.deskfit.domain.model.SurfaceKind
import io.embodit.deskfit.domain.model.Vec3
import io.embodit.deskfit.platform.DeskLog

/**
 * Plane detection (reused from Spatial Ruler). Surfaces arrive progressively (emulator: first after
 * about 20 s, all after about 45 s). Vertices are converted to Stage metres here; normals come from
 * the vertices. Main thread only.
 */
class PlaneTracker {

    fun interface Listener {
        fun onPlane(id: String, surface: PlaneSurface?, added: Boolean)
    }

    private val main = Handler(Looper.getMainLooper())
    private val surfaces = LinkedHashMap<String, PlaneSurface>()
    private var subscription: Cancellable? = null
    private var listener: Listener? = null

    /** QA hook `nodesk`: TABLE planes are hidden from the app. */
    @Volatile var hideTables = false

    var lastAddedAtMs: Long = 0L
        private set

    var startedAtMs: Long = 0L
        private set

    /** Bumped whenever a plane is added, changed or removed. */
    var revision: Int = 0
        private set

    val all: Collection<PlaneSurface>
        get() = if (hideTables) surfaces.values.filter { it.kind != SurfaceKind.TABLE } else surfaces.values

    val count: Int
        get() = all.size

    fun get(id: String): PlaneSurface? = surfaces[id]?.takeIf { !hideTables || it.kind != SurfaceKind.TABLE }

    fun bump() {
        revision++
    }

    fun start(listener: Listener) {
        if (subscription != null) return
        this.listener = listener
        startedAtMs = SystemClock.elapsedRealtime()
        lastAddedAtMs = startedAtMs
        subscription =
            PlaneTrackingManager.subscribeAnchorUpdate { update ->
                if (Looper.myLooper() == Looper.getMainLooper()) handle(update) else main.post { handle(update) }
            }
        runCatching { PlaneTrackingManager.start() }.onFailure { DeskLog.e("PLANES start failed: $it", it) }
        DeskLog.i("PLANES tracking started state=${runCatching { PlaneTrackingManager.state }.getOrNull()}")
    }

    fun stop() {
        runCatching { subscription?.cancel() }
        subscription = null
        runCatching { PlaneTrackingManager.stop() }
        listener = null
        surfaces.clear()
        revision++
    }

    private fun handle(update: AnchorUpdate<PlaneAnchor>) {
        if (subscription == null) return
        val anchor = update.anchor
        val id = anchor.anchorUUID.toString()
        when (update.event) {
            AnchorUpdate.Event.ADDED, AnchorUpdate.Event.LOADED, AnchorUpdate.Event.UPDATED -> {
                val surface = runCatching { anchor.toSurface(id) }.getOrNull() ?: return
                if (surface.vertices.size < 3 || surface.indices.size < 3) return
                val previous = surfaces[id]
                val isNew = previous == null
                if (!isNew && sameGeometry(previous!!, surface)) return
                surfaces[id] = surface
                revision++
                if (isNew) {
                    lastAddedAtMs = SystemClock.elapsedRealtime()
                    DeskLog.i("PLANES add ${surface.label} y=${DeskLog.f(surface.y)} n=${surfaces.size}")
                }
                listener?.onPlane(id, surface, isNew)
            }
            AnchorUpdate.Event.REMOVED -> {
                if (surfaces.remove(id) != null) {
                    revision++
                    listener?.onPlane(id, null, false)
                }
            }
            else -> Unit
        }
    }

    private fun PlaneAnchor.toSurface(id: String): PlaneSurface {
        val q = transform.quaternion
        val p = transform.position
        val stage =
            vertices.map { v ->
                val w = q.rotateVector(v) + p
                Vec3(w.x, w.y, w.z)
            }
        val name = semantics.name
        return PlaneSurface(id, SurfaceKind.fromSemanticName(name), name, stage, indices.toList())
    }

    private fun sameGeometry(a: PlaneSurface, b: PlaneSurface): Boolean {
        if (a.vertices.size != b.vertices.size || a.semanticName != b.semanticName) return false
        for (i in a.vertices.indices) if (a.vertices[i].distanceTo(b.vertices[i]) > GEOMETRY_EPS_M) return false
        return true
    }

    private companion object {
        const val GEOMETRY_EPS_M = 0.0005f
    }
}
