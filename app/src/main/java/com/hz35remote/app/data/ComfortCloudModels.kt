package com.hz35remote.app.data

import com.hz35remote.app.FanSpeed
import com.hz35remote.app.OperatingMode
import com.hz35remote.app.AirflowMode
import com.hz35remote.app.AirflowPosition

data class ComfortCloudSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSeconds: Long,
    val scope: String,
    val clientId: String,
    val appVersion: String,
)

data class PendingAuthorization(
    val codeVerifier: String,
    val state: String,
)

data class ComfortCloudDevice(
    val guid: String,
    val name: String,
    val model: String,
)

data class ComfortCloudSnapshot(
    val device: ComfortCloudDevice,
    val isPoweredOn: Boolean,
    val mode: OperatingMode,
    val fanSpeed: FanSpeed,
    val verticalAirflowMode: AirflowMode,
    val verticalAirflowPosition: AirflowPosition,
    val horizontalAirflowMode: AirflowMode,
    val horizontalAirflowPosition: AirflowPosition,
    val roomTemperature: Double?,
    val outsideTemperature: Double?,
    val targetTemperature: Double,
    val isQuietOperation: Boolean,
    val isPowerfulOperation: Boolean,
    val isNanoeXOn: Boolean,
    val isNanoeStandalone: Boolean,
    val isInsideCleaningOn: Boolean,
    val isFireplaceOn: Boolean,
    val isMaintenanceHeating: Boolean,
    val timestampEpochMillis: Long?,
)

class ComfortCloudException(
    val statusCode: Int,
    val apiCode: Int?,
    message: String,
) : Exception(message) {
    val requiresAgreement: Boolean
        get() = apiCode == 4103
}
