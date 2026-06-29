package com.example.chaircharge.accessibility

data class AccessibilityScoreBreakdown(
    val distanceScore: Int,
    val chargerInfoScore: Int,
    val crosswalkScore: Int,
    val slopeScore: Int,
    val convenienceScore: Int,
    val maxDistanceScore: Int = 30,
    val maxChargerInfoScore: Int = 20,
    val maxCrosswalkScore: Int = 20,
    val maxSlopeScore: Int = 20,
    val maxConvenienceScore: Int = 10
)
