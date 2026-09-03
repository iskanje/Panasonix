package com.hz35remote.app

enum class OperatingMode(
    val label: String,
    val apiValue: Int,
) {
    AUTO("Auto", 0),
    DRY("Dry", 1),
    COOL("Cool", 2),
    HEAT("Heat", 3),
    FAN("Fan", 4),
    ;

    companion object {
        fun fromApi(value: Int): OperatingMode = entries.firstOrNull {
            it.apiValue == value
        } ?: AUTO
    }
}

enum class FanSpeed(
    val label: String,
    val apiValue: Int,
) {
    AUTO("Auto", 0),
    ONE("1", 1),
    TWO("2", 2),
    THREE("3", 3),
    FOUR("4", 4),
    FIVE("5", 5),
    ;

    companion object {
        fun fromApi(value: Int): FanSpeed = entries.firstOrNull {
            it.apiValue == value
        } ?: AUTO
    }
}

enum class AirflowAxis {
    VERTICAL,
    HORIZONTAL,
}

enum class AirflowMode(val label: String) {
    AUTO("Auto"),
    SWING("Swing"),
    FIXED("Fixed"),
}

enum class AirflowPosition(val level: Int) {
    ONE(1),
    TWO(2),
    THREE(3),
    FOUR(4),
    FIVE(5),
    ;

    companion object {
        fun fromLevel(level: Int): AirflowPosition = entries.firstOrNull {
            it.level == level
        } ?: THREE
    }
}

fun airflowPositionFromApi(
    axis: AirflowAxis,
    value: Int,
): AirflowPosition {
    val apiValues = when (axis) {
        AirflowAxis.VERTICAL -> listOf(0, 3, 2, 4, 1)
        AirflowAxis.HORIZONTAL -> listOf(0, 4, 2, 3, 1)
    }
    return AirflowPosition.fromLevel(apiValues.indexOf(value) + 1)
}

fun airflowPositionApiValue(
    axis: AirflowAxis,
    position: AirflowPosition,
): Int = when (axis) {
    AirflowAxis.VERTICAL -> listOf(0, 3, 2, 4, 1)
    AirflowAxis.HORIZONTAL -> listOf(0, 4, 2, 3, 1)
}[position.level - 1]

enum class ConnectionStatus {
    SIGNED_OUT,
    AUTHENTICATING,
    CONNECTING,
    CONNECTED,
    ERROR,
}

enum class SyncStatus {
    UNKNOWN,
    CHECKING,
    ONLINE,
    OFFLINE,
}

internal fun syncStatusLabel(status: SyncStatus): String = when (status) {
    SyncStatus.UNKNOWN -> "Connection unknown"
    SyncStatus.CHECKING -> "Checking connection"
    SyncStatus.ONLINE -> "Online"
    SyncStatus.OFFLINE -> "Offline"
}

data class HeatPumpUiState(
    val connectionStatus: ConnectionStatus = ConnectionStatus.SIGNED_OUT,
    val syncStatus: SyncStatus = SyncStatus.UNKNOWN,
    val deviceName: String = "Living room",
    val model: String = "CS-HZ35ZKE",
    val isPoweredOn: Boolean = false,
    val mode: OperatingMode = OperatingMode.HEAT,
    val fanSpeed: FanSpeed = FanSpeed.AUTO,
    val verticalAirflowMode: AirflowMode = AirflowMode.AUTO,
    val verticalAirflowPosition: AirflowPosition = AirflowPosition.THREE,
    val horizontalAirflowMode: AirflowMode = AirflowMode.AUTO,
    val horizontalAirflowPosition: AirflowPosition = AirflowPosition.THREE,
    val roomTemperature: Double? = null,
    val outsideTemperature: Double? = null,
    val targetTemperature: Double = 22.0,
    val normalTargetTemperature: Double = 22.0,
    val isQuietOperation: Boolean = false,
    val isPowerfulOperation: Boolean = false,
    val isNanoeXOn: Boolean = false,
    val isNanoeStandalone: Boolean = false,
    val isInsideCleaningOn: Boolean = false,
    val isFireplaceOn: Boolean = false,
    val isMaintenanceHeating: Boolean = false,
    val isBusy: Boolean = false,
    val pendingAction: HeatPumpAction? = null,
    val statusMessage: String = "Not connected",
    val errorMessage: String? = null,
    val lastUpdatedEpochMillis: Long? = null,
) {
    val isConnected: Boolean
        get() = connectionStatus == ConnectionStatus.CONNECTED

    val minimumTargetTemperature: Double
        get() = if (isMaintenanceHeating) 8.0 else 16.0

    val maximumTargetTemperature: Double
        get() = if (isMaintenanceHeating) 15.0 else 30.0
}

sealed interface HeatPumpAction {
    data object TogglePower : HeatPumpAction
    data object IncreaseTemperature : HeatPumpAction
    data object DecreaseTemperature : HeatPumpAction
    data class SelectMode(val mode: OperatingMode) : HeatPumpAction
    data class SelectFanSpeed(val speed: FanSpeed) : HeatPumpAction
    data class SelectAirflowMode(
        val axis: AirflowAxis,
        val mode: AirflowMode,
    ) : HeatPumpAction
    data class SelectAirflowPosition(
        val axis: AirflowAxis,
        val position: AirflowPosition,
    ) : HeatPumpAction
    data object ToggleQuietOperation : HeatPumpAction
    data object TogglePowerfulOperation : HeatPumpAction
    data object ToggleNanoeX : HeatPumpAction
    data object ToggleInsideCleaning : HeatPumpAction
    data object ToggleFireplace : HeatPumpAction
    data object ToggleMaintenanceHeating : HeatPumpAction
}

fun controlParameters(
    state: HeatPumpUiState,
    action: HeatPumpAction,
): Map<String, Number> = when (action) {
    HeatPumpAction.TogglePower -> mapOf("operate" to if (state.isPoweredOn) 0 else 1)
    HeatPumpAction.IncreaseTemperature -> mapOf(
        "temperatureSet" to (state.targetTemperature + 1.0)
            .coerceIn(state.minimumTargetTemperature, state.maximumTargetTemperature),
    )
    HeatPumpAction.DecreaseTemperature -> mapOf(
        "temperatureSet" to (state.targetTemperature - 1.0)
            .coerceIn(state.minimumTargetTemperature, state.maximumTargetTemperature),
    )
    is HeatPumpAction.SelectMode -> mapOf(
        "operate" to 1,
        "operationMode" to action.mode.apiValue,
    )
    is HeatPumpAction.SelectFanSpeed -> mapOf(
        "ecoMode" to 0,
        "fanSpeed" to action.speed.apiValue,
    )
    is HeatPumpAction.SelectAirflowMode -> airflowModeParameters(state, action)
    is HeatPumpAction.SelectAirflowPosition -> airflowPositionParameters(state, action)
    HeatPumpAction.ToggleQuietOperation -> mapOf(
        "ecoMode" to if (state.isQuietOperation) 0 else 2,
    )
    HeatPumpAction.TogglePowerfulOperation -> mapOf(
        "ecoMode" to if (state.isPowerfulOperation) 0 else 1,
    )
    HeatPumpAction.ToggleNanoeX -> when {
        state.isNanoeStandalone -> mapOf("operate" to 0, "nanoe" to 1)
        state.isNanoeXOn -> mapOf("nanoe" to 1)
        !state.isPoweredOn -> mapOf(
            "operate" to 1,
            "operationMode" to 5,
            "nanoe" to 2,
        )
        else -> mapOf("nanoe" to 2)
    }
    HeatPumpAction.ToggleInsideCleaning -> mapOf(
        "insideCleaning" to if (state.isInsideCleaningOn) 1 else 2,
    )
    HeatPumpAction.ToggleFireplace -> mapOf(
        "fireplace" to if (state.isFireplaceOn) 1 else 2,
    )
    HeatPumpAction.ToggleMaintenanceHeating -> if (state.isMaintenanceHeating) {
        mapOf(
            "operate" to 1,
            "operationMode" to OperatingMode.HEAT.apiValue,
            "temperatureSet" to state.normalTargetTemperature.coerceIn(16.0, 30.0),
        )
    } else {
        mapOf(
            "operate" to 1,
            "operationMode" to OperatingMode.HEAT.apiValue,
            "temperatureSet" to 8.0,
        )
    }
}

fun actionDescription(action: HeatPumpAction): String = when (action) {
    HeatPumpAction.TogglePower -> "Changing power…"
    HeatPumpAction.IncreaseTemperature,
    HeatPumpAction.DecreaseTemperature,
    -> "Changing temperature…"
    is HeatPumpAction.SelectMode -> "Switching to ${action.mode.label}…"
    is HeatPumpAction.SelectFanSpeed -> "Changing fan speed…"
    is HeatPumpAction.SelectAirflowMode -> "Changing air direction…"
    is HeatPumpAction.SelectAirflowPosition -> "Changing air direction…"
    HeatPumpAction.ToggleQuietOperation -> "Changing quiet operation…"
    HeatPumpAction.TogglePowerfulOperation -> "Changing powerful operation…"
    HeatPumpAction.ToggleNanoeX -> "Changing nanoe™ X…"
    HeatPumpAction.ToggleInsideCleaning -> "Changing inside cleaning…"
    HeatPumpAction.ToggleFireplace -> "Changing fireplace mode…"
    HeatPumpAction.ToggleMaintenanceHeating -> "Changing maintenance heating…"
}

private fun airflowModeParameters(
    state: HeatPumpUiState,
    action: HeatPumpAction.SelectAirflowMode,
): Map<String, Number> {
    val parameters = linkedMapOf<String, Number>(
        "fanAutoMode" to fanAutoModeFor(state, action.axis, action.mode),
    )
    when (action.mode) {
        AirflowMode.AUTO -> Unit
        AirflowMode.SWING -> parameters[action.axis.parameterName] = AIRFLOW_SWING_API_VALUE
        AirflowMode.FIXED -> parameters[action.axis.parameterName] = airflowPositionApiValue(
            action.axis,
            state.airflowPosition(action.axis),
        )
    }
    return parameters
}

private fun airflowPositionParameters(
    state: HeatPumpUiState,
    action: HeatPumpAction.SelectAirflowPosition,
): Map<String, Number> = mapOf(
    "fanAutoMode" to fanAutoModeFor(state, action.axis, AirflowMode.FIXED),
    action.axis.parameterName to airflowPositionApiValue(action.axis, action.position),
)

private fun fanAutoModeFor(
    state: HeatPumpUiState,
    changedAxis: AirflowAxis,
    changedMode: AirflowMode,
): Int {
    val verticalIsAuto = if (changedAxis == AirflowAxis.VERTICAL) {
        changedMode == AirflowMode.AUTO
    } else {
        state.verticalAirflowMode == AirflowMode.AUTO
    }
    val horizontalIsAuto = if (changedAxis == AirflowAxis.HORIZONTAL) {
        changedMode == AirflowMode.AUTO
    } else {
        state.horizontalAirflowMode == AirflowMode.AUTO
    }
    return when {
        verticalIsAuto && horizontalIsAuto -> 0
        verticalIsAuto -> 2
        horizontalIsAuto -> 3
        else -> 1
    }
}

private fun HeatPumpUiState.airflowPosition(axis: AirflowAxis): AirflowPosition = when (axis) {
    AirflowAxis.VERTICAL -> verticalAirflowPosition
    AirflowAxis.HORIZONTAL -> horizontalAirflowPosition
}

private val AirflowAxis.parameterName: String
    get() = when (this) {
        AirflowAxis.VERTICAL -> "airSwingUD"
        AirflowAxis.HORIZONTAL -> "airSwingLR"
    }

private const val AIRFLOW_SWING_API_VALUE = 5
