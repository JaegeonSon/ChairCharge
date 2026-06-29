package com.example.chaircharge.publicdata

import com.example.chaircharge.data.Charger
import com.google.gson.Gson
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityPublicDataTest {
    private val gson = Gson()
    private val assetsDirectory: File by lazy {
        listOf(
            File("src/main/assets"),
            File("app/src/main/assets")
        ).first { it.isDirectory }
    }

    @Test
    fun requiredAssetsParseWithExpectedCounts() {
        val crosswalks = parse(
            "crosswalks_gunsan_app.geojson",
            CrosswalkFeatureCollection::class.java
        )
        val elevation = parse(
            "elevation_slope_gunsan_app.json",
            ElevationSlopeFile::class.java
        )
        val contexts = parse(
            "charger_accessibility_context_gunsan.json",
            ChargerAccessibilityContextFile::class.java
        )

        assertEquals(3_989, crosswalks.features.size)
        assertEquals(20, elevation.items.size)
        assertEquals(20, contexts.items.size)
        assertTrue(contexts.items.all { it.crosswalk != null })
        assertTrue(contexts.items.all {
            !it.elevationSlope?.slopeRisk.isNullOrBlank()
        })
    }

    @Test
    fun contextMatchingUsesIdThenNameAddressThenCoordinate() {
        val contexts = parse(
            "charger_accessibility_context_gunsan.json",
            ChargerAccessibilityContextFile::class.java
        ).items
        val first = contexts.first()

        val idMatch = findChargerAccessibilityContext(
            charger(id = first.chargerId),
            contexts
        )
        assertEquals("charger_id", idMatch?.strategy)

        val nameAddressMatch = findChargerAccessibilityContext(
            charger(
                id = null,
                name = first.name.orEmpty(),
                address = first.address.orEmpty()
            ),
            contexts
        )
        assertEquals("name+address", nameAddressMatch?.strategy)

        val coordinateMatch = findChargerAccessibilityContext(
            charger(
                id = null,
                name = "다른 시설",
                address = "다른 주소",
                lat = first.lat,
                lng = first.lng
            ),
            contexts
        )
        assertEquals("coordinate<=75m", coordinateMatch?.strategy)
        assertNotNull(coordinateMatch)
    }

    @Test
    fun distantUnknownChargerDoesNotMatch() {
        val contexts = parse(
            "charger_accessibility_context_gunsan.json",
            ChargerAccessibilityContextFile::class.java
        ).items

        val match = findChargerAccessibilityContext(
            charger(
                id = "unknown",
                name = "알 수 없는 시설",
                address = "알 수 없는 주소",
                lat = 33.0,
                lng = 127.0
            ),
            contexts
        )

        assertNull(match)
    }

    private fun <T> parse(fileName: String, clazz: Class<T>): T {
        return File(assetsDirectory, fileName)
            .bufferedReader(Charsets.UTF_8)
            .use { gson.fromJson(it, clazz) }
    }

    private fun charger(
        id: String? = "test",
        name: String = "테스트 충전소",
        address: String = "군산시 테스트로 1",
        lat: Double? = 35.9676,
        lng: Double? = 126.7368
    ) = Charger(
        id = id,
        name = name,
        address = address,
        lat = lat,
        lng = lng,
        install_place = null,
        install_type = null,
        is_indoor = null,
        is_outdoor = null,
        is_movable = null,
        contact_phone = null
    )
}
