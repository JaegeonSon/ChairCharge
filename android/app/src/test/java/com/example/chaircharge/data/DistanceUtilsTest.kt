package com.example.chaircharge.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DistanceUtilsTest {
    @Test
    fun sameCoordinateHasZeroDistance() {
        val distance = haversineDistanceMeters(
            startLat = 35.9676,
            startLng = 126.7368,
            endLat = 35.9676,
            endLng = 126.7368
        )

        assertEquals(0.0, distance, 0.001)
    }

    @Test
    fun oneDegreeLatitudeIsApproximately111Kilometers() {
        val distance = haversineDistanceMeters(
            startLat = 0.0,
            startLng = 0.0,
            endLat = 1.0,
            endLng = 0.0
        )

        assertEquals(111_195.0, distance, 200.0)
    }
}
