package com.example.chaircharge

import android.app.Application
import android.util.Log
import com.kakao.vectormap.KakaoMapSdk

private const val LOG_TAG = "WheelCharge"

class WheelChargeApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        Log.d(LOG_TAG, "Application onCreate 호출")
        Log.d(LOG_TAG, "Kakao Map SDK dependency 확인: V2 KakaoMapSdk 로드 성공")
        val nativeAppKey = BuildConfig.KAKAO_NATIVE_APP_KEY
        val keyLoaded = nativeAppKey.isNotBlank()
        Log.d(
            LOG_TAG,
            "Kakao Native App Key 존재 여부: $keyLoaded, length=${nativeAppKey.length}"
        )

        if (!keyLoaded) {
            Log.e(
                LOG_TAG,
                "Kakao Map 초기화 실패: local.properties의 " +
                    "KAKAO_NATIVE_APP_KEY가 비어 있습니다."
            )
            return
        }

        try {
            Log.d(LOG_TAG, "Kakao Map SDK init start")
            KakaoMapSdk.init(this, nativeAppKey)
            Log.d(LOG_TAG, "Kakao Map SDK init success")
        } catch (exception: Exception) {
            Log.e(LOG_TAG, "Kakao Map SDK init failed", exception)
        }
    }
}
