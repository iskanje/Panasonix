package com.hz35remote.app

import com.hz35remote.app.data.ComfortCloudProtocol
import com.hz35remote.app.data.shouldRetryComfortCloudStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class HeatPumpStateTest {
    @Test
    fun `fan mode and fan speed use different API parameters`() {
        val state = HeatPumpUiState(isPoweredOn = true)

        assertEquals(
            mapOf("operate" to 1, "operationMode" to 4),
            controlParameters(state, HeatPumpAction.SelectMode(OperatingMode.FAN)),
        )
        assertEquals(
            mapOf("ecoMode" to 0, "fanSpeed" to 5),
            controlParameters(state, HeatPumpAction.SelectFanSpeed(FanSpeed.FIVE)),
        )
    }

    @Test
    fun `air direction positions use Comfort Cloud axis mappings`() {
        val state = HeatPumpUiState(
            isPoweredOn = true,
            verticalAirflowMode = AirflowMode.FIXED,
            horizontalAirflowMode = AirflowMode.FIXED,
        )

        assertEquals(
            mapOf("fanAutoMode" to 1, "airSwingUD" to 3),
            controlParameters(
                state,
                HeatPumpAction.SelectAirflowPosition(
                    AirflowAxis.VERTICAL,
                    AirflowPosition.TWO,
                ),
            ),
        )
        assertEquals(
            mapOf("fanAutoMode" to 1, "airSwingLR" to 3),
            controlParameters(
                state,
                HeatPumpAction.SelectAirflowPosition(
                    AirflowAxis.HORIZONTAL,
                    AirflowPosition.FOUR,
                ),
            ),
        )
        assertEquals(
            AirflowPosition.FOUR,
            airflowPositionFromApi(AirflowAxis.VERTICAL, 4),
        )
    }

    @Test
    fun `air direction auto and swing preserve the other automatic axis`() {
        val horizontalAuto = HeatPumpUiState(
            isPoweredOn = true,
            verticalAirflowMode = AirflowMode.FIXED,
            horizontalAirflowMode = AirflowMode.AUTO,
        )

        assertEquals(
            mapOf("fanAutoMode" to 0),
            controlParameters(
                horizontalAuto,
                HeatPumpAction.SelectAirflowMode(AirflowAxis.VERTICAL, AirflowMode.AUTO),
            ),
        )
        assertEquals(
            mapOf("fanAutoMode" to 3, "airSwingUD" to 5),
            controlParameters(
                horizontalAuto,
                HeatPumpAction.SelectAirflowMode(AirflowAxis.VERTICAL, AirflowMode.SWING),
            ),
        )
        assertEquals(
            mapOf("fanAutoMode" to 2, "airSwingLR" to 5),
            controlParameters(
                horizontalAuto.copy(
                    verticalAirflowMode = AirflowMode.AUTO,
                    horizontalAirflowMode = AirflowMode.FIXED,
                ),
                HeatPumpAction.SelectAirflowMode(AirflowAxis.HORIZONTAL, AirflowMode.SWING),
            ),
        )
    }

    @Test
    fun `quiet and powerful operations share mutually exclusive eco mode`() {
        assertEquals(
            mapOf("ecoMode" to 2),
            controlParameters(HeatPumpUiState(), HeatPumpAction.ToggleQuietOperation),
        )
        assertEquals(
            mapOf("ecoMode" to 1),
            controlParameters(HeatPumpUiState(), HeatPumpAction.TogglePowerfulOperation),
        )
        assertEquals(
            mapOf("ecoMode" to 0),
            controlParameters(
                HeatPumpUiState(isQuietOperation = true),
                HeatPumpAction.ToggleQuietOperation,
            ),
        )
    }

    @Test
    fun `nanoe starts standalone purification while the heat pump is off`() {
        assertEquals(
            mapOf("operate" to 1, "operationMode" to 5, "nanoe" to 2),
            controlParameters(HeatPumpUiState(), HeatPumpAction.ToggleNanoeX),
        )
        assertEquals(
            mapOf("operate" to 0, "nanoe" to 1),
            controlParameters(
                HeatPumpUiState(
                    isPoweredOn = true,
                    isNanoeXOn = true,
                    isNanoeStandalone = true,
                ),
                HeatPumpAction.ToggleNanoeX,
            ),
        )
    }

    @Test
    fun `maintenance heating uses its own range and restores normal target`() {
        val maintenanceState = HeatPumpUiState(
            isPoweredOn = true,
            targetTemperature = 15.0,
            normalTargetTemperature = 22.0,
            isMaintenanceHeating = true,
        )

        assertEquals(
            mapOf("temperatureSet" to 15.0),
            controlParameters(maintenanceState, HeatPumpAction.IncreaseTemperature),
        )
        assertEquals(
            mapOf("operate" to 1, "operationMode" to 3, "temperatureSet" to 22.0),
            controlParameters(maintenanceState, HeatPumpAction.ToggleMaintenanceHeating),
        )
        assertEquals(
            mapOf("operate" to 1, "operationMode" to 3, "temperatureSet" to 8.0),
            controlParameters(HeatPumpUiState(), HeatPumpAction.ToggleMaintenanceHeating),
        )
    }

    @Test
    fun `temperature commands stay inside supported range`() {
        assertEquals(
            mapOf("temperatureSet" to 30.0),
            controlParameters(
                HeatPumpUiState(targetTemperature = 30.0),
                HeatPumpAction.IncreaseTemperature,
            ),
        )
        assertEquals(
            mapOf("temperatureSet" to 16.0),
            controlParameters(
                HeatPumpUiState(targetTemperature = 16.0),
                HeatPumpAction.DecreaseTemperature,
            ),
        )
    }

    @Test
    fun `API enums map Comfort Cloud values`() {
        assertEquals(OperatingMode.HEAT, OperatingMode.fromApi(3))
        assertEquals(OperatingMode.FAN, OperatingMode.fromApi(4))
        assertEquals(FanSpeed.AUTO, FanSpeed.fromApi(0))
        assertEquals(FanSpeed.FIVE, FanSpeed.fromApi(5))
    }

    @Test
    fun `API key matches known protocol vector`() {
        assertEquals(
            "13d5fbc8ccfc980bf887de07cde4cf3aae8528a9c87d9fcf9260a86fcebcbb96433",
            ComfortCloudProtocol.apiKey("2026-09-02 12:34:56", "token-value"),
        )
    }

    @Test
    fun `only transient Comfort Cloud responses are retried`() {
        assertEquals(true, shouldRetryComfortCloudStatus(408))
        assertEquals(true, shouldRetryComfortCloudStatus(429))
        assertEquals(true, shouldRetryComfortCloudStatus(503))
        assertEquals(false, shouldRetryComfortCloudStatus(401))
        assertEquals(false, shouldRetryComfortCloudStatus(404))
    }

    @Test
    fun `background refresh runs on cold start and after stale interval`() {
        assertEquals(true, shouldRefreshAfter(0L, 10_000L))
        assertEquals(false, shouldRefreshAfter(10_000L, 39_999L))
        assertEquals(true, shouldRefreshAfter(10_000L, 40_000L))
    }

    @Test
    fun `sync indicator distinguishes cached checking online and offline states`() {
        assertEquals("Checking connection", syncStatusLabel(SyncStatus.CHECKING))
        assertEquals("● Online", syncStatusLabel(SyncStatus.ONLINE))
        assertEquals("● Offline", syncStatusLabel(SyncStatus.OFFLINE))
    }
}
