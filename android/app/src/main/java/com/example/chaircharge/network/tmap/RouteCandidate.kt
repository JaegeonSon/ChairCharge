package com.example.chaircharge.network.tmap

import com.example.chaircharge.data.haversineDistanceMeters

data class RouteCandidate(
    val searchOption: String,
    val routePoints: List<PedestrianRoutePoint>,
    val distanceM: Double?,
    val durationS: Double?,
    val score: RouteScoreResult? = null,
    val isFallback: Boolean = false
)

data class RouteScoreResult(
    val totalScore: Double,
    val distanceScore: Double,
    val timeScore: Double,
    val detourScore: Double,
    val optionScore: Double,
    val routeDataScore: Double,
    val slopeReferenceScore: Double
)

data class RouteSelectionResult(
    val selectedRoute: RouteCandidate,
    val candidates: List<RouteCandidate>,
    val fallbackUsed: Boolean
)

internal object RouteCandidateSelector {
    fun select(
        candidates: List<RouteCandidate>,
        directDistanceM: Double?,
        destinationSlopeRisk: String?
    ): RouteSelectionResult? {
        val usableCandidates = candidates.mapNotNull { candidate ->
            if (candidate.routePoints.size < 2) {
                return@mapNotNull null
            }
            val resolvedDistance = candidate.distanceM.validMetric()
                ?: candidate.routePoints.polylineDistanceM()
                    .takeIf { it.isFinite() && it > 0.0 }
            if (resolvedDistance == null) {
                return@mapNotNull null
            }
            candidate.copy(
                distanceM = resolvedDistance,
                durationS = candidate.durationS.validMetric()
            )
        }
        if (usableCandidates.isEmpty()) {
            return null
        }

        val shortestDistance = usableCandidates
            .mapNotNull { it.distanceM }
            .minOrNull()
            ?: return null
        val shortestDuration = usableCandidates
            .mapNotNull { it.durationS }
            .minOrNull()
        val validDirectDistance = directDistanceM.validMetric()
        val scoredCandidates = usableCandidates.map { candidate ->
            val distance = requireNotNull(candidate.distanceM)
            val distanceScore = (
                shortestDistance / distance * 25.0
                ).coerceIn(0.0, 25.0)
            val timeScore = if (
                shortestDuration != null &&
                candidate.durationS != null
            ) {
                (
                    shortestDuration / candidate.durationS * 15.0
                    ).coerceIn(0.0, 15.0)
            } else {
                5.0
            }
            val detourScore = calculateDetourScore(
                routeDistanceM = distance,
                directDistanceM = validDirectDistance
            )
            val optionScore = when (candidate.searchOption) {
                "30" -> 30.0
                "4" -> 27.0
                "0" -> 24.0
                "10" -> 21.0
                else -> 18.0
            }
            val routeDataScore =
                when {
                    candidate.routePoints.size >= 20 -> 6.0
                    candidate.routePoints.size >= 8 -> 5.0
                    candidate.routePoints.size >= 4 -> 3.0
                    else -> 1.0
                } + 2.0 +
                    if (candidate.durationS != null) 2.0 else 0.0
            val slopeReferenceScore = when (
                destinationSlopeRisk?.trim()?.lowercase()
            ) {
                "낮음", "low" -> 5.0
                "보통", "medium" -> 3.0
                "높음", "high" -> 1.0
                else -> 2.5
            }
            val score = RouteScoreResult(
                totalScore = optionScore +
                    distanceScore +
                    timeScore +
                    detourScore +
                    routeDataScore +
                    slopeReferenceScore,
                distanceScore = distanceScore,
                timeScore = timeScore,
                detourScore = detourScore,
                optionScore = optionScore,
                routeDataScore = routeDataScore,
                slopeReferenceScore = slopeReferenceScore
            )
            candidate.copy(score = score)
        }
        val selected = scoredCandidates.sortedWith(
            compareByDescending<RouteCandidate> {
                it.score?.totalScore ?: Double.NEGATIVE_INFINITY
            }
                .thenBy { it.distanceM ?: Double.POSITIVE_INFINITY }
                .thenBy { it.durationS ?: Double.POSITIVE_INFINITY }
        ).first()

        return RouteSelectionResult(
            selectedRoute = selected,
            candidates = scoredCandidates,
            fallbackUsed = false
        )
    }

    private fun calculateDetourScore(
        routeDistanceM: Double,
        directDistanceM: Double?
    ): Double {
        if (directDistanceM == null || directDistanceM <= 0.0) {
            return 7.5
        }
        return when (routeDistanceM / directDistanceM) {
            in 0.0..1.15 -> 15.0
            in 1.15..1.30 -> 12.0
            in 1.30..1.50 -> 8.0
            in 1.50..2.00 -> 4.0
            else -> 0.0
        }
    }
}

private fun Double?.validMetric(): Double? =
    this?.takeIf { it.isFinite() && it > 0.0 }

private fun List<PedestrianRoutePoint>.polylineDistanceM(): Double =
    zipWithNext().sumOf { (start, end) ->
        haversineDistanceMeters(
            startLat = start.lat,
            startLng = start.lng,
            endLat = end.lat,
            endLng = end.lng
        )
    }
