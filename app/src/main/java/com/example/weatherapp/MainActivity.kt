package com.example.weatherapp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.weatherapp.ui.forecast.ForecastScreen
import com.example.weatherapp.ui.forecast.ForecastViewModel
import com.example.weatherapp.ui.setup.SetupScreen
import com.example.weatherapp.ui.setup.SetupViewModel
import com.example.weatherapp.ui.widget.updateWeatherWidgets

private val CyberpunkColorScheme = darkColorScheme(
    primary = Color(0xFF00E5FF),
    onPrimary = Color(0xFF001B22),
    primaryContainer = Color(0xFF07313A),
    onPrimaryContainer = Color(0xFF9DF4FF),
    secondary = Color(0xFFFF3DF2),
    onSecondary = Color(0xFF2B0029),
    secondaryContainer = Color(0xFF3C123C),
    onSecondaryContainer = Color(0xFFFFB8F8),
    tertiary = Color(0xFFE8FF4A),
    onTertiary = Color(0xFF202400),
    background = Color(0xFF050711),
    onBackground = Color(0xFFE8F7FF),
    surface = Color(0xFF0A1020),
    onSurface = Color(0xFFE8F7FF),
    surfaceVariant = Color(0xFF10202A),
    onSurfaceVariant = Color(0xFFC5D8DE),
    outline = Color(0xFF00E5FF),
    error = Color(0xFFFF5A7A),
    errorContainer = Color(0xFF3A101A),
    onErrorContainer = Color(0xFFFFD6DE)
)

class MainActivity : ComponentActivity() {
    private var selectedLocationFromIntent by mutableLongStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedLocationFromIntent = intent.locationIdExtra()
        setContent {
            WeatherApp(selectedLocationFromIntent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        selectedLocationFromIntent = intent.locationIdExtra()
    }

    private fun Intent.locationIdExtra(): Long = getLongExtra(EXTRA_LOCATION_ID, 0)

    companion object {
        const val EXTRA_LOCATION_ID = "locationId"
    }
}

@Composable
private fun WeatherApp(selectedLocationId: Long) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as WeatherApplication
    val forecastViewModel: ForecastViewModel = viewModel(
        factory = simpleFactory {
            ForecastViewModel(
                locationRepository = app.container.locationRepository,
                weatherRepository = app.container.weatherRepository,
                settingsRepository = app.container.settingsRepository
            )
        }
    )
    val setupViewModel: SetupViewModel = viewModel(
        factory = simpleFactory {
            SetupViewModel(
                locationRepository = app.container.locationRepository,
                weatherRepository = app.container.weatherRepository
            )
        }
    )

    LaunchedEffect(selectedLocationId) {
        if (selectedLocationId > 0) forecastViewModel.selectLocation(selectedLocationId)
    }

    val view = LocalView.current
    SideEffect {
        val window = (view.context as Activity).window
        window.statusBarColor = Color.Transparent.toArgb()
        window.navigationBarColor = CyberpunkColorScheme.background.toArgb()
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
    }

    MaterialTheme(colorScheme = CyberpunkColorScheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
                    .border(
                        BorderStroke(
                            2.dp,
                            Brush.linearGradient(
                                listOf(
                                    Color(0xFF00E5FF),
                                    Color(0xFFFF3DF2),
                                    Color(0xFFE8FF4A),
                                    Color(0xFF00E5FF)
                                )
                            )
                        ),
                        RoundedCornerShape(14.dp)
                    )
                    .padding(2.dp)
            ) {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background
                ) { padding ->
                    WeatherRoot(
                        forecastViewModel = forecastViewModel,
                        setupViewModel = setupViewModel,
                        padding = padding
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun WeatherRoot(
    forecastViewModel: ForecastViewModel,
    setupViewModel: SetupViewModel,
    padding: PaddingValues
) {
    val state by forecastViewModel.state
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(state.items.map { it.location.id to it.forecast?.fetchedAt }) {
        updateWeatherWidgets(context)
    }

    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = forecastViewModel::refreshSelected,
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (state.items.size < 2) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = if (state.items.isEmpty()) "Choose two locations" else "Add a second location",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        SetupScreen(viewModel = setupViewModel)
                    }
                }
            }

            if (state.items.isNotEmpty()) {
                item {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Forecast", style = MaterialTheme.typography.headlineSmall)
                            state.refreshMessage?.let {
                                Spacer(Modifier.height(4.dp))
                                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                item {
                    ForecastScreen(
                        state = state,
                        onSelectLocation = forecastViewModel::selectLocation,
                        onSetWidgetLocation = forecastViewModel::setWidgetLocation,
                        onRefresh = forecastViewModel::refreshSelected,
                        onExpandDay = forecastViewModel::toggleExpandedDay
                    )
                }
            }
            if (state.items.size >= 2) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Manage locations", style = MaterialTheme.typography.titleLarge)
                        SetupScreen(viewModel = setupViewModel)
                    }
                }
            }
        }
    }
}

private fun <T : ViewModel> simpleFactory(create: () -> T): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
    }
