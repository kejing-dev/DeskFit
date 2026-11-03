package io.embodit.deskfit.platform

import android.content.Context
import io.embodit.deskfit.data.PrefsResultsRepository
import io.embodit.deskfit.data.ResultsRepository
import io.embodit.deskfit.domain.model.LanguagePref
import io.embodit.deskfit.domain.model.Settings
import io.embodit.deskfit.domain.usecase.FormatUseCase
import io.embodit.deskfit.platform.input.ControllerInput
import io.embodit.deskfit.platform.sense.PlaneTracker
import io.embodit.deskfit.platform.tracking.HeadTracker
import java.util.Locale

/** Process-wide wiring of the platform services. The scene starts and stops them (every start has a stop). */
object DeskGraph {
    val planes = PlaneTracker()
    val head = HeadTracker()
    val controller = ControllerInput()

    @Volatile private var repository: ResultsRepository? = null

    fun repository(context: Context): ResultsRepository =
        repository ?: synchronized(this) { repository ?: PrefsResultsRepository(context).also { repository = it } }

    /** First-launch settings: language follows the system, unit by region (en-US: inches). */
    fun defaultSettings(): Settings {
        val locale = Locale.getDefault()
        return Settings(LanguagePref.SYSTEM, FormatUseCase().defaultUnit(locale.language, locale.country))
    }
}
