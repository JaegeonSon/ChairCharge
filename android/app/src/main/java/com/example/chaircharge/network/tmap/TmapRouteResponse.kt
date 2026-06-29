package com.example.chaircharge.network.tmap

import com.google.gson.JsonElement
import com.google.gson.JsonObject

data class TmapRouteResponse(
    val type: String? = null,
    val features: List<TmapRouteFeature>? = null,
    val properties: JsonObject? = null
)

data class TmapRouteFeature(
    val type: String? = null,
    val geometry: TmapRouteGeometry? = null,
    val properties: JsonObject? = null
)

data class TmapRouteGeometry(
    val type: String? = null,
    val coordinates: JsonElement? = null
)

internal object TmapRouteParser {
    fun parse(response: TmapRouteResponse): PedestrianRouteResult {
        val routePoints = buildList {
            response.features.orEmpty().forEach { feature ->
                val geometry = feature.geometry ?: return@forEach
                if (!geometry.type.equals("LineString", ignoreCase = true)) {
                    return@forEach
                }
                val coordinates = geometry.coordinates
                    ?.takeIf(JsonElement::isJsonArray)
                    ?.asJsonArray
                    ?: return@forEach
                coordinates.forEach { coordinate ->
                    val pair = coordinate
                        .takeIf(JsonElement::isJsonArray)
                        ?.asJsonArray
                        ?: return@forEach
                    if (pair.size() < 2) {
                        return@forEach
                    }
                    val lng = pair[0].finiteDoubleOrNull() ?: return@forEach
                    val lat = pair[1].finiteDoubleOrNull() ?: return@forEach
                    if (lat !in -90.0..90.0 || lng !in -180.0..180.0) {
                        return@forEach
                    }
                    val point = PedestrianRoutePoint(lat = lat, lng = lng)
                    if (lastOrNull() != point) {
                        add(point)
                    }
                }
            }
        }

        require(routePoints.size >= 2) {
            "TMAP 응답에 유효한 LineString 경로 좌표가 없습니다."
        }

        val propertyCandidates = buildList {
            response.properties?.let(::add)
            response.features.orEmpty().mapNotNullTo(this) { it.properties }
        }
        val distance = propertyCandidates.findMetric("totalDistance")
            ?: propertyCandidates.findMetric("distance")
        val duration = propertyCandidates.findMetric("totalTime")
            ?: propertyCandidates.findMetric("time")

        return PedestrianRouteResult(
            routePoints = routePoints,
            totalDistanceM = distance?.takeIf { it >= 0.0 },
            totalDurationS = duration?.takeIf { it >= 0.0 }
        )
    }
}

private fun List<JsonObject>.findMetric(name: String): Double? {
    return firstNotNullOfOrNull { properties ->
        properties.entrySet()
            .firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }
            ?.value
            ?.finiteDoubleOrNull()
    }
}

private fun JsonElement.finiteDoubleOrNull(): Double? {
    if (!isJsonPrimitive) {
        return null
    }
    return runCatching { asDouble }
        .getOrNull()
        ?.takeIf(Double::isFinite)
}
