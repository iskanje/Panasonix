package com.hz35remote.app

import com.hz35remote.app.data.ComfortCloudClient
import com.hz35remote.app.data.ComfortCloudDevice
import com.hz35remote.app.data.ComfortCloudSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class HeatPumpViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val client = mock(ComfortCloudClient::class.java)
    private val requests = mutableListOf<Double>()
    private var onRequest: (Double) -> Unit = {}

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `four rapid taps select 24 before the device responds`() = runTest(dispatcher) {
        val model = viewModel()

        repeat(4) { model.send(HeatPumpAction.IncreaseTemperature) }

        assertEquals(24.0, model.uiState.pendingTargetTemperature)
        assertEquals(20.0, model.uiState.targetTemperature, 0.0)
        assertTrue(model.uiState.isBusy)
        advanceUntilIdle()
        assertEquals(listOf(24.0), requests)
        assertEquals(24.0, model.uiState.targetTemperature, 0.0)
        assertNull(model.uiState.pendingTargetTemperature)
        assertFalse(model.uiState.isBusy)
    }

    @Test
    fun `taps during a request send only the latest queued target`() = runTest(dispatcher) {
        val model = viewModel()
        onRequest = { target ->
            if (target == 21.0) {
                repeat(3) { model.send(HeatPumpAction.IncreaseTemperature) }
                assertEquals(24.0, model.uiState.pendingTargetTemperature)
                model.send(HeatPumpAction.TogglePower)
            } else {
                assertEquals(24.0, model.uiState.pendingTargetTemperature)
                assertEquals(21.0, model.uiState.targetTemperature, 0.0)
            }
        }

        model.send(HeatPumpAction.IncreaseTemperature)
        advanceUntilIdle()

        assertEquals(listOf(21.0, 24.0), requests)
        assertEquals(24.0, model.uiState.targetTemperature, 0.0)
        assertNull(model.uiState.pendingAction)
    }

    @Test
    fun `direction can reverse while the first command is pending`() = runTest(dispatcher) {
        val model = viewModel()
        onRequest = { target ->
            if (target == 21.0) {
                model.send(HeatPumpAction.DecreaseTemperature)
                model.send(HeatPumpAction.DecreaseTemperature)
                assertEquals(19.0, model.uiState.pendingTargetTemperature)
            }
        }

        model.send(HeatPumpAction.IncreaseTemperature)
        advanceUntilIdle()

        assertEquals(listOf(21.0, 19.0), requests)
        assertEquals(19.0, model.uiState.targetTemperature, 0.0)
    }

    @Test
    fun `queued target equal to the active command does not send again`() = runTest(dispatcher) {
        val model = viewModel()
        onRequest = {
            model.send(HeatPumpAction.IncreaseTemperature)
            model.send(HeatPumpAction.DecreaseTemperature)
        }

        model.send(HeatPumpAction.IncreaseTemperature)
        advanceUntilIdle()

        assertEquals(listOf(21.0), requests)
        assertNull(model.uiState.pendingTargetTemperature)
    }

    @Test
    fun `failed queued command restores latest confirmed temperature and allows retry`() = runTest(dispatcher) {
        val model = viewModel()
        onRequest = { target ->
            if (target == 21.0) {
                repeat(3) { model.send(HeatPumpAction.IncreaseTemperature) }
            } else {
                throw IllegalStateException("Device unavailable")
            }
        }

        model.send(HeatPumpAction.IncreaseTemperature)
        advanceUntilIdle()

        assertEquals(listOf(21.0, 24.0), requests)
        assertEquals(21.0, model.uiState.targetTemperature, 0.0)
        assertNull(model.uiState.pendingTargetTemperature)
        assertNull(model.uiState.pendingAction)
        assertFalse(model.uiState.isBusy)
        assertEquals("Device unavailable", model.uiState.errorMessage)
        onRequest = {}
        model.send(HeatPumpAction.IncreaseTemperature)
        advanceUntilIdle()
        assertEquals(22.0, model.uiState.targetTemperature, 0.0)
    }

    @Test
    fun `rapid taps respect normal and maintenance limits`() = runTest(dispatcher) {
        for ((minimum, maximum, maintenance) in listOf(Triple(16.0, 30.0, false), Triple(8.0, 15.0, true))) {
            val model = viewModel(maximum - 1, maintenance)
            repeat(5) { model.send(HeatPumpAction.IncreaseTemperature) }
            assertEquals(maximum, model.uiState.pendingTargetTemperature)
            model.send(HeatPumpAction.DecreaseTemperature)
            assertEquals(maximum - 1, model.uiState.pendingTargetTemperature)
            repeat(20) { model.send(HeatPumpAction.DecreaseTemperature) }
            assertEquals(minimum, model.uiState.pendingTargetTemperature)
            advanceUntilIdle()
            assertEquals(minimum, model.uiState.targetTemperature, 0.0)
        }
    }

    private suspend fun viewModel(temperature: Double = 20.0, maintenance: Boolean = false): HeatPumpViewModel {
        `when`(client.cachedSnapshot).thenReturn(snapshot(temperature, maintenance))
        for (target in 8..30) {
            `when`(client.sendControl(mapOf("temperatureSet" to target.toDouble()))).thenAnswer {
                requests += target.toDouble()
                onRequest(target.toDouble())
                snapshot(target.toDouble(), maintenance)
            }
        }
        return HeatPumpViewModel(client)
    }

    private fun snapshot(temperature: Double, maintenance: Boolean = false) = ComfortCloudSnapshot(
        device = ComfortCloudDevice("test-device", "Living room", "CS-HZ35ZKE"),
        isPoweredOn = true,
        mode = OperatingMode.HEAT,
        fanSpeed = FanSpeed.AUTO,
        verticalAirflowMode = AirflowMode.AUTO,
        verticalAirflowPosition = AirflowPosition.THREE,
        horizontalAirflowMode = AirflowMode.AUTO,
        horizontalAirflowPosition = AirflowPosition.THREE,
        roomTemperature = 20.0,
        outsideTemperature = 5.0,
        targetTemperature = temperature,
        isQuietOperation = false,
        isPowerfulOperation = false,
        isNanoeXOn = false,
        isNanoeStandalone = false,
        isInsideCleaningOn = false,
        isFireplaceOn = false,
        isMaintenanceHeating = maintenance,
        timestampEpochMillis = 1L,
    )
}
