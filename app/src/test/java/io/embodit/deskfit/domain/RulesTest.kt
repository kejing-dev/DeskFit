package io.embodit.deskfit.domain

import io.embodit.deskfit.domain.model.LengthUnit
import io.embodit.deskfit.domain.model.PlaneSurface
import io.embodit.deskfit.domain.model.PostureKind
import io.embodit.deskfit.domain.model.SurfaceKind
import io.embodit.deskfit.domain.model.Vec3
import io.embodit.deskfit.domain.usecase.DeskRulesUseCase
import io.embodit.deskfit.domain.usecase.EyeSample
import io.embodit.deskfit.domain.usecase.EyeSampleResult
import io.embodit.deskfit.domain.usecase.EyeSampleUseCase
import io.embodit.deskfit.domain.usecase.FormatUseCase
import io.embodit.deskfit.domain.usecase.JudgeUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JudgeUseCaseTest {
    private val judge = JudgeUseCase()

    @Test
    fun chairAndScreenNeedAdjusting() {
        // desk 74, forearm 71 -> chair need = 74 + 2.5 - 71 = +5.5 -> raise 6 (cm); eye after = 118 + 6 = 124
        val j = judge.judge(eyeH = 1.18f, deskH = 0.74f, forearmH = 0.71f, topH = 1.14f, distance = 0.70f, sideDeg = 10f, unit = LengthUnit.CM)
        assertFalse(j.chair.ok)
        assertEquals(0.06f, j.chair.delta, 1e-4f)
        assertEquals(1.24f, j.eyeAfter, 1e-4f)
        // target = 124 - 2 = 122, top 114 -> raise 8
        assertFalse(j.screenHeight.ok)
        assertEquals(0.08f, j.screenHeight.delta, 1e-4f)
        assertTrue(j.screenDistance.ok)
        assertFalse(j.offCentre)
        assertFalse(j.allGood)
    }

    @Test
    fun everythingFits() {
        val j = judge.judge(eyeH = 1.18f, deskH = 0.74f, forearmH = 0.765f, topH = 1.155f, distance = 0.70f, sideDeg = 5f, unit = LengthUnit.CM)
        assertTrue(j.chair.ok)
        assertTrue(j.screenHeight.ok)
        assertTrue(j.screenDistance.ok)
        assertTrue(j.allGood)
        assertEquals(1.18f, j.eyeAfter, 1e-4f)
    }

    @Test
    fun distanceVerdicts() {
        val near = judge.judge(1.18f, 0.74f, 0.765f, 1.155f, distance = 0.40f, sideDeg = null, unit = LengthUnit.CM)
        assertFalse(near.screenDistance.ok)
        assertEquals(0.15f, near.screenDistance.delta, 1e-4f) // push to 55
        val far = judge.judge(1.18f, 0.74f, 0.765f, 1.155f, distance = 1.20f, sideDeg = null, unit = LengthUnit.CM)
        assertEquals(-0.25f, far.screenDistance.delta, 1e-4f) // pull to 95
        assertFalse(far.offCentre)
    }

    @Test
    fun offCentreAndSideAngle() {
        val angle = judge.sideAngleDeg(pole = Vec3(0.7f, 0.74f, -0.7f), headXZ = Vec3(0f, 0f, 0f), inward = Vec3(0f, 0f, -1f))
        assertNotNull(angle)
        assertEquals(45f, angle!!, 0.01f)
        val j = judge.judge(1.18f, 0.74f, 0.765f, 1.155f, 0.99f, angle, LengthUnit.CM)
        assertTrue(j.offCentre)
        assertFalse(j.allGood)
        assertNull(judge.sideAngleDeg(Vec3(0.7f, 0.74f, -0.7f), Vec3.ZERO, null))
    }

    @Test
    fun inchesRoundToWholeInches() {
        val j = judge.judge(1.18f, 0.74f, 0.71f, 1.14f, 0.70f, 0f, LengthUnit.IN)
        assertEquals(2 * JudgeUseCase.INCH_M, j.chair.delta, 1e-4f) // 5.5 cm = 2.17 in -> 2 in
    }

    @Test
    fun formatting() {
        val f = FormatUseCase()
        assertEquals("118 cm", f.withUnit(1.18f, LengthUnit.CM))
        assertEquals("46 in", f.withUnit(1.18f, LengthUnit.IN))
        assertEquals("6", f.delta(-0.06f, LengthUnit.CM))
        assertEquals(LengthUnit.IN, f.defaultUnit("en", "US"))
        assertEquals(LengthUnit.CM, f.defaultUnit("zh", "CN"))
    }
}

class EyeSampleUseCaseTest {
    private val sampler = EyeSampleUseCase()

    private fun steady(n: Int, y: Float, floor: Float = 0f, stepMs: Long = 50): List<EyeSample> =
        List(n) { EyeSample(it * stepMs, Vec3(0f, y, 0f), floor) }

    @Test
    fun stableTailGivesEyeHeight() {
        val r = sampler.evaluate(steady(28, 1.18f))
        assertTrue(r is EyeSampleResult.Stable)
        assertEquals(1.18f, (r as EyeSampleResult.Stable).eyeH, 1e-4f)
    }

    @Test
    fun originAndLowSamplesAreDropped() {
        val junk = listOf(EyeSample(0, Vec3.ZERO, 0f), EyeSample(10, Vec3(0f, 0.1f, 0f), 0f))
        val r = sampler.evaluate(junk + steady(28, 1.18f).map { it.copy(tMs = it.tMs + 100) })
        assertTrue(r is EyeSampleResult.Stable)
    }

    @Test
    fun movingHeadIsUnstable() {
        val moving = List(28) { EyeSample(it * 50L, Vec3(0f, 1.18f + it * 0.01f, 0f), 0f) }
        assertTrue(sampler.evaluate(moving) is EyeSampleResult.Unstable)
        // too few samples (emulator idle fps)
        assertTrue(sampler.evaluate(steady(5, 1.18f, stepMs = 200)) is EyeSampleResult.Unstable)
    }

    @Test
    fun tailIsMeasuredFromTheLastSample() {
        val r = sampler.evaluate(List(10) { EyeSample(it * 50L, Vec3(0f, 1.40f, 0f), 0f) } + List(14) { EyeSample(500 + it * 50L, Vec3(0f, 1.18f, 0f), 0f) })
        assertTrue(r is EyeSampleResult.Stable)
        assertEquals(1.18f, (r as EyeSampleResult.Stable).eyeH, 1e-4f)
    }
}

class DeskRulesUseCaseTest {
    private val rules = DeskRulesUseCase()

    private fun quad(kind: SurfaceKind, y: Float, x0: Float, x1: Float, z0: Float, z1: Float, id: String = kind.name) =
        PlaneSurface(id, kind, kind.name, listOf(Vec3(x0, y, z0), Vec3(x1, y, z0), Vec3(x1, y, z1), Vec3(x0, y, z1)), listOf(0, 1, 2, 0, 2, 3))

    @Test
    fun picksTheNearDeskAndItsNearEdge() {
        val floor = quad(SurfaceKind.FLOOR, 0f, -3f, 3f, -3f, 3f)
        val desk = quad(SurfaceKind.TABLE, 0.74f, -0.6f, 0.6f, -1.3f, -0.5f)
        val farDesk = quad(SurfaceKind.TABLE, 0.74f, 2f, 3f, 2f, 3f, id = "far")
        val shelf = quad(SurfaceKind.TABLE, 1.4f, -0.6f, 0.6f, -1.3f, -0.5f, id = "shelf")
        val head = Vec3(0f, 1.2f, 0f)
        val floorY = rules.floorY(listOf(floor, desk), head)
        assertEquals(0f, floorY!!, 1e-5f)
        val pick = rules.pickDesk(listOf(floor, desk, farDesk, shelf), floorY, head)
        assertNotNull(pick)
        assertEquals("TABLE", pick!!.planeId)
        assertEquals(0.74f, pick.topY, 1e-5f)
        val edge = pick.edge!!
        assertEquals(-0.5f, edge.a.z, 1e-5f)
        assertEquals(-0.5f, edge.b.z, 1e-5f)
        assertEquals(-1f, edge.inward.z, 1e-5f)
    }

    @Test
    fun postureRanges() {
        assertNull(rules.posture(1.18f, 0.74f))
        assertEquals(PostureKind.STANDING, rules.posture(1.62f, 0.74f))
        assertEquals(PostureKind.STANDING, rules.posture(1.45f, 0.70f))
        assertEquals(PostureKind.LOW, rules.posture(0.92f, 0.74f))
        assertEquals(PostureKind.LOW, rules.posture(1.10f, 0.95f))
        assertTrue(rules.keepAllowed(1.62f))
        assertFalse(rules.keepAllowed(1.75f))
    }

    @Test
    fun tapRange() {
        val head = Vec3(0f, 0f, 0f)
        assertTrue(rules.tapInRange(Vec3(0f, 0.74f, -0.7f), head))
        assertFalse(rules.tapInRange(Vec3(0f, 0.74f, -0.2f), head))
        assertFalse(rules.tapInRange(Vec3(0f, 0.74f, -1.6f), head))
    }
}
