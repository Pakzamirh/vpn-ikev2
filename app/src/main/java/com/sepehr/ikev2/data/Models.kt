package com.sepehr.ikev2.data

import kotlinx.serialization.Serializable

/**
 * Raw models used strictly inside the data layer.
 * Never passed to Compose UI or logged.
 */
@Serializable
data class UserRecord(
    val password: String,
    val active: Boolean = false,
    val note: String = ""
)

@Serializable
data class DefaultAuth(
    val username: String,
    val password: String
)

@Serializable
data class RawServerItem(
    val country: String = "",
    val city: String = "",
    val domain: String = "",
    val ips: List<String> = emptyList(),
    val username: String? = null,
    val password: String? = null,
    val auth: DefaultAuth? = null
)

@Serializable
data class ServersResponse(
    val defaultAuth: DefaultAuth? = null,
    val servers: List<RawServerItem> = emptyList()
)

/**
 * Internal vault holding private IKEv2 endpoint & credentials keyed by an opaque ID.
 * - serverHost (Hostname) = IP address
 * - serverIdentity (Remote ID) = Domain name
 * - ikeUsername / ikePassword = Dynamically read from servers.json on each refresh
 * The UI layer never sees domain, IP, or IKEv2 credentials.
 */
data class SecretTunnelSpec(
    val serverHost: String,
    val serverIdentity: String,
    val ikeUsername: String,
    val ikePassword: String
)

/**
 * Sanitized UI-safe models containing ONLY Country Flag, Country Name, and City label.
 */
data class ServerNodeUi(
    val opaqueId: String,
    val countryCode: String,
    val countryNameFa: String,
    val flagEmoji: String,
    val cityLabel: String
)

data class CountryGroupUi(
    val countryCode: String,
    val countryNameFa: String,
    val flagEmoji: String,
    val nodes: List<ServerNodeUi>
)

sealed interface AuthResult {
    data class Success(val username: String, val note: String) : AuthResult
    data object InvalidCredentials : AuthResult
    data object AccountDisabled : AuthResult
    data class NetworkError(val message: String) : AuthResult
}
