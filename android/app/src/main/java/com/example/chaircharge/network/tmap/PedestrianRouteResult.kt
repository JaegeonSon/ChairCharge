package com.example.chaircharge.network.tmap

data class PedestrianRoutePoint(
    val lat: Double,
    val lng: Double
)

data class PedestrianRouteResult(
    val routePoints: List<PedestrianRoutePoint>,
    val totalDistanceM: Double?,
    val totalDurationS: Double?
)
