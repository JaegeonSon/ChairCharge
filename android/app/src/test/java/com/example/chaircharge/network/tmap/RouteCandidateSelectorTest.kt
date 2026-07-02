package com.example.chaircharge.network.tmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RouteCandidateSelectorTest {
    @Test
    fun searchOptionsUseRequiredStableOrder() {
        assertEquals(listOf("30", "0", "4", "10"), TMAP_ROUTE_SEARCH_OPTIONS)
    }

    @Test
    fun selectsHighestScoringUsableCandidate() {
        val selection = RouteCandidateSelector.select(
            candidates = listOf(
                candidate(
                    option = "30",
                    distanceM = 1_400.0,
                    durationS = 900.0
                ),
                candidate(
                    option = "0",
                    distanceM = 800.0,
                    durationS = 600.0
                ),
                candidate(
                    option = "4",
                    distanceM = 500.0,
                    durationS = 300.0,
                    pointCount = 1
                )
            ),
            directDistanceM = 700.0,
            destinationSlopeRisk = "낮음"
        )

        assertEquals("0", selection?.selectedRoute?.searchOption)
        assertEquals(2, selection?.candidates?.size)
        assertFalse(selection?.fallbackUsed ?: true)
    }

    @Test
    fun returnsNullWhenEveryCandidateHasInsufficientPoints() {
        val selection = RouteCandidateSelector.select(
            candidates = listOf(
                candidate(
                    option = "30",
                    distanceM = 100.0,
                    durationS = 100.0,
                    pointCount = 1
                )
            ),
            directDistanceM = 100.0,
            destinationSlopeRisk = null
        )

        assertNull(selection)
    }

    private fun candidate(
        option: String,
        distanceM: Double,
        durationS: Double,
        pointCount: Int = 8
    ) = RouteCandidate(
        searchOption = option,
        routePoints = List(pointCount) { index ->
            PedestrianRoutePoint(
                lat = 35.9 + index * 0.0001,
                lng = 126.7 + index * 0.0001
            )
        },
        distanceM = distanceM,
        durationS = durationS
    )
}
