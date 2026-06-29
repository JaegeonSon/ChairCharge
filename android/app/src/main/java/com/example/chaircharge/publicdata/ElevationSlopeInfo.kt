package com.example.chaircharge.publicdata

import com.google.gson.annotations.SerializedName

data class ElevationSlopeInfo(
    @SerializedName("charger_id")
    val chargerId: String? = null,
    val name: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    @SerializedName("elevation_m")
    val elevationM: Double? = null,
    @SerializedName("slope_percent_est")
    val slopePercentEstimate: Double? = null,
    @SerializedName("slope_degree_est")
    val slopeDegreeEstimate: Double? = null,
    @SerializedName("slope_risk")
    val slopeRisk: String? = null
)

internal data class ElevationSlopeFile(
    val items: List<ElevationSlopeInfo> = emptyList()
)
