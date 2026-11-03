package io.embodit.deskfit.domain.usecase

import io.embodit.deskfit.domain.model.LengthUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/** Readings: whole centimetres or whole inches, no sign, no tolerance (design `rules.units`). */
class FormatUseCase {

    /** `118` for 1.18 m in cm; `46` for 1.18 m in inches. */
    fun whole(metres: Float, unit: LengthUnit): String = wholeValue(metres, unit).toString()

    fun wholeValue(metres: Float, unit: LengthUnit): Int =
        if (unit == LengthUnit.CM) (metres * 100f).roundToInt() else (metres / JudgeUseCase.INCH_M).roundToInt()

    /** Unsigned whole units of a delta: `6` for +/-0.06 m. */
    fun delta(metres: Float, unit: LengthUnit): String = whole(abs(metres), unit)

    /** `118 cm` / `46 in`. */
    fun withUnit(metres: Float, unit: LengthUnit): String = "${whole(metres, unit)} ${symbol(unit)}"

    fun symbol(unit: LengthUnit): String = if (unit == LengthUnit.CM) "cm" else "in"

    /** First-run default: inches for en-US, centimetres everywhere else. */
    fun defaultUnit(language: String, country: String): LengthUnit =
        if (language.equals("en", true) && country.equals("US", true)) LengthUnit.IN else LengthUnit.CM
}
