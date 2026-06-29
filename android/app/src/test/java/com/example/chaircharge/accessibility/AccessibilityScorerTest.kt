package com.example.chaircharge.accessibility

import com.example.chaircharge.data.Charger
import com.example.chaircharge.publicdata.ChargerAccessibilityContext
import com.example.chaircharge.publicdata.CrosswalkAccessibilityContext
import com.example.chaircharge.publicdata.ElevationSlopeContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityScorerTest {
    @Test
    fun completeNearbyChargerUsesUnified100PointScale() {
        val result = calculateAccessibilityScore(
            charger = charger(
                installPlace = "1층 안내데스크 옆",
                contactPhone = "063-123-4567",
                isIndoor = true,
                isMovable = true
            ),
            distanceM = 400.0,
            context = context(
                nearestCrosswalkM = 80.0,
                slopeRisk = "낮음"
            ),
            basisLocationText = "현재 위치"
        )

        assertEquals(100, result.totalScore)
        assertEquals("적극 추천", result.grade)
        assertEquals(30, result.breakdown.distanceScore)
        assertEquals(20, result.breakdown.chargerInfoScore)
        assertEquals(20, result.breakdown.crosswalkScore)
        assertEquals(20, result.breakdown.slopeScore)
        assertEquals(10, result.breakdown.convenienceScore)
        assertEquals("현재 위치", result.basisLocationText)
        assertTrue(result.reasons.size in 3..5)
    }

    @Test
    fun distanceRulesUseRequiredThresholdsAndMissingDefault() {
        assertEquals(30, scoreAt(500.0).breakdown.distanceScore)
        assertEquals(25, scoreAt(1_000.0).breakdown.distanceScore)
        assertEquals(18, scoreAt(2_000.0).breakdown.distanceScore)
        assertEquals(10, scoreAt(3_000.0).breakdown.distanceScore)
        assertEquals(5, scoreAt(3_001.0).breakdown.distanceScore)
        assertEquals(10, scoreAt(null).breakdown.distanceScore)
    }

    @Test
    fun informationCompletenessTreatsPlaceholderValuesAsMissing() {
        val result = calculateAccessibilityScore(
            charger = charger(
                address = "-",
                installPlace = "정보 없음",
                contactPhone = "없음"
            ),
            distanceM = null,
            context = null,
            basisLocationText = "군산시청 인근 테스트 위치"
        )

        assertEquals(0, result.breakdown.chargerInfoScore)
        assertEquals(6, result.breakdown.crosswalkScore)
        assertEquals(8, result.breakdown.slopeScore)
        assertEquals(24, result.totalScore)
        assertEquals("이용 전 주의", result.grade)
    }

    @Test
    fun crosswalkNearestDistanceScoreDoesNotAccumulateThresholds() {
        val result = calculateAccessibilityScore(
            charger = charger(),
            distanceM = 4_000.0,
            context = context(
                nearestCrosswalkM = 100.0,
                curbCutCount = 0,
                tactileBlockCount = 0,
                pedestrianSignalCount = 0,
                slopeRisk = "높음"
            ),
            basisLocationText = "현재 위치"
        )

        assertEquals(10, result.breakdown.crosswalkScore)
        assertEquals(4, result.breakdown.slopeScore)
    }

    @Test
    fun crosswalkDistanceUsesExclusiveScoreBands() {
        assertEquals(10, crosswalkScoreAt(100.0))
        assertEquals(9, crosswalkScoreAt(150.0))
        assertEquals(7, crosswalkScoreAt(300.0))
        assertEquals(5, crosswalkScoreAt(301.0))
    }

    @Test
    fun unifiedScoreEqualsBreakdownSum() {
        val result = calculateAccessibilityScore(
            charger = charger(),
            distanceM = 4_000.0,
            context = context(slopeRisk = "높음"),
            basisLocationText = "테스트 위치"
        )

        assertEquals(34, result.totalScore)
        assertEquals(
            result.totalScore,
            result.breakdown.distanceScore +
                result.breakdown.chargerInfoScore +
                result.breakdown.crosswalkScore +
                result.breakdown.slopeScore +
                result.breakdown.convenienceScore
        )
    }

    @Test
    fun slopeRiskSupportsKoreanAndEnglishValues() {
        assertEquals(20, scoreWithSlope("LOW").breakdown.slopeScore)
        assertEquals(12, scoreWithSlope("medium").breakdown.slopeScore)
        assertEquals(4, scoreWithSlope("높음").breakdown.slopeScore)
        assertEquals(8, scoreWithSlope("정보 없음").breakdown.slopeScore)
    }

    @Test
    fun gradesUseFourRequiredBands() {
        assertEquals("적극 추천", gradeFor(85))
        assertEquals("이용 추천", gradeFor(70))
        assertEquals("확인 후 이용", gradeFor(55))
        assertEquals("이용 전 주의", gradeFor(54))
    }

    private fun gradeFor(target: Int): String {
        val result = when (target) {
            85 -> calculateAccessibilityScore(
                charger = charger(
                    installPlace = "위치",
                    contactPhone = "전화",
                    isIndoor = true
                ),
                distanceM = 1_000.0,
                context = context(
                    nearestCrosswalkM = 150.0,
                    curbCutCount = 0,
                    tactileBlockCount = 1,
                    pedestrianSignalCount = 1,
                    slopeRisk = "낮음"
                ),
                basisLocationText = "테스트"
            )
            70 -> calculateAccessibilityScore(
                charger = charger(
                    installPlace = "위치",
                    contactPhone = "전화"
                ),
                distanceM = 2_000.0,
                context = context(slopeRisk = "보통"),
                basisLocationText = "테스트"
            )
            55 -> calculateAccessibilityScore(
                charger = charger(),
                distanceM = 500.0,
                context = context(
                    count = 0,
                    nearestCrosswalkM = 301.0,
                    curbCutCount = 0,
                    tactileBlockCount = 0,
                    pedestrianSignalCount = 0,
                    slopeRisk = "낮음"
                ),
                basisLocationText = "테스트"
            )
            else -> calculateAccessibilityScore(
                charger = charger(
                    installPlace = "위치",
                    contactPhone = "전화"
                ),
                distanceM = 3_001.0,
                context = context(
                    count = 1,
                    nearestCrosswalkM = 100.0,
                    curbCutCount = 4,
                    tactileBlockCount = 1,
                    pedestrianSignalCount = 0,
                    slopeRisk = "보통"
                ),
                basisLocationText = "테스트"
            )
        }
        assertEquals(target, result.totalScore)
        return result.grade
    }

    private fun scoreAt(distanceM: Double?) =
        calculateAccessibilityScore(
            charger = charger(),
            distanceM = distanceM,
            context = null,
            basisLocationText = "테스트 위치"
        )

    private fun scoreWithSlope(slopeRisk: String) =
        calculateAccessibilityScore(
            charger = charger(),
            distanceM = 4_000.0,
            context = context(slopeRisk = slopeRisk),
            basisLocationText = "테스트 위치"
        )

    private fun crosswalkScoreAt(nearestDistanceM: Double): Int {
        return calculateAccessibilityScore(
            charger = charger(),
            distanceM = 4_000.0,
            context = context(
                nearestCrosswalkM = nearestDistanceM,
                curbCutCount = 0,
                tactileBlockCount = 0,
                pedestrianSignalCount = 0
            ),
            basisLocationText = "테스트 위치"
        ).breakdown.crosswalkScore
    }

    private fun context(
        count: Int = 1,
        nearestCrosswalkM: Double = 80.0,
        curbCutCount: Int = 1,
        tactileBlockCount: Int = 1,
        pedestrianSignalCount: Int = 1,
        slopeRisk: String = "낮음"
    ) = ChargerAccessibilityContext(
        chargerId = "test",
        crosswalk = CrosswalkAccessibilityContext(
            countWithin150m = count,
            nearestDistanceM = nearestCrosswalkM,
            curbCutCount = curbCutCount,
            tactileBlockCount = tactileBlockCount,
            pedestrianSignalCount = pedestrianSignalCount
        ),
        elevationSlope = ElevationSlopeContext(slopeRisk = slopeRisk)
    )

    private fun charger(
        address: String = "군산시 테스트로 1",
        installPlace: String? = null,
        contactPhone: String? = null,
        isIndoor: Boolean? = null,
        isMovable: Boolean? = null
    ) = Charger(
        id = "test",
        name = "테스트 충전소",
        address = address,
        lat = 35.9676,
        lng = 126.7368,
        install_place = installPlace,
        install_type = null,
        is_indoor = isIndoor,
        is_outdoor = null,
        is_movable = isMovable,
        contact_phone = contactPhone
    )
}
