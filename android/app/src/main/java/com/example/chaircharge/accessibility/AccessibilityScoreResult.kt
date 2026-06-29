package com.example.chaircharge.accessibility

data class AccessibilityScoreResult(
    val totalScore: Int,
    val grade: String,
    val reasons: List<String>,
    val breakdown: AccessibilityScoreBreakdown,
    val basisLocationText: String,
    val distanceM: Double?
)
