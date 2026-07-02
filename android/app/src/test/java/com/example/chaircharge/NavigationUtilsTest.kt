package com.example.chaircharge

import com.example.chaircharge.ui.map.MapCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationUtilsTest {
    @Test
    fun remainingDistanceUsesMeterAndKilometerFormats() {
        assertEquals("약 420m", formatNavigationDistance(420.0))
        assertEquals("약 1.2km", formatNavigationDistance(1_200.0))
    }

    @Test
    fun estimatedTimeUsesFourKilometersPerHour() {
        assertEquals("1분 미만", calculateNavigationEstimatedTimeText(50.0))
        assertEquals("약 6분", calculateNavigationEstimatedTimeText(399.0))
    }

    @Test
    fun offRouteUsesNearestRoutePointDistance() {
        val routePoint = MapCoordinate(lat = 35.9676, lng = 126.7368)

        assertFalse(
            isLocationOffRoute(
                currentLocation = routePoint,
                routePoints = listOf(routePoint),
                thresholdMeters = 50.0
            )
        )
        assertTrue(
            isLocationOffRoute(
                currentLocation = MapCoordinate(
                    lat = 35.9686,
                    lng = 126.7368
                ),
                routePoints = listOf(routePoint),
                thresholdMeters = 50.0
            )
        )
    }
}
