package io.embodit.deskfit.ui.deskfit.components

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import io.embodit.deskfit.domain.model.LanguagePref
import java.util.Locale

/**
 * Localised resources for the language chosen in the app (follow system / 中文 / English). The
 * language switches at run time without restarting the Stage: the screen provides a Resources object
 * for the chosen locale through this local and every panel reads its copy from it (Spatial Ruler
 * pattern). `LocalContext` is not overridden.
 */
val LocalDeskResources = staticCompositionLocalOf<Resources> { error("LocalDeskResources not provided") }

fun localizedResources(context: Context, language: LanguagePref): Resources {
    val tag =
        when (language) {
            LanguagePref.SYSTEM -> return context.resources
            LanguagePref.ZH -> "zh-CN"
            LanguagePref.EN -> "en"
        }
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocale(Locale.forLanguageTag(tag))
    return context.createConfigurationContext(configuration).resources
}

@Composable
@ReadOnlyComposable
fun rs(@StringRes id: Int): String = LocalDeskResources.current.getString(id)

@Composable
@ReadOnlyComposable
fun rs(@StringRes id: Int, vararg args: Any): String = LocalDeskResources.current.getString(id, *args)
