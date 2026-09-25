package com.hz35remote.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hz35remote.app.data.ComfortCloudClient
import com.hz35remote.app.data.ComfortCloudException
import com.hz35remote.app.data.ComfortCloudSnapshot
import com.hz35remote.app.security.SecureSessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class HeatPumpViewModel(
    private val client: ComfortCloudClient,
) : ViewModel() {
    private val initialSnapshot = client.cachedSnapshot
    private var refreshJob: Job? = null
    private var lastSuccessfulNetworkSyncAt = 0L

    var uiState by mutableStateOf(
        initialSnapshot?.toUiState(
            previousNormalTargetTemperature = 22.0,
            message = "Updating status…",
            syncStatus = SyncStatus.CHECKING,
        ) ?: HeatPumpUiState(
                connectionStatus = if (client.hasStoredSession) {
                    ConnectionStatus.CONNECTING
                } else {
                    ConnectionStatus.SIGNED_OUT
                },
                statusMessage = if (client.hasStoredSession) {
                    "Restoring Comfort Cloud session…"
                } else {
                    "Not connected"
                },
            ),
    )
        private set

    init {
        if (client.hasStoredSession) {
            startRefresh(blockUntilConnected = initialSnapshot == null)
        }
    }

    fun beginAuthorization(): String? = runCatching {
        client.createAuthorizationUrl()
    }.onSuccess {
        uiState = uiState.copy(
            connectionStatus = ConnectionStatus.AUTHENTICATING,
            isBusy = false,
            statusMessage = "Complete sign-in in your browser",
            errorMessage = null,
        )
    }.onFailure(::showFatalError).getOrNull()

    fun completeAuthorization(
        code: String,
        returnedState: String,
    ) {
        uiState = uiState.copy(
            connectionStatus = ConnectionStatus.CONNECTING,
            isBusy = true,
            statusMessage = "Connecting to Comfort Cloud…",
            errorMessage = null,
        )
        viewModelScope.launch {
            runCatching { client.completeAuthorization(code, returnedState) }
                .onSuccess { snapshot -> showNetworkSnapshot(snapshot, "Connected") }
                .onFailure(::showFatalError)
        }
    }

    fun authorizationFailed(message: String) {
        uiState = uiState.copy(
            connectionStatus = ConnectionStatus.ERROR,
            isBusy = false,
            statusMessage = "Sign-in was not completed",
            errorMessage = message,
        )
    }

    fun refresh() {
        startRefresh(blockUntilConnected = !uiState.isConnected)
    }

    fun refreshIfStale(nowEpochMillis: Long = System.currentTimeMillis()) {
        if (!client.hasStoredSession || !shouldRefreshAfter(lastSuccessfulNetworkSyncAt, nowEpochMillis)) {
            return
        }
        startRefresh(blockUntilConnected = !uiState.isConnected)
    }

    private fun startRefresh(blockUntilConnected: Boolean) {
        if (refreshJob?.isActive == true || uiState.isBusy || uiState.pendingAction != null) return
        uiState = uiState.copy(
            isBusy = blockUntilConnected,
            statusMessage = if (uiState.isConnected) "Updating status…" else "Restoring Comfort Cloud session…",
            errorMessage = null,
        )
        refreshJob = viewModelScope.launch {
            try {
                val snapshot = client.refreshSnapshot()
                showNetworkSnapshot(snapshot, "Status confirmed")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                showRefreshError(error)
            } finally {
                refreshJob = null
            }
        }
    }

    fun send(action: HeatPumpAction) {
        val current = uiState
        val isTemperatureAction = action == HeatPumpAction.IncreaseTemperature ||
            action == HeatPumpAction.DecreaseTemperature
        val isAdjustingTemperature = isTemperatureAction && current.pendingTargetTemperature != null
        if (!current.isConnected ||
            ((current.isBusy || current.pendingAction != null) && !isAdjustingTemperature)
        ) return
        val parameters = controlParameters(
            current.copy(targetTemperature = current.pendingTargetTemperature ?: current.targetTemperature),
            action,
        )
        val pendingTemperature = if (isTemperatureAction) parameters["temperatureSet"]?.toDouble() else null
        if (isTemperatureAction &&
            pendingTemperature == (current.pendingTargetTemperature ?: current.targetTemperature)
        ) return
        if (isAdjustingTemperature) {
            uiState = current.copy(pendingAction = action, pendingTargetTemperature = pendingTemperature)
            return
        }
        refreshJob?.cancel()
        refreshJob = null
        uiState = current.copy(
            isBusy = true,
            pendingAction = action,
            pendingTargetTemperature = pendingTemperature,
            statusMessage = actionDescription(action),
            errorMessage = null,
        )
        viewModelScope.launch {
            try {
                var nextParameters = if (isTemperatureAction) {
                    delay(TEMPERATURE_ADJUSTMENT_DELAY_MILLIS)
                    mapOf("temperatureSet" to (uiState.pendingTargetTemperature ?: pendingTemperature!!))
                } else {
                    parameters
                }
                while (true) {
                    val snapshot = client.sendControl(nextParameters)
                    val latestTemperature = uiState.pendingTargetTemperature
                    val latestAction = uiState.pendingAction
                    showNetworkSnapshot(snapshot, "Command confirmed")
                    if (latestTemperature == null ||
                        latestTemperature == nextParameters["temperatureSet"]?.toDouble()
                    ) break
                    // Keep the latest selection visible while the next command is sent.
                    uiState = uiState.copy(
                        isBusy = true,
                        pendingAction = latestAction,
                        pendingTargetTemperature = latestTemperature,
                        statusMessage = "Changing temperature…",
                    )
                    nextParameters = mapOf("temperatureSet" to latestTemperature)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                uiState = uiState.copy(
                    syncStatus = SyncStatus.OFFLINE,
                    isBusy = false,
                    pendingAction = null,
                    pendingTargetTemperature = null,
                    statusMessage = "Command not confirmed",
                    errorMessage = error.userMessage(),
                )
            }
        }
    }

    fun disconnect() {
        if (uiState.isBusy) return
        refreshJob?.cancel()
        refreshJob = null
        uiState = uiState.copy(isBusy = true, statusMessage = "Disconnecting…")
        viewModelScope.launch {
            client.disconnect()
            uiState = HeatPumpUiState()
        }
    }

    private fun showNetworkSnapshot(snapshot: ComfortCloudSnapshot, message: String) {
        lastSuccessfulNetworkSyncAt = System.currentTimeMillis()
        uiState = snapshot.toUiState(
            previousNormalTargetTemperature = uiState.normalTargetTemperature,
            message = message,
        )
    }

    private fun showFatalError(error: Throwable) {
        uiState = uiState.copy(
            connectionStatus = ConnectionStatus.ERROR,
            isBusy = false,
            pendingAction = null,
            statusMessage = "Comfort Cloud connection failed",
            errorMessage = error.userMessage(),
        )
    }

    private fun showRefreshError(error: Throwable) {
        if (uiState.connectionStatus != ConnectionStatus.CONNECTED) {
            showFatalError(error)
            return
        }
        uiState = uiState.copy(
            syncStatus = SyncStatus.OFFLINE,
            isBusy = false,
            pendingAction = null,
            statusMessage = "Offline — showing last confirmed status",
            errorMessage = error.userMessage(),
        )
    }

    private fun Throwable.userMessage(): String = when (this) {
        is ComfortCloudException -> message ?: "Comfort Cloud rejected the request."
        else -> message ?: "An unexpected error occurred."
    }

    companion object {
        private const val TEMPERATURE_ADJUSTMENT_DELAY_MILLIS = 300L

        fun factory(store: SecureSessionStore): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return HeatPumpViewModel(ComfortCloudClient(store)) as T
                }
            }
    }
}

internal fun shouldRefreshAfter(
    lastSuccessfulNetworkSyncAt: Long,
    nowEpochMillis: Long,
): Boolean = lastSuccessfulNetworkSyncAt == 0L ||
    nowEpochMillis - lastSuccessfulNetworkSyncAt >= 30_000L

private fun ComfortCloudSnapshot.toUiState(
    previousNormalTargetTemperature: Double,
    message: String,
    syncStatus: SyncStatus = SyncStatus.ONLINE,
): HeatPumpUiState {
    val normalTargetTemperature = if (targetTemperature >= 16.0) {
        targetTemperature
    } else {
        previousNormalTargetTemperature
    }
    return HeatPumpUiState(
        connectionStatus = ConnectionStatus.CONNECTED,
        syncStatus = syncStatus,
        deviceName = device.name,
        model = device.model,
        isPoweredOn = isPoweredOn,
        mode = mode,
        fanSpeed = fanSpeed,
        verticalAirflowMode = verticalAirflowMode,
        verticalAirflowPosition = verticalAirflowPosition,
        horizontalAirflowMode = horizontalAirflowMode,
        horizontalAirflowPosition = horizontalAirflowPosition,
        roomTemperature = roomTemperature,
        outsideTemperature = outsideTemperature,
        targetTemperature = targetTemperature,
        normalTargetTemperature = normalTargetTemperature,
        isQuietOperation = isQuietOperation,
        isPowerfulOperation = isPowerfulOperation,
        isNanoeXOn = isNanoeXOn,
        isNanoeStandalone = isNanoeStandalone,
        isInsideCleaningOn = isInsideCleaningOn,
        isFireplaceOn = isFireplaceOn,
        isMaintenanceHeating = isMaintenanceHeating,
        isBusy = false,
        statusMessage = message,
        errorMessage = null,
        lastUpdatedEpochMillis = timestampEpochMillis ?: System.currentTimeMillis(),
    )
}
