package io.embodit.deskfit.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.embodit.deskfit.platform.DeskGraph
import io.embodit.deskfit.platform.DeskLog
import io.embodit.deskfit.ui.deskfit.BarKind
import io.embodit.deskfit.ui.deskfit.DeskEvent
import io.embodit.deskfit.ui.deskfit.components.DeskProbe
import io.embodit.deskfit.domain.model.LanguagePref
import io.embodit.deskfit.domain.model.LengthUnit
import io.embodit.deskfit.ui.deskfit.scene.DeskScene
import java.util.Locale

/**
 * Debug builds only: the QA hooks of the design package (`qaHooks`), driven over adb:
 *
 *   adb -s emulator-5554 shell "am broadcast -a io.embodit.deskfit.DEBUG -p io.embodit.deskfit --es cmd <cmd> ..."
 *
 * | cmd      | extras                         | effect |
 * |----------|--------------------------------|--------|
 * | state    | --es id S1..S17 (S13A/S13B, S11B) | jump to that named state with plausible values |
 * | eye      | --ef cm 118                    | inject the eye height (skips sampling) |
 * | tap      | --ef x 0 --ef z 70             | raise the pole at desk-local cm (x right, z into the desk) from the sampled head |
 * | bar      | --ef cm 112                    | bar on the pole at that height above the floor (cm) |
 * | forearm  | --ef cm 71                     | forearm bar at that height above the floor (cm) |
 * | plate    | --ef cm 75                     | manual desk plate at that height (S11a) |
 * | lost     | --ez on true/false             | tracking lost (S15) on / off |
 * | hold     | --ez on true/false             | freeze transients (S4 / S5 / S12 stay for a screenshot) |
 * | nodesk   | --ez on true (default) / false | hide TABLE planes -> S11 by timeout |
 * | reset    |                                | clear the saved results (S3 without a last result) |
 * | lang     | --es id system/zh/en           | language |
 * | unit     | --es id cm/in                  | unit |
 * | ui       | --es id <control>              | press a panel control: primary secondary back more menu_restart menu_exit lang_* unit_* blocked_retry blocked_exit |
 * | pointer  | --es a "ox,oy,oz,dx,dy,dz"     | synthetic aiming ray (desk reticle); none: remove |
 * | trigger  |                                | one press of the synthetic ray |
 * | stick    | --ef x -1..1                   | synthetic thumbstick X (turns the bar); 0 releases |
 * | dump     |                                | `DUMP {json}` |
 * | pose / planes / panels / fps (--ez on) |  | read-backs |
 *
 * Numbers may also be passed as `--es a "<value>"`.
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd")?.trim()?.lowercase(Locale.US) ?: return
        val host = DeskProbe.host
        DeskLog.i("CMD recv '$cmd' extras=${intent.extras?.keySet()?.joinToString(",")}")
        if (host == null) {
            DeskLog.i("CMD $cmd ignored: the Stage is not running")
            return
        }
        fun num(key: String): Float? {
            if (intent.hasExtra(key)) {
                val f = intent.getFloatExtra(key, Float.NaN)
                if (!f.isNaN()) return f
                intent.getStringExtra(key)?.toFloatOrNull()?.let { return it }
                val i = intent.getIntExtra(key, Int.MIN_VALUE)
                if (i != Int.MIN_VALUE) return i.toFloat()
            }
            intent.getStringExtra("a")?.toFloatOrNull()?.let { return it }
            return null
        }
        fun on(): Boolean = if (intent.hasExtra("on")) intent.getBooleanExtra("on", true) else true
        val id = intent.getStringExtra("id") ?: intent.getStringExtra("s")
        val scene = host.scene() as? DeskScene
        try {
            when (cmd) {
                "state" -> host.dispatch(DeskEvent.DebugState(id ?: return DeskLog.i("CMD state needs --es id S<n>")))
                "eye" -> host.dispatch(DeskEvent.DebugEye((num("cm") ?: return DeskLog.i("CMD eye needs --ef cm")) / 100f))
                "tap" -> host.dispatch(DeskEvent.DebugTap((num("x") ?: 0f) / 100f, (num("z") ?: 70f) / 100f))
                "bar" -> host.dispatch(DeskEvent.DebugBar(BarKind.SCREEN, (num("cm") ?: return DeskLog.i("CMD bar needs --ef cm")) / 100f))
                "forearm" -> host.dispatch(DeskEvent.DebugBar(BarKind.FOREARM, (num("cm") ?: return DeskLog.i("CMD forearm needs --ef cm")) / 100f))
                "plate" -> host.dispatch(DeskEvent.DebugBar(BarKind.MANUAL_PLATE, (num("cm") ?: return DeskLog.i("CMD plate needs --ef cm")) / 100f))
                "lost" -> DeskLog.i(scene?.forceTrackingLost(on()) ?: NO_SCENE)
                "hold" -> host.dispatch(DeskEvent.DebugHold(on()))
                "nodesk" -> {
                    DeskGraph.planes.hideTables = on()
                    DeskGraph.planes.bump()
                    DeskLog.i("CMD nodesk hideTables=${DeskGraph.planes.hideTables}")
                }
                "reset" -> host.dispatch(DeskEvent.DebugReset)
                "lang" -> host.dispatch(DeskEvent.LanguageSelected(LanguagePref.entries.firstOrNull { it.tag == id } ?: LanguagePref.SYSTEM))
                "unit" -> host.dispatch(DeskEvent.UnitSelected(LengthUnit.entries.firstOrNull { it.tag == id } ?: LengthUnit.CM))
                "ui" -> {
                    val name = id ?: return DeskLog.i("CMD ui needs --es id <control>; known=${DeskProbe.names()}")
                    val ok = DeskProbe.press(name)
                    DeskLog.i("UI $name pressed=$ok known=${DeskProbe.names()}")
                }
                "pointer" -> {
                    val values = intent.getStringExtra("a")?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }?.toFloatArray()
                    DeskLog.i(scene?.setSyntheticRay(values) ?: NO_SCENE)
                }
                "trigger" -> DeskLog.i(scene?.triggerSynthetic() ?: NO_SCENE)
                "stick" -> DeskLog.i(scene?.setSyntheticThumb(num("x") ?: 0f) ?: NO_SCENE)
                "dump" -> DeskLog.i("DUMP ${host.dumpJson()} barYaw=${scene?.barYawDeg}")
                "pose" -> DeskLog.i(scene?.poseLine() ?: NO_SCENE)
                "planes" -> (scene?.planeLines() ?: listOf(NO_SCENE)).forEach { DeskLog.i(it) }
                "panels" -> (scene?.panelLines() ?: listOf(NO_SCENE)).forEach { DeskLog.i(it) }
                "fps" -> DeskLog.i(scene?.setFps(on()) ?: NO_SCENE)
                else -> DeskLog.i("CMD unknown '$cmd'")
            }
        } catch (t: Throwable) {
            DeskLog.e("CMD $cmd failed: $t", t)
        }
    }

    private companion object {
        const val NO_SCENE = "CMD ignored: no scene"
    }
}
