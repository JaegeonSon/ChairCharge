package com.example.chaircharge

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.chaircharge.data.Charger
import com.example.chaircharge.data.ChargerDataOrigin
import com.example.chaircharge.data.ChargerRepository
import com.example.chaircharge.data.haversineDistanceMeters
import com.example.chaircharge.network.ApiConfig
import com.example.chaircharge.network.tmap.TmapRouteRepository
import com.example.chaircharge.network.tmap.TmapRouteRequest
import com.example.chaircharge.publicdata.AccessibilityPublicDataSource
import com.example.chaircharge.publicdata.ElevationSlopeInfo
import com.example.chaircharge.ui.map.MapCameraRequest
import com.example.chaircharge.ui.theme.ChairChargeTheme
import com.example.chaircharge.ui.map.MapCoordinate
import com.example.chaircharge.ui.map.RouteLineRequest
import com.example.chaircharge.ui.map.RouteLineType
import com.example.chaircharge.ui.map.WheelChargeKakaoMap
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.ceil

private const val LOG_TAG = "WheelCharge"
private const val TEST_LAT = 35.9676
private const val TEST_LNG = 126.7368
private const val SELECTED_CHARGER_ZOOM_LEVEL = 16
private const val NAVIGATION_LOCATION_INTERVAL_MS = 3_000L
private const val NAVIGATION_MIN_LOCATION_INTERVAL_MS = 2_000L
private const val NAVIGATION_ARRIVAL_DISTANCE_METERS = 30.0
private const val NAVIGATION_OFF_ROUTE_DISTANCE_METERS = 50.0
private const val NAVIGATION_SPEED_METERS_PER_HOUR = 4_000.0
private const val TTS_DUPLICATE_COOLDOWN_MS = 5_000L
private const val TTS_OFF_ROUTE_COOLDOWN_MS = 30_000L

private enum class NavigationState {
    IDLE,
    ROUTE_LOADING,
    NAVIGATING,
    ARRIVED,
    OFF_ROUTE,
    ERROR
}

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
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val fusedLocationClient = remember(context) {
        LocationServices.getFusedLocationProviderClient(context)
    }
    val chargerRepository = remember(context) {
        ChargerRepository(context)
    }
    val tmapRouteRepository = remember { TmapRouteRepository() }
    val elevationSlopeDataSource = remember(context) {
        AccessibilityPublicDataSource(context)
    }
    var elevationSlopeItems by remember {
        mutableStateOf(emptyList<ElevationSlopeInfo>())
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
    var isBottomSheetExpanded by remember { mutableStateOf(false) }
    var navigationState by remember { mutableStateOf(NavigationState.IDLE) }
    var isNavigationMode by remember { mutableStateOf(false) }
    var navigationTargetCharger by remember { mutableStateOf<Charger?>(null) }
    var navigationRoutePoints by remember {
        mutableStateOf(emptyList<MapCoordinate>())
    }
    var navigationRemainingDistanceM by remember { mutableStateOf<Double?>(null) }
    var navigationEstimatedTimeText by remember { mutableStateOf<String?>(null) }
    var navigationStatusMessage by remember { mutableStateOf<String?>(null) }
    var navigationStartPending by remember { mutableStateOf(false) }
    var textToSpeech by remember { mutableStateOf<TextToSpeech?>(null) }
    var isTtsInitialized by remember { mutableStateOf(false) }
    var lastTtsMessage by remember { mutableStateOf<String?>(null) }
    var lastTtsSpokenTime by remember { mutableStateOf(0L) }
    var ttsUtteranceSequence by remember { mutableStateOf(0L) }
    var hasSpokenStart by remember { mutableStateOf(false) }
    var hasSpoken500m by remember { mutableStateOf(false) }
    var hasSpoken300m by remember { mutableStateOf(false) }
    var hasSpoken100m by remember { mutableStateOf(false) }
    var hasSpokenArrived by remember { mutableStateOf(false) }
    var lastOffRouteSpokenTime by remember { mutableStateOf(0L) }
    var speechRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    var isVoiceRecognitionActive by remember { mutableStateOf(false) }
    val selectedDistanceText = remember(selectedCharger, mapCurrentLocation) {
        formatDistanceFromCurrentLocation(
            charger = selectedCharger,
            currentLocation = mapCurrentLocation
        )
    }
    val selectedDestinationSlopeRisk = remember(
        selectedCharger,
        elevationSlopeItems
    ) {
        findDestinationSlopeRisk(
            charger = selectedCharger,
            elevationSlopeItems = elevationSlopeItems
        )
    }

    DisposableEffect(context) {
        var disposed = false
        lateinit var engine: TextToSpeech
        engine = TextToSpeech(context.applicationContext) { status ->
            if (!disposed) {
                if (status == TextToSpeech.SUCCESS) {
                    runCatching {
                        val languageStatus = engine.setLanguage(Locale.KOREAN)
                        check(
                            languageStatus != TextToSpeech.LANG_MISSING_DATA &&
                                languageStatus != TextToSpeech.LANG_NOT_SUPPORTED
                        ) {
                            "Korean TTS language is unavailable"
                        }
                        engine.setSpeechRate(0.95f)
                        engine.setPitch(1.0f)
                        isTtsInitialized = true
                        Log.d(LOG_TAG, "TTS initialized")
                    }.onFailure { exception ->
                        isTtsInitialized = false
                        Log.e(
                            LOG_TAG,
                            "TTS initialization failed",
                            exception
                        )
                    }
                } else {
                    isTtsInitialized = false
                    Log.e(
                        LOG_TAG,
                        "TTS initialization failed",
                        IllegalStateException("TextToSpeech init status=$status")
                    )
                }
            }
        }
        textToSpeech = engine

        onDispose {
            disposed = true
            isTtsInitialized = false
            textToSpeech = null
            runCatching {
                engine.stop()
                Log.d(LOG_TAG, "TTS stopped")
            }.onFailure { exception ->
                Log.e(LOG_TAG, "TTS stop failed", exception)
            }
            runCatching {
                engine.shutdown()
                Log.d(LOG_TAG, "TTS shutdown")
            }.onFailure { exception ->
                Log.e(LOG_TAG, "TTS shutdown failed", exception)
            }
        }
    }

    fun stopTtsPlayback() {
        val engine = textToSpeech ?: return
        runCatching {
            engine.stop()
            Log.d(LOG_TAG, "TTS stopped")
        }.onFailure { exception ->
            Log.e(LOG_TAG, "TTS stop failed", exception)
        }
    }

    fun speakNavigationMessage(
        message: String,
        flushQueue: Boolean = false
    ): Boolean {
        val engine = textToSpeech
        if (!isTtsInitialized || engine == null || message.isBlank()) {
            return false
        }
        val now = System.currentTimeMillis()
        if (
            message == lastTtsMessage &&
            now - lastTtsSpokenTime < TTS_DUPLICATE_COOLDOWN_MS
        ) {
            return false
        }

        return runCatching {
            ttsUtteranceSequence += 1
            val result = engine.speak(
                message,
                if (flushQueue) {
                    TextToSpeech.QUEUE_FLUSH
                } else {
                    TextToSpeech.QUEUE_ADD
                },
                null,
                "wheelcharge_navigation_$ttsUtteranceSequence"
            )
            check(result != TextToSpeech.ERROR) {
                "TextToSpeech speak returned ERROR"
            }
            lastTtsMessage = message
            lastTtsSpokenTime = now
            Log.d(LOG_TAG, "TTS speak start")
            true
        }.getOrElse { exception ->
            Log.e(LOG_TAG, "TTS speak failed", exception)
            false
        }
    }

    fun resetNavigationSpeechProgress() {
        hasSpokenStart = false
        hasSpoken500m = false
        hasSpoken300m = false
        hasSpoken100m = false
        hasSpokenArrived = false
        lastOffRouteSpokenTime = 0L
        lastTtsMessage = null
        lastTtsSpokenTime = 0L
    }

    fun speakNavigationStartIfNeeded() {
        if (hasSpokenStart) {
            return
        }
        val targetName = navigationTargetCharger
            ?.name
            ?.takeIf { it.isNotBlank() }
            ?: "선택한 충전소"
        if (
            speakNavigationMessage(
                "경로 안내를 시작합니다. 목적지는 ${targetName}입니다."
            )
        ) {
            hasSpokenStart = true
            Log.d(LOG_TAG, "TTS navigation start spoken")
        }
    }

    fun speakDistanceGuideIfNeeded(remainingDistanceM: Double) {
        val spoken = when {
            remainingDistanceM <= 100.0 && !hasSpoken100m -> {
                speakNavigationMessage(
                    "목적지까지 약 100미터 남았습니다."
                ).also { succeeded ->
                    if (succeeded) {
                        hasSpoken500m = true
                        hasSpoken300m = true
                        hasSpoken100m = true
                    }
                }
            }
            remainingDistanceM <= 300.0 && !hasSpoken300m -> {
                speakNavigationMessage(
                    "목적지까지 약 300미터 남았습니다."
                ).also { succeeded ->
                    if (succeeded) {
                        hasSpoken500m = true
                        hasSpoken300m = true
                    }
                }
            }
            remainingDistanceM <= 500.0 && !hasSpoken500m -> {
                speakNavigationMessage(
                    "목적지까지 약 500미터 남았습니다."
                ).also { succeeded ->
                    if (succeeded) {
                        hasSpoken500m = true
                    }
                }
            }
            else -> false
        }
        if (spoken) {
            Log.d(LOG_TAG, "TTS distance guide spoken")
        }
    }

    fun speakArrivedIfNeeded() {
        if (
            !hasSpokenArrived &&
            speakNavigationMessage(
                "목적지 근처에 도착했습니다. 충전소 위치를 확인해 주세요."
            )
        ) {
            hasSpokenArrived = true
            Log.d(LOG_TAG, "TTS arrived spoken")
        }
    }

    fun speakOffRouteIfAllowed() {
        val now = System.currentTimeMillis()
        if (
            now - lastOffRouteSpokenTime >= TTS_OFF_ROUTE_COOLDOWN_MS &&
            speakNavigationMessage(
                "경로에서 벗어난 것으로 보입니다. 지도를 확인해 주세요."
            )
        ) {
            lastOffRouteSpokenTime = now
        }
    }

    LaunchedEffect(
        isTtsInitialized,
        navigationState,
        navigationTargetCharger
    ) {
        if (!isTtsInitialized) {
            return@LaunchedEffect
        }
        when (navigationState) {
            NavigationState.NAVIGATING -> speakNavigationStartIfNeeded()
            NavigationState.OFF_ROUTE -> {
                speakNavigationStartIfNeeded()
                speakOffRouteIfAllowed()
            }
            NavigationState.ARRIVED -> {
                speakNavigationStartIfNeeded()
                speakArrivedIfNeeded()
            }
            else -> Unit
        }
    }

    val navigationLocationCallback = remember(fusedLocationClient) {
        object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (!isNavigationMode) {
                    return
                }
                val location = result.lastLocation ?: return
                val currentLocation = MapCoordinate(
                    lat = location.latitude,
                    lng = location.longitude
                )
                if (!isValidCoordinate(currentLocation.lat, currentLocation.lng)) {
                    navigationState = NavigationState.ERROR
                    navigationStatusMessage = "현재 위치 정보가 올바르지 않습니다."
                    isNavigationMode = false
                    fusedLocationClient.removeLocationUpdates(this)
                    Log.e(
                        LOG_TAG,
                        "Navigation error",
                        IllegalArgumentException("Invalid navigation location")
                    )
                    return
                }

                Log.d(LOG_TAG, "Navigation location update received")
                speakNavigationStartIfNeeded()
                mapCurrentLocation = currentLocation
                mapUsesCurrentLocation = true
                myLocationErrorMessage = null

                val target = navigationTargetCharger
                val targetLat = target?.lat
                val targetLng = target?.lng
                if (
                    targetLat == null ||
                    targetLng == null ||
                    !isValidCoordinate(targetLat, targetLng)
                ) {
                    navigationState = NavigationState.ERROR
                    navigationStatusMessage = "목적지 위치 정보를 확인할 수 없습니다."
                    isNavigationMode = false
                    fusedLocationClient.removeLocationUpdates(this)
                    Log.e(
                        LOG_TAG,
                        "Navigation error",
                        IllegalStateException("Navigation target is unavailable")
                    )
                    return
                }

                val remainingDistance = haversineDistanceMeters(
                    startLat = currentLocation.lat,
                    startLng = currentLocation.lng,
                    endLat = targetLat,
                    endLng = targetLng
                )
                navigationRemainingDistanceM = remainingDistance
                navigationEstimatedTimeText =
                    calculateNavigationEstimatedTimeText(remainingDistance)
                Log.d(
                    LOG_TAG,
                    "Navigation remaining distance: $remainingDistance"
                )

                if (remainingDistance <= NAVIGATION_ARRIVAL_DISTANCE_METERS) {
                    navigationState = NavigationState.ARRIVED
                    navigationStatusMessage =
                        "목적지 근처에 도착했습니다. 충전소 위치를 확인해 주세요."
                    speakArrivedIfNeeded()
                    fusedLocationClient.removeLocationUpdates(this)
                        .addOnCompleteListener {
                            Log.d(
                                LOG_TAG,
                                "Navigation location updates removed"
                            )
                        }
                    Log.d(LOG_TAG, "Navigation arrived")
                    return
                }

                speakDistanceGuideIfNeeded(remainingDistance)
                val offRoute = navigationRoutePoints.size > 2 &&
                    isLocationOffRoute(
                        currentLocation = currentLocation,
                        routePoints = navigationRoutePoints,
                        thresholdMeters = NAVIGATION_OFF_ROUTE_DISTANCE_METERS
                    )
                if (offRoute) {
                    navigationState = NavigationState.OFF_ROUTE
                    navigationStatusMessage =
                        "경로에서 벗어난 것으로 보입니다. 지도를 확인해 주세요."
                    speakOffRouteIfAllowed()
                    Log.d(LOG_TAG, "Navigation off route detected")
                } else {
                    navigationState = NavigationState.NAVIGATING
                    navigationStatusMessage = "경로 안내 중입니다. 지도를 확인해 주세요."
                }
            }
        }
    }

    fun removeNavigationLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(navigationLocationCallback)
            .addOnCompleteListener {
                Log.d(LOG_TAG, "Navigation location updates removed")
            }
    }

    fun stopNavigation(stopSpeech: Boolean = true) {
        if (stopSpeech) {
            stopTtsPlayback()
        }
        navigationStartPending = false
        removeNavigationLocationUpdates()
        navigationState = NavigationState.IDLE
        isNavigationMode = false
        navigationTargetCharger = null
        navigationRoutePoints = emptyList()
        navigationRemainingDistanceM = null
        navigationEstimatedTimeText = null
        navigationStatusMessage = null
        resetNavigationSpeechProgress()
        Log.d(LOG_TAG, "Navigation stopped")
    }

    fun stopNavigationWithSpeech() {
        stopTtsPlayback()
        speakNavigationMessage(
            message = "경로 안내를 종료합니다.",
            flushQueue = true
        )
        stopNavigation(stopSpeech = false)
    }

    @SuppressLint("MissingPermission")
    fun startNavigationLocationUpdates() {
        val fineLocationGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseLocationGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fineLocationGranted && !coarseLocationGranted) {
            navigationStartPending = false
            navigationState = NavigationState.ERROR
            isNavigationMode = false
            navigationStatusMessage = "현재 위치를 먼저 확인해 주세요."
            speakNavigationMessage("현재 위치를 먼저 확인해 주세요.")
            Log.e(
                LOG_TAG,
                "Navigation error",
                SecurityException("Location permission is not granted")
            )
            return
        }

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            NAVIGATION_LOCATION_INTERVAL_MS
        )
            .setMinUpdateIntervalMillis(NAVIGATION_MIN_LOCATION_INTERVAL_MS)
            .build()
        fusedLocationClient.requestLocationUpdates(
            request,
            navigationLocationCallback,
            Looper.getMainLooper()
        ).addOnSuccessListener {
            if (!navigationStartPending) {
                removeNavigationLocationUpdates()
                return@addOnSuccessListener
            }
            navigationStartPending = false
            navigationState = NavigationState.NAVIGATING
            isNavigationMode = true
            navigationStatusMessage = "경로 안내 중입니다. 지도를 확인해 주세요."
            speakNavigationStartIfNeeded()
            Log.d(LOG_TAG, "Navigation mode started")
        }.addOnFailureListener { exception ->
            navigationStartPending = false
            navigationState = NavigationState.ERROR
            isNavigationMode = false
            navigationStatusMessage = "위치 업데이트를 시작하지 못했습니다."
            Log.e(LOG_TAG, "Navigation error", exception)
        }
    }

    fun activateNavigationWithRoute(
        target: Charger,
        routePoints: List<MapCoordinate>
    ) {
        if (!navigationStartPending) {
            return
        }
        val currentLocation = mapCurrentLocation
        val targetLat = target.lat
        val targetLng = target.lng
        if (
            currentLocation == null ||
            targetLat == null ||
            targetLng == null ||
            !isValidCoordinate(currentLocation.lat, currentLocation.lng) ||
            !isValidCoordinate(targetLat, targetLng)
        ) {
            navigationStartPending = false
            navigationState = NavigationState.ERROR
            isNavigationMode = false
            navigationStatusMessage = "현재 위치를 먼저 확인해 주세요."
            speakNavigationMessage("현재 위치를 먼저 확인해 주세요.")
            Log.e(
                LOG_TAG,
                "Navigation error",
                IllegalStateException("Navigation start coordinates unavailable")
            )
            return
        }

        navigationTargetCharger = target
        navigationRoutePoints = routePoints
        val remainingDistance = haversineDistanceMeters(
            startLat = currentLocation.lat,
            startLng = currentLocation.lng,
            endLat = targetLat,
            endLng = targetLng
        )
        navigationRemainingDistanceM = remainingDistance
        navigationEstimatedTimeText =
            calculateNavigationEstimatedTimeText(remainingDistance)

        if (remainingDistance <= NAVIGATION_ARRIVAL_DISTANCE_METERS) {
            navigationStartPending = false
            navigationState = NavigationState.ARRIVED
            isNavigationMode = true
            navigationStatusMessage =
                "목적지 근처에 도착했습니다. 충전소 위치를 확인해 주세요."
            speakNavigationStartIfNeeded()
            speakArrivedIfNeeded()
            Log.d(LOG_TAG, "Navigation mode started")
            Log.d(LOG_TAG, "Navigation arrived")
            return
        }

        navigationState = NavigationState.ROUTE_LOADING
        navigationStatusMessage = "위치 업데이트를 시작하는 중입니다."
        startNavigationLocationUpdates()
    }

    DisposableEffect(context, fusedLocationClient, navigationLocationCallback) {
        val activity = context as? ComponentActivity
        val observer = LifecycleEventObserver { _, event ->
            if (
                event == Lifecycle.Event.ON_STOP &&
                (isNavigationMode || navigationStartPending)
            ) {
                stopNavigation()
            }
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose {
            activity?.lifecycle?.removeObserver(observer)
            fusedLocationClient.removeLocationUpdates(navigationLocationCallback)
            Log.d(LOG_TAG, "Navigation location updates removed")
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
        speakNavigationMessage(
            "보행자 경로를 불러오지 못해 직선 경로를 표시합니다."
        )
        routeType = RouteLineType.FALLBACK_STRAIGHT
        isRouteLoading = false
        isRouteVisible = true
        routeLineRequest = RouteLineRequest(
            requestId = requestId,
            points = listOf(start, end),
            type = RouteLineType.FALLBACK_STRAIGHT
        )
        navigationTargetCharger?.let { target ->
            activateNavigationWithRoute(
                target = target,
                routePoints = listOf(start, end)
            )
        }
        Log.d(LOG_TAG, "Route fallback used")
        Log.d(LOG_TAG, "fallback straight route used")
        Log.d(
            LOG_TAG,
            "fallback straight distance: $distanceMeters, requestId=$requestId"
        )
    }

    fun applySelectedCharger(
        charger: Charger,
        mapIndex: Int?,
        source: String
    ) {
        val selectionChanged =
            selectedCharger?.let { !sameCharger(it, charger) } == true
        if (selectionChanged) {
            if (isNavigationMode || navigationStartPending) {
                stopNavigation()
            }
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
            speakNavigationMessage("현재 위치를 먼저 확인해 주세요.")
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
        routeSourceText = "경로: TMAP 보행자 경로"
        routeDistanceText = null
        routeDurationText = null
        routeMessage = "경로를 불러오는 중입니다..."

        val directDistanceM = haversineDistanceMeters(
            startLat = start.lat,
            startLng = start.lng,
            endLat = end.lat,
            endLng = end.lng
        )
        tmapRouteRepository.requestBestPedestrianRoute(
            request = TmapRouteRequest(
                startX = start.lng,
                startY = start.lat,
                endX = end.lng,
                endY = end.lat,
                startName = "현재 위치",
                endName = charger.name,
            ),
            directDistanceM = directDistanceM,
            destinationSlopeRisk = selectedDestinationSlopeRisk
        ) { result ->
            if (requestId != routeRequestSequence) {
                Log.d(LOG_TAG, "TMAP route result ignored: stale request")
                return@requestBestPedestrianRoute
            }
            result.fold(
                onSuccess = { selection ->
                    val selectedRoute = selection.selectedRoute
                    val points = selectedRoute.routePoints.map { point ->
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
                    routeDistanceText = selectedRoute.distanceM?.let { distance ->
                        "거리: ${formatDistance(distance)}"
                    } ?: "거리: 정보 없음"
                    routeDurationText = selectedRoute.durationS?.let { seconds ->
                        val minutes = ceil(seconds / 60.0).toInt().coerceAtLeast(1)
                        "예상 시간: 약 ${minutes}분"
                    } ?: "예상 시간: 정보 없음"
                    routeSourceText = "경로: TMAP 보행자 경로"
                    routeMessage = "실제 보행자 경로를 표시합니다."
                    routeType = RouteLineType.TMAP_PEDESTRIAN
                    isRouteLoading = false
                    isRouteVisible = true
                    routeLineRequest = RouteLineRequest(
                        requestId = requestId,
                        points = points,
                        type = RouteLineType.TMAP_PEDESTRIAN
                    )
                    navigationTargetCharger?.let { target ->
                        activateNavigationWithRoute(
                            target = target,
                            routePoints = points
                        )
                    }
                    Log.d(
                        LOG_TAG,
                        "TMAP route success: requestId=$requestId, " +
                            "points=${points.size}"
                    )
                },
                onFailure = { exception ->
                    Log.e(LOG_TAG, "TMAP route candidates failed", exception)
                    showStraightRouteFallback(start, end, requestId)
                }
            )
        }
    }

    fun requestNavigationStart() {
        Log.d(LOG_TAG, "Navigation start requested")
        val charger = selectedCharger
        if (charger == null) {
            navigationState = NavigationState.ERROR
            navigationStatusMessage = "경로를 안내할 충전소를 먼저 선택해 주세요."
            Log.e(
                LOG_TAG,
                "Navigation error",
                IllegalStateException("Navigation target is not selected")
            )
            return
        }

        val currentLocation = mapCurrentLocation?.takeIf {
            isValidCoordinate(it.lat, it.lng)
        }
        if (currentLocation == null) {
            navigationState = NavigationState.ERROR
            navigationStatusMessage = "현재 위치를 먼저 확인해 주세요."
            speakNavigationMessage("현재 위치를 먼저 확인해 주세요.")
            Log.e(
                LOG_TAG,
                "Navigation error",
                IllegalStateException("Current location is unavailable")
            )
            return
        }

        val targetLat = charger.lat
        val targetLng = charger.lng
        if (
            targetLat == null ||
            targetLng == null ||
            !isValidCoordinate(targetLat, targetLng)
        ) {
            navigationState = NavigationState.ERROR
            navigationStatusMessage = "충전소 위치 정보를 확인할 수 없습니다."
            Log.e(
                LOG_TAG,
                "Navigation error",
                IllegalArgumentException("Navigation target coordinates invalid")
            )
            return
        }

        resetNavigationSpeechProgress()
        navigationTargetCharger = charger
        navigationStartPending = true
        navigationState = NavigationState.ROUTE_LOADING
        navigationStatusMessage = "경로를 준비하는 중입니다."
        val initialDistance = haversineDistanceMeters(
            startLat = currentLocation.lat,
            startLng = currentLocation.lng,
            endLat = targetLat,
            endLng = targetLng
        )
        navigationRemainingDistanceM = initialDistance
        navigationEstimatedTimeText =
            calculateNavigationEstimatedTimeText(initialDistance)

        val existingRoute = routeLineRequest
            ?.takeIf { it.points.size >= 2 }
        if (existingRoute != null) {
            activateNavigationWithRoute(
                target = charger,
                routePoints = existingRoute.points
            )
        } else {
            showRouteToSelectedCharger()
        }
    }

    fun handleRecognizedVoiceCommand(command: String) {
        val compactCommand = command
            .lowercase(Locale.KOREAN)
            .replace(" ", "")
        val requestsNearestCharger =
            compactCommand.contains("가까운") &&
                compactCommand.contains("충전소")
        val requestsNavigation =
            compactCommand.contains("충전소") &&
                listOf("추천", "안내", "경로", "시작")
                    .any(compactCommand::contains)
        if (!requestsNearestCharger && !requestsNavigation) {
            speakNavigationMessage(
                "가까운 충전소 안내라고 말씀해 주세요.",
                flushQueue = true
            )
            return
        }

        val currentLocation = mapCurrentLocation?.takeIf {
            isValidCoordinate(it.lat, it.lng)
        }
        if (currentLocation == null) {
            navigationState = NavigationState.ERROR
            navigationStatusMessage = "현재 위치를 먼저 확인해 주세요."
            speakNavigationMessage(
                "현재 위치를 먼저 확인해 주세요.",
                flushQueue = true
            )
            return
        }

        val nearestMatch = mapChargers.mapIndexedNotNull { index, charger ->
            val lat = charger.lat
            val lng = charger.lng
            if (
                lat == null ||
                lng == null ||
                !isValidCoordinate(lat, lng)
            ) {
                null
            } else {
                val distance = haversineDistanceMeters(
                    startLat = currentLocation.lat,
                    startLng = currentLocation.lng,
                    endLat = lat,
                    endLng = lng
                )
                ChargerMatch(
                    charger = charger,
                    index = index,
                    strategy = "voice-nearest:$distance"
                )
            }
        }.minByOrNull { match ->
            val lat = requireNotNull(match.charger.lat)
            val lng = requireNotNull(match.charger.lng)
            haversineDistanceMeters(
                startLat = currentLocation.lat,
                startLng = currentLocation.lng,
                endLat = lat,
                endLng = lng
            )
        }
        if (nearestMatch == null) {
            speakNavigationMessage(
                "충전소 정보를 불러오지 못했습니다.",
                flushQueue = true
            )
            return
        }

        val charger = nearestMatch.charger
        applySelectedCharger(
            charger = charger,
            mapIndex = nearestMatch.index,
            source = "VOICE_RECOGNITION"
        )
        val lat = requireNotNull(charger.lat)
        val lng = requireNotNull(charger.lng)
        cameraRequestSequence += 1
        cameraRequest = MapCameraRequest(
            requestId = cameraRequestSequence,
            target = MapCoordinate(lat = lat, lng = lng),
            zoomLevel = SELECTED_CHARGER_ZOOM_LEVEL
        )
        requestNavigationStart()
    }

    DisposableEffect(context) {
        val activity = context as? ComponentActivity
        var recognizer: SpeechRecognizer? = null
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            recognizer = runCatching {
                SpeechRecognizer.createSpeechRecognizer(context).also {
                    it.setRecognitionListener(
                        object : RecognitionListener {
                            override fun onReadyForSpeech(params: Bundle?) {
                                isVoiceRecognitionActive = true
                                Log.d(LOG_TAG, "Voice recognition ready")
                            }

                            override fun onBeginningOfSpeech() = Unit

                            override fun onRmsChanged(rmsdB: Float) = Unit

                            override fun onBufferReceived(
                                buffer: ByteArray?
                            ) = Unit

                            override fun onEndOfSpeech() = Unit

                            override fun onError(error: Int) {
                                val wasActive = isVoiceRecognitionActive
                                isVoiceRecognitionActive = false
                                if (
                                    error == SpeechRecognizer.ERROR_CLIENT &&
                                    !wasActive
                                ) {
                                    return
                                }
                                val message = when (error) {
                                    SpeechRecognizer.ERROR_NO_MATCH,
                                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                        "음성 명령을 인식하지 못했습니다. 다시 말씀해 주세요."
                                    else -> "음성 인식을 사용할 수 없습니다."
                                }
                                speakNavigationMessage(
                                    message = message,
                                    flushQueue = true
                                )
                                Log.e(
                                    LOG_TAG,
                                    "Voice recognition error: code=$error"
                                )
                            }

                            override fun onResults(results: Bundle?) {
                                isVoiceRecognitionActive = false
                                val command = results
                                    ?.getStringArrayList(
                                        SpeechRecognizer.RESULTS_RECOGNITION
                                    )
                                    ?.firstOrNull()
                                    ?.takeIf { it.isNotBlank() }
                                if (command == null) {
                                    speakNavigationMessage(
                                        "음성 명령을 인식하지 못했습니다. 다시 말씀해 주세요.",
                                        flushQueue = true
                                    )
                                } else {
                                    Log.d(
                                        LOG_TAG,
                                        "Voice recognition result received"
                                    )
                                    handleRecognizedVoiceCommand(command)
                                }
                            }

                            override fun onPartialResults(
                                partialResults: Bundle?
                            ) = Unit

                            override fun onEvent(
                                eventType: Int,
                                params: Bundle?
                            ) = Unit
                        }
                    )
                }
            }.onFailure { exception ->
                Log.e(
                    LOG_TAG,
                    "Voice recognition initialization failed",
                    exception
                )
            }.getOrNull()
        }
        speechRecognizer = recognizer

        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                isVoiceRecognitionActive = false
                recognizer?.cancel()
            }
        }
        activity?.lifecycle?.addObserver(observer)

        onDispose {
            activity?.lifecycle?.removeObserver(observer)
            isVoiceRecognitionActive = false
            speechRecognizer = null
            runCatching {
                recognizer?.cancel()
                recognizer?.destroy()
            }.onFailure { exception ->
                Log.e(LOG_TAG, "Voice recognition cleanup failed", exception)
            }
        }
    }

    fun startVoiceRecognition() {
        if (isNavigationMode || navigationStartPending) {
            speakNavigationMessage(
                "경로 안내를 종료한 후 음성 명령을 사용해 주세요.",
                flushQueue = true
            )
            return
        }
        val recognizer = speechRecognizer
        if (
            recognizer == null ||
            !SpeechRecognizer.isRecognitionAvailable(context)
        ) {
            speakNavigationMessage(
                "음성 인식을 사용할 수 없습니다.",
                flushQueue = true
            )
            return
        }

        stopTtsPlayback()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        runCatching {
            isVoiceRecognitionActive = true
            recognizer.startListening(intent)
            Log.d(LOG_TAG, "Voice recognition started")
        }.onFailure { exception ->
            isVoiceRecognitionActive = false
            speakNavigationMessage(
                "음성 인식을 시작하지 못했습니다.",
                flushQueue = true
            )
            Log.e(LOG_TAG, "Voice recognition start failed", exception)
        }
    }

    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startVoiceRecognition()
        } else {
            speakNavigationMessage(
                "음성 인식을 사용하려면 마이크 권한을 허용해 주세요.",
                flushQueue = true
            )
        }
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
                "현재 위치를 표시하려면 위치 권한을 허용해 주세요."
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

    @SuppressLint("MissingPermission")
    fun fetchCurrentLocation() {
        if (!isNavigationMode && !navigationStartPending) {
            clearRoute(reason = "현재 위치 재측정")
        }
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
        }.addOnFailureListener { exception ->
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
            mapCurrentLocation = null
            mapUsesCurrentLocation = false
            myLocationDisplaySucceeded = false
            myLocationErrorMessage =
                "현재 위치를 표시하려면 위치 권한을 허용해 주세요."
            Log.d(LOG_TAG, "현재 위치 재측정 실패: 위치 권한 거부")
        }
    }

    LaunchedEffect(elevationSlopeDataSource) {
        elevationSlopeItems = try {
            withContext(Dispatchers.IO) {
                elevationSlopeDataSource.loadElevationSlopeItems()
            }
        } catch (exception: Exception) {
            Log.e(LOG_TAG, "Elevation slope data load failed", exception)
            emptyList()
        }
    }

    LaunchedEffect(Unit) {
        Log.d(LOG_TAG, "UI 최종 정리 적용")
        Log.d(
            LOG_TAG,
            "삭제된 개발용 버튼 목록: 서버 상태 확인, 전체 충전소"
        )
        Log.d(LOG_TAG, "테스트 좌표 UI 제거 여부: true")
        Log.d(
            LOG_TAG,
            "기존 기능 유지 여부: 지도, 마커, 위치, 상세, 경로, " +
                "로컬 fallback 유지"
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
                onChargerMarkerClick = { charger, index ->
                    Log.d(
                        LOG_TAG,
                        "마커 Charger 선택: id=${charger.id}, index=$index"
                    )
                    applySelectedCharger(
                        charger = charger,
                        mapIndex = index,
                        source = "MAP_MARKER"
                    )
                    val lat = charger.lat
                    val lng = charger.lng
                    if (
                        lat != null &&
                        lng != null &&
                        isValidCoordinate(lat, lng)
                    ) {
                        cameraRequestSequence += 1
                        cameraRequest = MapCameraRequest(
                            requestId = cameraRequestSequence,
                            target = MapCoordinate(lat = lat, lng = lng),
                            zoomLevel = SELECTED_CHARGER_ZOOM_LEVEL
                        )
                    }
                    mapInteractionMessage = "충전소를 선택했습니다."
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
            isVoiceRecognitionActive = isVoiceRecognitionActive,
            isVoiceRecognitionEnabled =
                !isNavigationMode && !navigationStartPending,
            onLocationClick = {
                Log.d(LOG_TAG, "현재 위치 아이콘 버튼 클릭")
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
            onVoiceRecognitionClick = {
                if (isVoiceRecognitionActive) {
                    isVoiceRecognitionActive = false
                    speechRecognizer?.cancel()
                    Log.d(LOG_TAG, "Voice recognition cancelled")
                } else {
                    val microphoneGranted =
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    if (microphoneGranted) {
                        startVoiceRecognition()
                    } else {
                        microphonePermissionLauncher.launch(
                            Manifest.permission.RECORD_AUDIO
                        )
                    }
                }
            }
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .then(
                    if (isBottomSheetExpanded) {
                        Modifier.fillMaxHeight(0.52f)
                    } else if (selectedCharger != null) {
                        Modifier.height(84.dp)
                    } else {
                        Modifier.height(36.dp)
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
                    selectedDistanceText = selectedDistanceText,
                    onExpandedChange = { expanded ->
                        if (selectedCharger != null) {
                            updateBottomSheetExpanded(
                                expanded = expanded,
                                reason = "handle 조작"
                            )
                        }
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
        selectedCharger?.let { charger ->
            ChargerDetailPanel(
                charger = charger,
                distanceText = selectedDistanceText,
                isRouteVisible = isRouteVisible,
                isRouteLoading = isRouteLoading,
                navigationState = navigationState,
                isNavigationMode = isNavigationMode,
                navigationRemainingDistanceM = navigationRemainingDistanceM,
                navigationEstimatedTimeText = navigationEstimatedTimeText,
                navigationStatusMessage = navigationStatusMessage,
                routeMessage = routeMessage,
                onShowRoute = ::showRouteToSelectedCharger,
                onHideRoute = {
                    clearRoute(reason = "사용자 경로 숨기기")
                    mapInteractionMessage = "경로를 숨겼습니다."
                },
                onStartNavigation = ::requestNavigationStart,
                onStopNavigation = ::stopNavigationWithSpeech
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
    isVoiceRecognitionActive: Boolean,
    isVoiceRecognitionEnabled: Boolean,
    onLocationClick: () -> Unit,
    onVoiceRecognitionClick: () -> Unit
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MapRoundIconButton(
            iconRes = R.drawable.ic_my_location_action,
            contentDescription = "현재 위치 재측정",
            backgroundColor = MaterialTheme.colorScheme.surface,
            iconTint = MaterialTheme.colorScheme.primary,
            borderColor = MaterialTheme.colorScheme.primary,
            onClick = onLocationClick
        )
        MapRoundIconButton(
            iconRes = R.drawable.ic_microphone_action,
            contentDescription = if (isVoiceRecognitionActive) {
                "음성 인식 취소"
            } else {
                "가까운 충전소 음성 안내"
            },
            backgroundColor = if (isVoiceRecognitionActive) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surface
            },
            iconTint = if (isVoiceRecognitionActive) {
                Color.White
            } else {
                MaterialTheme.colorScheme.primary
            },
            borderColor = MaterialTheme.colorScheme.primary,
            enabled = isVoiceRecognitionEnabled || isVoiceRecognitionActive,
            onClick = onVoiceRecognitionClick
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
    selectedDistanceText: String,
    onExpandedChange: (Boolean) -> Unit
) {
    var accumulatedDrag by remember { mutableStateOf(0f) }
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
        if (!expanded && selectedChargerName != null) {
            Text(
                text = selectedChargerName.ifBlank { "시설명 정보 없음" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = selectedDistanceText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ChargerDetailPanel(
    charger: Charger,
    distanceText: String,
    isRouteVisible: Boolean,
    isRouteLoading: Boolean,
    navigationState: NavigationState,
    isNavigationMode: Boolean,
    navigationRemainingDistanceM: Double?,
    navigationEstimatedTimeText: String?,
    navigationStatusMessage: String?,
    routeMessage: String?,
    onShowRoute: () -> Unit,
    onHideRoute: () -> Unit,
    onStartNavigation: () -> Unit,
    onStopNavigation: () -> Unit
) {
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
                text = charger.name.displayOr("시설명 정보 없음"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = distanceText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = "기본정보",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            DetailInfoRow(
                label = "주소",
                value = charger.address.displayOr("주소 정보 없음")
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
                label = "이동식 충전기 여부",
                value = charger.is_movable.booleanLabel("가능", "불가")
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            RouteSummary(
                isRouteVisible = isRouteVisible,
                isRouteLoading = isRouteLoading,
                navigationState = navigationState,
                isNavigationMode = isNavigationMode,
                navigationRemainingDistanceM = navigationRemainingDistanceM,
                navigationEstimatedTimeText = navigationEstimatedTimeText,
                navigationStatusMessage = navigationStatusMessage,
                routeMessage = routeMessage,
                onShowRoute = onShowRoute,
                onHideRoute = onHideRoute,
                onStartNavigation = onStartNavigation,
                onStopNavigation = onStopNavigation
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
private fun RouteSummary(
    isRouteVisible: Boolean,
    isRouteLoading: Boolean,
    navigationState: NavigationState,
    isNavigationMode: Boolean,
    navigationRemainingDistanceM: Double?,
    navigationEstimatedTimeText: String?,
    navigationStatusMessage: String?,
    routeMessage: String?,
    onShowRoute: () -> Unit,
    onHideRoute: () -> Unit,
    onStartNavigation: () -> Unit,
    onStopNavigation: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (isRouteLoading) {
            OutlinedButton(
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                enabled = false,
                onClick = {}
            ) {
                Text("경로 준비 중", maxLines = 1)
            }
        } else if (isRouteVisible) {
            OutlinedButton(
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                onClick = onHideRoute
            ) {
                Text("경로 숨기기", maxLines = 1)
            }
        } else {
            OutlinedButton(
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                onClick = onShowRoute
            ) {
                Text("경로 표시", maxLines = 1)
            }
        }

        when {
            navigationState == NavigationState.ROUTE_LOADING -> {
                Button(
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    enabled = false,
                    onClick = {}
                ) {
                    Text("안내 준비 중", maxLines = 1)
                }
            }
            isNavigationMode -> {
                OutlinedButton(
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    onClick = onStopNavigation
                ) {
                    Text("경로 안내 종료", maxLines = 1)
                }
            }
            else -> {
                Button(
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    onClick = onStartNavigation
                ) {
                    Text("경로 안내 시작", maxLines = 1)
                }
            }
        }
    }

    if (navigationState != NavigationState.IDLE) {
        navigationRemainingDistanceM?.let { distance ->
            Text(
                text = "남은 거리: " + formatNavigationDistance(distance),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        navigationEstimatedTimeText?.let { estimatedTime ->
            Text(
                text = "예상 시간: $estimatedTime",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        navigationStatusMessage
            ?.takeIf { it.isNotBlank() }
            ?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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

internal fun formatNavigationDistance(distanceMeters: Double): String {
    if (!distanceMeters.isFinite() || distanceMeters < 0.0) {
        return "거리 정보 없음"
    }
    return if (distanceMeters < 1_000.0) {
        "약 ${distanceMeters.roundToInt()}m"
    } else {
        String.format(Locale.US, "약 %.1fkm", distanceMeters / 1_000.0)
    }
}

internal fun calculateNavigationEstimatedTimeText(
    remainingDistanceMeters: Double
): String {
    if (
        !remainingDistanceMeters.isFinite() ||
        remainingDistanceMeters < 0.0
    ) {
        return "예상 시간 정보 없음"
    }
    val minutes = remainingDistanceMeters /
        (NAVIGATION_SPEED_METERS_PER_HOUR / 60.0)
    return if (minutes < 1.0) {
        "1분 미만"
    } else {
        "약 ${ceil(minutes).toInt()}분"
    }
}

internal fun isLocationOffRoute(
    currentLocation: MapCoordinate,
    routePoints: List<MapCoordinate>,
    thresholdMeters: Double = NAVIGATION_OFF_ROUTE_DISTANCE_METERS
): Boolean {
    if (
        routePoints.isEmpty() ||
        !isValidCoordinate(currentLocation.lat, currentLocation.lng)
    ) {
        return false
    }
    val nearestDistance = routePoints
        .asSequence()
        .filter { isValidCoordinate(it.lat, it.lng) }
        .map { point ->
            haversineDistanceMeters(
                startLat = currentLocation.lat,
                startLng = currentLocation.lng,
                endLat = point.lat,
                endLng = point.lng
            )
        }
        .minOrNull()
        ?: return false
    return nearestDistance > thresholdMeters
}

private fun formatDataOrigin(origin: ChargerDataOrigin): String = when (origin) {
    ChargerDataOrigin.SERVER -> "서버"
    ChargerDataOrigin.LOCAL_JSON -> "로컬 JSON"
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

private fun findDestinationSlopeRisk(
    charger: Charger?,
    elevationSlopeItems: List<ElevationSlopeInfo>
): String? {
    charger ?: return null
    val chargerId = charger.id.normalized()
    if (chargerId.isNotEmpty()) {
        elevationSlopeItems.firstOrNull {
            it.chargerId.normalized() == chargerId
        }?.slopeRisk?.let { return it }
    }

    val chargerLat = charger.lat
    val chargerLng = charger.lng
    if (
        chargerLat == null ||
        chargerLng == null ||
        !isValidCoordinate(chargerLat, chargerLng)
    ) {
        return null
    }
    return elevationSlopeItems.mapNotNull { item ->
        val lat = item.lat
        val lng = item.lng
        if (
            lat == null ||
            lng == null ||
            !isValidCoordinate(lat, lng)
        ) {
            null
        } else {
            item to haversineDistanceMeters(
                startLat = chargerLat,
                startLng = chargerLng,
                endLat = lat,
                endLng = lng
            )
        }
    }.minByOrNull { it.second }
        ?.takeIf { it.second <= 75.0 }
        ?.first
        ?.slopeRisk
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

private fun formatDistanceFromCurrentLocation(
    charger: Charger?,
    currentLocation: MapCoordinate?
): String {
    val lat = charger?.lat
    val lng = charger?.lng
    if (
        currentLocation == null ||
        lat == null ||
        lng == null ||
        !isValidCoordinate(currentLocation.lat, currentLocation.lng) ||
        !isValidCoordinate(lat, lng)
    ) {
        return "거리 정보 없음"
    }

    val distanceMeters = haversineDistanceMeters(
        startLat = currentLocation.lat,
        startLng = currentLocation.lng,
        endLat = lat,
        endLng = lng
    )
    return "현재 위치에서 약 ${formatDistance(distanceMeters)}"
}

private fun formatDistance(distanceMeters: Double?): String {
    if (
        distanceMeters == null ||
        !distanceMeters.isFinite() ||
        distanceMeters < 0.0
    ) {
        return "거리 정보 없음"
    }

    return if (distanceMeters < 1000.0) {
        "${distanceMeters.roundToInt()}m"
    } else {
        String.format(Locale.US, "%.1fkm", distanceMeters / 1000.0)
    }
}
