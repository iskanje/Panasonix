package com.hz35remote.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.compose.runtime.saveable.rememberSaveable
import com.hz35remote.app.data.ComfortCloudClient
import com.hz35remote.app.security.SecureSessionStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val heatPumpViewModel: HeatPumpViewModel by lazy {
        ViewModelProvider(
            this,
            HeatPumpViewModel.factory(SecureSessionStore(applicationContext)),
        )[HeatPumpViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                ConfigureStatusBar()
                HeatPumpApp(
                    state = heatPumpViewModel.uiState,
                    onConnect = ::startAuthorization,
                    onRefresh = heatPumpViewModel::refresh,
                    onDisconnect = heatPumpViewModel::disconnect,
                    onAction = heatPumpViewModel::send,
                )
            }
        }
        handleAuthorizationIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        heatPumpViewModel.refreshIfStale()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthorizationIntent(intent)
    }

    private fun startAuthorization() {
        val url = heatPumpViewModel.beginAuthorization() ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            heatPumpViewModel.authorizationFailed("No web browser is available for Panasonic sign-in.")
        }
    }

    private fun handleAuthorizationIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (
            uri.scheme != ComfortCloudClient.REDIRECT_SCHEME ||
            uri.host != ComfortCloudClient.REDIRECT_HOST ||
            uri.path != ComfortCloudClient.REDIRECT_PATH
        ) {
            return
        }

        val error = uri.getQueryParameter("error")
        if (error != null) {
            heatPumpViewModel.authorizationFailed(
                uri.getQueryParameter("error_description") ?: "Panasonic sign-in was cancelled.",
            )
            return
        }

        val code = uri.getQueryParameter("code")
        val state = uri.getQueryParameter("state")
        if (code.isNullOrBlank() || state.isNullOrBlank()) {
            heatPumpViewModel.authorizationFailed("Panasonic returned an incomplete sign-in response.")
            return
        }
        heatPumpViewModel.completeAuthorization(code, state)
    }
}

@Composable
private fun ConfigureStatusBar() {
    val view = LocalView.current
    val backgroundColor = MaterialTheme.colorScheme.surface
    val useDarkIcons = backgroundColor.luminance() > 0.5f

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            @Suppress("DEPRECATION")
            window.statusBarColor = backgroundColor.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = useDarkIcons
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HeatPumpApp(
    state: HeatPumpUiState,
    onConnect: () -> Unit,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
    onAction: (HeatPumpAction) -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    if (state.isConnected) {
                        Column {
                            Text(state.deviceName)
                            Text(state.model, style = MaterialTheme.typography.labelSmall)
                        }
                    } else {
                        Text(stringResource(R.string.app_name))
                    }
                },
                actions = {
                    if (state.isConnected) {
                        TextButton(onClick = onDisconnect) {
                            Text("Sign out")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        if (state.isConnected) {
            HeatPumpControls(
                state = state,
                onAction = onAction,
                onRefresh = onRefresh,
                modifier = Modifier.padding(innerPadding),
            )
        } else {
            ConnectionScreen(
                state = state,
                onConnect = onConnect,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

@Composable
private fun ConnectionScreen(
    state: HeatPumpUiState,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Connect to Comfort Cloud", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Sign-in opens Panasonic's secure browser page. This app never receives or stores your Panasonic password.",
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Status", style = MaterialTheme.typography.titleMedium)
                Text(state.statusMessage)
                state.errorMessage?.let { Text(it) }
            }
        }
        Button(
            onClick = onConnect,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isBusy,
        ) {
            Text(
                if (state.connectionStatus == ConnectionStatus.AUTHENTICATING) {
                    "Restart sign-in"
                } else {
                    "Continue to Panasonic"
                },
            )
        }
        Text("If Android asks which app should open the sign-in callback, choose ${stringResource(R.string.app_name)}.")
        Text("The heat pump must already be registered in Panasonic Comfort Cloud.")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HeatPumpControls(
    state: HeatPumpUiState,
    onAction: (HeatPumpAction) -> Unit,
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var moreExpanded by rememberSaveable { mutableStateOf(false) }
    val pendingMode = (state.pendingAction as? HeatPumpAction.SelectMode)?.mode
    val displayedMode = pendingMode ?: state.mode
    val pendingFanSpeed = (state.pendingAction as? HeatPumpAction.SelectFanSpeed)?.speed
    val displayedFanSpeed = pendingFanSpeed ?: state.fanSpeed
    val pendingAirflowMode = state.pendingAction as? HeatPumpAction.SelectAirflowMode
    val pendingAirflowPosition = state.pendingAction as? HeatPumpAction.SelectAirflowPosition
    val displayedVerticalAirflowMode = when {
        pendingAirflowMode?.axis == AirflowAxis.VERTICAL -> pendingAirflowMode.mode
        pendingAirflowPosition?.axis == AirflowAxis.VERTICAL -> AirflowMode.FIXED
        else -> state.verticalAirflowMode
    }
    val displayedHorizontalAirflowMode = when {
        pendingAirflowMode?.axis == AirflowAxis.HORIZONTAL -> pendingAirflowMode.mode
        pendingAirflowPosition?.axis == AirflowAxis.HORIZONTAL -> AirflowMode.FIXED
        else -> state.horizontalAirflowMode
    }
    val displayedVerticalAirflowPosition = if (
        pendingAirflowPosition?.axis == AirflowAxis.VERTICAL
    ) {
        pendingAirflowPosition.position
    } else {
        state.verticalAirflowPosition
    }
    val displayedHorizontalAirflowPosition = if (
        pendingAirflowPosition?.axis == AirflowAxis.HORIZONTAL
    ) {
        pendingAirflowPosition.position
    } else {
        state.horizontalAirflowPosition
    }
    val temperatureIsPending = state.pendingAction == HeatPumpAction.IncreaseTemperature ||
        state.pendingAction == HeatPumpAction.DecreaseTemperature
    val hasAnimatedPendingAction = pendingMode != null ||
        pendingFanSpeed != null ||
        pendingAirflowMode != null ||
        pendingAirflowPosition != null ||
        temperatureIsPending
    val pendingPulseAlpha = if (hasAnimatedPendingAction) {
        rememberInfiniteTransition(label = "Pending control").animateFloat(
            initialValue = 0.5f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 650),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "Pending control emphasis",
        ).value
    } else {
        1f
    }
    val displayedTemperature = when (state.pendingAction) {
        HeatPumpAction.IncreaseTemperature ->
            (state.targetTemperature + 1.0).coerceIn(
                state.minimumTargetTemperature,
                state.maximumTargetTemperature,
            )
        HeatPumpAction.DecreaseTemperature ->
            (state.targetTemperature - 1.0).coerceIn(
                state.minimumTargetTemperature,
                state.maximumTargetTemperature,
            )
        else -> state.targetTemperature
    }
    val displayedQuietOperation = when (state.pendingAction) {
        HeatPumpAction.ToggleQuietOperation -> !state.isQuietOperation
        HeatPumpAction.TogglePowerfulOperation -> false
        else -> state.isQuietOperation
    }
    val displayedPowerfulOperation = when (state.pendingAction) {
        HeatPumpAction.TogglePowerfulOperation -> !state.isPowerfulOperation
        HeatPumpAction.ToggleQuietOperation -> false
        else -> state.isPowerfulOperation
    }
    val displayedNanoeX = if (state.pendingAction == HeatPumpAction.ToggleNanoeX) {
        !state.isNanoeXOn
    } else {
        state.isNanoeXOn
    }
    val displayedInsideCleaning = if (state.pendingAction == HeatPumpAction.ToggleInsideCleaning) {
        !state.isInsideCleaningOn
    } else {
        state.isInsideCleaningOn
    }
    val displayedFireplace = if (state.pendingAction == HeatPumpAction.ToggleFireplace) {
        !state.isFireplaceOn
    } else {
        state.isFireplaceOn
    }
    val displayedMaintenanceHeating = if (
        state.pendingAction == HeatPumpAction.ToggleMaintenanceHeating
    ) {
        !state.isMaintenanceHeating
    } else {
        state.isMaintenanceHeating
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StatusCard(
            state = state,
            onPowerChange = { onAction(HeatPumpAction.TogglePower) },
            onRefresh = onRefresh,
        )

        state.errorMessage?.let { message ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(message, modifier = Modifier.padding(16.dp))
            }
        }

        ControlSection(
            title = "Mode",
            supportingText = if (state.isPoweredOn) "One tap to switch" else "Select a mode to start",
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                OperatingMode.entries
                    .sortedBy { modeOrder(it) }
                    .forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = displayedMode == mode,
                            onClick = { onAction(HeatPumpAction.SelectMode(mode)) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = OperatingMode.entries.size,
                            ),
                            modifier = Modifier.weight(1f),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = MaterialTheme.colorScheme.secondaryContainer.copy(
                                    alpha = if (pendingMode == mode) pendingPulseAlpha else 1f,
                                ),
                                activeContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ),
                            contentPadding = PaddingValues(horizontal = 0.dp),
                            icon = {},
                            label = { Text(mode.label) },
                        )
                    }
            }
        }

        if (displayedMode != OperatingMode.FAN) {
            ControlSection(
                title = "Target temperature",
                supportingText = "${formatTemperature(state.minimumTargetTemperature)}–" +
                    "${formatTemperature(state.maximumTargetTemperature)} °C",
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = { onAction(HeatPumpAction.DecreaseTemperature) },
                            enabled = state.isPoweredOn &&
                                state.targetTemperature > state.minimumTargetTemperature,
                        ) {
                            Text("−")
                        }
                        Text(
                            text = "${formatTemperature(displayedTemperature)} °C",
                            modifier = Modifier.alpha(
                                if (temperatureIsPending) pendingPulseAlpha else 1f,
                            ),
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Button(
                            onClick = { onAction(HeatPumpAction.IncreaseTemperature) },
                            enabled = state.isPoweredOn &&
                                state.targetTemperature < state.maximumTargetTemperature,
                        ) {
                            Text("+")
                        }
                    }
                }
            }
        }

        ControlSection(
            title = "Fan speed",
            supportingText = if (displayedFanSpeed == FanSpeed.AUTO) {
                "Automatic"
            } else {
                "Level ${displayedFanSpeed.label}"
            },
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                fanSpeedDisplayOrder.forEachIndexed { index, speed ->
                    SegmentedButton(
                        selected = displayedFanSpeed == speed,
                        onClick = { onAction(HeatPumpAction.SelectFanSpeed(speed)) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = fanSpeedDisplayOrder.size,
                        ),
                        modifier = Modifier.weight(1f),
                        enabled = state.isPoweredOn,
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = MaterialTheme.colorScheme.secondaryContainer.copy(
                                alpha = if (pendingFanSpeed == speed) pendingPulseAlpha else 1f,
                            ),
                            activeContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                        contentPadding = PaddingValues(horizontal = 0.dp),
                        icon = {},
                        label = { Text(speed.label) },
                    )
                }
            }
        }

        OutlinedButton(
            onClick = { moreExpanded = !moreExpanded },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (moreExpanded) "Less" else "More")
        }

        AnimatedVisibility(
            visible = moreExpanded,
            enter = EnterTransition.None,
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ControlSection(
                    title = "Air direction",
                    supportingText = "Vertical and horizontal",
                ) {
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            AirflowAxisControl(
                                title = "Vertical airflow",
                                startLabel = "Up",
                                endLabel = "Down",
                                mode = displayedVerticalAirflowMode,
                                position = displayedVerticalAirflowPosition,
                                pendingMode = pendingAirflowMode
                                    ?.takeIf { it.axis == AirflowAxis.VERTICAL }
                                    ?.mode,
                                positionIsPending = pendingAirflowPosition
                                    ?.axis == AirflowAxis.VERTICAL,
                                pendingPulseAlpha = pendingPulseAlpha,
                                enabled = state.isPoweredOn,
                                onModeSelected = { mode ->
                                    onAction(
                                        HeatPumpAction.SelectAirflowMode(
                                            AirflowAxis.VERTICAL,
                                            mode,
                                        ),
                                    )
                                },
                                onPositionSelected = { position ->
                                    onAction(
                                        HeatPumpAction.SelectAirflowPosition(
                                            AirflowAxis.VERTICAL,
                                            position,
                                        ),
                                    )
                                },
                            )
                            HorizontalDivider()
                            AirflowAxisControl(
                                title = "Horizontal airflow",
                                startLabel = "Left",
                                endLabel = "Right",
                                mode = displayedHorizontalAirflowMode,
                                position = displayedHorizontalAirflowPosition,
                                pendingMode = pendingAirflowMode
                                    ?.takeIf { it.axis == AirflowAxis.HORIZONTAL }
                                    ?.mode,
                                positionIsPending = pendingAirflowPosition
                                    ?.axis == AirflowAxis.HORIZONTAL,
                                pendingPulseAlpha = pendingPulseAlpha,
                                enabled = state.isPoweredOn,
                                onModeSelected = { mode ->
                                    onAction(
                                        HeatPumpAction.SelectAirflowMode(
                                            AirflowAxis.HORIZONTAL,
                                            mode,
                                        ),
                                    )
                                },
                                onPositionSelected = { position ->
                                    onAction(
                                        HeatPumpAction.SelectAirflowPosition(
                                            AirflowAxis.HORIZONTAL,
                                            position,
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }

                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        AdvancedToggleRow(
                            title = "Quiet operation",
                            supportingText = "Reduced operating sound",
                            checked = displayedQuietOperation,
                            enabled = state.isPoweredOn,
                            onToggle = { onAction(HeatPumpAction.ToggleQuietOperation) },
                        )
                        HorizontalDivider()
                        AdvancedToggleRow(
                            title = "Powerful operation",
                            supportingText = "Maximum output for faster heating or cooling",
                            checked = displayedPowerfulOperation,
                            enabled = state.isPoweredOn,
                            onToggle = { onAction(HeatPumpAction.TogglePowerfulOperation) },
                        )
                        HorizontalDivider()
                        AdvancedToggleRow(
                            title = "nanoe™ X",
                            supportingText = "Air purification, including standalone operation",
                            checked = displayedNanoeX,
                            onToggle = { onAction(HeatPumpAction.ToggleNanoeX) },
                        )
                        HorizontalDivider()
                        AdvancedToggleRow(
                            title = "Inside Cleaning",
                            supportingText = "On-demand internal cleaning cycle",
                            checked = displayedInsideCleaning,
                            onToggle = { onAction(HeatPumpAction.ToggleInsideCleaning) },
                        )
                        HorizontalDivider()
                        AdvancedToggleRow(
                            title = "Fireplace mode",
                            supportingText = "Circulates heat from another heat source",
                            checked = displayedFireplace,
                            enabled = state.isPoweredOn &&
                                state.mode == OperatingMode.HEAT &&
                                !state.isMaintenanceHeating,
                            onToggle = { onAction(HeatPumpAction.ToggleFireplace) },
                        )
                        HorizontalDivider()
                        AdvancedToggleRow(
                            title = "Maintenance heating",
                            supportingText = "8–15 °C frost-protection range",
                            checked = displayedMaintenanceHeating,
                            onToggle = { onAction(HeatPumpAction.ToggleMaintenanceHeating) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AirflowAxisControl(
    title: String,
    startLabel: String,
    endLabel: String,
    mode: AirflowMode,
    position: AirflowPosition,
    pendingMode: AirflowMode?,
    positionIsPending: Boolean,
    pendingPulseAlpha: Float,
    enabled: Boolean,
    onModeSelected: (AirflowMode) -> Unit,
    onPositionSelected: (AirflowPosition) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            airflowModeDisplayOrder.forEachIndexed { index, airflowMode ->
                SegmentedButton(
                    selected = mode == airflowMode,
                    onClick = {
                        if (mode != airflowMode) onModeSelected(airflowMode)
                    },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = airflowModeDisplayOrder.size,
                    ),
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = MaterialTheme.colorScheme.secondaryContainer.copy(
                            alpha = if (pendingMode == airflowMode) pendingPulseAlpha else 1f,
                        ),
                        activeContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                    icon = {},
                    label = { Text(airflowMode.label) },
                )
            }
        }

        if (mode == AirflowMode.FIXED) {
            var sliderValue by remember(position) {
                mutableFloatStateOf(position.level.toFloat())
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(startLabel, style = MaterialTheme.typography.bodySmall)
                Text(endLabel, style = MaterialTheme.typography.bodySmall)
            }
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = {
                    val selectedPosition = AirflowPosition.fromLevel(sliderValue.roundToInt())
                    if (selectedPosition != position) onPositionSelected(selectedPosition)
                },
                valueRange = 1f..5f,
                steps = 3,
                enabled = enabled,
            )
            Text(
                text = "Position ${sliderValue.roundToInt()} of 5",
                modifier = Modifier.alpha(if (positionIsPending) pendingPulseAlpha else 1f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun AdvancedToggleRow(
    title: String,
    supportingText: String,
    checked: Boolean,
    onToggle: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(supportingText, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            enabled = enabled,
        )
    }
}

@Composable
private fun StatusCard(
    state: HeatPumpUiState,
    onPowerChange: () -> Unit,
    onRefresh: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "●",
                        style = MaterialTheme.typography.titleSmall,
                        color = when (state.syncStatus) {
                            SyncStatus.ONLINE -> OnlineIndicatorGreen
                            SyncStatus.OFFLINE -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                        },
                    )
                    Text(
                        text = syncStatusLabel(state.syncStatus),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (state.isPoweredOn) "On" else "Off")
                    Switch(
                        checked = state.isPoweredOn,
                        onCheckedChange = { onPowerChange() },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Current state", style = MaterialTheme.typography.labelMedium)
                    Text(
                        text = if (state.isPoweredOn) operationLabel(state) else "Off",
                        style = MaterialTheme.typography.headlineLarge,
                    )
                    Text(
                        text = state.roomTemperature?.let { "Room ${formatTemperature(it)} °C" }
                            ?: "Room temperature unavailable",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    state.outsideTemperature?.let {
                        Text("Outside ${formatTemperature(it)} °C")
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        if (state.mode == OperatingMode.FAN) "Fan speed" else "Target",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        text = if (state.mode == OperatingMode.FAN) {
                            state.fanSpeed.label
                        } else {
                            "${formatTemperature(state.targetTemperature)}°"
                        },
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(state.statusMessage, style = MaterialTheme.typography.labelMedium)
                    state.lastUpdatedEpochMillis?.let { timestamp ->
                        Text(
                            "Last confirmed ${formatTime(timestamp)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                TextButton(
                    onClick = onRefresh,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                ) {
                    Text("Refresh")
                }
            }
        }
    }
}

private val OnlineIndicatorGreen = Color(0xFF006D3C)

@Composable
private fun ControlSection(
    title: String,
    supportingText: String? = null,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            supportingText?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
        content()
    }
}

private fun modeOrder(mode: OperatingMode): Int = when (mode) {
    OperatingMode.HEAT -> 0
    OperatingMode.FAN -> 1
    OperatingMode.COOL -> 2
    OperatingMode.DRY -> 3
    OperatingMode.AUTO -> 4
}

private val fanSpeedDisplayOrder = listOf(
    FanSpeed.ONE,
    FanSpeed.TWO,
    FanSpeed.THREE,
    FanSpeed.FOUR,
    FanSpeed.FIVE,
    FanSpeed.AUTO,
)

private val airflowModeDisplayOrder = listOf(
    AirflowMode.FIXED,
    AirflowMode.SWING,
    AirflowMode.AUTO,
)

private fun operationLabel(state: HeatPumpUiState): String = when {
    state.isNanoeStandalone -> "Air purification"
    state.isMaintenanceHeating -> "Maintenance heating"
    state.mode == OperatingMode.HEAT -> "Heating"
    state.mode == OperatingMode.FAN -> "Fan only"
    state.mode == OperatingMode.COOL -> "Cooling"
    state.mode == OperatingMode.DRY -> "Drying"
    else -> "Automatic"
}

private fun formatTemperature(value: Double): String =
    if (value == value.roundToInt().toDouble()) value.roundToInt().toString() else "%.1f".format(value)

private fun formatTime(timestamp: Long): String = DateTimeFormatter
    .ofLocalizedTime(FormatStyle.SHORT)
    .withZone(ZoneId.systemDefault())
    .format(Instant.ofEpochMilli(timestamp))

@Preview(showBackground = true)
@Composable
private fun ConnectedPreview() {
    MaterialTheme {
        HeatPumpApp(
            state = HeatPumpUiState(
                connectionStatus = ConnectionStatus.CONNECTED,
                isPoweredOn = true,
                roomTemperature = 21.0,
                outsideTemperature = 5.0,
            ),
            onConnect = {},
            onRefresh = {},
            onDisconnect = {},
            onAction = {},
        )
    }
}
