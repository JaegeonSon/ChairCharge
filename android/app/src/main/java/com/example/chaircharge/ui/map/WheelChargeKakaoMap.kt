package com.example.chaircharge.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.chaircharge.R
import com.example.chaircharge.data.Charger
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.GestureType
import com.kakao.vectormap.LatLng
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.MapView
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.label.LabelOptions
import com.kakao.vectormap.label.LabelStyle
import com.kakao.vectormap.label.LabelStyles
import com.kakao.vectormap.route.RouteLineOptions
import com.kakao.vectormap.route.RouteLineSegment
import com.kakao.vectormap.route.RouteLineStyle
import com.kakao.vectormap.route.RouteLineStyles
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

private const val LOG_TAG = "WheelCharge"
private const val DEFAULT_ZOOM_LEVEL = 14
private const val CHARGER_MARKER_WIDTH_DP = 40
private const val CHARGER_MARKER_HEIGHT_DP = 52
private const val SELECTED_MARKER_WIDTH_DP = 48
private const val SELECTED_MARKER_HEIGHT_DP = 62
private const val MY_LOCATION_DOT_SIZE_DP = 24

private data class ChargerMarkerTag(
    val charger: Charger,
    val index: Int
)

private data object MyLocationMarkerTag

data class MapCoordinate(
    val lat: Double,
    val lng: Double
)

data class MapCameraRequest(
    val requestId: Long,
    val target: MapCoordinate,
    val zoomLevel: Int
)

enum class RouteLineType {
    TMAP_PEDESTRIAN,
    FALLBACK_STRAIGHT
}

data class RouteLineRequest(
    val requestId: Long,
    val points: List<MapCoordinate>,
    val type: RouteLineType
)

@Composable
fun WheelChargeKakaoMap(
    chargers: List<Charger>,
    center: MapCoordinate,
    myLocation: MapCoordinate?,
    selectedChargerIndex: Int?,
    cameraRequest: MapCameraRequest?,
    routeLineRequest: RouteLineRequest?,
    modifier: Modifier = Modifier,
    onMapViewCreated: () -> Unit,
    onMapViewSizeChanged: (width: Int, height: Int) -> Unit,
    onMapReady: () -> Unit,
    onMapError: (Throwable) -> Unit,
    onMarkerError: (Throwable) -> Unit,
    onMarkersDisplayed: (Int) -> Unit,
    onMyLocationDisplayed: (Boolean) -> Unit,
    onMyLocationError: (Throwable) -> Unit,
    onCameraMoveResult: (succeeded: Boolean, error: Throwable?) -> Unit,
    onSelectionMarkerApplied: (Boolean) -> Unit,
    onRouteLineResult: (succeeded: Boolean, error: Throwable?) -> Unit,
    onRouteCameraMoveResult: (succeeded: Boolean, error: Throwable?) -> Unit,
    onChargerMarkerClick: (charger: Charger, index: Int) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val mapView = remember(context) { MapView(context) }
    val mapStarted = remember { AtomicBoolean(false) }
    var kakaoMap by remember { mutableStateOf<KakaoMap?>(null) }

    val currentCenter by rememberUpdatedState(center)
    val currentOnMapViewCreated by rememberUpdatedState(onMapViewCreated)
    val currentOnMapViewSizeChanged by rememberUpdatedState(onMapViewSizeChanged)
    val currentOnMapReady by rememberUpdatedState(onMapReady)
    val currentOnMapError by rememberUpdatedState(onMapError)
    val currentOnMarkerError by rememberUpdatedState(onMarkerError)
    val currentOnMarkersDisplayed by rememberUpdatedState(onMarkersDisplayed)
    val currentOnMyLocationDisplayed by rememberUpdatedState(onMyLocationDisplayed)
    val currentOnMyLocationError by rememberUpdatedState(onMyLocationError)
    val currentOnCameraMoveResult by rememberUpdatedState(onCameraMoveResult)
    val currentOnSelectionMarkerApplied by rememberUpdatedState(
        onSelectionMarkerApplied
    )
    val currentOnRouteLineResult by rememberUpdatedState(onRouteLineResult)
    val currentOnRouteCameraMoveResult by rememberUpdatedState(
        onRouteCameraMoveResult
    )
    val currentOnChargerMarkerClick by rememberUpdatedState(onChargerMarkerClick)

    fun startMapView(view: MapView) {
        if (!mapStarted.compareAndSet(false, true)) {
            return
        }

        try {
            Log.d(LOG_TAG, "Kakao MapView start 호출")
            view.start(
                object : MapLifeCycleCallback() {
                    override fun onMapDestroy() {
                        Log.d(LOG_TAG, "Kakao Map 종료")
                    }

                    override fun onMapError(error: Exception) {
                        Log.e(LOG_TAG, "Kakao Map 준비 실패", error)
                        currentOnMapError(error)
                    }
                },
                object : KakaoMapReadyCallback() {
                    override fun onMapReady(map: KakaoMap) {
                        kakaoMap = map
                        val initialCenter = currentCenter
                        Log.d(LOG_TAG, "Kakao Map 준비 성공")
                        Log.d(
                            LOG_TAG,
                            "지도 초기 카메라 좌표: " +
                                "lat=${initialCenter.lat}, lng=${initialCenter.lng}"
                        )

                        try {
                            listOf(
                                GestureType.Pan,
                                GestureType.Zoom,
                                GestureType.Rotate,
                                GestureType.Tilt,
                                GestureType.RotateZoom,
                                GestureType.OneFingerZoom,
                                GestureType.OneFingerDoubleTap,
                                GestureType.TwoFingerSingleTap,
                                GestureType.LongTapAndDrag
                            ).forEach { gestureType ->
                                map.setGestureEnable(gestureType, true)
                            }
                            Log.d(
                                LOG_TAG,
                                "지도 조작 가능 상태: true - pan/zoom/drag 활성화"
                            )
                        } catch (exception: Exception) {
                            Log.e(
                                LOG_TAG,
                                "지도 gesture 활성화 실패",
                                exception
                            )
                        }

                        try {
                            map.setOnLabelClickListener { _, _, label ->
                                Log.d(
                                    LOG_TAG,
                                    "마커 클릭 감지: labelId=${label.labelId}"
                                )
                                when (val tag = label.tag) {
                                    is ChargerMarkerTag -> {
                                        Log.d(
                                            LOG_TAG,
                                            "클릭한 마커 식별값: " +
                                                "chargerId=${tag.charger.id}, " +
                                                "index=${tag.index}"
                                        )
                                        currentOnChargerMarkerClick(
                                            tag.charger,
                                            tag.index
                                        )
                                        true
                                    }
                                    MyLocationMarkerTag -> {
                                        Log.d(
                                            LOG_TAG,
                                            "현재 위치 파란색 점 클릭: 상세 패널 미표시"
                                        )
                                        false
                                    }
                                    else -> false
                                }
                            }
                        } catch (exception: Exception) {
                            Log.e(
                                LOG_TAG,
                                "충전소 마커 클릭 리스너 등록 실패",
                                exception
                            )
                            currentOnMarkerError(exception)
                        }

                        currentOnMapReady()
                    }

                    override fun getPosition(): LatLng {
                        val initialCenter = currentCenter
                        return LatLng.from(initialCenter.lat, initialCenter.lng)
                    }

                    override fun getZoomLevel(): Int = DEFAULT_ZOOM_LEVEL
                }
            )

            if (
                activity?.lifecycle?.currentState
                    ?.isAtLeast(Lifecycle.State.RESUMED) == true
            ) {
                view.resume()
            }
        } catch (exception: Exception) {
            Log.e(LOG_TAG, "Kakao MapView 초기화 실패", exception)
            currentOnMapError(exception)
        }
    }

    DisposableEffect(mapView, activity) {
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (!mapStarted.get()) {
                return@LifecycleEventObserver
            }

            try {
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        Log.d(LOG_TAG, "Kakao MapView resume")
                        mapView.resume()
                    }
                    Lifecycle.Event.ON_PAUSE -> {
                        Log.d(LOG_TAG, "Kakao MapView pause")
                        mapView.pause()
                    }
                    Lifecycle.Event.ON_DESTROY -> {
                        Log.d(LOG_TAG, "Kakao MapView finish")
                        mapView.finish()
                    }
                    else -> Unit
                }
            } catch (exception: Exception) {
                Log.e(LOG_TAG, "Kakao MapView lifecycle 처리 실패", exception)
            }
        }

        activity?.lifecycle?.addObserver(lifecycleObserver)

        onDispose {
            activity?.lifecycle?.removeObserver(lifecycleObserver)
            if (mapStarted.get()) {
                try {
                    mapView.pause()
                    mapView.finish()
                } catch (exception: Exception) {
                    Log.e(LOG_TAG, "Kakao MapView 종료 실패", exception)
                }
            }
        }
    }

    LaunchedEffect(kakaoMap, center) {
        val map = kakaoMap ?: return@LaunchedEffect

        try {
            Log.d(
                LOG_TAG,
                "지도 카메라 이동: lat=${center.lat}, lng=${center.lng}"
            )
            map.moveCamera(
                CameraUpdateFactory.newCenterPosition(
                    LatLng.from(center.lat, center.lng)
                )
            )
        } catch (exception: Exception) {
            Log.e(LOG_TAG, "지도 카메라 이동 실패", exception)
            currentOnMapError(exception)
        }
    }

    LaunchedEffect(kakaoMap, cameraRequest) {
        val map = kakaoMap ?: return@LaunchedEffect
        val request = cameraRequest ?: return@LaunchedEffect

        try {
            check(isValidMapCoordinate(request.target.lat, request.target.lng)) {
                "지도 카메라 이동 좌표가 유효하지 않습니다."
            }
            Log.d(
                LOG_TAG,
                "지도 카메라 이동 시도: requestId=${request.requestId}, " +
                    "lat=${request.target.lat}, lng=${request.target.lng}, " +
                    "zoom=${request.zoomLevel}"
            )
            map.moveCamera(
                CameraUpdateFactory.newCenterPosition(
                    LatLng.from(request.target.lat, request.target.lng),
                    request.zoomLevel
                )
            )
            Log.d(
                LOG_TAG,
                "지도 카메라 이동 성공: requestId=${request.requestId}"
            )
            currentOnCameraMoveResult(true, null)
        } catch (exception: Exception) {
            Log.e(
                LOG_TAG,
                "지도 카메라 이동 실패: requestId=${request.requestId}",
                exception
            )
            currentOnCameraMoveResult(false, exception)
        }
    }

    LaunchedEffect(kakaoMap, routeLineRequest) {
        val map = kakaoMap ?: return@LaunchedEffect
        val request = routeLineRequest

        try {
            val routeLineManager = map.routeLineManager
                ?: error("Kakao Map RouteLineManager를 가져오지 못했습니다.")
            val routeLineLayer = routeLineManager.layer
                ?: error("Kakao Map 기본 RouteLineLayer를 가져오지 못했습니다.")
            val previousRouteCount = routeLineLayer.routeLineCount
            routeLineLayer.removeAll()
            Log.d(
                LOG_TAG,
                "기존 Polyline 제거 여부: ${previousRouteCount > 0}, " +
                    "count=$previousRouteCount"
            )

            if (request == null) {
                if (previousRouteCount > 0) {
                    Log.d(LOG_TAG, "route polyline cleared")
                }
                return@LaunchedEffect
            }

            check(request.points.size >= 2) {
                "Polyline 경로 좌표가 2개 미만입니다."
            }
            check(request.points.all {
                isValidMapCoordinate(it.lat, it.lng)
            }) {
                "Polyline 경로 좌표가 유효하지 않습니다."
            }

            val routePoints = request.points.map { point ->
                LatLng.from(point.lat, point.lng)
            }
            Log.d(
                LOG_TAG,
                "Polyline 생성 시도: requestId=${request.requestId}, " +
                    "type=${request.type}, points=${routePoints.size}"
            )
            val lineColor = when (request.type) {
                RouteLineType.TMAP_PEDESTRIAN -> Color.rgb(30, 64, 175)
                RouteLineType.FALLBACK_STRAIGHT -> Color.rgb(96, 165, 250)
            }
            val lineWidth = when (request.type) {
                RouteLineType.TMAP_PEDESTRIAN -> 12f
                RouteLineType.FALLBACK_STRAIGHT -> 8f
            }
            val routeStyle = RouteLineStyle.from(
                lineWidth,
                lineColor,
                3f,
                Color.WHITE
            )
            val routeStyles = RouteLineStyles.from(routeStyle)
            val routeSegment = RouteLineSegment.from(
                routePoints,
                routeStyles
            )
            routeLineLayer.addRouteLine(
                RouteLineOptions.from(
                    "selected_charger_route_${request.type.name.lowercase()}",
                    routeSegment
                )
            )
            Log.d(
                LOG_TAG,
                "route polyline rendered: requestId=${request.requestId}, " +
                    "type=${request.type}, points=${routePoints.size}"
            )
            currentOnRouteLineResult(true, null)

            try {
                Log.d(
                    LOG_TAG,
                    "경로 지도 카메라 이동 시도: requestId=${request.requestId}"
                )
                map.moveCamera(
                    CameraUpdateFactory.fitMapPoints(
                        routePoints.toTypedArray(),
                        120
                    )
                )
                Log.d(
                    LOG_TAG,
                    "경로 지도 카메라 이동 성공: requestId=${request.requestId}"
                )
                currentOnRouteCameraMoveResult(true, null)
            } catch (exception: Exception) {
                Log.e(
                    LOG_TAG,
                    "경로 지도 카메라 이동 실패: requestId=${request.requestId}",
                    exception
                )
                currentOnRouteCameraMoveResult(false, exception)
            }
        } catch (exception: Exception) {
            Log.e(LOG_TAG, "Polyline 생성 실패", exception)
            if (request != null) {
                currentOnRouteLineResult(false, exception)
            }
        }
    }

    LaunchedEffect(
        kakaoMap,
        chargers,
        myLocation,
        selectedChargerIndex
    ) {
        val map = kakaoMap ?: return@LaunchedEffect
        val labelManager = try {
            map.labelManager
                ?: error("Kakao Map LabelManager를 가져오지 못했습니다.")
        } catch (exception: Exception) {
            Log.e(LOG_TAG, "Kakao Map LabelManager 준비 실패", exception)
            currentOnMarkerError(exception)
            currentOnMyLocationError(exception)
            return@LaunchedEffect
        }
        val labelLayer = try {
            labelManager.layer
                ?: error("Kakao Map 기본 LabelLayer를 가져오지 못했습니다.")
        } catch (exception: Exception) {
            Log.e(LOG_TAG, "Kakao Map LabelLayer 준비 실패", exception)
            currentOnMarkerError(exception)
            currentOnMyLocationError(exception)
            return@LaunchedEffect
        }

        labelLayer.removeAll()
        labelLayer.setClickable(true)

        var displayedChargerCount = 0
        var selectedMarkerApplied = false
        try {
            Log.d(LOG_TAG, "충전소 마커 표시 시도: ${chargers.size}개")
            val chargerMarkerBitmap = drawableToBitmap(
                context = context,
                drawableResId = R.drawable.ic_charger_marker_blue,
                widthDp = CHARGER_MARKER_WIDTH_DP,
                heightDp = CHARGER_MARKER_HEIGHT_DP
            )
            Log.d(LOG_TAG, "진한 블루 충전소 마커 아이콘 로드 성공")
            val chargerMarkerStyles = labelManager.addLabelStyles(
                LabelStyles.from(LabelStyle.from(chargerMarkerBitmap))
            )
            val selectedMarkerBitmap = drawableToBitmap(
                context = context,
                drawableResId = R.drawable.ic_charger_marker_selected,
                widthDp = SELECTED_MARKER_WIDTH_DP,
                heightDp = SELECTED_MARKER_HEIGHT_DP
            )
            val selectedMarkerStyles = labelManager.addLabelStyles(
                LabelStyles.from(LabelStyle.from(selectedMarkerBitmap))
            )

            chargers.forEachIndexed { index, charger ->
                val chargerLat = charger.lat
                val chargerLng = charger.lng
                if (!isValidMapCoordinate(chargerLat, chargerLng)) {
                    Log.w(
                        LOG_TAG,
                        "좌표 오류로 충전소 마커 제외: id=${charger.id}, " +
                            "index=$index, lat=$chargerLat, lng=$chargerLng"
                    )
                    return@forEachIndexed
                }
                val validLat = requireNotNull(chargerLat)
                val validLng = requireNotNull(chargerLng)
                val isSelected = index == selectedChargerIndex
                labelLayer.addLabel(
                    LabelOptions.from(
                        "charger_${charger.id}_$index",
                        LatLng.from(validLat, validLng)
                    )
                        .setStyles(
                            if (isSelected) {
                                selectedMarkerStyles
                            } else {
                                chargerMarkerStyles
                            }
                        )
                        .setClickable(true)
                        .setTag(
                            ChargerMarkerTag(
                                charger = charger,
                                index = index
                            )
                        )
                )
                displayedChargerCount += 1
                if (isSelected) {
                    selectedMarkerApplied = true
                    Log.d(
                        LOG_TAG,
                        "선택 마커 색상/아이콘 적용: 진한 블루 핀 + 하늘색 외곽, " +
                            "id=${charger.id}, index=$index"
                    )
                }
            }

            Log.d(
                LOG_TAG,
                "빨간색 충전소 마커 표시 개수: $displayedChargerCount"
            )
            currentOnMarkersDisplayed(displayedChargerCount)
            if (selectedChargerIndex != null) {
                if (!selectedMarkerApplied) {
                    Log.w(
                        LOG_TAG,
                        "선택 마커 강조 적용 여부: false, " +
                            "index=$selectedChargerIndex"
                    )
                }
                currentOnSelectionMarkerApplied(selectedMarkerApplied)
            }
        } catch (exception: Exception) {
            Log.e(LOG_TAG, "빨간색 충전소 마커 아이콘 로드 또는 표시 실패", exception)
            currentOnMarkersDisplayed(displayedChargerCount)
            if (selectedChargerIndex != null) {
                Log.e(LOG_TAG, "선택 마커 강조 적용 여부: false", exception)
                currentOnSelectionMarkerApplied(false)
            }
            currentOnMarkerError(exception)
        }

        if (myLocation == null) {
            Log.d(LOG_TAG, "현재 위치 파란색 점 표시 실패: 현재 위치 없음")
            currentOnMyLocationDisplayed(false)
            return@LaunchedEffect
        }

        try {
            val myLocationBitmap = drawableToBitmap(
                context = context,
                drawableResId = R.drawable.ic_my_location_blue_dot,
                widthDp = MY_LOCATION_DOT_SIZE_DP,
                heightDp = MY_LOCATION_DOT_SIZE_DP
            )
            Log.d(LOG_TAG, "파란색 현재 위치 점 아이콘 로드 성공")
            val myLocationStyles = labelManager.addLabelStyles(
                LabelStyles.from(LabelStyle.from(myLocationBitmap))
            )
            labelLayer.addLabel(
                LabelOptions.from(
                    "my_location",
                    LatLng.from(myLocation.lat, myLocation.lng)
                )
                    .setStyles(myLocationStyles)
                    .setClickable(false)
                    .setTag(MyLocationMarkerTag)
            )
            Log.d(
                LOG_TAG,
                "현재 위치 파란색 점 표시 성공: " +
                    "lat=${myLocation.lat}, lng=${myLocation.lng}"
            )
            currentOnMyLocationDisplayed(true)
        } catch (exception: Exception) {
            Log.e(LOG_TAG, "파란색 현재 위치 점 아이콘 로드 또는 표시 실패", exception)
            currentOnMyLocationDisplayed(false)
            currentOnMyLocationError(exception)
        }
    }

    AndroidView(
        factory = {
            Log.d(LOG_TAG, "Kakao MapView 생성")
            currentOnMapViewCreated()
            mapView.apply {
                post {
                    Log.d(LOG_TAG, "Kakao MapView attach 이후 start")
                    startMapView(this)
                }
            }
        },
        modifier = modifier.onSizeChanged { size ->
            Log.d(
                LOG_TAG,
                "Kakao Map 지도 영역 크기: width=${size.width}, height=${size.height}"
            )
            currentOnMapViewSizeChanged(size.width, size.height)
        }
    )
}

private fun isValidMapCoordinate(lat: Double?, lng: Double?): Boolean =
    lat != null && lng != null &&
        lat.isFinite() && lng.isFinite() &&
        lat in -90.0..90.0 && lng in -180.0..180.0

private fun drawableToBitmap(
    context: Context,
    @DrawableRes drawableResId: Int,
    widthDp: Int,
    heightDp: Int
): Bitmap {
    val drawable = ContextCompat.getDrawable(context, drawableResId)
        ?: error("마커 drawable을 찾지 못했습니다: $drawableResId")
    val density = context.resources.displayMetrics.density
    val widthPx = (widthDp * density).roundToInt().coerceAtLeast(1)
    val heightPx = (heightDp * density).roundToInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(
        widthPx,
        heightPx,
        Bitmap.Config.ARGB_8888
    )

    drawable.setBounds(0, 0, widthPx, heightPx)
    drawable.draw(Canvas(bitmap))
    return bitmap
}
