package com.example.chaircharge.data

import com.google.gson.annotations.JsonAdapter

data class Charger(
    val id: String?,
    val name: String,
    val address: String,
    @field:JsonAdapter(FlexibleDoubleAdapter::class)
    val lat: Double?,
    @field:JsonAdapter(FlexibleDoubleAdapter::class)
    val lng: Double?,
    val install_place: String?,
    val install_type: String?,
    @field:JsonAdapter(FlexibleBooleanAdapter::class)
    val is_indoor: Boolean?,
    @field:JsonAdapter(FlexibleBooleanAdapter::class)
    val is_outdoor: Boolean?,
    @field:JsonAdapter(FlexibleBooleanAdapter::class)
    val is_movable: Boolean?,
    val contact_phone: String?,
    val distance_m: Double? = null
)
