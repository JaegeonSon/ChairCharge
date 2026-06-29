package com.example.chaircharge.publicdata

import android.content.Context
import android.util.Log
import com.google.gson.Gson

private const val LOG_TAG = "WheelCharge"
private const val CROSSWALKS_ASSET = "crosswalks_gunsan_app.geojson"
private const val ELEVATION_SLOPE_ASSET = "elevation_slope_gunsan_app.json"
private const val CONTEXT_ASSET = "charger_accessibility_context_gunsan.json"

data class AccessibilityPublicDataBundle(
    val crosswalks: List<Crosswalk>,
    val elevationSlopeItems: List<ElevationSlopeInfo>,
    val chargerContexts: List<ChargerAccessibilityContext>
)

class AccessibilityPublicDataSource(
    context: Context,
    private val gson: Gson = Gson()
) {
    private val appContext = context.applicationContext
    private var cachedBundle: AccessibilityPublicDataBundle? = null

    fun loadAll(): AccessibilityPublicDataBundle {
        cachedBundle?.let { return it }

        Log.d(LOG_TAG, "접근성 공공데이터 assets 로드 시작")
        val bundle = AccessibilityPublicDataBundle(
            crosswalks = loadCrosswalks(),
            elevationSlopeItems = loadElevationSlopeItems(),
            chargerContexts = loadChargerContexts()
        )
        cachedBundle = bundle
        Log.d(
            LOG_TAG,
            "보행자전용도로는 UI/점수 미반영, README에만 기록했는지 여부: true"
        )
        return bundle
    }

    private fun loadCrosswalks(): List<Crosswalk> {
        return runCatching {
            val file = readAsset(
                assetName = CROSSWALKS_ASSET,
                clazz = CrosswalkFeatureCollection::class.java
            )
            file.features.mapNotNull { feature ->
                val properties = feature.properties
                val lat = properties?.lat
                    ?: feature.geometry?.coordinates?.getOrNull(1)
                val lng = properties?.lng
                    ?: feature.geometry?.coordinates?.getOrNull(0)
                if (
                    lat == null ||
                    lng == null ||
                    !lat.isFinite() ||
                    !lng.isFinite() ||
                    lat !in -90.0..90.0 ||
                    lng !in -180.0..180.0
                ) {
                    null
                } else {
                    Crosswalk(
                        id = properties?.id,
                        lat = lat,
                        lng = lng,
                        pedestrianSignal =
                            properties?.pedestrianSignal == true,
                        curbCut = properties?.curbCut == true,
                        tactileBlock = properties?.tactileBlock == true
                    )
                }
            }
        }.onSuccess { items ->
            Log.d(LOG_TAG, "횡단보도 데이터 로드 성공 및 개수: ${items.size}")
        }.onFailure { exception ->
            Log.e(LOG_TAG, "횡단보도 데이터 로드 실패", exception)
        }.getOrElse { emptyList() }
    }

    private fun loadElevationSlopeItems(): List<ElevationSlopeInfo> {
        return runCatching {
            readAsset(
                assetName = ELEVATION_SLOPE_ASSET,
                clazz = ElevationSlopeFile::class.java
            ).items
        }.onSuccess { items ->
            Log.d(
                LOG_TAG,
                "고도/경사도 데이터 로드 성공 및 개수: ${items.size}"
            )
        }.onFailure { exception ->
            Log.e(LOG_TAG, "고도/경사도 데이터 로드 실패", exception)
        }.getOrElse { emptyList() }
    }

    private fun loadChargerContexts(): List<ChargerAccessibilityContext> {
        return runCatching {
            readAsset(
                assetName = CONTEXT_ASSET,
                clazz = ChargerAccessibilityContextFile::class.java
            ).items
        }.onSuccess { items ->
            Log.d(
                LOG_TAG,
                "charger_accessibility_context 로드 성공 및 개수: " +
                    items.size
            )
        }.onFailure { exception ->
            Log.e(
                LOG_TAG,
                "charger_accessibility_context 로드 실패",
                exception
            )
        }.getOrElse { emptyList() }
    }

    private fun <T> readAsset(assetName: String, clazz: Class<T>): T {
        return appContext.assets.open(assetName)
            .bufferedReader(Charsets.UTF_8)
            .use { reader -> gson.fromJson(reader, clazz) }
            ?: error("assets 데이터가 비어 있습니다: $assetName")
    }
}
