package com.example.chaircharge.data

import android.content.Context
import android.util.Log
import com.example.chaircharge.network.RetrofitClient
import com.example.chaircharge.network.WheelChargeApi
import retrofit2.Call
import retrofit2.Callback
import retrofit2.HttpException
import retrofit2.Response

private const val REPOSITORY_LOG_TAG = "WheelCharge"

enum class ChargerDataOrigin {
    SERVER,
    LOCAL_JSON
}

data class ChargerLoadResult(
    val chargers: List<Charger>,
    val origin: ChargerDataOrigin,
    val fallbackUsed: Boolean
)

class ChargerRepository(
    context: Context,
    private val api: WheelChargeApi = RetrofitClient.api,
    private val localDataSource: LocalChargerDataSource = LocalChargerDataSource(context)
) {
    fun loadAllChargers(
        onResult: (Result<ChargerLoadResult>) -> Unit
    ) {
        Log.d(REPOSITORY_LOG_TAG, "서버 API 호출 시도: GET /chargers")

        api.getChargers().enqueue(object : Callback<List<Charger>> {
            override fun onResponse(
                call: Call<List<Charger>>,
                response: Response<List<Charger>>
            ) {
                if (response.isSuccessful) {
                    val chargers = response.body().orEmpty()
                    Log.d(
                        REPOSITORY_LOG_TAG,
                        "서버 API 성공: GET /chargers, 응답 개수: ${chargers.size}"
                    )
                    Log.d(REPOSITORY_LOG_TAG, "fallback 사용 여부: false")
                    onResult(
                        Result.success(
                            ChargerLoadResult(
                                chargers = chargers,
                                origin = ChargerDataOrigin.SERVER,
                                fallbackUsed = false
                            )
                        )
                    )
                } else {
                    val error = HttpException(response)
                    Log.e(REPOSITORY_LOG_TAG, "서버 API 실패: GET /chargers", error)
                    loadLocalFallback(
                        serverError = error,
                        transform = { it },
                        onResult = onResult
                    )
                }
            }

            override fun onFailure(call: Call<List<Charger>>, t: Throwable) {
                Log.e(REPOSITORY_LOG_TAG, "서버 API 실패: GET /chargers", t)
                loadLocalFallback(
                    serverError = t,
                    transform = { it },
                    onResult = onResult
                )
            }
        })
    }

    fun loadNearestChargers(
        lat: Double,
        lng: Double,
        limit: Int,
        onResult: (Result<ChargerLoadResult>) -> Unit
    ) {
        Log.d(
            REPOSITORY_LOG_TAG,
            "서버 API 호출 시도: GET /nearest?lat=$lat&lng=$lng&limit=$limit"
        )

        api.getNearestChargers(lat = lat, lng = lng, limit = limit)
            .enqueue(object : Callback<List<Charger>> {
                override fun onResponse(
                    call: Call<List<Charger>>,
                    response: Response<List<Charger>>
                ) {
                    if (response.isSuccessful) {
                        val chargers = response.body().orEmpty()
                        Log.d(
                            REPOSITORY_LOG_TAG,
                            "서버 API 성공: GET /nearest, 응답 개수: ${chargers.size}"
                        )
                        Log.d(REPOSITORY_LOG_TAG, "fallback 사용 여부: false")
                        onResult(
                            Result.success(
                                ChargerLoadResult(
                                    chargers = chargers,
                                    origin = ChargerDataOrigin.SERVER,
                                    fallbackUsed = false
                                )
                            )
                        )
                    } else {
                        val error = HttpException(response)
                        Log.e(REPOSITORY_LOG_TAG, "서버 API 실패: GET /nearest", error)
                        loadLocalFallback(
                            serverError = error,
                            transform = { chargers ->
                                calculateNearestChargers(
                                    chargers = chargers,
                                    lat = lat,
                                    lng = lng,
                                    limit = limit
                                )
                            },
                            onResult = onResult
                        )
                    }
                }

                override fun onFailure(call: Call<List<Charger>>, t: Throwable) {
                    Log.e(REPOSITORY_LOG_TAG, "서버 API 실패: GET /nearest", t)
                    loadLocalFallback(
                        serverError = t,
                        transform = { chargers ->
                            calculateNearestChargers(
                                chargers = chargers,
                                lat = lat,
                                lng = lng,
                                limit = limit
                            )
                        },
                        onResult = onResult
                    )
                }
            })
    }

    private fun loadLocalFallback(
        serverError: Throwable,
        transform: (List<Charger>) -> List<Charger>,
        onResult: (Result<ChargerLoadResult>) -> Unit
    ) {
        Log.w(
            REPOSITORY_LOG_TAG,
            "fallback 사용 여부: true - 서버 연결 실패로 로컬 JSON 사용",
            serverError
        )

        localDataSource.loadChargers().fold(
            onSuccess = { localChargers ->
                val chargers = transform(localChargers)
                Log.d(
                    REPOSITORY_LOG_TAG,
                    "로컬 fallback 성공. 결과 개수: ${chargers.size}"
                )
                onResult(
                    Result.success(
                        ChargerLoadResult(
                            chargers = chargers,
                            origin = ChargerDataOrigin.LOCAL_JSON,
                            fallbackUsed = true
                        )
                    )
                )
            },
            onFailure = { localError ->
                localError.addSuppressed(serverError)
                Log.e(
                    REPOSITORY_LOG_TAG,
                    "서버 및 로컬 JSON 데이터 로드 모두 실패",
                    localError
                )
                onResult(Result.failure(localError))
            }
        )
    }

    private fun calculateNearestChargers(
        chargers: List<Charger>,
        lat: Double,
        lng: Double,
        limit: Int
    ): List<Charger> {
        val nearestChargers = chargers
            .mapNotNull { charger ->
                val chargerLat = charger.lat
                val chargerLng = charger.lng
                if (!isValidCoordinate(chargerLat, chargerLng)) {
                    Log.w(
                        REPOSITORY_LOG_TAG,
                        "로컬 거리 계산 제외: id=${charger.id}, " +
                            "lat=$chargerLat, lng=$chargerLng"
                    )
                    return@mapNotNull null
                }
                val validLat = requireNotNull(chargerLat)
                val validLng = requireNotNull(chargerLng)

                charger.copy(
                    distance_m = haversineDistanceMeters(
                        startLat = lat,
                        startLng = lng,
                        endLat = validLat,
                        endLng = validLng
                    )
                )
            }
            .sortedBy { it.distance_m }
            .take(limit)

        Log.d(
            REPOSITORY_LOG_TAG,
            "로컬 거리 계산 결과 개수: ${nearestChargers.size}"
        )
        return nearestChargers
    }

    private fun isValidCoordinate(lat: Double?, lng: Double?): Boolean =
        lat != null && lng != null &&
            lat.isFinite() && lng.isFinite() &&
            lat in -90.0..90.0 && lng in -180.0..180.0

}
