package com.example.chaircharge.data

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_METERS = 6_371_000.0

fun haversineDistanceMeters(
    startLat: Double,
    startLng: Double,
    endLat: Double,
    endLng: Double
): Double {
    val startLatRadians = Math.toRadians(startLat)
    val endLatRadians = Math.toRadians(endLat)
    val latitudeDelta = Math.toRadians(endLat - startLat)
    val longitudeDelta = Math.toRadians(endLng - startLng)

    val haversine = sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
        cos(startLatRadians) * cos(endLatRadians) *
        sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
    val angularDistance = 2 * atan2(
        sqrt(haversine.coerceIn(0.0, 1.0)),
        sqrt((1 - haversine).coerceIn(0.0, 1.0))
    )

    return EARTH_RADIUS_METERS * angularDistance
}
