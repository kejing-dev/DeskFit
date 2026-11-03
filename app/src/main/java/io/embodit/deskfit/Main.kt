package io.embodit.deskfit

import com.pico.spatial.ui.foundation.dsl.DefaultStage
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import io.embodit.deskfit.ui.deskfit.DeskScreen
import io.embodit.deskfit.ui.theme.DeskFitTheme

/**
 * App entry. One Mixed Stage (AndroidManifest: `pico.spatial.stage.style=1`, immersion 0/0/0) holds
 * the whole product (design package: container STAGE_MIXED, window model single_panel_with_popup);
 * there is no WindowContainer and no Shared Space variant.
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultStage {
            DeskFitTheme {
                DeskScreen()
            }
        }
    }
