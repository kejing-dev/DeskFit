package io.embodit.deskfit.ui.deskfit

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.deskfit.platform.DeskLog
import io.embodit.deskfit.ui.deskfit.components.DeskProbe
import io.embodit.deskfit.ui.deskfit.components.DeskStage
import io.embodit.deskfit.ui.deskfit.components.LocalDeskResources
import io.embodit.deskfit.ui.deskfit.components.localizedResources
import io.embodit.deskfit.ui.deskfit.scene.DeskFrameSystem
import io.embodit.deskfit.ui.deskfit.scene.DeskScene

/**
 * The one screen of the app, living in a Mixed Stage: wires the ViewModel (state machine), the
 * [DeskScene] (entities, taps, drags, per-frame work) and [DeskStage] (the panel pool).
 */
@Composable
fun DeskScreen() {
    val context = LocalContext.current
    val viewModel: DeskViewModel = viewModel(factory = DeskViewModel.Factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current

    remember(context) {
        DeskProbe.enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        true
    }
    val scene = remember(viewModel) { DeskScene(viewModel::onEvent, viewModel::onFrame) }

    DisposableEffect(scene) {
        registerSystem<DeskFrameSystem>()
        DeskProbe.host =
            object : DeskProbe.Host {
                override fun dispatch(event: DeskEvent) = viewModel.onEvent(event)

                override fun uiState(): DeskUiState = viewModel.state.value

                override fun dumpJson(): String = viewModel.dumpJson()

                override fun scene(): DeskProbe.SceneProbe = scene
            }
        DeskLog.i("STAGE enter")
        onDispose {
            DeskProbe.host = null
            unregisterSystem<DeskFrameSystem>()
            scene.detach()
            DeskLog.i("STAGE disposed")
        }
    }

    LaunchedEffect(scene, state) { scene.render(state) }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                DeskUiEffect.ExitApp -> {
                    DeskLog.i("STAGE close requested")
                    runCatching { navigator.closeStage() }.onFailure { DeskLog.e("STAGE close failed: $it", it) }
                }
            }
        }
    }

    val resources = remember(state.language, context) { localizedResources(context, state.language) }
    CompositionLocalProvider(LocalDeskResources provides resources) {
        DeskStage(state = state, scene = scene, onEvent = viewModel::onEvent)
    }
}
