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
import kotlinx.coroutines.launch

class HeatPumpViewModel(
    private val client: ComfortCloudClient,
) : ViewModel() {
    var uiState by mutableStateOf(
        HeatPumpUiState(
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
        if (client.hasStoredSession) refresh()
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
                .onSuccess { snapshot -> showSnapshot(snapshot, "Connected") }
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
        if (uiState.isBusy) return
        uiState = uiState.copy(
            isBusy = true,
            pendingAction = null,
            statusMessage = "Refreshing status…",
            errorMessage = null,
        )
        viewModelScope.launch {
            runCatching { client.refreshSnapshot() }
                .onSuccess { snapshot -> showSnapshot(snapshot, "Status confirmed") }
                .onFailure(::showRefreshError)
        }
    }

    fun send(action: HeatPumpAction) {
        val current = uiState
        if (!current.isConnected || current.isBusy) return
        uiState = current.copy(
            isBusy = true,
            pendingAction = action,
            statusMessage = actionDescription(action),
            errorMessage = null,
        )
        viewModelScope.launch {
            runCatching {
                client.sendControl(controlParameters(current, action))
            }.onSuccess { snapshot ->
                showSnapshot(snapshot, "Command confirmed")
            }.onFailure { error ->
                uiState = uiState.copy(
                    isBusy = false,
                    pendingAction = null,
                    statusMessage = "Command not confirmed",
                    errorMessage = error.userMessage(),
                )
            }
        }
    }

    fun disconnect() {
        if (uiState.isBusy) return
        uiState = uiState.copy(isBusy = true, statusMessage = "Disconnecting…")
        viewModelScope.launch {
            client.disconnect()
            uiState = HeatPumpUiState()
        }
    }

    private fun showSnapshot(snapshot: ComfortCloudSnapshot, message: String) {
        val normalTargetTemperature = if (snapshot.targetTemperature >= 16.0) {
            snapshot.targetTemperature
        } else {
            uiState.normalTargetTemperature
        }
        uiState = HeatPumpUiState(
            connectionStatus = ConnectionStatus.CONNECTED,
            deviceName = snapshot.device.name,
            model = snapshot.device.model,
            isPoweredOn = snapshot.isPoweredOn,
            mode = snapshot.mode,
            fanSpeed = snapshot.fanSpeed,
            verticalAirflowMode = snapshot.verticalAirflowMode,
            verticalAirflowPosition = snapshot.verticalAirflowPosition,
            horizontalAirflowMode = snapshot.horizontalAirflowMode,
            horizontalAirflowPosition = snapshot.horizontalAirflowPosition,
            roomTemperature = snapshot.roomTemperature,
            outsideTemperature = snapshot.outsideTemperature,
            targetTemperature = snapshot.targetTemperature,
            normalTargetTemperature = normalTargetTemperature,
            isQuietOperation = snapshot.isQuietOperation,
            isPowerfulOperation = snapshot.isPowerfulOperation,
            isNanoeXOn = snapshot.isNanoeXOn,
            isNanoeStandalone = snapshot.isNanoeStandalone,
            isInsideCleaningOn = snapshot.isInsideCleaningOn,
            isFireplaceOn = snapshot.isFireplaceOn,
            isMaintenanceHeating = snapshot.isMaintenanceHeating,
            isBusy = false,
            statusMessage = message,
            errorMessage = null,
            lastUpdatedEpochMillis = snapshot.timestampEpochMillis ?: System.currentTimeMillis(),
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
            isBusy = false,
            pendingAction = null,
            statusMessage = "Could not refresh — showing last confirmed status",
            errorMessage = error.userMessage(),
        )
    }

    private fun Throwable.userMessage(): String = when (this) {
        is ComfortCloudException -> message ?: "Comfort Cloud rejected the request."
        else -> message ?: "An unexpected error occurred."
    }

    companion object {
        fun factory(store: SecureSessionStore): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return HeatPumpViewModel(ComfortCloudClient(store)) as T
                }
            }
    }
}
