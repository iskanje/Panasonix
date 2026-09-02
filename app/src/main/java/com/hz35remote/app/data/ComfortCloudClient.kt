package com.hz35remote.app.data

import android.util.Base64
import com.hz35remote.app.FanSpeed
import com.hz35remote.app.OperatingMode
import com.hz35remote.app.AirflowAxis
import com.hz35remote.app.AirflowMode
import com.hz35remote.app.airflowPositionFromApi
import com.hz35remote.app.security.SecureSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class ComfortCloudClient(
    private val store: SecureSessionStore,
) {
    private var session: ComfortCloudSession? = store.loadSession()
    private var selectedDevice: ComfortCloudDevice? = null

    val hasStoredSession: Boolean
        get() = session != null

    fun createAuthorizationUrl(): String {
        val codeVerifier = randomString(43)
        val state = randomString(24)
        val codeChallenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray()),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        store.savePendingAuthorization(PendingAuthorization(codeVerifier, state))

        val parameters = linkedMapOf(
            "scope" to OAUTH_SCOPE,
            "audience" to "https://digital.panasonic.com/$APP_CLIENT_ID/api/v1/",
            "protocol" to "oauth2",
            "response_type" to "code",
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256",
            "auth0Client" to AUTH0_CLIENT,
            "client_id" to APP_CLIENT_ID,
            "redirect_uri" to REDIRECT_URI,
            "state" to state,
        )
        return "$AUTH_BASE/authorize?${parameters.toQueryString()}"
    }

    suspend fun completeAuthorization(
        authorizationCode: String,
        returnedState: String,
    ): ComfortCloudSnapshot {
        val pending = store.consumePendingAuthorization()
            ?: throw IllegalStateException("The sign-in request has expired. Start again.")
        require(pending.state == returnedState) { "The sign-in response could not be verified." }

        val appVersion = fetchLatestAppVersion()
        val tokenResponse = requestToken(
            JSONObject()
                .put("scope", "openid")
                .put("client_id", APP_CLIENT_ID)
                .put("grant_type", "authorization_code")
                .put("code", authorizationCode)
                .put("redirect_uri", REDIRECT_URI)
                .put("code_verifier", pending.codeVerifier),
        )
        val provisional = tokenResponse.toSession(appVersion = appVersion, clientId = "")
        val clientId = retrieveClientId(provisional)
        session = provisional.copy(clientId = clientId).also(store::saveSession)
        selectedDevice = null
        return refreshSnapshot()
    }

    suspend fun refreshSnapshot(): ComfortCloudSnapshot {
        val device = selectedDevice ?: discoverDevice().also { selectedDevice = it }
        return getSnapshot(device)
    }

    suspend fun sendControl(parameters: Map<String, Number>): ComfortCloudSnapshot {
        val device = selectedDevice ?: discoverDevice().also { selectedDevice = it }
        val jsonParameters = JSONObject()
        parameters.forEach { (key, value) -> jsonParameters.put(key, value) }
        authorizedRequest(
            method = "POST",
            url = "$ACC_BASE/deviceStatus/control",
            body = JSONObject()
                .put("deviceGuid", device.guid)
                .put("parameters", jsonParameters),
        )
        delay(600)
        return getSnapshot(device)
    }

    suspend fun disconnect() {
        runCatching {
            authorizedRequest(
                method = "POST",
                url = "$ACC_BASE/auth/v2/logout",
                body = JSONObject(),
            )
        }
        session = null
        selectedDevice = null
        store.clear()
    }

    private suspend fun discoverDevice(): ComfortCloudDevice {
        val response = authorizedRequest("GET", "$ACC_BASE/device/group")
        val devices = mutableListOf<ComfortCloudDevice>()
        val groups = response.optJSONArray("groupList") ?: JSONArray()

        for (groupIndex in 0 until groups.length()) {
            val group = groups.optJSONObject(groupIndex) ?: continue
            val groupName = group.optString("groupName")
            val list = group.optJSONArray("deviceList")
                ?: group.optJSONArray("deviceIdList")
                ?: JSONArray()
            for (deviceIndex in 0 until list.length()) {
                val rawDevice = list.optJSONObject(deviceIndex) ?: continue
                val deviceType = rawDevice.optString("deviceType")
                if (deviceType == "2" || deviceType == "11") continue
                if (!rawDevice.has("parameters")) continue
                val guid = rawDevice.optString("deviceGuid")
                if (guid.isBlank()) continue
                devices += ComfortCloudDevice(
                    guid = guid,
                    name = rawDevice.optString("deviceName").ifBlank {
                        groupName.ifBlank { "Heat pump" }
                    },
                    model = rawDevice.optString("deviceModuleNumber").ifBlank {
                        "Comfort Cloud air conditioner"
                    },
                )
            }
        }

        if (devices.isEmpty()) {
            throw IllegalStateException("No compatible air conditioner was found on this account.")
        }
        return devices.firstOrNull { device ->
            device.model.contains("HZ35", ignoreCase = true) ||
                device.name.contains("HZ35", ignoreCase = true)
        } ?: devices.first()
    }

    private suspend fun getSnapshot(device: ComfortCloudDevice): ComfortCloudSnapshot {
        val preparedGuid = URLEncoder.encode(
            device.guid.replace("/", "f"),
            StandardCharsets.UTF_8.name(),
        )
        val response = try {
            authorizedRequest("GET", "$ACC_BASE/deviceStatus/$preparedGuid")
        } catch (_: ComfortCloudException) {
            authorizedRequest("GET", "$ACC_BASE/deviceStatus/now/$preparedGuid")
        }
        val parameters = response.optJSONObject("parameters")
            ?: throw IllegalStateException("Comfort Cloud returned no device status.")
        val isPoweredOn = parameters.optInt("operate", 0) == 1
        val operationModeValue = parameters.optInt("operationMode", 0)
        val ecoMode = parameters.optInt("ecoMode", 0)
        val fanAutoMode = parameters.optInt("fanAutoMode", FAN_AUTO_DISABLED)
        val verticalAirflowValue = parameters.optInt("airSwingUD", AIRFLOW_CENTER_API_VALUE)
        val horizontalAirflowValue = parameters.optInt("airSwingLR", AIRFLOW_CENTER_API_VALUE)
        val targetTemperature = parameters.validTemperature("temperatureSet") ?: 22.0

        return ComfortCloudSnapshot(
            device = device,
            isPoweredOn = isPoweredOn,
            mode = if (operationModeValue == NANOE_STANDALONE_MODE) {
                OperatingMode.FAN
            } else {
                OperatingMode.fromApi(operationModeValue)
            },
            fanSpeed = FanSpeed.fromApi(parameters.optInt("fanSpeed", 0)),
            verticalAirflowMode = airflowModeFromApi(
                axis = AirflowAxis.VERTICAL,
                fanAutoMode = fanAutoMode,
                airflowValue = verticalAirflowValue,
            ),
            verticalAirflowPosition = airflowPositionFromApi(
                AirflowAxis.VERTICAL,
                verticalAirflowValue,
            ),
            horizontalAirflowMode = airflowModeFromApi(
                axis = AirflowAxis.HORIZONTAL,
                fanAutoMode = fanAutoMode,
                airflowValue = horizontalAirflowValue,
            ),
            horizontalAirflowPosition = airflowPositionFromApi(
                AirflowAxis.HORIZONTAL,
                horizontalAirflowValue,
            ),
            roomTemperature = parameters.validTemperature("insideTemperature"),
            outsideTemperature = parameters.validTemperature("outTemperature"),
            targetTemperature = targetTemperature,
            isQuietOperation = ecoMode == ECO_MODE_QUIET,
            isPowerfulOperation = ecoMode == ECO_MODE_POWERFUL,
            isNanoeXOn = parameters.optInt("nanoe", NANOE_OFF) >= NANOE_ON,
            isNanoeStandalone = isPoweredOn && operationModeValue == NANOE_STANDALONE_MODE,
            isInsideCleaningOn = parameters.optInt("insideCleaning", FEATURE_OFF) == FEATURE_ON,
            isFireplaceOn = parameters.optInt("fireplace", FEATURE_OFF) == FEATURE_ON,
            isMaintenanceHeating = isPoweredOn &&
                operationModeValue == OperatingMode.HEAT.apiValue &&
                targetTemperature in MAINTENANCE_TEMPERATURE_RANGE,
            timestampEpochMillis = response.optLongOrNull("timestamp"),
        )
    }

    private suspend fun retrieveClientId(provisional: ComfortCloudSession): String {
        val response = requestWithApiHeaders(
            method = "POST",
            url = "$ACC_BASE/auth/v2/login",
            body = JSONObject().put("language", 0),
            currentSession = provisional,
            includeClientId = false,
        )
        ensureSuccessful(response)
        return JSONObject(response.body).getString("clientId")
    }

    private suspend fun authorizedRequest(
        method: String,
        url: String,
        body: JSONObject? = null,
    ): JSONObject {
        var current = ensureFreshSession()
        var response = requestWithApiHeaders(method, url, body, current, includeClientId = true)

        if (response.statusCode == 401 && response.body.contains("4106")) {
            current = current.copy(appVersion = fetchLatestAppVersion()).also {
                session = it
                store.saveSession(it)
            }
            response = requestWithApiHeaders(method, url, body, current, includeClientId = true)
        } else if (response.statusCode == 401 && current.refreshToken.isNotBlank()) {
            current = refreshSession(current)
            response = requestWithApiHeaders(method, url, body, current, includeClientId = true)
        }

        ensureSuccessful(response)
        return if (response.body.isBlank()) JSONObject() else JSONObject(response.body)
    }

    private suspend fun requestWithApiHeaders(
        method: String,
        url: String,
        body: JSONObject?,
        currentSession: ComfortCloudSession,
        includeClientId: Boolean,
    ): HttpResponse {
        return requestWithTransientRetry {
            val timestamp = LocalDateTime.now().format(API_TIMESTAMP_FORMAT)
            val headers = linkedMapOf(
                "Accept" to "application/json; charset=utf-8",
                "Content-Type" to "application/json",
                "User-Agent" to "G-RAC",
                "x-app-name" to "Comfort Cloud",
                "x-app-timestamp" to timestamp,
                "x-app-type" to "1",
                "x-app-version" to currentSession.appVersion,
                "x-cfc-api-key" to ComfortCloudProtocol.apiKey(timestamp, currentSession.accessToken),
                "x-user-authorization-v2" to "Bearer ${currentSession.accessToken}",
            )
            if (includeClientId && currentSession.clientId.isNotBlank()) {
                headers["x-client-id"] = currentSession.clientId
            }
            rawRequest(method, url, headers, body?.toString())
        }
    }

    private suspend fun requestWithTransientRetry(
        request: suspend () -> HttpResponse,
    ): HttpResponse {
        repeat(API_REQUEST_ATTEMPTS) { attempt ->
            try {
                val response = request()
                if (
                    attempt == API_REQUEST_ATTEMPTS - 1 ||
                    !shouldRetryComfortCloudStatus(response.statusCode)
                ) {
                    return response
                }
            } catch (error: IOException) {
                if (attempt == API_REQUEST_ATTEMPTS - 1) throw error
            }
            delay(RETRY_BASE_DELAY_MILLIS * (attempt + 1))
        }
        error("Comfort Cloud retry loop ended unexpectedly.")
    }

    private suspend fun ensureFreshSession(): ComfortCloudSession {
        val current = session ?: throw IllegalStateException("Connect to Comfort Cloud first.")
        return if (current.expiresAtEpochSeconds <= Instant.now().epochSecond + TOKEN_EXPIRY_MARGIN_SECONDS) {
            refreshSession(current)
        } else {
            current
        }
    }

    private suspend fun refreshSession(current: ComfortCloudSession): ComfortCloudSession {
        if (current.refreshToken.isBlank()) {
            throw IllegalStateException("The Comfort Cloud session expired. Connect again.")
        }
        val response = requestToken(
            JSONObject()
                .put("scope", current.scope)
                .put("client_id", APP_CLIENT_ID)
                .put("refresh_token", current.refreshToken)
                .put("grant_type", "refresh_token"),
        )
        return response.toSession(
            appVersion = current.appVersion,
            clientId = current.clientId,
            previousRefreshToken = current.refreshToken,
        ).also {
            session = it
            store.saveSession(it)
        }
    }

    private suspend fun requestToken(body: JSONObject): JSONObject {
        val response = rawRequest(
            method = "POST",
            url = "$AUTH_BASE/oauth/token",
            headers = mapOf(
                "Auth0-Client" to AUTH0_CLIENT,
                "User-Agent" to "okhttp/4.10.0",
                "Content-Type" to "application/json",
            ),
            body = body.toString(),
        )
        if (response.statusCode != 200) {
            throw ComfortCloudException(
                response.statusCode,
                response.apiCode(),
                "Panasonic sign-in failed. Please try again.",
            )
        }
        return JSONObject(response.body)
    }

    private suspend fun fetchLatestAppVersion(): String {
        return runCatching {
            val response = rawRequest(
                method = "GET",
                url = PLAY_STORE_URL,
                headers = mapOf("User-Agent" to BROWSER_USER_AGENT),
                body = null,
            )
            APP_VERSION_PATTERN.find(response.body)?.groupValues?.get(1)
                ?: FALLBACK_APP_VERSION
        }.getOrDefault(FALLBACK_APP_VERSION)
    }

    private suspend fun rawRequest(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): HttpResponse = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 20_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            headers.forEach(connection::setRequestProperty)
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { output ->
                    output.write(body.toByteArray(Charsets.UTF_8))
                }
            }
            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            HttpResponse(statusCode, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } catch (exception: IOException) {
            throw IOException("Could not reach Comfort Cloud.", exception)
        } finally {
            connection.disconnect()
        }
    }

    private fun ensureSuccessful(response: HttpResponse) {
        if (response.statusCode !in 200..299) {
            val code = response.apiCode()
            val message = when (code) {
                4103 -> "Panasonic's terms or privacy notice changed. Review them in the official Comfort Cloud app, then reconnect."
                4106 -> "Comfort Cloud requires a newer app protocol version."
                else -> "Comfort Cloud request failed (${response.statusCode}${code?.let { ", code $it" }.orEmpty()})."
            }
            throw ComfortCloudException(response.statusCode, code, message)
        }
    }

    private fun JSONObject.toSession(
        appVersion: String,
        clientId: String,
        previousRefreshToken: String = "",
    ): ComfortCloudSession = ComfortCloudSession(
        accessToken = getString("access_token"),
        refreshToken = optString("refresh_token").ifBlank { previousRefreshToken },
        expiresAtEpochSeconds = Instant.now().epochSecond + getLong("expires_in"),
        scope = optString("scope").ifBlank { OAUTH_SCOPE },
        clientId = clientId,
        appVersion = appVersion,
    )

    private fun JSONObject.validTemperature(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val value = optDouble(key, Double.NaN)
        return value.takeIf { it.isFinite() && it != INVALID_TEMPERATURE }
    }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key) else null

    private fun HttpResponse.apiCode(): Int? = API_CODE_PATTERN.find(body)
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()

    private fun Map<String, String>.toQueryString(): String = entries.joinToString("&") { (key, value) ->
        URLEncoder.encode(key, StandardCharsets.UTF_8.name()) + "=" +
            URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    }

    private fun randomString(length: Int): String {
        val random = SecureRandom()
        return buildString(length) {
            repeat(length) { append(RANDOM_CHARACTERS[random.nextInt(RANDOM_CHARACTERS.length)]) }
        }
    }

    private data class HttpResponse(
        val statusCode: Int,
        val body: String,
    )

    companion object {
        const val REDIRECT_SCHEME = "panasonic-iot-cfc"
        const val REDIRECT_HOST = "authglb.digital.panasonic.com"
        const val REDIRECT_PATH = "/android/com.panasonic.ACCsmart/callback"

        private const val APP_CLIENT_ID = "Xmy6xIYIitMxngjB2rHvlm6HSDNnaMJx"
        private const val AUTH0_CLIENT = "eyJuYW1lIjoiQXV0aDAuQW5kcm9pZCIsImVudiI6eyJhbmRyb2lkIjoiMzAifSwidmVyc2lvbiI6IjIuOS4zIn0="
        private const val REDIRECT_URI = "$REDIRECT_SCHEME://$REDIRECT_HOST$REDIRECT_PATH"
        private const val AUTH_BASE = "https://authglb.digital.panasonic.com"
        private const val ACC_BASE = "https://accsmart.panasonic.com"
        private const val OAUTH_SCOPE = "openid offline_access comfortcloud.control a2w.control"
        private const val PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=com.panasonic.ACCsmart&hl=en&gl=US"
        private const val FALLBACK_APP_VERSION = "4.4.0"
        private const val BROWSER_USER_AGENT = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/113 Mobile Safari/537.36"
        private const val RANDOM_CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        private const val TOKEN_EXPIRY_MARGIN_SECONDS = 60L
        private const val INVALID_TEMPERATURE = 126.0
        private const val ECO_MODE_POWERFUL = 1
        private const val ECO_MODE_QUIET = 2
        private const val NANOE_OFF = 1
        private const val NANOE_ON = 2
        private const val FEATURE_OFF = 1
        private const val FEATURE_ON = 2
        private const val NANOE_STANDALONE_MODE = 5
        private const val FAN_AUTO_BOTH = 0
        private const val FAN_AUTO_DISABLED = 1
        private const val FAN_AUTO_VERTICAL = 2
        private const val FAN_AUTO_HORIZONTAL = 3
        private const val AIRFLOW_CENTER_API_VALUE = 2
        private const val AIRFLOW_SWING_API_VALUE = 5
        private const val API_REQUEST_ATTEMPTS = 3
        private const val RETRY_BASE_DELAY_MILLIS = 400L
        private val MAINTENANCE_TEMPERATURE_RANGE = 8.0..15.0
        private val APP_VERSION_PATTERN = Regex("\\[\\\"(\\d+\\.\\d+\\.\\d+)\\\"\\]")
        private val API_CODE_PATTERN = Regex("\\\"(?:code|errorCode)\\\"\\s*:\\s*\\\"?(\\d+)\\\"?")
        private val API_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }

    private fun airflowModeFromApi(
        axis: AirflowAxis,
        fanAutoMode: Int,
        airflowValue: Int,
    ): AirflowMode = when {
        fanAutoMode == FAN_AUTO_BOTH -> AirflowMode.AUTO
        axis == AirflowAxis.VERTICAL && fanAutoMode == FAN_AUTO_VERTICAL -> AirflowMode.AUTO
        axis == AirflowAxis.HORIZONTAL && fanAutoMode == FAN_AUTO_HORIZONTAL -> AirflowMode.AUTO
        airflowValue == AIRFLOW_SWING_API_VALUE -> AirflowMode.SWING
        else -> AirflowMode.FIXED
    }
}

internal fun shouldRetryComfortCloudStatus(statusCode: Int): Boolean =
    statusCode == 408 ||
        statusCode == 425 ||
        statusCode == 429 ||
        statusCode in 500..599

internal object ComfortCloudProtocol {
    private const val API_KEY_SEED = "521325fb2dd486bf4831b47644317fca"

    fun apiKey(timestamp: String, accessToken: String): String {
        val timestampMillis = LocalDateTime.parse(
            timestamp,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        ).toInstant(ZoneOffset.UTC).toEpochMilli().toString()
        val input = "Comfort Cloud$API_KEY_SEED${timestampMillis}Bearer $accessToken"
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return hash.take(9) + "cfc" + hash.drop(9)
    }
}
