package com.example.chaircharge.accessibility

import com.example.chaircharge.data.Charger
import com.example.chaircharge.publicdata.ChargerAccessibilityContext

fun calculateAccessibilityScore(
    charger: Charger,
    distanceM: Double?,
    context: ChargerAccessibilityContext?,
    basisLocationText: String
): AccessibilityScoreResult {
    val validDistance = distanceM?.takeIf { it.isFinite() && it >= 0.0 }

    val distanceScore = when {
        validDistance == null -> 10
        validDistance <= 500.0 -> 30
        validDistance <= 1_000.0 -> 25
        validDistance <= 2_000.0 -> 18
        validDistance <= 3_000.0 -> 10
        else -> 5
    }

    val hasAddress = charger.address.hasUsefulInformation()
    val hasInstallPlace = charger.install_place.hasUsefulInformation()
    val hasContact = charger.contact_phone.hasUsefulInformation()
    val chargerInfoScore =
        (if (hasAddress) 5 else 0) +
            (if (hasInstallPlace) 8 else 0) +
            (if (hasContact) 7 else 0)

    val crosswalk = context?.crosswalk
    val crosswalkScore = if (crosswalk == null) {
        6
    } else {
        val countScore = if ((crosswalk.countWithin150m ?: 0) >= 1) 5 else 0
        val nearestDistanceScore = when {
            crosswalk.nearestDistanceM == null -> 0
            !crosswalk.nearestDistanceM.isFinite() -> 0
            crosswalk.nearestDistanceM < 0.0 -> 0
            crosswalk.nearestDistanceM <= 100.0 -> 5
            crosswalk.nearestDistanceM <= 150.0 -> 4
            crosswalk.nearestDistanceM <= 300.0 -> 2
            else -> 0
        }
        countScore +
            nearestDistanceScore +
            (if ((crosswalk.curbCutCount ?: 0) >= 1) 4 else 0) +
            (if ((crosswalk.tactileBlockCount ?: 0) >= 1) 3 else 0) +
            (if ((crosswalk.pedestrianSignalCount ?: 0) >= 1) 3 else 0)
    }.coerceIn(0, 20)

    val slopeRisk = normalizeSlopeRisk(context?.elevationSlope?.slopeRisk)
    val slopeScore = when (slopeRisk) {
        SlopeRisk.LOW -> 20
        SlopeRisk.MEDIUM -> 12
        SlopeRisk.HIGH -> 4
        SlopeRisk.UNKNOWN -> 8
    }

    val isIndoor = charger.is_indoor == true
    val isMovable = charger.is_movable == true
    val convenienceScore =
        (if (isIndoor) 5 else 0) + (if (isMovable) 5 else 0)

    val breakdown = AccessibilityScoreBreakdown(
        distanceScore = distanceScore,
        chargerInfoScore = chargerInfoScore,
        crosswalkScore = crosswalkScore,
        slopeScore = slopeScore,
        convenienceScore = convenienceScore
    )
    val totalScore = (
        breakdown.distanceScore +
            breakdown.chargerInfoScore +
            breakdown.crosswalkScore +
            breakdown.slopeScore +
            breakdown.convenienceScore
        ).coerceIn(0, 100)
    val grade = when {
        totalScore >= 85 -> "적극 추천"
        totalScore >= 70 -> "이용 추천"
        totalScore >= 55 -> "확인 후 이용"
        else -> "이용 전 주의"
    }

    return AccessibilityScoreResult(
        totalScore = totalScore,
        grade = grade,
        reasons = buildReasons(
            validDistance = validDistance,
            hasAddress = hasAddress,
            hasInstallPlace = hasInstallPlace,
            hasContact = hasContact,
            context = context,
            slopeRisk = slopeRisk,
            isIndoor = isIndoor,
            isMovable = isMovable
        ),
        breakdown = breakdown,
        basisLocationText = basisLocationText,
        distanceM = validDistance
    )
}

private fun buildReasons(
    validDistance: Double?,
    hasAddress: Boolean,
    hasInstallPlace: Boolean,
    hasContact: Boolean,
    context: ChargerAccessibilityContext?,
    slopeRisk: SlopeRisk,
    isIndoor: Boolean,
    isMovable: Boolean
): List<String> {
    val reasons = mutableListOf<String>()
    reasons += when {
        validDistance == null ->
            "거리 정보가 불완전하여 기준 위치 확인이 필요합니다."
        validDistance <= 500.0 ->
            "현재 기준 위치에서 매우 가까운 충전소입니다."
        validDistance <= 1_000.0 ->
            "현재 기준 위치에서 비교적 가까운 충전소입니다."
        validDistance <= 2_000.0 ->
            "이동 가능한 거리의 충전소입니다."
        validDistance <= 3_000.0 ->
            "현재 기준 위치에서 다소 거리가 있는 충전소입니다."
        else ->
            "거리가 다소 멀어 이동 전 확인이 필요합니다."
    }

    val crosswalk = context?.crosswalk
    when {
        crosswalk == null || (crosswalk.countWithin150m ?: 0) <= 0 ->
            reasons += "주변 횡단보도 정보가 부족해 이동 전 경로 확인이 필요합니다."
        crosswalk.nearestDistanceM?.let {
            it.isFinite() && it >= 0.0 && it <= 100.0
        } == true -> reasons +=
            "가까운 횡단보도 정보가 확인되어 도로 횡단 접근성을 참고할 수 있습니다."
        else -> reasons +=
            "주변 횡단보도 정보가 확인되어 도로 횡단 접근성을 참고할 수 있습니다."
    }
    if ((crosswalk?.curbCutCount ?: 0) > 0) {
        reasons +=
            "보도턱 낮춤 정보가 확인되어 전동휠체어 이동 편의성이 높을 수 있습니다."
    } else if (
        (crosswalk?.tactileBlockCount ?: 0) > 0 ||
        (crosswalk?.pedestrianSignalCount ?: 0) > 0
    ) {
        reasons +=
            "점자블록 또는 보행자신호 정보가 확인되어 보행 안전 정보를 함께 참고할 수 있습니다."
    }

    reasons += when (slopeRisk) {
        SlopeRisk.LOW ->
            "경사 위험도가 낮아 전동휠체어 이동 부담이 비교적 적습니다."
        SlopeRisk.MEDIUM ->
            "경사 위험도가 보통 수준이므로 이동 전 주변 경로 확인을 권장합니다."
        SlopeRisk.HIGH ->
            "경사 위험도가 높아 전동휠체어 이동 시 주의가 필요합니다."
        SlopeRisk.UNKNOWN ->
            "경사 정보가 불완전하여 실제 이동 전 확인이 필요합니다."
    }

    when {
        hasInstallPlace ->
            reasons += "설치 위치 설명이 있어 충전소를 찾기 쉽습니다."
        hasContact ->
            reasons += "문의처 정보가 제공되어 이용 전 확인이 가능합니다."
        hasAddress ->
            reasons += "주소 정보가 제공되어 위치 확인이 가능합니다."
    }
    when {
        isIndoor ->
            reasons += "실내 충전소로 날씨 영향을 비교적 적게 받을 수 있습니다."
        isMovable ->
            reasons += "이동식 충전기 정보가 있어 이용 편의성이 높을 수 있습니다."
    }
    return reasons.distinct().take(5)
}

private enum class SlopeRisk {
    LOW,
    MEDIUM,
    HIGH,
    UNKNOWN
}

private fun normalizeSlopeRisk(value: String?): SlopeRisk {
    return when (value?.trim()?.lowercase()) {
        "낮음", "low" -> SlopeRisk.LOW
        "보통", "medium" -> SlopeRisk.MEDIUM
        "높음", "high" -> SlopeRisk.HIGH
        else -> SlopeRisk.UNKNOWN
    }
}

private fun String?.hasUsefulInformation(): Boolean {
    val normalized = this?.trim()?.lowercase().orEmpty()
    return normalized.isNotEmpty() &&
        normalized !in setOf(
            "정보 없음",
            "정보없음",
            "없음",
            "null",
            "-"
        )
}
