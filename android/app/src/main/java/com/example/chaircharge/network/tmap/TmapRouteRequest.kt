package com.example.chaircharge.network.tmap

data class TmapRouteRequest(
    val startX: Double,
    val startY: Double,
    val endX: Double,
    val endY: Double,
    val startName: String,
    val endName: String,
    val reqCoordType: String = "WGS84GEO",
    val resCoordType: String = "WGS84GEO",
    val searchOption: String = "30",
    val sort: String = "index",
    val angle: Int = 0,
    val speed: Int = 0
)
