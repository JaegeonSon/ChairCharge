package com.example.chaircharge.network.tmap

import android.util.Log
import com.example.chaircharge.BuildConfig
import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Callback
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

private const val LOG_TAG = "WheelCharge"
private const val TMAP_BASE_URL = "https://apis.openapi.sk.com/"
internal val TMAP_ROUTE_SEARCH_OPTIONS = listOf("30", "0", "4", "10")

class TmapRouteRepository(
    private val api: TmapApiService = TmapRetrofitClient.api,
    private val appKey: String = BuildConfig.TMAP_APP_KEY
) {
    fun requestBestPedestrianRoute(
        request: TmapRouteRequest,
        directDistanceM: Double?,
        destinationSlopeRisk: String?,
        onResult: (Result<RouteSelectionResult>) -> Unit
    ) {
        Log.d(LOG_TAG, "Route candidate request start")
        val candidates = mutableListOf<RouteCandidate>()

        fun requestNext(optionIndex: Int) {
            if (optionIndex >= TMAP_ROUTE_SEARCH_OPTIONS.size) {
                Log.d(LOG_TAG, "Route candidate count: ${candidates.size}")
                val selection = runCatching {
                    RouteCandidateSelector.select(
                        candidates = candidates,
                        directDistanceM = directDistanceM,
                        destinationSlopeRisk = destinationSlopeRisk
                    )
                }.onFailure { exception ->
                    Log.e(LOG_TAG, "Route candidate selection failed", exception)
                }.getOrNull()
                if (selection == null) {
                    onResult(
                        Result.failure(
                            IllegalStateException(
                                "No usable TMAP route candidates"
                            )
                        )
                    )
                    return
                }

                selection.candidates.forEach { candidate ->
                    Log.d(
                        LOG_TAG,
                        "Route candidate score: option=${candidate.searchOption}, " +
                            "score=${candidate.score?.totalScore}"
                    )
                }
                val selected = selection.selectedRoute
                Log.d(
                    LOG_TAG,
                    "Selected route option: ${selected.searchOption}"
                )
                Log.d(
                    LOG_TAG,
                    "Selected route distance: ${selected.distanceM}"
                )
                Log.d(
                    LOG_TAG,
                    "Selected route duration: ${selected.durationS}"
                )
                Log.d(
                    LOG_TAG,
                    "Selected route points count: ${selected.routePoints.size}"
                )
                onResult(Result.success(selection))
                return
            }

            val option = TMAP_ROUTE_SEARCH_OPTIONS[optionIndex]
            Log.d(LOG_TAG, "Route candidate option: $option")
            requestPedestrianRoute(
                request = request.copy(searchOption = option)
            ) { result ->
                result.fold(
                    onSuccess = { route ->
                        if (route.routePoints.size >= 2) {
                            candidates += RouteCandidate(
                                searchOption = option,
                                routePoints = route.routePoints,
                                distanceM = route.totalDistanceM,
                                durationS = route.totalDurationS
                            )
                            Log.d(
                                LOG_TAG,
                                "Route candidate success: option=$option"
                            )
                        } else {
                            Log.w(
                                LOG_TAG,
                                "Route candidate failed: option=$option, " +
                                    "insufficient points"
                            )
                        }
                    },
                    onFailure = { exception ->
                        Log.w(
                            LOG_TAG,
                            "Route candidate failed: option=$option",
                            exception
                        )
                    }
                )
                requestNext(optionIndex + 1)
            }
        }

        requestNext(0)
    }

    fun requestPedestrianRoute(
        request: TmapRouteRequest,
        onResult: (Result<PedestrianRouteResult>) -> Unit
    ) {
        val normalizedKey = appKey.trim()
        val appKeyExists = normalizedKey.isNotEmpty() &&
            !normalizedKey.equals("YOUR_TMAP_APP_KEY", ignoreCase = true) &&
            !normalizedKey.contains("TMAP_APP_KEY_", ignoreCase = true)
        Log.d(LOG_TAG, "TMAP route request start")
        Log.d(LOG_TAG, "TMAP appKey exists: $appKeyExists")
        Log.d(LOG_TAG, "start lat/lng: ${request.startY}, ${request.startX}")
        Log.d(LOG_TAG, "end lat/lng: ${request.endY}, ${request.endX}")
        Log.d(LOG_TAG, "searchOption: ${request.searchOption}")

        if (!appKeyExists) {
            val exception = IllegalStateException(
                "TMAP_APP_KEY가 local.properties에 설정되지 않았습니다."
            )
            Log.e(LOG_TAG, "TMAP route failed", exception)
            onResult(Result.failure(exception))
            return
        }

        api.getPedestrianRoute(
            appKey = normalizedKey,
            request = request
        ).enqueue(object : Callback<TmapRouteResponse> {
            override fun onResponse(
                call: Call<TmapRouteResponse>,
                response: Response<TmapRouteResponse>
            ) {
                Log.d(LOG_TAG, "TMAP HTTP response code: ${response.code()}")
                if (!response.isSuccessful) {
                    val exception = HttpException(response)
                    Log.e(LOG_TAG, "TMAP route failed", exception)
                    onResult(Result.failure(exception))
                    return
                }

                val body = response.body()
                if (body == null) {
                    val exception = IllegalStateException("TMAP 응답 body가 없습니다.")
                    Log.e(LOG_TAG, "TMAP route failed", exception)
                    onResult(Result.failure(exception))
                    return
                }

                runCatching { TmapRouteParser.parse(body) }
                    .onSuccess { result ->
                        Log.d(
                            LOG_TAG,
                            "routePoints count: ${result.routePoints.size}"
                        )
                        Log.d(LOG_TAG, "parsed distance: ${result.totalDistanceM}")
                        Log.d(LOG_TAG, "parsed duration: ${result.totalDurationS}")
                        Log.d(LOG_TAG, "TMAP route success")
                        onResult(Result.success(result))
                    }
                    .onFailure { exception ->
                        Log.e(LOG_TAG, "TMAP route failed", exception)
                        onResult(Result.failure(exception))
                    }
            }

            override fun onFailure(
                call: Call<TmapRouteResponse>,
                throwable: Throwable
            ) {
                Log.e(LOG_TAG, "TMAP route failed", throwable)
                onResult(Result.failure(throwable))
            }
        })
    }
}

private object TmapRetrofitClient {
    private val okHttpClient = OkHttpClient.Builder().build()

    val api: TmapApiService = Retrofit.Builder()
        .baseUrl(TMAP_BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(TmapApiService::class.java)
}
