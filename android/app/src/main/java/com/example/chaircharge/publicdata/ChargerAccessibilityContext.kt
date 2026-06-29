package com.example.chaircharge.publicdata

import com.example.chaircharge.data.Charger
import com.example.chaircharge.data.haversineDistanceMeters
import com.google.gson.annotations.SerializedName

data class ChargerAccessibilityContext(
    @SerializedName("charger_id")
    val chargerId: String? = null,
    val name: String? = null,
    val address: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val crosswalk: CrosswalkAccessibilityContext? = null,
    @SerializedName("elevation_slope")
    val elevationSlope: ElevationSlopeContext? = null
)

data class CrosswalkAccessibilityContext(
    @SerializedName("count_within_150m")
    val countWithin150m: Int? = null,
    @SerializedName("nearest_distance_m")
    val nearestDistanceM: Double? = null,
    @SerializedName("pedestrian_signal_count")
    val pedestrianSignalCount: Int? = null,
    @SerializedName("curb_cut_count")
    val curbCutCount: Int? = null,
    @SerializedName("tactile_block_count")
    val tactileBlockCount: Int? = null,
    @SerializedName("raised_crosswalk_count")
    val raisedCrosswalkCount: Int? = null
)

data class ElevationSlopeContext(
    @SerializedName("elevation_m")
    val elevationM: Double? = null,
    @SerializedName("slope_percent_est")
    val slopePercentEstimate: Double? = null,
    @SerializedName("slope_degree_est")
    val slopeDegreeEstimate: Double? = null,
    @SerializedName("slope_risk")
    val slopeRisk: String? = null
)

data class ChargerAccessibilityContextMatch(
    val context: ChargerAccessibilityContext,
    val strategy: String
)

internal data class ChargerAccessibilityContextFile(
    val items: List<ChargerAccessibilityContext> = emptyList()
)

fun findChargerAccessibilityContext(
    charger: Charger,
    contexts: List<ChargerAccessibilityContext>
): ChargerAccessibilityContextMatch? {
    val chargerId = charger.id.normalizedPublicDataKey()
    if (chargerId.isNotEmpty()) {
        contexts.firstOrNull {
            it.chargerId.normalizedPublicDataKey() == chargerId
        }?.let {
            return ChargerAccessibilityContextMatch(it, "charger_id")
        }
    }

    val chargerName = charger.name.normalizedPublicDataKey()
    val chargerAddress = charger.address.normalizedPublicDataKey()
    if (chargerName.isNotEmpty() && chargerAddress.isNotEmpty()) {
        contexts.firstOrNull {
            it.name.normalizedPublicDataKey() == chargerName &&
                it.address.normalizedPublicDataKey() == chargerAddress
        }?.let {
            return ChargerAccessibilityContextMatch(it, "name+address")
        }
    }

    val chargerLat = charger.lat
    val chargerLng = charger.lng
    if (
        chargerLat == null ||
        chargerLng == null ||
        !chargerLat.isFinite() ||
        !chargerLng.isFinite()
    ) {
        return null
    }

    val nearest = contexts.mapNotNull { context ->
        val contextLat = context.lat
        val contextLng = context.lng
        if (
            contextLat == null ||
            contextLng == null ||
            !contextLat.isFinite() ||
            !contextLng.isFinite()
        ) {
            null
        } else {
            context to haversineDistanceMeters(
                startLat = chargerLat,
                startLng = chargerLng,
                endLat = contextLat,
                endLng = contextLng
            )
        }
    }.minByOrNull { it.second }

    return nearest
        ?.takeIf { it.second <= 75.0 }
        ?.let {
            ChargerAccessibilityContextMatch(
                context = it.first,
                strategy = "coordinate<=75m"
            )
        }
}

private fun String?.normalizedPublicDataKey(): String =
    this?.trim()?.lowercase().orEmpty()
