package com.example.chaircharge.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

private const val LOG_TAG = "WheelCharge"
private const val CHARGERS_ASSET_FILE = "chargers_gunsan_app.json"

class LocalChargerDataSource(
    context: Context,
    private val gson: Gson = Gson()
) {
    private val appContext = context.applicationContext
    private var cachedChargers: List<Charger>? = null

    fun loadChargers(): Result<List<Charger>> {
        cachedChargers?.let { chargers ->
            Log.d(LOG_TAG, "로컬 JSON 캐시 사용. 충전소 개수: ${chargers.size}")
            return Result.success(chargers)
        }

        return runCatching {
            val chargerListType = object : TypeToken<List<Charger>>() {}.type
            val chargers = appContext.assets.open(CHARGERS_ASSET_FILE)
                .bufferedReader(Charsets.UTF_8)
                .use { reader ->
                    gson.fromJson<List<Charger>>(reader, chargerListType)
                }
                ?: error("로컬 JSON 데이터가 비어 있습니다.")

            cachedChargers = chargers
            Log.d(LOG_TAG, "로컬 JSON 로드 성공: $CHARGERS_ASSET_FILE")
            Log.d(LOG_TAG, "로컬 JSON 충전소 개수: ${chargers.size}")
            chargers
        }.onFailure { exception ->
            Log.e(LOG_TAG, "로컬 JSON 로드 실패: $CHARGERS_ASSET_FILE", exception)
        }
    }
}
