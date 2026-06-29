package com.example.chaircharge.network.tmap

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Query

interface TmapApiService {
    @Headers(
        "Accept: application/json",
        "Content-Type: application/json"
    )
    @POST("tmap/routes/pedestrian")
    fun getPedestrianRoute(
        @Query("version") version: Int = 1,
        @Header("appKey") appKey: String,
        @Body request: TmapRouteRequest
    ): Call<TmapRouteResponse>
}
