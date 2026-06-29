package com.example.chaircharge

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.chaircharge.accessibility.AccessibilityScoreResult
import com.example.chaircharge.accessibility.calculateAccessibilityScore
import com.example.chaircharge.data.Charger
import com.example.chaircharge.data.ChargerDataOrigin
import com.example.chaircharge.data.ChargerRepository
import com.example.chaircharge.data.haversineDistanceMeters
import com.example.chaircharge.network.ApiConfig
import com.example.chaircharge.network.tmap.TmapRouteRepository
import com.example.chaircharge.network.tmap.TmapRouteRequest
import com.example.chaircharge.publicdata.AccessibilityPublicDataSource
import com.example.chaircharge.publicdata.ChargerAccessibilityContext
import com.example.chaircharge.publicdata.Crosswalk
import com.example.chaircharge.publicdata.findChargerAccessibilityContext
import com.example.chaircharge.ui.map.MapCameraRequest
import com.example.chaircharge.ui.theme.ChairChargeTheme
import com.example.chaircharge.ui.map.MapCoordinate
import com.example.chaircharge.ui.map.RouteLineRequest
import com.example.chaircharge.ui.map.RouteLineType
import com.example.chaircharge.ui.map.WheelChargeKakaoMap
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.ceil

private const val LOG_TAG = "WheelCharge"
private const val NEAREST_PATH = "nearest"
private const val TEST_LAT = 35.9676
private const val TEST_LNG = 126.7368
private const val NEAREST_LIMIT = 5
private const val SELECTED_CHARGER_ZOOM_LEVEL = 16
private const val CROSSWALK_LAYER_RADIUS_METERS = 1_000.0
private const val CROSSWALK_LAYER_MAX_ITEMS = 250

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ChairChargeTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    WheelChargeScreen(
                        modifier = Modifier
                            .padding(innerPadding)
                            .fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
fun WheelChargeScreen(modifier: Modifier = Modifier) {
    var resultText by remember {
        mutableStateOf(
            "우측 상단 현재 위치 버튼을 눌러 가까운 충전소를 추천받을 수 있습니다."
        )
    }
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val fusedLocationClient = remember(context) {
        LocationServices.getFusedLocationProviderClient(context)
    }
    val chargerRepository = remember(context) {
        ChargerRepository(context)
    }
    val tmapRouteRepository = remember { TmapRouteRepository() }
    val accessibilityPublicDataSource = remember(context) {
        AccessibilityPublicDataSource(context)
    }
    var mapChargers by remember { mutableStateOf(emptyList<Charger>()) }
    var mapDataOrigin by remember { mutableStateOf<ChargerDataOrigin?>(null) }
    var mapCenter by remember {
        mutableStateOf(MapCoordinate(lat = TEST_LAT, lng = TEST_LNG))
    }
    var mapUsesCurrentLocation by remember { mutableStateOf(false) }
    var mapCurrentLocation by remember { mutableStateOf<MapCoordinate?>(null) }
    var myLocationDisplaySucceeded by remember { mutableStateOf(false) }
    var myLocationErrorMessage by remember { mutableStateOf<String?>(null) }
    var mapMarkerCount by remember { mutableStateOf(0) }
    var mapDataLoadStarted by remember { mutableStateOf(false) }
    var mapStatusText by remember {
        mutableStateOf(
            if (BuildConfig.KAKAO_NATIVE_APP_KEY.isBlank()) {
                "Kakao Native App Key가 비어 있습니다."
            } else {
                "지도 초기화 중입니다..."
            }
        )
    }
    var markerErrorMessage by remember { mutableStateOf<String?>(null) }
    var selectedCharger by remember { mutableStateOf<Charger?>(null) }
    var selectedChargerMapIndex by remember { mutableStateOf<Int?>(null) }
    var selectedNearestIndex by remember { mutableStateOf<Int?>(null) }
    var nearestChargers by remember { mutableStateOf(emptyList<Charger>()) }
    var cameraRequest by remember { mutableStateOf<MapCameraRequest?>(null) }
    var cameraRequestSequence by remember { mutableStateOf(0L) }
    var mapInteractionMessage by remember { mutableStateOf<String?>(null) }
    var routeLineRequest by remember { mutableStateOf<RouteLineRequest?>(null) }
    var routeRequestSequence by remember { mutableStateOf(0L) }
    var isRouteVisible by remember { mutableStateOf(false) }
    var routeDistanceText by remember { mutableStateOf<String?>(null) }
    var routeDurationText by remember { mutableStateOf<String?>(null) }
    var routeSourceText by remember { mutableStateOf<String?>(null) }
    var routeMessage by remember { mutableStateOf<String?>(null) }
    var routeType by remember { mutableStateOf<RouteLineType?>(null) }
    var isRouteLoading by remember { mutableStateOf(false) }
    var isBottomSheetExpanded by remember { mutableStateOf(true) }
    var crosswalks by remember { mutableStateOf(emptyList<Crosswalk>()) }
    var chargerAccessibilityContexts by remember {
        mutableStateOf(emptyList<ChargerAccessibilityContext>())
    }
    var accessibilityPublicDataLoaded by remember { mutableStateOf(false) }
    var isCrosswalkLayerVisible by remember { mutableStateOf(false) }
    var crosswalkLayerMarkerCount by remember { mutableStateOf(0) }
    val accessibilityReferenceCoordinate = mapCurrentLocation
        ?.takeIf { isValidCoordinate(it.lat, it.lng) }
    val accessibilityReferenceLabel = if (accessibilityReferenceCoordinate != null) {
        "현재 위치"
    } else {
        "현재 위치 미확인"
    }
    val selectedPublicDataMatch = remember(
        selectedCharger,
        chargerAccessibilityContexts
    ) {
        selectedCharger?.let { charger ->
            findChargerAccessibilityContext(
                charger = charger,
                contexts = chargerAccessibilityContexts
            )
        }
    }
    val selectedPublicDataContext = selectedPublicDataMatch?.context
    val selectedAccessibilityResult = remember(
        selectedCharger,
        accessibilityReferenceCoordinate,
        selectedPublicDataContext
    ) {
        selectedCharger?.let { charger ->
            accessibilityReferenceCoordinate?.let { reference ->
                calculateAccessibilityForReference(
                    charger = charger,
                    reference = reference,
                    publicDataContext = selectedPublicDataContext,
                    basisLocationText = accessibilityReferenceLabel
                )
            } ?: calculateAccessibilityScore(
                charger = charger,
                distanceM = null,
                context = selectedPublicDataContext,
                basisLocationText = accessibilityReferenceLabel
            )
        }
    }
    val crosswalkLayerCenter = selectedCharger
        ?.takeIf {
            val lat = it.lat
            val lng = it.lng
            lat != null && lng != null && isValidCoordinate(lat, lng)
        }
        ?.let { MapCoordinate(requireNotNull(it.lat), requireNotNull(it.lng)) }
        ?: mapCurrentLocation
        ?: mapCenter
    val displayedCrosswalkLocations = remember(
        isCrosswalkLayerVisible,
        crosswalks,
        crosswalkLayerCenter
    ) {
        if (!isCrosswalkLayerVisible) {
            emptyList()
        } else {
            crosswalks.asSequence()
                .map { crosswalk ->
                    crosswalk to haversineDistanceMeters(
                        startLat = crosswalkLayerCenter.lat,
                        startLng = crosswalkLayerCenter.lng,
                        endLat = crosswalk.lat,
                        endLng = crosswalk.lng
                    )
                }
                .filter { it.second <= CROSSWALK_LAYER_RADIUS_METERS }
                .sortedBy { it.second }
                .take(CROSSWALK_LAYER_MAX_ITEMS)
                .map { MapCoordinate(it.first.lat, it.first.lng) }
                .toList()
        }
    }

    fun updateBottomSheetExpanded(expanded: Boolean, reason: String) {
        if (isBottomSheetExpanded == expanded) {
            return
        }
        isBottomSheetExpanded = expanded
        Log.d(
            LOG_TAG,
            "하단 패널 expanded/collapsed 상태: " +
                if (expanded) "expanded, reason=$reason" else {
                    "collapsed, reason=$reason"
                }
        )
    }

    fun clearRoute(reason: String) {
        val hadRoute = routeLineRequest != null || isRouteVisible
        routeRequestSequence += 1
        routeLineRequest = null
        isRouteVisible = false
        routeDistanceText = null
        routeDurationText = null
        routeSourceText = null
        routeMessage = null
        routeType = null
        isRouteLoading = false
        Log.d(
            LOG_TAG,
            "기존 Polyline 제거 요청 여부: $hadRoute, reason=$reason"
        )
        Log.d(LOG_TAG, "route polyline cleared: reason=$reason")
    }

    fun showStraightRouteFallback(
        start: MapCoordinate,
        end: MapCoordinate,
        requestId: Long
    ) {
        if (requestId != routeRequestSequence) {
            Log.d(LOG_TAG, "fallback straight route ignored: stale request")
            return
        }
        val distanceMeters = haversineDistanceMeters(
            startLat = start.lat,
            startLng = start.lng,
            endLat = end.lat,
            endLng = end.lng
        )
        routeDistanceText = "거리: ${formatDistance(distanceMeters)}"
        routeDurationText = null
        routeSourceText = "경로: 위치 확인용 직선 경로"
        routeMessage =
            "실제 보행자 경로를 불러오지 못해 위치 확인용 직선 경로를 표시합니다."
        routeType = RouteLineType.FALLBACK_STRAIGHT
        isRouteLoading = false
        isRouteVisible = true
        routeLineRequest = RouteLineRequest(
            requestId = requestId,
            points = listOf(start, end),
            type = RouteLineType.FALLBACK_STRAIGHT
        )
        Log.d(LOG_TAG, "fallback straight route used")
        Log.d(
            LOG_TAG,
            "fallback straight distance: $distanceMeters, requestId=$requestId"
        )
    }

    fun applySelectedCharger(
        charger: Charger,
        mapIndex: Int?,
        source: String,
        nearestIndex: Int? = null
    ) {
        val selectionChanged =
            selectedCharger?.let { !sameCharger(it, charger) } == true
        if (selectionChanged) {
            clearRoute(reason = "선택 충전소 변경")
        }

        val previousIndex = selectedChargerMapIndex
        val previousHighlightReleased =
            previousIndex != null && previousIndex != mapIndex
        Log.d(
            LOG_TAG,
            "이전 선택 마커 강조 해제 여부: $previousHighlightReleased, " +
                "previousIndex=$previousIndex, nextIndex=$mapIndex"
        )

        selectedCharger = charger
        updateBottomSheetExpanded(
            expanded = true,
            reason = "충전소 선택"
        )
        selectedChargerMapIndex = mapIndex
        selectedNearestIndex = nearestIndex
            ?: nearestChargers.indexOfFirst { sameCharger(it, charger) }
                .takeIf { it >= 0 }

        Log.d(LOG_TAG, "selectedCharger 설정 성공 여부: true")
        Log.d(
            LOG_TAG,
            "선택 충전소 상태 갱신: id=${charger.id}, name=${charger.name}"
        )
        Log.d(
            LOG_TAG,
            "마커 클릭과 목록 클릭 상태 동기화 여부: true, source=$source, " +
                "mapIndex=$mapIndex"
        )
    }

    fun showRouteToSelectedCharger() {
        if (isRouteLoading) {
            Log.d(LOG_TAG, "TMAP route request ignored: already loading")
            return
        }
        val charger = selectedCharger
        if (charger == null) {
            clearRoute(reason = "선택 충전소 없음")
            routeMessage = "경로를 확인할 충전소를 먼저 선택해 주세요."
            val exception = IllegalStateException("선택된 충전소가 없습니다.")
            Log.e(LOG_TAG, "selected charger missing coordinates", exception)
            return
        }
        Log.d(LOG_TAG, "경로 버튼 클릭")
        Log.d(LOG_TAG, "경로 보기 버튼 클릭")
        Log.d(LOG_TAG, "선택한 충전소 식별값: id=${charger.id}")
        Log.d(LOG_TAG, "선택한 충전소 시설명: ${charger.name}")

        val endLat = charger.lat
        val endLng = charger.lng
        if (
            endLat == null ||
            endLng == null ||
            !isValidCoordinate(endLat, endLng)
        ) {
            clearRoute(reason = "선택 충전소 좌표 오류")
            routeMessage =
                "선택한 충전소의 좌표 정보가 없어 경로를 표시할 수 없습니다."
            val exception = IllegalArgumentException(
                "유효하지 않은 경로 도착 좌표: lat=$endLat, lng=$endLng"
            )
            Log.e(LOG_TAG, "selected charger missing coordinates", exception)
            return
        }

        val currentLocation = mapCurrentLocation?.takeIf {
            isValidCoordinate(it.lat, it.lng)
        }
        if (currentLocation == null) {
            clearRoute(reason = "현재 위치 미확인")
            routeMessage =
                "경로 안내를 위해 우측 상단 현재 위치 버튼을 눌러 주세요."
            val exception = IllegalStateException(
                "경로 출발점으로 사용할 현재 위치가 없습니다."
            )
            Log.e(LOG_TAG, "current location missing", exception)
            return
        }
        val start = currentLocation
        val end = MapCoordinate(lat = endLat, lng = endLng)
        clearRoute(reason = "새 TMAP 경로 요청")
        val requestId = routeRequestSequence
        isRouteLoading = true
        routeSourceText = "경로: TMAP 보행자 경로\n옵션: 계단 제외 우선"
        routeDistanceText = null
        routeDurationText = null
        routeMessage = "경로를 불러오는 중입니다..."

        tmapRouteRepository.requestPedestrianRoute(
            request = TmapRouteRequest(
                startX = start.lng,
                startY = start.lat,
                endX = end.lng,
                endY = end.lat,
                startName = "현재 위치",
                endName = charger.name,
            )
        ) { result ->
            if (requestId != routeRequestSequence) {
                Log.d(LOG_TAG, "TMAP route result ignored: stale request")
                return@requestPedestrianRoute
            }
            result.fold(
                onSuccess = { route ->
                    val points = route.routePoints.map { point ->
                        MapCoordinate(lat = point.lat, lng = point.lng)
                    }
                    if (points.size < 2) {
                        val exception = IllegalStateException(
                            "TMAP 경로 좌표가 2개 미만입니다."
                        )
                        Log.e(LOG_TAG, "TMAP route failed", exception)
                        showStraightRouteFallback(start, end, requestId)
                        return@fold
                    }
                    routeDistanceText = route.totalDistanceM?.let { distance ->
                        "거리: ${formatDistance(distance)}"
                    } ?: "거리: 정보 없음"
                    routeDurationText = route.totalDurationS?.let { seconds ->
                        val minutes = ceil(seconds / 60.0).toInt().coerceAtLeast(1)
                        "예상 시간: 약 ${minutes}분"
                    } ?: "예상 시간: 정보 없음"
                    routeSourceText =
                        "경로: TMAP 보행자 경로\n옵션: 계단 제외 우선"
                    routeMessage = "실제 보행자 경로를 표시합니다."
                    routeType = RouteLineType.TMAP_PEDESTRIAN
                    isRouteLoading = false
                    isRouteVisible = true
                    routeLineRequest = RouteLineRequest(
                        requestId = requestId,
                        points = points,
                        type = RouteLineType.TMAP_PEDESTRIAN
                    )
                    Log.d(
                        LOG_TAG,
                        "TMAP route success: requestId=$requestId, " +
                            "points=${points.size}"
                    )
                },
                onFailure = { exception ->
                    Log.e(LOG_TAG, "TMAP route failed", exception)
                    showStraightRouteFallback(start, end, requestId)
                }
            )
        }
    }

    fun selectNearestCharger(charger: Charger, nearestIndex: Int) {
        Log.d(LOG_TAG, "TOP 5 항목 클릭 감지: index=$nearestIndex")
        Log.d(
            LOG_TAG,
            "클릭한 충전소 식별값: id=${charger.id}, index=$nearestIndex"
        )
        Log.d(LOG_TAG, "클릭한 충전소 시설명: ${charger.name}")
        Log.d(
            LOG_TAG,
            "클릭한 충전소 좌표: lat=${charger.lat}, lng=${charger.lng}"
        )

        val match = findMatchingMapCharger(
            mapChargers = mapChargers,
            target = charger,
            fallbackIndex = nearestIndex
        )
        Log.d(
            LOG_TAG,
            "충전소 객체 매칭 결과: strategy=${match?.strategy ?: "none"}, " +
                "mapIndex=${match?.index}"
        )
        applySelectedCharger(
            charger = charger,
            mapIndex = match?.index,
            source = "TOP_5_LIST",
            nearestIndex = nearestIndex
        )

        coroutineScope.launch {
            scrollState.animateScrollTo(0)
        }

        val lat = charger.lat
        val lng = charger.lng
        if (lat == null || lng == null || !isValidCoordinate(lat, lng)) {
            mapInteractionMessage =
                "이 충전소는 좌표 정보가 없어 지도에서 이동할 수 없습니다."
            val exception = IllegalArgumentException(
                "유효하지 않은 충전소 좌표: lat=$lat, lng=$lng"
            )
            Log.e(LOG_TAG, "지도 카메라 이동 실패", exception)
            return
        }

        if (match == null) {
            mapInteractionMessage = "선택한 충전소 마커를 찾지 못했습니다."
            Log.w(LOG_TAG, "선택 마커 강조 적용 여부: false - 매칭 실패")
        }

        cameraRequestSequence += 1
        cameraRequest = MapCameraRequest(
            requestId = cameraRequestSequence,
            target = MapCoordinate(lat = lat, lng = lng),
            zoomLevel = SELECTED_CHARGER_ZOOM_LEVEL
        )
        Log.d(
            LOG_TAG,
            "지도 카메라 이동 시도 예약: requestId=$cameraRequestSequence"
        )
    }

    fun loadMapChargers() {
        chargerRepository.loadAllChargers { result ->
            result.fold(
                onSuccess = { loadResult ->
                    mapChargers = loadResult.chargers
                    selectedCharger?.let { charger ->
                        val match = findMatchingMapCharger(
                            mapChargers = loadResult.chargers,
                            target = charger,
                            fallbackIndex = selectedChargerMapIndex
                        )
                        selectedChargerMapIndex = match?.index
                        Log.d(
                            LOG_TAG,
                            "지도 데이터 로드 후 선택 상태 동기화: " +
                                "strategy=${match?.strategy ?: "none"}, " +
                                "mapIndex=${match?.index}"
                        )
                    }
                    mapDataOrigin = loadResult.origin
                    Log.d(
                        LOG_TAG,
                        "데이터 출처 상태 갱신: " +
                            formatDataOrigin(loadResult.origin)
                    )
                    markerErrorMessage = null
                    Log.d(
                        LOG_TAG,
                        "충전소 데이터 출처: ${formatDataOrigin(loadResult.origin)}"
                    )
                    Log.d(
                        LOG_TAG,
                        "충전소 데이터 개수: ${loadResult.chargers.size}"
                    )
                },
                onFailure = { exception ->
                    mapChargers = emptyList()
                    mapDataOrigin = null
                    markerErrorMessage =
                        "지도는 표시되었지만 충전소 마커를 표시하지 못했습니다."
                    Log.e(LOG_TAG, "지도용 충전소 데이터 로드 실패", exception)
                }
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun tryUseCurrentLocationForMap() {
        val fineLocationGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseLocationGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        Log.d(
            LOG_TAG,
            "현재 위치 권한 상태: fine=$fineLocationGranted, " +
                "coarse=$coarseLocationGranted"
        )

        if (!fineLocationGranted && !coarseLocationGranted) {
            mapUsesCurrentLocation = false
            mapCurrentLocation = null
            myLocationDisplaySucceeded = false
            myLocationErrorMessage =
                "위치 권한을 허용하면 가까운 충전소를 추천받을 수 있습니다."
            Log.d(LOG_TAG, "현재 위치 사용 여부: false - 기본 위치 사용(권한 없음)")
            return
        }

        val locationRequest = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(5_000)
            .setDurationMillis(15_000)
            .build()

        fusedLocationClient.getCurrentLocation(
            locationRequest,
            CancellationTokenSource().token
        ).addOnSuccessListener { location ->
            if (location == null) {
                mapUsesCurrentLocation = false
                mapCurrentLocation = null
                myLocationDisplaySucceeded = false
                myLocationErrorMessage = "현재 위치를 확인할 수 없습니다."
                Log.d(LOG_TAG, "현재 위치 사용 여부: false - 기본 위치 사용(위치 없음)")
                return@addOnSuccessListener
            }

            val currentLocation = MapCoordinate(
                lat = location.latitude,
                lng = location.longitude
            )
            mapCenter = currentLocation
            mapCurrentLocation = currentLocation
            mapUsesCurrentLocation = true
            myLocationErrorMessage = null
            Log.d(
                LOG_TAG,
                "현재 위치 사용 여부: true, " +
                    "lat=${location.latitude}, lng=${location.longitude}"
            )
        }.addOnFailureListener { exception ->
            mapUsesCurrentLocation = false
            mapCurrentLocation = null
            myLocationDisplaySucceeded = false
            myLocationErrorMessage = "현재 위치를 확인할 수 없습니다."
            Log.e(LOG_TAG, "현재 위치 사용 여부: false - 기본 위치 사용", exception)
        }
    }

    fun loadNearestForCurrentLocation(lat: Double, lng: Double) {
        nearestChargers = emptyList()
        val requestUrl = buildNearestApiUrl(lat, lng)
        resultText = "현재 위치 기준 가까운 충전소를 불러오는 중입니다..."
        Log.d(LOG_TAG, "현재 Base URL: ${ApiConfig.BASE_URL}")
        Log.d(LOG_TAG, "현재 위치 좌표: lat=$lat, lng=$lng")
        Log.d(LOG_TAG, "호출 API: /$NEAREST_PATH")

        chargerRepository.loadNearestChargers(
            lat = lat,
            lng = lng,
            limit = NEAREST_LIMIT
        ) { result ->
            result.fold(
                onSuccess = { loadResult ->
                    nearestChargers = loadResult.chargers.take(NEAREST_LIMIT)
                    resultText = buildCurrentLocationNearestHeaderText(
                        chargers = loadResult.chargers,
                        origin = loadResult.origin
                    )
                    Log.d(
                        LOG_TAG,
                        "현재 위치 기준 응답 개수: ${loadResult.chargers.size}"
                    )
                    Log.d(
                        LOG_TAG,
                        "현재 위치 기반 추천 갱신: count=${nearestChargers.size}, " +
                            "source=${formatDataOrigin(loadResult.origin)}"
                    )
                },
                onFailure = { exception ->
                    resultText = "가까운 충전소 추천을 불러오지 못했습니다."
                    Log.e(LOG_TAG, "현재 위치 기준 데이터 로드 최종 실패", exception)
                }
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun fetchCurrentLocation() {
        clearRoute(reason = "현재 위치 재측정")
        nearestChargers = emptyList()
        resultText = "현재 위치를 확인하는 중입니다..."
        mapInteractionMessage = null
        myLocationErrorMessage = null
        Log.d(LOG_TAG, "현재 위치 재측정 시작")

        val locationRequest = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(5_000)
            .setDurationMillis(15_000)
            .build()

        fusedLocationClient.getCurrentLocation(
            locationRequest,
            CancellationTokenSource().token
        ).addOnSuccessListener { location ->
            if (location == null) {
                resultText = "현재 위치를 확인할 수 없습니다."
                mapUsesCurrentLocation = false
                mapCurrentLocation = null
                myLocationDisplaySucceeded = false
                myLocationErrorMessage = "현재 위치를 확인할 수 없습니다."
                val exception = IllegalStateException("현재 위치 결과가 null입니다.")
                Log.e(LOG_TAG, "현재 위치 재측정 실패", exception)
                return@addOnSuccessListener
            }

            val currentLocation = MapCoordinate(
                lat = location.latitude,
                lng = location.longitude
            )
            mapCenter = currentLocation
            mapCurrentLocation = currentLocation
            mapUsesCurrentLocation = true
            myLocationErrorMessage = null
            Log.d(
                LOG_TAG,
                "현재 위치 좌표: lat=${location.latitude}, lng=${location.longitude}"
            )
            Log.d(LOG_TAG, "현재 위치 사용 여부: true")
            Log.d(
                LOG_TAG,
                "현재 위치 재측정 성공: lat=${location.latitude}, " +
                    "lng=${location.longitude}"
            )
            loadNearestForCurrentLocation(location.latitude, location.longitude)
        }.addOnFailureListener { exception ->
            resultText = "현재 위치를 확인할 수 없습니다."
            mapUsesCurrentLocation = false
            mapCurrentLocation = null
            myLocationDisplaySucceeded = false
            myLocationErrorMessage = "현재 위치를 확인할 수 없습니다."
            Log.d(LOG_TAG, "현재 위치 사용 여부: false - 기본 위치 유지")
            Log.e(LOG_TAG, "현재 위치 재측정 실패", exception)
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted =
            permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseLocationGranted =
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val locationGranted = fineLocationGranted || coarseLocationGranted

        Log.d(
            LOG_TAG,
            "위치 권한 상태: fine=$fineLocationGranted, " +
                "coarse=$coarseLocationGranted"
        )

        if (locationGranted) {
            fetchCurrentLocation()
        } else {
            resultText =
                "위치 권한을 허용하면 가까운 충전소를 추천받을 수 있습니다."
            mapCurrentLocation = null
            mapUsesCurrentLocation = false
            myLocationDisplaySucceeded = false
            myLocationErrorMessage =
                "위치 권한을 허용하면 가까운 충전소를 추천받을 수 있습니다."
            Log.d(LOG_TAG, "현재 위치 재측정 실패: 위치 권한 거부")
        }
    }

    LaunchedEffect(
        selectedCharger,
        accessibilityReferenceCoordinate,
        accessibilityReferenceLabel,
        selectedPublicDataContext
    ) {
        val charger = selectedCharger ?: return@LaunchedEffect
        try {
            Log.d(LOG_TAG, "통합 접근성 점수 계산 시작")
            Log.d(LOG_TAG, "선택한 충전소 식별값: id=${charger.id}")
            Log.d(LOG_TAG, "선택한 충전소 시설명: ${charger.name}")
            Log.d(
                LOG_TAG,
                "접근성 기준 위치: $accessibilityReferenceLabel"
            )
            if (selectedPublicDataMatch != null) {
                Log.d(
                    LOG_TAG,
                    "선택 충전소 context 매칭 성공: " +
                        "strategy=${selectedPublicDataMatch.strategy}"
                )
            } else {
                Log.w(
                    LOG_TAG,
                    "선택 충전소 context 매칭 실패: id=${charger.id}"
                )
            }
            val result = selectedAccessibilityResult ?: return@LaunchedEffect
            Log.d(
                LOG_TAG,
                "계산된 거리: ${result.distanceM?.let(::formatDistance) ?: "계산 불가"}"
            )
            Log.d(
                LOG_TAG,
                "거리 접근성 점수: ${result.breakdown.distanceScore}/30"
            )
            Log.d(
                LOG_TAG,
                "충전소 정보 점수: ${result.breakdown.chargerInfoScore}/20"
            )
            Log.d(
                LOG_TAG,
                "횡단보도 접근성 점수: ${result.breakdown.crosswalkScore}/20"
            )
            Log.d(
                LOG_TAG,
                "경사도/고도 안전성 점수: ${result.breakdown.slopeScore}/20"
            )
            Log.d(
                LOG_TAG,
                "이용 편의성 점수: ${result.breakdown.convenienceScore}/10"
            )
            Log.d(LOG_TAG, "최종 통합 점수: ${result.totalScore}/100")
            Log.d(LOG_TAG, "추천 등급: ${result.grade}")
            Log.d(LOG_TAG, "추천 사유 개수: ${result.reasons.size}")
            Log.d(
                LOG_TAG,
                "접근성 점수 표시: ${result.totalScore}점, ${result.grade}"
            )
            Log.d(
                LOG_TAG,
                "상세정보 패널 표시: id=${charger.id}, name=${charger.name}"
            )
        } catch (exception: Exception) {
            Log.e(LOG_TAG, "접근성 점수 계산 실패", exception)
        }
    }

    LaunchedEffect(Unit) {
        Log.d(LOG_TAG, "UI 최종 정리 적용")
        Log.d(
            LOG_TAG,
            "삭제된 개발용 버튼 목록: 서버 상태 확인, 전체 충전소, " +
                "테스트 위치 추천, 현재 위치 추천"
        )
        Log.d(LOG_TAG, "테스트 좌표 UI 제거 여부: true")
        Log.d(
            LOG_TAG,
            "기존 기능 유지 여부: 지도, 마커, 위치, 추천, 상세, 점수, " +
                "경로, 횡단보도, 로컬 fallback 유지"
        )
        Log.d(LOG_TAG, "UI 초기화")
        Log.d(LOG_TAG, "bottom sheet 스타일 적용")
        Log.d(LOG_TAG, "하단 패널 expanded/collapsed 상태: expanded, reason=초기화")
        Log.d(LOG_TAG, "primary color theme 적용: blue #2563EB")
        Log.d(
            LOG_TAG,
            "지도 drag 영역 터치 이벤트 충돌 여부: false - " +
                "MapView를 부모 verticalScroll에서 분리"
        )
        Log.d(LOG_TAG, "Current Base URL = ${ApiConfig.BASE_URL}")
        Log.d(
            LOG_TAG,
            "지도 초기 좌표: lat=$TEST_LAT, lng=$TEST_LNG"
        )
        tryUseCurrentLocationForMap()
    }

    LaunchedEffect(accessibilityPublicDataSource) {
        try {
            val bundle = withContext(Dispatchers.IO) {
                accessibilityPublicDataSource.loadAll()
            }
            crosswalks = bundle.crosswalks
            chargerAccessibilityContexts = bundle.chargerContexts
            accessibilityPublicDataLoaded = true
        } catch (exception: Exception) {
            crosswalks = emptyList()
            chargerAccessibilityContexts = emptyList()
            accessibilityPublicDataLoaded = true
            Log.e(LOG_TAG, "접근성 공공데이터 assets 로드 실패", exception)
        }
    }

    LaunchedEffect(
        mapStatusText,
        mapMarkerCount,
        mapDataOrigin,
        myLocationDisplaySucceeded
    ) {
        Log.d(
            LOG_TAG,
            "지도 상태 영역 갱신: status=$mapStatusText, " +
                "markers=$mapMarkerCount, location=$myLocationDisplaySucceeded"
        )
        Log.d(
            LOG_TAG,
            "데이터 출처 상태 갱신: " +
                (mapDataOrigin?.let(::formatDataOrigin) ?: "로딩 중")
        )
    }

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (BuildConfig.KAKAO_NATIVE_APP_KEY.isNotBlank()) {
            WheelChargeKakaoMap(
                chargers = mapChargers,
                center = mapCenter,
                myLocation = mapCurrentLocation,
                selectedChargerIndex = selectedChargerMapIndex,
                cameraRequest = cameraRequest,
                routeLineRequest = routeLineRequest,
                crosswalkLocations = displayedCrosswalkLocations,
                modifier = Modifier
                    .fillMaxSize(),
                onMapViewCreated = {
                    mapStatusText = "지도 초기화 중입니다..."
                    Log.d(LOG_TAG, "지도 View 생성 여부: true")
                },
                onMapViewSizeChanged = { width, height ->
                    Log.d(
                        LOG_TAG,
                        "지도 영역 크기 확인: width=$width, height=$height"
                    )
                },
                onMapReady = {
                    mapStatusText = "지도 로딩 성공"
                    Log.d(LOG_TAG, "Kakao Map 화면 표시 성공")
                    if (!mapDataLoadStarted) {
                        mapDataLoadStarted = true
                        loadMapChargers()
                    }
                },
                onMapError = { exception ->
                    val errorMessage =
                        exception.message ?: exception.javaClass.simpleName
                    mapStatusText =
                        "지도를 불러오지 못했습니다. " +
                            "Logcat의 WheelCharge 로그를 확인하세요.\n" +
                            "Kakao MapView 초기화 실패: $errorMessage"
                    Log.e(LOG_TAG, "Kakao MapView 초기화 실패", exception)
                },
                onMarkerError = { exception ->
                    markerErrorMessage =
                        "지도는 표시되었지만 충전소 마커를 표시하지 못했습니다."
                    Log.e(LOG_TAG, "충전소 마커 표시 실패", exception)
                },
                onMarkersDisplayed = { markerCount ->
                    mapMarkerCount = markerCount
                    markerErrorMessage = null
                },
                onMyLocationDisplayed = { displayed ->
                    myLocationDisplaySucceeded = displayed
                    if (displayed) {
                        myLocationErrorMessage = null
                    }
                },
                onMyLocationError = { exception ->
                    myLocationDisplaySucceeded = false
                    myLocationErrorMessage =
                        "현재 위치를 표시하지 못했습니다. " +
                            "위치 권한과 위치 서비스 상태를 확인해주세요."
                    Log.e(LOG_TAG, "현재 위치 파란색 점 표시 실패", exception)
                },
                onCameraMoveResult = { succeeded, exception ->
                    if (succeeded) {
                        mapInteractionMessage =
                            if (selectedChargerMapIndex == null) {
                                "선택한 충전소 위치로 지도를 이동했습니다. " +
                                    "선택한 충전소 마커를 찾지 못했습니다."
                            } else {
                                "선택한 충전소 위치로 지도를 이동했습니다."
                            }
                        Log.d(LOG_TAG, "지도 카메라 이동 성공")
                    } else {
                        mapInteractionMessage = "지도 이동 중 오류가 발생했습니다."
                        Log.e(LOG_TAG, "지도 카메라 이동 실패", exception)
                    }
                },
                onSelectionMarkerApplied = { applied ->
                    Log.d(LOG_TAG, "선택 마커 강조 적용 여부: $applied")
                    if (!applied) {
                        mapInteractionMessage =
                            "선택한 충전소 마커를 찾지 못했습니다."
                    }
                },
                onRouteLineResult = { succeeded, exception ->
                    if (succeeded) {
                        isRouteVisible = true
                        mapInteractionMessage = "경로를 표시했습니다."
                        Log.d(LOG_TAG, "Polyline 생성 성공")
                    } else {
                        isRouteVisible = false
                        routeLineRequest = null
                        routeMessage = "경로를 표시하지 못했습니다."
                        Log.e(LOG_TAG, "Polyline 생성 실패", exception)
                    }
                },
                onRouteCameraMoveResult = { succeeded, exception ->
                    if (succeeded) {
                        Log.d(LOG_TAG, "경로 지도 카메라 이동 성공")
                    } else {
                        routeMessage =
                            (routeMessage ?: "경로를 표시했습니다.") +
                                "\n지도 화면 조정 중 오류가 발생했습니다."
                        Log.e(
                            LOG_TAG,
                            "경로 지도 카메라 이동 실패",
                            exception
                        )
                    }
                },
                onCrosswalksDisplayed = { count ->
                    crosswalkLayerMarkerCount = count
                },
                onCrosswalkLayerError = { exception ->
                    mapInteractionMessage =
                        "횡단보도 위치를 지도에 표시하지 못했습니다."
                    Log.e(LOG_TAG, "횡단보도 레이어 표시 실패", exception)
                },
                onChargerMarkerClick = { chargerId, index ->
                    val indexedCharger = mapChargers.getOrNull(index)
                        ?.takeIf { it.id == chargerId }
                    val matchedCharger = indexedCharger
                        ?: mapChargers.firstOrNull { it.id == chargerId }

                    Log.d(
                        LOG_TAG,
                        "매칭된 Charger 식별값: id=$chargerId, index=$index"
                    )

                    if (matchedCharger != null) {
                        applySelectedCharger(
                            charger = matchedCharger,
                            mapIndex = mapChargers.indexOf(matchedCharger),
                            source = "MAP_MARKER"
                        )
                        mapInteractionMessage = "충전소를 선택했습니다."
                        Log.d(
                            LOG_TAG,
                            "매칭된 Charger 시설명: ${matchedCharger.name}"
                        )
                        Log.d(LOG_TAG, "selectedCharger 설정 성공 여부: true")
                    } else {
                        clearRoute(reason = "마커 Charger 매칭 실패")
                        selectedCharger = null
                        selectedChargerMapIndex = null
                        selectedNearestIndex = null
                        mapInteractionMessage =
                            "선택한 충전소 마커를 찾지 못했습니다."
                        val exception = IllegalStateException(
                            "클릭한 마커에 대응하는 Charger를 찾지 못했습니다."
                        )
                        Log.e(
                            LOG_TAG,
                            "selectedCharger 설정 성공 여부: false",
                            exception
                        )
                    }
                }
            )
        } else {
            Spacer(
                modifier = Modifier
                    .fillMaxSize()
            )
        }

        MapOverlayActions(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 18.dp, end = 16.dp),
            isCrosswalkVisible = isCrosswalkLayerVisible,
            isCrosswalkEnabled = accessibilityPublicDataLoaded &&
                crosswalks.isNotEmpty(),
            onLocationClick = {
                Log.d(LOG_TAG, "현재 위치 아이콘 버튼 클릭")
                updateBottomSheetExpanded(
                    expanded = true,
                    reason = "현재 위치 아이콘 버튼"
                )
                val fineLocationGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                val coarseLocationGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                if (fineLocationGranted || coarseLocationGranted) {
                    fetchCurrentLocation()
                } else {
                    locationPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            },
            onCrosswalkClick = {
                isCrosswalkLayerVisible = !isCrosswalkLayerVisible
                Log.d(
                    LOG_TAG,
                    "횡단보도 토글 ON/OFF: " +
                        if (isCrosswalkLayerVisible) "ON" else "OFF"
                )
            }
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .then(
                    if (isBottomSheetExpanded) {
                        Modifier.fillMaxHeight(0.52f)
                    } else {
                        Modifier.height(84.dp)
                    }
                ),
            shape = RoundedCornerShape(
                topStart = 28.dp,
                topEnd = 28.dp
            ),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                BottomSheetHeader(
                    expanded = isBottomSheetExpanded,
                    selectedChargerName = selectedCharger?.name,
                    selectedScore = selectedAccessibilityResult?.totalScore,
                    selectedGrade = selectedAccessibilityResult?.grade,
                    dataSourceText = mapDataOrigin
                        ?.let(::formatDataOrigin)
                        ?: "로딩 중",
                    onExpandedChange = { expanded ->
                        updateBottomSheetExpanded(
                            expanded = expanded,
                            reason = "handle 조작"
                        )
                    }
                )

                if (isBottomSheetExpanded) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(scrollState)
                            .padding(
                                start = 20.dp,
                                end = 20.dp,
                                bottom = 24.dp
                            ),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
        StatusMessageArea(
            messages = listOfNotNull(
                mapInteractionMessage,
                myLocationErrorMessage ?: resultText
            )
        )

        if (
            nearestChargers.isNotEmpty() &&
            accessibilityReferenceCoordinate != null
        ) {
            Text(
                text = "추천 충전소 TOP $NEAREST_LIMIT",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            NearestChargerList(
                chargers = nearestChargers,
                selectedCharger = selectedCharger,
                selectedNearestIndex = selectedNearestIndex,
                accessibilityReference = accessibilityReferenceCoordinate,
                accessibilityReferenceLabel = accessibilityReferenceLabel,
                publicDataContexts = chargerAccessibilityContexts,
                onChargerClick = ::selectNearestCharger
            )
        }

        selectedCharger?.let { charger ->
            ChargerDetailPanel(
                charger = charger,
                accessibilityResult = selectedAccessibilityResult,
                publicDataContext = selectedPublicDataContext,
                isRouteVisible = isRouteVisible,
                routeDistanceText = routeDistanceText,
                routeDurationText = routeDurationText,
                routeSourceText = routeSourceText,
                routeMessage = routeMessage,
                routeType = routeType,
                isRouteLoading = isRouteLoading,
                onShowRoute = ::showRouteToSelectedCharger,
                onHideRoute = {
                    clearRoute(reason = "사용자 경로 숨기기")
                    mapInteractionMessage = "경로를 숨겼습니다."
                },
                onClose = {
                    val hadHighlightedMarker = selectedChargerMapIndex != null
                    clearRoute(reason = "상세 패널 닫기")
                    selectedCharger = null
                    selectedChargerMapIndex = null
                    selectedNearestIndex = null
                    mapInteractionMessage = null
                    Log.d(
                        LOG_TAG,
                        "이전 선택 마커 강조 해제 여부: $hadHighlightedMarker"
                    )
                }
            )
        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MapOverlayActions(
    modifier: Modifier = Modifier,
    isCrosswalkVisible: Boolean,
    isCrosswalkEnabled: Boolean,
    onLocationClick: () -> Unit,
    onCrosswalkClick: () -> Unit
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MapRoundIconButton(
            iconRes = R.drawable.ic_my_location_action,
            contentDescription = "현재 위치 재측정 및 추천 갱신",
            backgroundColor = MaterialTheme.colorScheme.surface,
            iconTint = MaterialTheme.colorScheme.primary,
            borderColor = MaterialTheme.colorScheme.primary,
            onClick = onLocationClick
        )
        MapRoundIconButton(
            iconRes = R.drawable.ic_crosswalk_action,
            contentDescription = if (isCrosswalkVisible) {
                "횡단보도 레이어 끄기"
            } else {
                "횡단보도 레이어 켜기"
            },
            backgroundColor = if (isCrosswalkVisible) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surface
            },
            iconTint = if (isCrosswalkVisible) {
                Color.White
            } else {
                MaterialTheme.colorScheme.primary
            },
            borderColor = MaterialTheme.colorScheme.primary,
            enabled = isCrosswalkEnabled,
            onClick = onCrosswalkClick
        )
    }
}

@Composable
private fun MapRoundIconButton(
    iconRes: Int,
    contentDescription: String,
    backgroundColor: Color,
    iconTint: Color,
    borderColor: Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .size(52.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = CircleShape,
        color = if (enabled) {
            backgroundColor
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.86f)
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (enabled) {
                borderColor
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        ),
        shadowElevation = 6.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = contentDescription,
                modifier = Modifier.size(26.dp),
                tint = if (enabled) {
                    iconTint
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                }
            )
        }
    }
}

@Composable
private fun BottomSheetHeader(
    expanded: Boolean,
    selectedChargerName: String?,
    selectedScore: Int?,
    selectedGrade: String?,
    dataSourceText: String,
    onExpandedChange: (Boolean) -> Unit
) {
    var accumulatedDrag by remember { mutableStateOf(0f) }
    val selectedSummary = listOfNotNull(
        selectedScore?.let { "${it}점" },
        selectedGrade
    ).joinToString(" · ")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(expanded) {
                detectVerticalDragGestures(
                    onDragStart = { accumulatedDrag = 0f },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        accumulatedDrag += dragAmount
                    },
                    onDragEnd = {
                        when {
                            accumulatedDrag > 40f -> onExpandedChange(false)
                            accumulatedDrag < -40f -> onExpandedChange(true)
                        }
                        accumulatedDrag = 0f
                    },
                    onDragCancel = { accumulatedDrag = 0f }
                )
            }
            .clickable { onExpandedChange(!expanded) }
            .padding(
                start = 20.dp,
                top = 10.dp,
                end = 20.dp,
                bottom = 12.dp
            ),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            Spacer(
                modifier = Modifier
                    .width(44.dp)
                    .height(4.dp)
                    .background(
                        color = if (expanded) {
                            MaterialTheme.colorScheme.outline
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        shape = RoundedCornerShape(50)
                    )
            )
        }
        if (expanded) {
            Text(
                text = "WheelCharge",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "전동휠체어 충전소 접근성 안내",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "데이터: $dataSourceText · 아래로 내려 지도 크게 보기",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            Text(
                text = selectedChargerName
                    ?.takeIf { it.isNotBlank() }
                    ?.let { name ->
                        if (selectedSummary.isBlank()) name else "$name · $selectedSummary"
                    }
                    ?: "WheelCharge · 추천 충전소 보기",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "위로 올려 정보 보기 · 데이터: $dataSourceText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun StatusMessageArea(messages: List<String>) {
    val visibleMessages = messages
        .filter { it.isNotBlank() }
        .distinct()
    if (visibleMessages.isEmpty()) {
        return
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            visibleMessages.forEach { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun NearestChargerList(
    chargers: List<Charger>,
    selectedCharger: Charger?,
    selectedNearestIndex: Int?,
    accessibilityReference: MapCoordinate,
    accessibilityReferenceLabel: String,
    publicDataContexts: List<ChargerAccessibilityContext>,
    onChargerClick: (Charger, Int) -> Unit
) {
    val displayedChargers = chargers.take(NEAREST_LIMIT)
    val matchedPublicDataContexts = remember(
        displayedChargers,
        publicDataContexts
    ) {
        displayedChargers.map { charger ->
            findChargerAccessibilityContext(
                charger = charger,
                contexts = publicDataContexts
            )?.context
        }
    }
    val accessibilityResults = remember(
        displayedChargers,
        accessibilityReference,
        matchedPublicDataContexts
    ) {
        displayedChargers.mapIndexed { index, charger ->
            calculateAccessibilityForReference(
                charger = charger,
                reference = accessibilityReference,
                publicDataContext = matchedPublicDataContexts[index],
                basisLocationText = accessibilityReferenceLabel
            )
        }
    }
    LaunchedEffect(displayedChargers, selectedNearestIndex) {
        Log.d(
            LOG_TAG,
            "TOP 5 카드 렌더링: count=${displayedChargers.size}, " +
                "selectedIndex=$selectedNearestIndex"
        )
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        displayedChargers.forEachIndexed { index, charger ->
            val accessibilityResult = accessibilityResults[index]
            val publicDataContext = matchedPublicDataContexts[index]
            val isSelected =
                selectedCharger?.let { sameCharger(charger, it) } == true ||
                    (
                        index == selectedNearestIndex &&
                            selectedCharger == charger
                    )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        Log.d(
                            LOG_TAG,
                            "TOP 5 카드 클릭: index=$index, id=${charger.id}"
                        )
                        onChargerClick(charger, index)
                    },
                shape = RoundedCornerShape(18.dp),
                border = if (isSelected) {
                    BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    null
                },
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    }
                )
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                modifier = Modifier.padding(
                                    horizontal = 10.dp,
                                    vertical = 5.dp
                                ),
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        Text(
                            modifier = Modifier.weight(1f),
                            text = charger.name.displayOr("시설명 정보 없음"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isSelected) {
                            Surface(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(50)
                            ) {
                                Text(
                                    modifier = Modifier.padding(
                                        horizontal = 9.dp,
                                        vertical = 4.dp
                                    ),
                                    text = "선택됨",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    Text(
                        text = "${formatDistance(charger.distance_m)} · " +
                            "접근성 ${accessibilityResult.totalScore}점 · " +
                            accessibilityResult.grade,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (publicDataContext != null) {
                        Text(
                            text = "공공데이터 반영: 횡단보도 · 경사도",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "탭하여 지도에서 보기",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun ChargerDetailPanel(
    charger: Charger,
    accessibilityResult: AccessibilityScoreResult?,
    publicDataContext: ChargerAccessibilityContext?,
    isRouteVisible: Boolean,
    routeDistanceText: String?,
    routeDurationText: String?,
    routeSourceText: String?,
    routeMessage: String?,
    routeType: RouteLineType?,
    isRouteLoading: Boolean,
    onShowRoute: () -> Unit,
    onHideRoute: () -> Unit,
    onClose: () -> Unit
) {
    LaunchedEffect(charger.id, accessibilityResult?.totalScore, isRouteVisible) {
        Log.d(
            LOG_TAG,
            "상세정보 패널 렌더링: id=${charger.id}, " +
                "score=${accessibilityResult?.totalScore}, route=$isRouteVisible"
        )
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant
        ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "선택 충전소",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = charger.name.displayOr("시설명 정보 없음"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            accessibilityResult?.let { result ->
                AccessibilitySummary(result = result)
            }
            Text(
                text = charger.address.displayOr("주소 정보 없음"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            RouteSummary(
                isRouteVisible = isRouteVisible,
                routeDistanceText = routeDistanceText,
                routeDurationText = routeDurationText,
                routeSourceText = routeSourceText,
                routeMessage = routeMessage,
                routeType = routeType,
                isRouteLoading = isRouteLoading,
                onShowRoute = onShowRoute,
                onHideRoute = onHideRoute
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = "기본정보",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            DetailInfoRow(
                label = "설치 위치",
                value = charger.install_place.displayOr("설치 위치 정보 없음")
            )
            DetailInfoRow(
                label = "문의처",
                value = charger.contact_phone.displayOr("문의처 정보 없음")
            )
            DetailInfoRow(
                label = "실내 여부",
                value = charger.is_indoor.booleanLabel("실내", "실외")
            )
            DetailInfoRow(
                label = "이동식 충전기",
                value = charger.is_movable.booleanLabel("가능", "불가")
            )

            accessibilityResult?.let { result ->
                AccessibilityBreakdownSummary(result = result)
                RecommendationReasonSummary(reasons = result.reasons)
            }
            PublicDataAccessibilitySummary(context = publicDataContext)

            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onClose
            ) {
                Text("닫기")
            }
        }
    }
}

@Composable
private fun AccessibilitySummary(
    result: AccessibilityScoreResult
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val gradeColor = accessibilityGradeColor(result.grade)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "접근성 점수",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${result.totalScore}점",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Surface(
                    color = gradeColor.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        modifier = Modifier.padding(
                            horizontal = 12.dp,
                            vertical = 6.dp
                        ),
                        text = result.grade,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = gradeColor
                    )
                }
            }
            Text(
                text = result.distanceM?.let { distance ->
                    "${result.basisLocationText}에서 ${formatDistance(distance)}"
                } ?: "현재 위치를 확인하면 거리 점수가 갱신됩니다.",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "거리 · 충전소 정보 · 횡단보도 · 경사 · 이용 편의 통합",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DetailInfoRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            modifier = Modifier.weight(0.38f),
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            modifier = Modifier.weight(0.62f),
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun AccessibilityBreakdownSummary(
    result: AccessibilityScoreResult
) {
    val breakdown = result.breakdown
    LaunchedEffect(result.totalScore, breakdown) {
        Log.d(
            LOG_TAG,
            "점수 breakdown 렌더링: total=${result.totalScore}/100"
        )
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "점수 구성",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "합계 ${result.totalScore}/100",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            ScoreBreakdownRow(
                label = "거리 접근성",
                score = breakdown.distanceScore,
                maxScore = breakdown.maxDistanceScore
            )
            ScoreBreakdownRow(
                label = "충전소 정보",
                score = breakdown.chargerInfoScore,
                maxScore = breakdown.maxChargerInfoScore
            )
            ScoreBreakdownRow(
                label = "횡단보도 접근성",
                score = breakdown.crosswalkScore,
                maxScore = breakdown.maxCrosswalkScore
            )
            ScoreBreakdownRow(
                label = "경사 안전성",
                score = breakdown.slopeScore,
                maxScore = breakdown.maxSlopeScore
            )
            ScoreBreakdownRow(
                label = "이용 편의성",
                score = breakdown.convenienceScore,
                maxScore = breakdown.maxConvenienceScore
            )
        }
    }
}

@Composable
private fun ScoreBreakdownRow(
    label: String,
    score: Int,
    maxScore: Int
) {
    val progress = if (maxScore > 0) {
        (score.toFloat() / maxScore.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "$score/$maxScore",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.outlineVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(6.dp)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

@Composable
private fun RecommendationReasonSummary(reasons: List<String>) {
    val blockedLabels = listOf(
        "보행자전용도로",
        "보호구역",
        "편의시설",
        "기상청"
    )
    val visibleReasons = reasons
        .filterNot { reason ->
            blockedLabels.any { blocked -> reason.contains(blocked) }
        }
        .take(5)
    LaunchedEffect(visibleReasons) {
        Log.d(LOG_TAG, "추천 사유 렌더링: count=${visibleReasons.size}")
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(
                text = "추천 이유",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            if (visibleReasons.isEmpty()) {
                Text(
                    text = "추천 근거 정보 없음",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                visibleReasons.forEach { reason ->
                    Text(
                        text = "• $reason",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun PublicDataAccessibilitySummary(
    context: ChargerAccessibilityContext?
) {
    val crosswalk = context?.crosswalk
    val slope = context?.elevationSlope
    LaunchedEffect(context) {
        Log.d(
            LOG_TAG,
            "공공데이터 접근성 영역 렌더링: available=${context != null}"
        )
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "공공데이터 기반 접근성",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            PublicDataInfoRow(
                firstLabel = "주변 횡단보도",
                firstValue = crosswalk?.countWithin150m.countText(),
                secondLabel = "가장 가까운 횡단보도",
                secondValue = crosswalk?.nearestDistanceM?.let(::formatDistance)
                    ?: "정보 없음"
            )
            PublicDataInfoRow(
                firstLabel = "보도턱 낮춤",
                firstValue = crosswalk?.curbCutCount.countText(),
                secondLabel = "점자블록",
                secondValue = crosswalk?.tactileBlockCount.countText()
            )
            PublicDataInfoRow(
                firstLabel = "보행자신호",
                firstValue = crosswalk?.pedestrianSignalCount.countText(),
                secondLabel = "고도",
                secondValue = slope?.elevationM?.let {
                    String.format(Locale.US, "%.1fm", it)
                } ?: "정보 없음"
            )
            PublicDataInfoRow(
                firstLabel = "경사도 추정",
                firstValue = slope?.slopePercentEstimate?.let {
                    String.format(Locale.US, "%.1f%%", it)
                } ?: "정보 없음",
                secondLabel = "경사 위험도",
                secondValue = slope?.slopeRisk.displayOr("정보 없음")
            )
            Text(
                text = "고도와 경사도는 VWorld DEM 90m 기반 추정값입니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PublicDataInfoRow(
    firstLabel: String,
    firstValue: String,
    secondLabel: String,
    secondValue: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PublicDataInfoCell(
            modifier = Modifier.weight(1f),
            label = firstLabel,
            value = firstValue
        )
        PublicDataInfoCell(
            modifier = Modifier.weight(1f),
            label = secondLabel,
            value = secondValue
        )
    }
}

@Composable
private fun PublicDataInfoCell(
    modifier: Modifier,
    label: String,
    value: String
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun RouteSummary(
    isRouteVisible: Boolean,
    routeDistanceText: String?,
    routeDurationText: String?,
    routeSourceText: String?,
    routeMessage: String?,
    routeType: RouteLineType?,
    isRouteLoading: Boolean,
    onShowRoute: () -> Unit,
    onHideRoute: () -> Unit
) {
    LaunchedEffect(isRouteVisible, routeMessage, routeType, isRouteLoading) {
        Log.d(
            LOG_TAG,
            "경로 보기/숨기기 버튼 상태: " +
                when {
                    isRouteLoading -> "경로 불러오는 중"
                    isRouteVisible -> "경로 숨기기"
                    else -> "경로 보기"
                }
        )
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "경로 안내",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            routeSourceText?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            routeDistanceText?.let { text ->
                Text(text = text, fontWeight = FontWeight.Bold)
            }
            routeDurationText?.let { text ->
                Text(text = text, fontWeight = FontWeight.SemiBold)
            }
            if (isRouteLoading) {
                Button(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    enabled = false,
                    onClick = {}
                ) {
                    Text("경로 불러오는 중...")
                }
            } else if (isRouteVisible) {
                OutlinedButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    onClick = onHideRoute
                ) {
                    Text("경로 숨기기")
                }
            } else {
                Button(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    onClick = onShowRoute
                ) {
                    Text("경로 보기")
                }
            }
            routeMessage
                ?.takeIf { it.isNotBlank() }
                ?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            val helperText = when (routeType) {
                RouteLineType.TMAP_PEDESTRIAN ->
                    "TMAP 경로는 실제 현장 상황과 차이가 있을 수 있습니다."
                RouteLineType.FALLBACK_STRAIGHT -> null
                null ->
                    "현재 위치를 기준으로 계단 제외 우선 보행자 경로를 탐색합니다."
            }
            helperText?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun String?.displayOr(fallback: String): String =
    if (this.isNullOrBlank()) fallback else this

private fun Boolean?.booleanLabel(
    trueText: String,
    falseText: String
): String = when (this) {
    true -> trueText
    false -> falseText
    null -> "정보 없음"
}

private fun Int?.countText(): String = this?.let { "${it}개" } ?: "정보 없음"

private fun accessibilityGradeColor(grade: String): Color = when (grade) {
    "적극 추천" -> Color(0xFF047857)
    "이용 추천" -> Color(0xFF2563EB)
    "확인 후 이용" -> Color(0xFFF59E0B)
    else -> Color(0xFFDC2626)
}

private fun buildApiUrl(path: String): String = ApiConfig.BASE_URL + path

private fun buildNearestApiUrl(
    lat: Double = TEST_LAT,
    lng: Double = TEST_LNG
): String =
    buildApiUrl(NEAREST_PATH) + "?lat=$lat&lng=$lng&limit=$NEAREST_LIMIT"

private fun buildChargerListText(
    chargers: List<Charger>,
    origin: ChargerDataOrigin,
    fallbackUsed: Boolean
): String {
    val sourceHeader = buildDataSourceHeader(
        origin = origin,
        fallbackUsed = fallbackUsed,
        fallbackMessage = "서버 연결 실패로 로컬 데이터를 사용합니다."
    )

    if (chargers.isEmpty()) {
        return "$sourceHeader\n\n충전소 데이터가 없습니다."
    }

    return "$sourceHeader\n\n" +
        chargers.joinToString(separator = "\n\n") { charger ->
            "시설명: ${charger.name}\n" +
                "주소: ${charger.address}\n" +
                "설치구분: ${charger.install_type ?: "정보 없음"}\n" +
                "문의처: ${charger.contact_phone ?: "정보 없음"}"
        }
}

private fun buildNearestChargerHeaderText(
    chargers: List<Charger>,
    origin: ChargerDataOrigin,
    fallbackUsed: Boolean
): String {
    val sourceHeader = buildDataSourceHeader(
        origin = origin,
        fallbackUsed = fallbackUsed,
        fallbackMessage = "서버 연결 실패로 로컬 추천 결과를 사용합니다."
    )
    val header = "가까운 충전소 TOP $NEAREST_LIMIT\n$sourceHeader"
    return if (chargers.isEmpty()) {
        "$header\n\n가까운 충전소 데이터가 없습니다."
    } else {
        header
    }
}

private fun buildCurrentLocationNearestHeaderText(
    chargers: List<Charger>,
    origin: ChargerDataOrigin
): String {
    val source = formatDataOrigin(origin)
    val header = "현재 위치 기준 추천 완료 · 데이터: $source"
    return if (chargers.isEmpty()) {
        "$header\n가까운 충전소 데이터가 없습니다."
    } else {
        header
    }
}

private fun buildNearestChargerListText(
    chargers: List<Charger>,
    origin: ChargerDataOrigin,
    fallbackUsed: Boolean
): String {
    val sourceHeader = buildDataSourceHeader(
        origin = origin,
        fallbackUsed = fallbackUsed,
        fallbackMessage = "서버 연결 실패로 로컬 추천 결과를 사용합니다."
    )

    if (chargers.isEmpty()) {
        return "$sourceHeader\n\n가까운 충전소 데이터가 없습니다."
    }

    return "가까운 충전소 TOP $NEAREST_LIMIT\n" +
        "$sourceHeader\n\n" +
        buildNearestChargerItemsText(chargers)
}

private fun buildCurrentLocationNearestListText(
    chargers: List<Charger>,
    lat: Double,
    lng: Double,
    origin: ChargerDataOrigin,
    fallbackUsed: Boolean
): String {
    val sourceHeader = buildDataSourceHeader(
        origin = origin,
        fallbackUsed = fallbackUsed,
        fallbackMessage = "서버 연결 실패로 로컬 추천 결과를 사용합니다."
    )
    val header = "현재 위치 기준 가까운 충전소 TOP $NEAREST_LIMIT\n" +
        "기준 위치: 현재 위치\n" +
        "위도: ${formatCoordinate(lat)} / 경도: ${formatCoordinate(lng)}\n" +
        sourceHeader

    if (chargers.isEmpty()) {
        return "$header\n\n가까운 충전소 데이터가 없습니다."
    }

    return "$header\n\n" + buildNearestChargerItemsText(chargers)
}

private fun buildDataSourceHeader(
    origin: ChargerDataOrigin,
    fallbackUsed: Boolean,
    fallbackMessage: String
): String {
    val source = formatDataOrigin(origin)

    return if (fallbackUsed) {
        "$fallbackMessage\n데이터 출처: $source"
    } else {
        "데이터 출처: $source"
    }
}

private fun formatDataOrigin(origin: ChargerDataOrigin): String = when (origin) {
    ChargerDataOrigin.SERVER -> "서버"
    ChargerDataOrigin.LOCAL_JSON -> "로컬 JSON"
}

private fun buildNearestChargerItemsText(chargers: List<Charger>): String {
    return chargers.take(NEAREST_LIMIT).mapIndexed { index, charger ->
        "${index + 1}위\n" +
            "시설명: ${charger.name}\n" +
            "거리: ${formatDistance(charger.distance_m)}\n" +
            "주소: ${charger.address}\n" +
            "설치구분: ${charger.install_type ?: "정보 없음"}\n" +
            "설치 위치 설명: ${charger.install_place ?: "정보 없음"}\n" +
            "문의처: ${charger.contact_phone ?: "정보 없음"}"
    }.joinToString(separator = "\n\n")
}

private fun calculateAccessibilityForReference(
    charger: Charger,
    reference: MapCoordinate,
    publicDataContext: ChargerAccessibilityContext?,
    basisLocationText: String
): AccessibilityScoreResult {
    val chargerLat = charger.lat
    val chargerLng = charger.lng
    val distanceM = if (
        chargerLat != null &&
        chargerLng != null &&
        isValidCoordinate(chargerLat, chargerLng) &&
        isValidCoordinate(reference.lat, reference.lng)
    ) {
        haversineDistanceMeters(
            startLat = reference.lat,
            startLng = reference.lng,
            endLat = chargerLat,
            endLng = chargerLng
        )
    } else {
        null
    }

    return calculateAccessibilityScore(
        charger = charger,
        distanceM = distanceM,
        context = publicDataContext,
        basisLocationText = basisLocationText
    )
}

private data class ChargerMatch(
    val charger: Charger,
    val index: Int,
    val strategy: String
)

private fun findMatchingMapCharger(
    mapChargers: List<Charger>,
    target: Charger,
    fallbackIndex: Int?
): ChargerMatch? {
    val targetId = target.id.normalized()
    if (targetId.isNotEmpty()) {
        val idIndex = mapChargers.indexOfFirst {
            it.id.normalized() == targetId
        }
        if (idIndex >= 0) {
            return ChargerMatch(
                charger = mapChargers[idIndex],
                index = idIndex,
                strategy = "id"
            )
        }
    }

    val targetName = target.name.normalized()
    val targetAddress = target.address.normalized()
    if (targetName.isNotEmpty() && targetAddress.isNotEmpty()) {
        val nameAddressIndex = mapChargers.indexOfFirst {
            it.name.normalized() == targetName &&
                it.address.normalized() == targetAddress
        }
        if (nameAddressIndex >= 0) {
            return ChargerMatch(
                charger = mapChargers[nameAddressIndex],
                index = nameAddressIndex,
                strategy = "name+address"
            )
        }
    }

    val index = fallbackIndex ?: return null
    val charger = mapChargers.getOrNull(index) ?: return null
    return ChargerMatch(
        charger = charger,
        index = index,
        strategy = "index"
    )
}

private fun sameCharger(first: Charger, second: Charger): Boolean {
    if (first === second || first == second) {
        return true
    }

    val firstId = first.id.normalized()
    val secondId = second.id.normalized()
    if (firstId.isNotEmpty() && firstId == secondId) {
        return true
    }

    val firstName = first.name.normalized()
    val secondName = second.name.normalized()
    val firstAddress = first.address.normalized()
    val secondAddress = second.address.normalized()
    return firstName.isNotEmpty() &&
        firstAddress.isNotEmpty() &&
        firstName == secondName &&
        firstAddress == secondAddress
}

private fun String?.normalized(): String = this?.trim()?.lowercase().orEmpty()

private fun isValidCoordinate(lat: Double, lng: Double): Boolean =
    lat.isFinite() && lng.isFinite() &&
        lat in -90.0..90.0 && lng in -180.0..180.0

private fun formatCoordinate(coordinate: Double): String =
    String.format(Locale.US, "%.6f", coordinate)

private fun formatDistance(distanceMeters: Double?): String {
    if (distanceMeters == null) {
        return "거리 정보 없음"
    }

    return if (distanceMeters < 1000.0) {
        "${distanceMeters.roundToInt()}m"
    } else {
        String.format(Locale.US, "%.1fkm", distanceMeters / 1000.0)
    }
}
