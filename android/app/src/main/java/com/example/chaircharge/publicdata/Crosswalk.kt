package com.example.chaircharge.publicdata

import com.google.gson.annotations.SerializedName

data class Crosswalk(
    val id: String?,
    val lat: Double,
    val lng: Double,
    val pedestrianSignal: Boolean,
    val curbCut: Boolean,
    val tactileBlock: Boolean
)

internal data class CrosswalkFeatureCollection(
    val features: List<CrosswalkFeature> = emptyList()
)

internal data class CrosswalkFeature(
    val properties: CrosswalkProperties? = null,
    val geometry: CrosswalkGeometry? = null
)

internal data class CrosswalkProperties(
    val id: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    @SerializedName("pedestrian_signal")
    val pedestrianSignal: Boolean? = null,
    @SerializedName("curb_cut")
    val curbCut: Boolean? = null,
    @SerializedName("tactile_block")
    val tactileBlock: Boolean? = null
)

internal data class CrosswalkGeometry(
    val coordinates: List<Double?> = emptyList()
)
