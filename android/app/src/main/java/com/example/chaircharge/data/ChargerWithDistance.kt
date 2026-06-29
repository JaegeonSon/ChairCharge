package com.example.chaircharge.data

data class ChargerWithDistance(
    val id: String,
    val name: String,
    val address: String,
    val lat: Double,
    val lng: Double,
    val install_place: String?,
    val install_type: String?,
    val is_indoor: Boolean?,
    val is_outdoor: Boolean?,
    val is_movable: Boolean?,
    val contact_phone: String?,
    val distance_m: Double?
)
