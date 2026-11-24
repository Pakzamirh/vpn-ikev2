package com.sepehr.ikev2.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private val Context.sessionStore by preferencesDataStore(name = "sepehr_session")

class RemoteRepository(private val context: Context) {

    companion object {
        private const val SERVERS_URL =
            "https://gist.githubusercontent.com/Pakzamirh/3b183d15ad7afb0f72c3c2d574a6ee73/raw/122f0b580cfa6b45e5bd58a7d100b0b347ac02dc/servers.json"

        private const val USERS_URL =
            "https://gist.githubusercontent.com/Pakzamirh/ff56c30b1d6b1397e4320a2fde51792b/raw/7fd1b41a4dabcbe0697a3b706712b8a3d4aff8b3/users.json"

        private val KEY_LOGGED_USER = stringPreferencesKey("logged_user")
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // In-memory opaque vault: opaqueId -> SecretTunnelSpec (never exposed to UI)
    private val secretVault = ConcurrentHashMap<String, SecretTunnelSpec>()

    fun resolveSecretSpec(opaqueId: String): SecretTunnelSpec? = secretVault[opaqueId]

    suspend fun getSavedUsername(): String? {
        return context.sessionStore.data.first()[KEY_LOGGED_USER]
    }

    suspend fun saveSessionUsername(username: String) {
        context.sessionStore.edit { prefs ->
            prefs[KEY_LOGGED_USER] = username
        }
    }

    suspend fun clearSession() {
        context.sessionStore.edit { prefs ->
            prefs.remove(KEY_LOGGED_USER)
        }
        secretVault.clear()
    }

    suspend fun verifyUserActive(username: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val usersMap = fetchUsersMap()
            val matched = usersMap.entries.firstOrNull {
                it.key.equals(username.trim(), ignoreCase = true)
            }?.value
            matched?.active == true
        } catch (_: Exception) {
            true // Allow offline grace only if already authenticated; next online check enforces
        }
    }

    suspend fun login(username: String, password: String): AuthResult = withContext(Dispatchers.IO) {
        val cleanUser = username.trim()
        if (cleanUser.isEmpty() || password.isEmpty()) {
            return@withContext AuthResult.InvalidCredentials
        }

        try {
            val usersMap = fetchUsersMap()
            val entry = usersMap.entries.firstOrNull {
                it.key.equals(cleanUser, ignoreCase = true)
            } ?: return@withContext AuthResult.InvalidCredentials

            val record = entry.value
            if (record.password != password) {
                return@withContext AuthResult.InvalidCredentials
            }
            if (!record.active) {
                return@withContext AuthResult.AccountDisabled
            }

            saveSessionUsername(entry.key)
            AuthResult.Success(username = entry.key, note = record.note)
        } catch (e: Exception) {
            AuthResult.NetworkError("خطا در اتصال به سرور احراز هویت")
        }
    }

    suspend fun fetchCountryGroups(): List<CountryGroupUi> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$SERVERS_URL?_t=${System.currentTimeMillis()}")
            .cacheControl(CacheControl.FORCE_NETWORK)
            .build()

        val bodyString = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Server list fetch failed: ${response.code}")
            }
            response.body?.string() ?: throw IllegalStateException("Empty server list")
        }

        val payload = json.decodeFromString<ServersResponse>(bodyString)
        val defaultAuth = payload.defaultAuth
        val newVault = HashMap<String, SecretTunnelSpec>()
        val grouped = LinkedHashMap<String, MutableList<ServerNodeUi>>()

        payload.servers.forEach { raw ->
            val domain = raw.domain.trim()
            val validIps = raw.ips.map { it.trim() }.filter { it.isNotEmpty() }
            if (domain.isEmpty() || validIps.isEmpty()) return@forEach

            // Support updated defaultAuth or per-server username/password in servers.json
            val ikeUser = raw.auth?.username?.takeIf { it.isNotBlank() }
                ?: raw.username?.takeIf { it.isNotBlank() }
                ?: defaultAuth?.username.orEmpty()
            val ikePass = raw.auth?.password?.takeIf { it.isNotBlank() }
                ?: raw.password?.takeIf { it.isNotBlank() }
                ?: defaultAuth?.password.orEmpty()
            if (ikeUser.isEmpty() || ikePass.isEmpty()) return@forEach

            val code = raw.country.trim().uppercase(Locale.US).ifEmpty { "XX" }
            val countryNameFa = getPersianCountryName(code)
            val flag = countryCodeToFlag(code)
            val baseCity = raw.city.trim().ifEmpty { countryNameFa }

            // Expand multi-IP domains into separate selectable servers
            validIps.forEachIndexed { ipIndex, ip ->
                val opaqueId = UUID.randomUUID().toString()

                newVault[opaqueId] = SecretTunnelSpec(
                    serverHost = ip,          // Hostname -> IP
                    serverIdentity = domain,  // Remote ID -> Domain
                    ikeUsername = ikeUser,    // Live username from JSON
                    ikePassword = ikePass     // Live password from JSON
                )

                val displayCity = if (validIps.size > 1) {
                    "$baseCity · سرور ${toPersianDigits(ipIndex + 1)}"
                } else {
                    baseCity
                }

                val uiNode = ServerNodeUi(
                    opaqueId = opaqueId,
                    countryCode = code,
                    countryNameFa = countryNameFa,
                    flagEmoji = flag,
                    cityLabel = displayCity
                )
                grouped.getOrPut(code) { mutableListOf() }.add(uiNode)
            }
        }

        secretVault.clear()
        secretVault.putAll(newVault)

        val faCollator = java.text.Collator.getInstance(Locale("fa", "IR"))
        grouped.entries
            .map { (code, nodes) ->
                CountryGroupUi(
                    countryCode = code,
                    countryNameFa = nodes.first().countryNameFa,
                    flagEmoji = nodes.first().flagEmoji,
                    nodes = nodes
                )
            }
            .sortedWith { a, b -> faCollator.compare(a.countryNameFa, b.countryNameFa) }
    }

    private fun toPersianDigits(value: Int): String {
        val digits = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
        return value.toString().map { ch ->
            if (ch in '0'..'9') digits[ch - '0'] else ch
        }.joinToString("")
    }

    private fun fetchUsersMap(): Map<String, UserRecord> {
        val request = Request.Builder()
            .url("$USERS_URL?_t=${System.currentTimeMillis()}")
            .cacheControl(CacheControl.FORCE_NETWORK)
            .build()

        val bodyString = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Auth fetch failed: ${response.code}")
            }
            response.body?.string() ?: throw IllegalStateException("Empty auth response")
        }

        return json.decodeFromString<Map<String, UserRecord>>(bodyString)
    }

    private fun countryCodeToFlag(code: String): String {
        if (code.length != 2 || !code.all { it in 'A'..'Z' }) return "🌐"
        val first = Character.codePointAt(code, 0) - 'A'.code + 0x1F1E6
        val second = Character.codePointAt(code, 1) - 'A'.code + 0x1F1E6
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }

    private fun getPersianCountryName(code: String): String {
        val overrides = mapOf(
            "US" to "ایالات متحده",
            "GB" to "بریتانیا",
            "DE" to "آلمان",
            "NL" to "هلند",
            "FR" to "فرانسه",
            "CA" to "کانادا",
            "AU" to "استرالیا",
            "JP" to "ژاپن",
            "SG" to "سنگاپور",
            "KR" to "کره جنوبی",
            "CH" to "سوئیس",
            "SE" to "سوئد",
            "AT" to "اتریش",
            "PL" to "لهستان",
            "EE" to "استونی",
            "AL" to "آلبانی",
            "BG" to "بلغارستان",
            "GE" to "گرجستان",
            "ID" to "اندونزی",
            "HR" to "کرواسی",
            "LT" to "لیتوانی",
            "MK" to "مقدونیه شمالی",
            "AQ" to "قطب جنوب"
        )
        overrides[code]?.let { return it }
        val localized = Locale("", code).getDisplayCountry(Locale("fa"))
        return localized.ifEmpty { code }
    }
}
