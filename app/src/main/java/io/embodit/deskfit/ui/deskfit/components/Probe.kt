package io.embodit.deskfit.ui.deskfit.components

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import io.embodit.deskfit.ui.deskfit.DeskEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * Seams for the QA hooks (debug builds): panel controls register a stable name with the action they
 * perform, so that `am broadcast ... --es cmd ui --es id <name>` can press them without a pointer.
 * In release builds nothing is registered and [host] stays null.
 */
object DeskProbe {
    @Volatile var enabled = false

    interface Host {
        fun dispatch(event: DeskEvent)

        fun uiState(): io.embodit.deskfit.ui.deskfit.DeskUiState

        fun dumpJson(): String

        fun scene(): SceneProbe?
    }

    interface SceneProbe {
        fun poseLine(): String

        fun planeLines(): List<String>

        fun panelLines(): List<String>

        fun forceTrackingLost(lost: Boolean): String

        fun setFps(on: Boolean): String

        fun setSyntheticRay(values: FloatArray?): String

        fun triggerSynthetic(): String
    }

    @Volatile var host: Host? = null

    /** control name -> what pressing it does (the composable registers while it is shown). */
    private val controls = ConcurrentHashMap<String, () -> Unit>()

    fun register(name: String, action: () -> Unit) {
        controls[name] = action
    }

    fun unregister(name: String) {
        controls.remove(name)
    }

    fun press(name: String): Boolean {
        val action = controls[name] ?: return false
        action()
        return true
    }

    fun names(): List<String> = controls.keys.sorted()
}

/** Id of the attachment panel whose content is being composed. */
val LocalProbePanel = compositionLocalOf { "" }

/** Registers [action] under [name] while the composable is in the composition (no-op in release). */
@androidx.compose.runtime.Composable
fun Modifier.probe(name: String, action: () -> Unit): Modifier {
    if (!DeskProbe.enabled) return this
    androidx.compose.runtime.DisposableEffect(name) {
        DeskProbe.register(name, action)
        onDispose { DeskProbe.unregister(name) }
    }
    return this
}
