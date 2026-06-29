package com.example.chaircharge.network

import com.example.chaircharge.data.Charger
import com.example.chaircharge.data.HealthResponse
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Query

interface WheelChargeApi {
    @GET("health")
    fun getHealth(): Call<HealthResponse>

    @GET("chargers")
    fun getChargers(): Call<List<Charger>>

    @GET("nearest")
    fun getNearestChargers(
        @Query("lat") lat: Double,
        @Query("lng") lng: Double,
        @Query("limit") limit: Int = 5
    ): Call<List<Charger>>
}
