package com.example.chaircharge.network.tmap

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TmapRouteParserTest {
    private val gson = Gson()

    @Test
    fun parsesLineStringAsLatLngAndReadsTotalMetrics() {
        val response = gson.fromJson(
            """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "type": "Feature",
                  "properties": {"totalDistance": 850, "totalTime": "720"},
                  "geometry": {
                    "type": "Point",
                    "coordinates": [126.70, 35.90]
                  }
                },
                {
                  "type": "Feature",
                  "properties": {},
                  "geometry": {
                    "type": "LineString",
                    "coordinates": [
                      [126.701, 35.901],
                      [126.702, 35.902],
                      [126.703, 35.903]
                    ]
                  }
                }
              ]
            }
            """.trimIndent(),
            TmapRouteResponse::class.java
        )

        val result = TmapRouteParser.parse(response)

        assertEquals(3, result.routePoints.size)
        assertEquals(35.901, result.routePoints.first().lat, 0.000001)
        assertEquals(126.701, result.routePoints.first().lng, 0.000001)
        assertEquals(850.0, result.totalDistanceM ?: -1.0, 0.001)
        assertEquals(720.0, result.totalDurationS ?: -1.0, 0.001)
    }

    @Test
    fun fallsBackToDistanceAndTimeProperties() {
        val response = gson.fromJson(
            """
            {
              "properties": {"distance": "1200.5", "time": 900},
              "features": [
                {
                  "geometry": {
                    "type": "LineString",
                    "coordinates": [[126.7, 35.9], [126.8, 36.0]]
                  }
                }
              ]
            }
            """.trimIndent(),
            TmapRouteResponse::class.java
        )

        val result = TmapRouteParser.parse(response)

        assertEquals(1200.5, result.totalDistanceM ?: -1.0, 0.001)
        assertEquals(900.0, result.totalDurationS ?: -1.0, 0.001)
    }

    @Test
    fun rejectsResponseWithoutTwoValidRoutePoints() {
        val response = gson.fromJson(
            """
            {
              "features": [
                {
                  "geometry": {
                    "type": "Point",
                    "coordinates": [126.7, 35.9]
                  }
                }
              ]
            }
            """.trimIndent(),
            TmapRouteResponse::class.java
        )

        assertThrows(IllegalArgumentException::class.java) {
            TmapRouteParser.parse(response)
        }
    }

    @Test
    fun requestDefaultsUsePedestrianWgs84AndStairAvoidance() {
        val request = TmapRouteRequest(
            startX = 126.7,
            startY = 35.9,
            endX = 126.8,
            endY = 36.0,
            startName = "start",
            endName = "destination",
        )

        assertEquals("WGS84GEO", request.reqCoordType)
        assertEquals("WGS84GEO", request.resCoordType)
        assertEquals("30", request.searchOption)
        assertEquals("index", request.sort)
    }
}
