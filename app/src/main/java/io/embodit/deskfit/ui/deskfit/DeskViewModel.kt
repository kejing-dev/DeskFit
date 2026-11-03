package io.embodit.deskfit.ui.deskfit

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.embodit.deskfit.data.ResultsRepository
import io.embodit.deskfit.domain.model.BlockReason
import io.embodit.deskfit.domain.model.DeskEdge
import io.embodit.deskfit.domain.model.DeskPick
import io.embodit.deskfit.domain.model.LanguagePref
import io.embodit.deskfit.domain.model.LengthUnit
import io.embodit.deskfit.domain.model.Phase
import io.embodit.deskfit.domain.model.PostureKind
import io.embodit.deskfit.domain.model.SavedResult
import io.embodit.deskfit.domain.model.Settings
import io.embodit.deskfit.domain.model.SnackKind
import io.embodit.deskfit.domain.model.Vec3
import io.embodit.deskfit.domain.usecase.DeskRulesUseCase
import io.embodit.deskfit.domain.usecase.EyeSampleResult
import io.embodit.deskfit.domain.usecase.JudgeUseCase
import io.embodit.deskfit.platform.DeskGraph
import io.embodit.deskfit.platform.DeskLog
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The state machine of the design package (`states[]`, `rules`): every transition is here, the scene
 * only reports and renders. Timers are driven by [onFrame] from the frame system, so that the QA
 * `hold` hook can freeze them. Main thread only.
 */
class DeskViewModel(
    private val repository: ResultsRepository,
    private val rules: DeskRulesUseCase = DeskRulesUseCase(),
    private val judge: JudgeUseCase = JudgeUseCase(),
) : ViewModel() {

    private val _state = MutableStateFlow(DeskUiState())
    val state: StateFlow<DeskUiState> = _state.asStateFlow()

    private val _effects = Channel<DeskUiEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private var snackSeq = 0
    private var scanStartMs = 0L
    private var blockedSinceMs = NEVER
    private var sceneReadyCount = 0
    private var lastLoggedState = ""
    private var pendingPostureEye: Float? = null

    init {
        val settings = repository.loadSettings(DeskGraph.defaultSettings())
        _state.update { it.copy(language = settings.language, unit = settings.unit, lastResult = repository.loadResults().firstOrNull()) }
    }

    private val s: DeskUiState
        get() = _state.value

    private fun set(block: (DeskUiState) -> DeskUiState) {
        _state.update(block)
        val id = s.stateId
        if (id != lastLoggedState) {
            lastLoggedState = id
            DeskLog.i("STATE $id phase=${s.phase.name.lowercase(Locale.US)}")
        }
    }

    // =========================================================================================
    // events
    // =========================================================================================

    fun onEvent(event: DeskEvent) {
        val now = SystemClock.elapsedRealtime()
        when (event) {
            DeskEvent.SceneReady -> onSceneReady(now)
            is DeskEvent.PlanesChanged -> onPlanes(event, now)
            is DeskEvent.TrackingChanged -> onTracking(event.lost, now)
            is DeskEvent.BlockChanged -> onBlock(event.reason, now)
            is DeskEvent.SampleFinished -> onSample(event, now)
            is DeskEvent.DeskTapped -> onDeskTapped(event.planeId, event.point, now)
            DeskEvent.GhostTapped -> onGhostTapped(now)
            DeskEvent.ElsewhereTapped -> onElsewhere(now)
            is DeskEvent.BarMoved -> onBarMoved(event.kind, event.y)
            DeskEvent.PrimaryClicked -> onPrimary(now)
            DeskEvent.SecondaryClicked -> onSecondary(now)
            DeskEvent.BackClicked -> onBack(now)
            DeskEvent.MoreClicked -> if (s.phase != Phase.STARTING && s.phase != Phase.BLOCKED) set { it.copy(menuOpen = !it.menuOpen) }
            DeskEvent.MenuDismissed -> set { it.copy(menuOpen = false) }
            is DeskEvent.LanguageSelected -> setSettings(event.language, s.unit)
            is DeskEvent.UnitSelected -> setSettings(s.language, event.unit)
            DeskEvent.RestartClicked -> {
                set { it.copy(menuOpen = false, manualDesk = false) }
                restart(now, clearGhost = true)
            }
            DeskEvent.ExitClicked, DeskEvent.BlockedExitClicked -> {
                DeskLog.i("UI exit")
                _effects.trySend(DeskUiEffect.ExitApp)
            }
            DeskEvent.BlockedRetryClicked -> {
                blockedSinceMs = NEVER
                set { it.copy(phase = Phase.STARTING, blocked = null) }
                if (sceneReadyCount > 0) enterScanning(now)
            }
            is DeskEvent.SnackClosed -> if (s.snack?.id == event.id) set { it.copy(snack = null) }
            is DeskEvent.DebugState -> debugState(event.id, now)
            is DeskEvent.DebugEye -> debugEye(event.metres, now)
            is DeskEvent.DebugTap -> debugTap(event.x, event.z, now)
            is DeskEvent.DebugBar -> debugBar(event.kind, event.metresAboveFloor)
            is DeskEvent.DebugHold -> set { it.copy(hold = event.on) }
            DeskEvent.DebugReset -> {
                repository.clearResults()
                set { it.copy(lastResult = null) }
            }
        }
    }

    /** Once per rendered frame: the timed transitions (frozen while `hold`). */
    fun onFrame(now: Long) {
        val st = s
        // snackbar timeout
        st.snack?.let { snack ->
            if (snack.kind.durationMs != SnackKind.STICKY && now - snack.shownAtMs > snack.kind.durationMs) set { it.copy(snack = null) }
        }
        if (st.hold) return
        when (st.phase) {
            Phase.SCANNING -> {
                if (st.floorY == null) {
                    if (now - scanStartMs > NO_FLOOR_MS && st.snack?.kind != SnackKind.NO_FLOOR) snack(SnackKind.NO_FLOOR, now)
                } else if (st.desk == null && !st.manualDesk && now - scanStartMs > NO_DESK_MS) {
                    enterManual(now, hasBack = false)
                    snack(SnackKind.NO_DESK, now)
                }
            }
            Phase.EYE_LOCKED -> if (now - st.eyeLockedAtMs >= EYE_LOCKED_MS) set { it.copy(phase = Phase.MARK_SCREEN) }
            Phase.UNSTABLE -> if (now - st.samplingStartMs >= UNSTABLE_SHOW_MS) set { it.copy(phase = Phase.SIT) }
            else -> Unit
        }
    }

    // =========================================================================================
    // room
    // =========================================================================================

    private fun onSceneReady(now: Long) {
        sceneReadyCount++
        if (sceneReadyCount > 1) {
            // back from the background (design rules.resume): heights keep, every horizontal position is void
            set { it.copy(pole = null, ghostPole = null, judgement = null, posture = null, manualEdgeTapped = false, desk = null, floorY = null, menuOpen = false) }
        }
        if (s.phase == Phase.BLOCKED) return
        enterScanning(now)
    }

    private fun enterScanning(now: Long) {
        scanStartMs = now
        set { it.copy(phase = Phase.SCANNING, snack = null) }
        snack(SnackKind.SCANNING, now)
        tryLeaveScanning(now)
    }

    private fun onPlanes(event: DeskEvent.PlanesChanged, now: Long) {
        val st = s
        val keep = st.phase.ordinal >= Phase.MARK_SCREEN.ordinal && st.phase != Phase.DESK_MANUAL_A && st.phase != Phase.DESK_MANUAL_B && st.phase != Phase.UNSTABLE && st.phase != Phase.POSTURE
        val desk =
            when {
                st.manualDesk -> st.desk // manual mode ignores every automatic TABLE
                keep -> st.desk // locked from S6 on (the pole must not float)
                else -> event.desk
            }
        set { it.copy(floorY = event.floorY ?: it.floorY, desk = desk) }
        if (st.phase == Phase.SCANNING) tryLeaveScanning(now)
        // S11 reached by timeout: a TABLE that shows up ends it
        if (st.phase == Phase.DESK_MANUAL_A && !st.manualHasBack && event.desk != null && !st.manualDesk) set { it.copy(phase = Phase.SIT, desk = event.desk, deskFlashUntilMs = now + FLASH_MS) }
    }

    private fun tryLeaveScanning(now: Long) {
        val st = s
        if (st.phase != Phase.SCANNING) return
        if (st.floorY != null && st.desk != null) set { it.copy(phase = Phase.SIT, snack = null, deskFlashUntilMs = now + FLASH_MS) }
    }

    private fun onTracking(lost: Boolean, now: Long) {
        if (lost == s.trackingLost) return
        set { it.copy(trackingLost = lost) }
        if (lost) {
            snack(SnackKind.LOST, now)
            if (s.phase == Phase.SAMPLING) set { it.copy(phase = Phase.SIT) }
        } else if (s.snack?.kind == SnackKind.LOST) {
            set { it.copy(snack = null) }
        }
        DeskLog.i("TRACK ${if (lost) "lost" else "recovered"}")
    }

    /** `rules.blocked`: a reason must persist 2 s (the Stage opens as NOT_IN_FULL_SPACE for a moment). */
    private fun onBlock(reason: BlockReason?, now: Long) {
        if (reason == null) {
            blockedSinceMs = NEVER
            return
        }
        if (s.phase == Phase.BLOCKED) return
        if (blockedSinceMs == NEVER) blockedSinceMs = now
        if (now - blockedSinceMs >= BLOCK_PERSIST_MS) {
            DeskLog.i("BLOCKED reason=$reason")
            set { it.copy(phase = Phase.BLOCKED, blocked = reason, menuOpen = false, snack = null) }
        }
    }

    // =========================================================================================
    // the steps
    // =========================================================================================

    private fun onPrimary(now: Long) {
        if (s.menuOpen) return
        when (s.phase) {
            Phase.SIT, Phase.UNSTABLE -> startSampling(now)
            Phase.ALIGN_TOP -> enterForearm()
            Phase.FOREARM -> enterResult()
            Phase.RESULT -> restart(now, clearGhost = false)
            Phase.DESK_MANUAL_A -> set { it.copy(phase = Phase.DESK_MANUAL_B, manualEdgeTapped = false) }
            Phase.DESK_MANUAL_B -> if (s.manualEdgeTapped) set { it.copy(phase = Phase.SIT, deskFlashUntilMs = now + FLASH_MS) }
            Phase.POSTURE -> when (s.posture) {
                PostureKind.STANDING -> restart(now, clearGhost = false) // [重量]
                PostureKind.LOW -> acceptEye(pendingPostureEye ?: return, now) // [继续]
                null -> Unit
            }
            else -> Unit
        }
    }

    private fun onSecondary(now: Long) {
        if (s.menuOpen) return
        when (s.phase) {
            Phase.SIT, Phase.UNSTABLE -> enterManual(now, hasBack = true) // [桌子不对]
            Phase.POSTURE -> when (s.posture) {
                PostureKind.STANDING -> if (rules.keepAllowed(pendingPostureEye ?: return)) acceptEye(pendingPostureEye ?: return, now) // [就是这样]
                PostureKind.LOW -> restart(now, clearGhost = false) // [重量]
                null -> Unit
            }
            else -> Unit
        }
    }

    /** `rules.back`: S6 -> S3, S7 -> S6 (pole down), S8 -> S7, S9/10 -> S8, S11b -> S11a, S11a -> S3 (only from 桌子不对). */
    private fun onBack(now: Long) {
        if (s.menuOpen) return
        when (s.phase) {
            Phase.MARK_SCREEN -> set { it.copy(phase = Phase.SIT, pole = null) }
            Phase.ALIGN_TOP -> set { it.copy(phase = Phase.MARK_SCREEN, pole = null) }
            Phase.FOREARM -> set { it.copy(phase = Phase.ALIGN_TOP) }
            Phase.RESULT -> set { it.copy(phase = Phase.FOREARM, judgement = null) }
            Phase.DESK_MANUAL_B -> set { it.copy(phase = Phase.DESK_MANUAL_A) }
            Phase.DESK_MANUAL_A -> if (s.manualHasBack) set { it.copy(phase = Phase.SIT, manualDesk = false) }
            else -> Unit
        }
    }

    private fun startSampling(now: Long) {
        if (s.floorY == null || s.desk == null || s.trackingLost) return
        set { it.copy(phase = Phase.SAMPLING, samplingStartMs = now, snack = null) }
    }

    private fun onSample(event: DeskEvent.SampleFinished, now: Long) {
        if (s.phase != Phase.SAMPLING) return
        when (val r = event.result) {
            is EyeSampleResult.Unstable -> {
                DeskLog.i("EYE unstable tail_ms=${r.tailMs} n=${r.count}")
                set { it.copy(phase = Phase.UNSTABLE, samplingStartMs = now) }
                snack(SnackKind.UNSTABLE, now)
            }
            is EyeSampleResult.Stable -> {
                DeskLog.i("EYE sampled h=${DeskLog.f(r.eyeH)} head=${DeskLog.pos(r.headXZ)} tail_ms=${r.tailMs} n=${r.count}")
                set { it.copy(headXZ = r.headXZ, headFwd = Vec3(event.forward.x, 0f, event.forward.z).normalized()) }
                val deskH = s.deskH ?: return
                val kind = rules.posture(r.eyeH, deskH)
                if (kind != null) {
                    pendingPostureEye = r.eyeH
                    DeskLog.i("EYE posture=$kind h=${DeskLog.f(r.eyeH)} desk=${DeskLog.f(deskH)}")
                    set { it.copy(phase = Phase.POSTURE, posture = kind, eyeH = r.eyeH) }
                } else {
                    acceptEye(r.eyeH, now)
                }
            }
        }
    }

    private fun acceptEye(eyeH: Float, now: Long) {
        pendingPostureEye = null
        set { it.copy(phase = Phase.EYE_LOCKED, eyeH = eyeH, posture = null, eyeLockedAtMs = now) }
    }

    /** `rules.screenTap`: a tap on a qualifying desk plane (or the manual plane) raises the pole. */
    private fun onDeskTapped(planeId: String?, point: Vec3, now: Long) {
        val st = s
        if (st.menuOpen) {
            set { it.copy(menuOpen = false) }
            return
        }
        when (st.phase) {
            Phase.MARK_SCREEN -> {
                val headXZ = st.headXZ ?: return
                if (st.manualDesk != (planeId == null)) {
                    snack(SnackKind.TAP_DESK, now)
                    return
                }
                if (!rules.tapInRange(point, headXZ)) {
                    DeskLog.i("TAP desk refused reason=range p=${DeskLog.pos(point)}")
                    snack(SnackKind.TAP_UNDER, now)
                    return
                }
                raisePole(point)
            }
            Phase.DESK_MANUAL_B -> {
                // the tapped point gives the desk edge: through it, square to the head's horizontal forward
                val fwd = st.headFwd ?: Vec3.AHEAD
                val along = Vec3(-fwd.z, 0f, fwd.x).normalized()
                val y = st.manualPlateY
                val p = Vec3(point.x, y, point.z)
                val edge = DeskEdge(p - along * 0.6f, p + along * 0.6f, fwd)
                DeskLog.i("DESK manual edge p=${DeskLog.pos(p)} inward=${DeskLog.pos(fwd)}")
                set { it.copy(desk = DeskPick(null, y, edge, emptyList()), manualEdgeTapped = true) }
            }
            else -> Unit
        }
    }

    private fun raisePole(point: Vec3) {
        val st = s
        val deskY = st.desk?.topY ?: point.y
        val p = Vec3(point.x, deskY, point.z)
        DeskLog.i("POLE up p=${DeskLog.pos(p)}")
        set { it.copy(phase = Phase.ALIGN_TOP, pole = p, barY = deskY + JudgeUseCase.BAR_DEFAULT_ABOVE_DESK_M, snack = null) }
    }

    private fun onGhostTapped(now: Long) {
        if (s.menuOpen) {
            set { it.copy(menuOpen = false) }
            return
        }
        val ghost = s.ghostPole ?: return
        if (s.phase == Phase.MARK_SCREEN) raisePole(ghost)
    }

    private fun onElsewhere(now: Long) {
        if (s.menuOpen) {
            set { it.copy(menuOpen = false) }
            return
        }
        if (s.phase == Phase.MARK_SCREEN) snack(SnackKind.TAP_DESK, now)
    }

    private fun onBarMoved(kind: BarKind, y: Float) {
        when (kind) {
            BarKind.SCREEN -> set { it.copy(barY = clampBar(y)) }
            BarKind.FOREARM -> set { it.copy(forearmY = clampForearm(y)) }
            BarKind.MANUAL_PLATE -> set { it.copy(manualPlateY = y) }
        }
        DeskLog.i("BAR $kind y=${DeskLog.f(y)}")
    }

    private fun clampBar(y: Float): Float {
        val deskY = s.desk?.topY ?: return y
        return y.coerceIn(deskY + JudgeUseCase.BAR_MIN_ABOVE_DESK_M, deskY + JudgeUseCase.BAR_MAX_ABOVE_DESK_M)
    }

    private fun clampForearm(y: Float): Float {
        val deskY = s.desk?.topY ?: return y
        return y.coerceIn(deskY - JudgeUseCase.FOREARM_RANGE_M, deskY + JudgeUseCase.FOREARM_RANGE_M)
    }

    /** S8: the forearm bar starts at `rules.forearmDefault`. */
    private fun enterForearm() {
        val st = s
        val floorY = st.floorY ?: return
        val eyeH = st.eyeH ?: return
        val last = st.lastResult
        val drop = if (last != null) last.eyeH - last.forearmH else JudgeUseCase.EYE_TO_ELBOW_M
        val defaultY = clampForearm(floorY + eyeH - drop)
        set { it.copy(phase = Phase.FOREARM, forearmY = defaultY) }
    }

    private fun enterResult() {
        val st = s
        val floorY = st.floorY ?: return
        val eyeH = st.eyeH ?: return
        val desk = st.desk ?: return
        val pole = st.pole ?: return
        val headXZ = st.headXZ ?: return
        val deskH = desk.topY - floorY
        val forearmH = st.forearmY - floorY
        val topH = st.barY - floorY
        val distance = hypot(pole.x - headXZ.x, pole.z - headXZ.z)
        val side = judge.sideAngleDeg(pole, headXZ, desk.edge?.inward)
        val j = judge.judge(eyeH, deskH, forearmH, topH, distance, side, st.unit)
        DeskLog.i(
            "RESULT eye=${DeskLog.f(eyeH)} desk=${DeskLog.f(deskH)} forearm=${DeskLog.f(forearmH)} top=${DeskLog.f(topH)} dist=${DeskLog.f(distance)} side=${side?.let { DeskLog.f(it) }} chair=${j.chair.delta} screen=${j.screenHeight.delta} distDelta=${j.screenDistance.delta} off=${j.offCentre} allGood=${j.allGood}"
        )
        val saved = SavedResult(System.currentTimeMillis(), eyeH, deskH, forearmH, topH, distance, side ?: 0f, j.chair.delta, j.screenHeight.delta, j.screenDistance.delta, j.offCentre)
        viewModelScope.launch { runCatching { repository.saveResult(saved) }.onFailure { DeskLog.e("SAVE failed: $it", it) } }
        set { it.copy(phase = Phase.RESULT, judgement = j, lastResult = saved) }
    }

    /** `rules.recheck` / restart: back to S3; the pole stays as a ghost within one session. */
    private fun restart(now: Long, clearGhost: Boolean) {
        pendingPostureEye = null
        set {
            it.copy(
                phase = Phase.SIT,
                judgement = null,
                posture = null,
                pole = null,
                ghostPole = if (clearGhost) null else (it.pole ?: it.ghostPole),
                snack = null,
                deskFlashUntilMs = now + FLASH_MS,
            )
        }
        if (s.desk == null || s.floorY == null) enterScanning(now)
    }

    private fun enterManual(now: Long, hasBack: Boolean) {
        val floorY = s.floorY ?: return
        DeskLog.i("DESK manual enter hasBack=$hasBack")
        set {
            it.copy(
                phase = Phase.DESK_MANUAL_A,
                manualDesk = true,
                manualHasBack = hasBack,
                manualPlateY = floorY + DeskRulesUseCase.MANUAL_DEFAULT_M,
                manualEdgeTapped = false,
                desk = null,
                pole = null,
                snack = null,
            )
        }
    }

    private fun setSettings(language: LanguagePref, unit: LengthUnit) {
        set { it.copy(language = language, unit = unit) }
        repository.saveSettings(Settings(language, unit))
        DeskLog.i("SETTINGS lang=${language.tag} unit=${unit.tag}")
    }

    private fun snack(kind: SnackKind, now: Long) {
        snackSeq++
        set { it.copy(snack = SnackUi(snackSeq, kind, now)) }
    }

    // =========================================================================================
    // QA hooks (debug builds): jump to any named state with plausible values
    // =========================================================================================

    private fun debugState(id: String, now: Long) {
        val floorY = s.floorY ?: 0f
        val deskH = s.deskH ?: 0.74f
        val desk = s.desk ?: DeskPick(null, floorY + 0.74f, DeskEdge(Vec3(-0.6f, floorY + 0.74f, -0.5f), Vec3(0.6f, floorY + 0.74f, -0.5f), Vec3.AHEAD), emptyList())
        fun withEye(): DeskUiState = s.copy(eyeH = s.eyeH ?: 1.18f, headXZ = s.headXZ ?: Vec3(0f, 0f, 0f), headFwd = s.headFwd ?: Vec3.AHEAD, desk = desk, floorY = floorY)
        fun withPole(st: DeskUiState): DeskUiState = st.copy(pole = st.pole ?: (st.headXZ ?: Vec3.ZERO).let { h -> Vec3(h.x, desk.topY, h.z - 0.7f) }, barY = if (st.pole == null) desk.topY + 0.45f else st.barY)
        // a jump leaves the scanning / lost snackbars behind unless the target state owns one
        if (id.uppercase(Locale.US) !in setOf("S2", "S12", "S15")) set { it.copy(snack = null) }
        when (id.uppercase(Locale.US)) {
            "S1" -> set { it.copy(phase = Phase.STARTING, menuOpen = false, blocked = null) }
            "S2" -> {
                scanStartMs = now
                set { it.copy(phase = Phase.SCANNING, desk = null, menuOpen = false) }
                snack(SnackKind.SCANNING, now)
            }
            "S3" -> set { it.copy(phase = Phase.SIT, desk = desk, floorY = floorY, posture = null, deskFlashUntilMs = now + FLASH_MS, menuOpen = false) }
            "S17" -> set { it.copy(phase = Phase.SIT, desk = desk, floorY = floorY, posture = null, menuOpen = false) }
            "S4" -> set { withEye().copy(phase = Phase.SAMPLING, samplingStartMs = now, hold = true) }
            "S5" -> set { withEye().copy(phase = Phase.EYE_LOCKED, eyeLockedAtMs = now, hold = true) }
            "S6" -> set { withEye().copy(phase = Phase.MARK_SCREEN, pole = null, menuOpen = false) }
            "S7" -> set { withPole(withEye()).copy(phase = Phase.ALIGN_TOP) }
            "S8" -> {
                set { withPole(withEye()) }
                enterForearm()
            }
            "S9" -> {
                set { withPole(withEye()).copy(forearmY = if (it.forearmY == 0f) desk.topY - 0.035f else it.forearmY) }
                enterResult()
            }
            "S10" -> {
                // everything fits: bar inside the band, forearm at keyboard height, pole 70 cm ahead
                set { st ->
                    val e = withEye()
                    val eye = e.eyeH ?: 1.18f
                    val h = e.headXZ ?: Vec3.ZERO
                    e.copy(pole = Vec3(h.x, desk.topY, h.z - 0.7f), barY = floorY + eye - 0.025f, forearmY = desk.topY + 0.025f)
                }
                enterResult()
            }
            "S11" -> set { withEye().copy(phase = Phase.DESK_MANUAL_A, manualDesk = true, manualHasBack = false, manualPlateY = floorY + DeskRulesUseCase.MANUAL_DEFAULT_M, manualEdgeTapped = false, desk = null) }
            "S11B" -> set { withEye().copy(phase = Phase.DESK_MANUAL_B, manualDesk = true, manualPlateY = floorY + DeskRulesUseCase.MANUAL_DEFAULT_M, manualEdgeTapped = false, desk = null) }
            "S12" -> {
                set { it.copy(phase = Phase.UNSTABLE, desk = desk, floorY = floorY, samplingStartMs = now, hold = true) }
                snack(SnackKind.UNSTABLE, now)
            }
            "S13", "S13A" -> {
                pendingPostureEye = 1.62f
                set { it.copy(phase = Phase.POSTURE, posture = PostureKind.STANDING, eyeH = 1.62f, desk = desk, floorY = floorY) }
            }
            "S13B" -> {
                pendingPostureEye = 0.92f
                set { it.copy(phase = Phase.POSTURE, posture = PostureKind.LOW, eyeH = 0.92f, desk = desk, floorY = floorY) }
            }
            "S14" -> set { it.copy(menuOpen = true) }
            "S15" -> onTracking(true, now)
            "S16" -> set { it.copy(phase = Phase.BLOCKED, blocked = BlockReason.WITHOUT_PERMISSION, menuOpen = false) }
            else -> DeskLog.i("CMD state unknown id=$id")
        }
    }

    private fun debugEye(metres: Float, now: Long) {
        val st = s
        set { it.copy(headXZ = it.headXZ ?: Vec3.ZERO, headFwd = it.headFwd ?: Vec3.AHEAD, desk = it.desk ?: DeskPick(null, (it.floorY ?: 0f) + 0.74f, null, emptyList())) }
        val deskH = s.deskH ?: 0.74f
        val kind = rules.posture(metres, deskH)
        if (kind != null) {
            pendingPostureEye = metres
            set { it.copy(phase = Phase.POSTURE, posture = kind, eyeH = metres) }
        } else {
            acceptEye(metres, now)
        }
        if (st.phase == Phase.STARTING || st.phase == Phase.SCANNING) DeskLog.i("CMD eye injected before the room was ready")
    }

    private fun debugTap(x: Float, z: Float, now: Long) {
        val desk = s.desk ?: return
        val base = s.headXZ ?: Vec3.ZERO
        val inward = desk.edge?.inward ?: s.headFwd ?: Vec3.AHEAD
        val right = Vec3(-inward.z, 0f, inward.x)
        val p = Vec3(base.x, desk.topY, base.z) + right * x + inward * z
        if (s.phase != Phase.MARK_SCREEN) set { it.copy(phase = Phase.MARK_SCREEN) }
        onDeskTapped(desk.planeId, p, now)
    }

    private fun debugBar(kind: BarKind, metresAboveFloor: Float) {
        val floorY = s.floorY ?: 0f
        onBarMoved(kind, floorY + metresAboveFloor)
    }

    /** One JSON line for the QA `dump`. */
    fun dumpJson(): String {
        val st = s
        fun f(v: Float?) = v?.let { String.format(Locale.US, "%.4f", it) } ?: "null"
        return "{\"state\":\"${st.stateId}\",\"phase\":\"${st.phase}\",\"eyeH\":${f(st.eyeH)},\"deskH\":${f(st.deskH)},\"barH\":${f(st.floorY?.let { st.barY - it })}," +
            "\"forearmH\":${f(st.floorY?.let { st.forearmY - it })},\"pole\":\"${DeskLog.pos(st.pole)}\",\"headXZ\":\"${DeskLog.pos(st.headXZ)}\",\"menu\":${st.menuOpen},\"lost\":${st.trackingLost}," +
            "\"hold\":${st.hold},\"snack\":\"${st.snack?.kind}\",\"lang\":\"${st.language.tag}\",\"unit\":\"${st.unit.tag}\",\"manual\":${st.manualDesk},\"lastResult\":${st.lastResult != null}," +
            "\"judgement\":${st.judgement?.let { "{\"chair\":${it.chair.delta},\"screen\":${it.screenHeight.delta},\"dist\":${it.screenDistance.delta},\"off\":${it.offCentre},\"allGood\":${it.allGood}}" } ?: "null"}}"
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = DeskViewModel(DeskGraph.repository(context)) as T
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE
        const val NO_FLOOR_MS = 20_000L
        const val NO_DESK_MS = 30_000L
        const val EYE_LOCKED_MS = 1000L
        const val UNSTABLE_SHOW_MS = 600L
        const val BLOCK_PERSIST_MS = 2000L
        const val FLASH_MS = 1000L
    }
}
