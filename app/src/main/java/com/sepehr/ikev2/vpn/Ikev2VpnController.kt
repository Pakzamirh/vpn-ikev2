package com.sepehr.ikev2.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Ikev2VpnProfile
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnManager
import android.net.VpnProfileState
import android.net.eap.EapSessionConfig
import android.net.ipsec.ike.ChildSaProposal
import android.net.ipsec.ike.IkeFqdnIdentification
import android.net.ipsec.ike.IkeSaProposal
import android.net.ipsec.ike.IkeSessionParams
import android.net.ipsec.ike.IkeTunnelConnectionParams
import android.net.ipsec.ike.SaProposal
import android.net.ipsec.ike.TunnelModeChildSessionParams
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.system.OsConstants
import androidx.annotation.RequiresApi
import com.sepehr.ikev2.data.SecretTunnelSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VpnConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

class Ikev2VpnController(private val appContext: Context) {

    private val vpnManager = appContext.getSystemService(VpnManager::class.java)
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(VpnConnectionState.DISCONNECTED)
    val state: StateFlow<VpnConnectionState> = _state.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var pendingStartAfterConsent = false
    private var connectAttemptTimestampMs = 0L

    private val timeoutRunnable = Runnable {
        if (_state.value == VpnConnectionState.CONNECTING) {
            runCatching { vpnManager.stopProvisionedVpnProfile() }
            _state.value = VpnConnectionState.ERROR
            _errorMessage.value = "پاسخی از تونل IKEv2 دریافت نشد؛ سرور دیگری را امتحان کنید"
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val caps = connectivityManager.getNetworkCapabilities(network)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                cancelTimeout()
                _state.value = VpnConnectionState.CONNECTED
                _errorMessage.value = null
            }
        }

        override fun onLost(network: Network) {
            if (!hasActiveVpnTransport() && _state.value == VpnConnectionState.CONNECTED) {
                _state.value = VpnConnectionState.DISCONNECTED
            }
        }
    }

    private val vpnEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
            if (intent?.action != VpnManager.ACTION_VPN_MANAGER_EVENT) return
            val categories = intent.categories ?: emptySet()
            val errorClass = intent.getIntExtra(VpnManager.EXTRA_ERROR_CLASS, -1)

            when {
                categories.contains(VpnManager.CATEGORY_EVENT_DEACTIVATED_BY_USER) -> {
                    cancelTimeout()
                    _state.value = VpnConnectionState.DISCONNECTED
                    _errorMessage.value = null
                }
                categories.contains(VpnManager.CATEGORY_EVENT_IKE_ERROR) -> {
                    if (errorClass == VpnManager.ERROR_CLASS_NOT_RECOVERABLE ||
                        _state.value == VpnConnectionState.CONNECTING
                    ) {
                        cancelTimeout()
                        runCatching { vpnManager.stopProvisionedVpnProfile() }
                        _state.value = VpnConnectionState.ERROR
                        _errorMessage.value = "خطای مذاکره IKEv2 یا گواهی/احراز هویت سرور"
                    }
                }
                categories.contains(VpnManager.CATEGORY_EVENT_NETWORK_ERROR) -> {
                    if (errorClass == VpnManager.ERROR_CLASS_NOT_RECOVERABLE) {
                        cancelTimeout()
                        runCatching { vpnManager.stopProvisionedVpnProfile() }
                        _state.value = VpnConnectionState.ERROR
                        _errorMessage.value = "خطای شبکه هنگام برقراری تونل IKEv2"
                    }
                }
            }
        }
    }

    init {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching {
            connectivityManager.registerNetworkCallback(request, networkCallback)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val filter = IntentFilter(VpnManager.ACTION_VPN_MANAGER_EVENT)
            runCatching {
                appContext.registerReceiver(vpnEventReceiver, filter, Context.RECEIVER_EXPORTED)
            }
        }

        syncConnectionState()
    }

    private fun hasActiveVpnTransport(): Boolean {
        val active = connectivityManager.activeNetwork
        if (active != null) {
            val caps = connectivityManager.getNetworkCapabilities(active)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) return true
        }
        @Suppress("DEPRECATION")
        return connectivityManager.allNetworks.any { net ->
            val caps = connectivityManager.getNetworkCapabilities(net)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    /**
     * Synchronizes UI state with the genuine OS VPN tunnel state so the app
     * never reports CONNECTED unless the IPsec tunnel is actually established.
     */
    fun syncConnectionState() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val profileState = runCatching { vpnManager.provisionedVpnProfileState }.getOrNull()
            if (profileState != null) {
                when (profileState.state) {
                    VpnProfileState.STATE_CONNECTED -> {
                        cancelTimeout()
                        _state.value = VpnConnectionState.CONNECTED
                        _errorMessage.value = null
                        return
                    }
                    VpnProfileState.STATE_FAILED -> {
                        cancelTimeout()
                        _state.value = VpnConnectionState.ERROR
                        _errorMessage.value = "برقراری تونل IKEv2 ناموفق بود"
                        return
                    }
                    VpnProfileState.STATE_CONNECTING -> {
                        if (_state.value != VpnConnectionState.CONNECTING) {
                            _state.value = VpnConnectionState.CONNECTING
                        }
                        return
                    }
                    VpnProfileState.STATE_DISCONNECTED -> {
                        val elapsed = System.currentTimeMillis() - connectAttemptTimestampMs
                        if (_state.value == VpnConnectionState.CONNECTED ||
                            (_state.value == VpnConnectionState.CONNECTING && elapsed > 3500L)
                        ) {
                            if (!hasActiveVpnTransport()) {
                                cancelTimeout()
                                if (_state.value == VpnConnectionState.CONNECTING) {
                                    _state.value = VpnConnectionState.ERROR
                                    _errorMessage.value = "اتصال توسط سرور IKEv2 رد شد یا بسته شد"
                                } else {
                                    _state.value = VpnConnectionState.DISCONNECTED
                                }
                            }
                        }
                        return
                    }
                }
            }
        }

        if (hasActiveVpnTransport()) {
            cancelTimeout()
            _state.value = VpnConnectionState.CONNECTED
            _errorMessage.value = null
        } else if (_state.value == VpnConnectionState.CONNECTED) {
            _state.value = VpnConnectionState.DISCONNECTED
        }
    }

    /**
     * Builds a native Android Ikev2VpnProfile:
     * - API 33+: Uses IkeTunnelConnectionParams so ServerHostname = IP and Remote ID (IDr) = Domain!
     * - API 30-32: Uses domain as serverAddr & username as identity so Remote ID matches TLS SAN.
     */
    fun connect(spec: SecretTunnelSpec): Intent? {
        return try {
            cancelTimeout()
            _errorMessage.value = null
            _state.value = VpnConnectionState.CONNECTING
            connectAttemptTimestampMs = System.currentTimeMillis()

            val profile = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                buildApi33Profile(spec)
            } else {
                Ikev2VpnProfile.Builder(spec.serverIdentity, spec.ikeUsername)
                    .setAuthUsernamePassword(spec.ikeUsername, spec.ikePassword, null)
                    .setBypassable(false)
                    .setMetered(false)
                    .build()
            }

            val consentIntent = vpnManager.provisionVpnProfile(profile)
            if (consentIntent != null) {
                pendingStartAfterConsent = true
                consentIntent
            } else {
                startProvisionedTunnel()
                null
            }
        } catch (e: Exception) {
            cancelTimeout()
            _state.value = VpnConnectionState.ERROR
            _errorMessage.value = "پیکربندی تونل IKEv2 با خطا مواجه شد"
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun buildApi33Profile(spec: SecretTunnelSpec): Ikev2VpnProfile {
        val ikeProposalGcm = IkeSaProposal.Builder()
            .addEncryptionAlgorithm(SaProposal.ENCRYPTION_ALGORITHM_AES_GCM_16, SaProposal.KEY_LEN_AES_256)
            .addEncryptionAlgorithm(SaProposal.ENCRYPTION_ALGORITHM_AES_GCM_16, SaProposal.KEY_LEN_AES_128)
            .addPseudorandomFunction(SaProposal.PSEUDORANDOM_FUNCTION_SHA2_256)
            .addPseudorandomFunction(SaProposal.PSEUDORANDOM_FUNCTION_SHA2_384)
            .addPseudorandomFunction(SaProposal.PSEUDORANDOM_FUNCTION_SHA2_512)
            .addDhGroup(SaProposal.DH_GROUP_2048_BIT_MODP)
            .addDhGroup(SaProposal.DH_GROUP_CURVE_25519)
            .addDhGroup(SaProposal.DH_GROUP_3072_BIT_MODP)
            .addDhGroup(SaProposal.DH_GROUP_4096_BIT_MODP)
            .build()

        val ikeProposalCbc = IkeSaProposal.Builder()
            .addEncryptionAlgorithm(SaProposal.ENCRYPTION_ALGORITHM_AES_CBC, SaProposal.KEY_LEN_AES_256)
            .addEncryptionAlgorithm(SaProposal.ENCRYPTION_ALGORITHM_AES_CBC, SaProposal.KEY_LEN_AES_128)
            .addIntegrityAlgorithm(SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_256_128)
            .addIntegrityAlgorithm(SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_384_192)
            .addIntegrityAlgorithm(SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_512_256)
            .addIntegrityAlgorithm(SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA1_96)
            .addPseudorandomFunction(SaProposal.PSEUDORANDOM_FUNCTION_SHA2_256)
            .addPseudorandomFunction(SaProposal.PSEUDORANDOM_FUNCTION_SHA2_384)
            .addPseudorandomFunction(SaProposal.PSEUDORANDOM_FUNCTION_HMAC_SHA1)
            .addDhGroup(SaProposal.DH_GROUP_2048_BIT_MODP)
            .addDhGroup(SaProposal.DH_GROUP_1536_BIT_MODP)
            .addDhGroup(SaProposal.DH_GROUP_CURVE_25519)
            .build()

        val childProposalGcm = ChildSaProposal.Builder()
            .addEncryptionAlgorithm(SaProposal.ENCRYPTION_ALGORITHM_AES_GCM_16, SaProposal.KEY_LEN_AES_256)
            .addEncryptionAlgorithm(SaProposal.ENCRYPTION_ALGORITHM_AES_GCM_16, SaProposal.KEY_LEN_AES_128)
            .build()

        val childProposalCbc = ChildSaProposal.Builder()
            .addEncryptionAlgorithm(SaProposal.ENCRYPTION_ALGORITHM_AES_CBC, SaProposal.KEY_LEN_AES_256)
            .addEncryptionAlgorithm(SaProposal.ENCRYPTION_ALGORITHM_AES_CBC, SaProposal.KEY_LEN_AES_128)
            .addIntegrityAlgorithm(SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_256_128)
            .addIntegrityAlgorithm(SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_384_192)
            .addIntegrityAlgorithm(SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA1_96)
            .build()

        val eapConfig = EapSessionConfig.Builder()
            .setEapMsChapV2Config(spec.ikeUsername, spec.ikePassword)
            .build()

        val ikeParams = IkeSessionParams.Builder()
            .setServerHostname(spec.serverHost)
            .setRemoteIdentification(IkeFqdnIdentification(spec.serverIdentity))
            .setLocalIdentification(IkeFqdnIdentification(spec.ikeUsername))
            .addIkeSaProposal(ikeProposalGcm)
            .addIkeSaProposal(ikeProposalCbc)
            .setAuthEap(null, eapConfig)
            .setDpdDelaySeconds(30)
            .setNattKeepAliveDelaySeconds(20)
            .build()

        val childParams = TunnelModeChildSessionParams.Builder()
            .addChildSaProposal(childProposalGcm)
            .addChildSaProposal(childProposalCbc)
            .addInternalAddressRequest(OsConstants.AF_INET)
            .addInternalDnsServerRequest(OsConstants.AF_INET)
            .build()

        val tunnelParams = IkeTunnelConnectionParams(ikeParams, childParams)

        return Ikev2VpnProfile.Builder(tunnelParams)
            .setBypassable(false)
            .setMetered(false)
            .setMaxMtu(1360)
            .build()
    }

    fun onConsentResult(granted: Boolean) {
        if (granted && pendingStartAfterConsent) {
            pendingStartAfterConsent = false
            startProvisionedTunnel()
        } else {
            pendingStartAfterConsent = false
            cancelTimeout()
            _state.value = VpnConnectionState.DISCONNECTED
            _errorMessage.value = "مجوز اتصال VPN توسط کاربر تایید نشد"
        }
    }

    private fun startProvisionedTunnel() {
        try {
            connectAttemptTimestampMs = System.currentTimeMillis()
            _state.value = VpnConnectionState.CONNECTING
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vpnManager.startProvisionedVpnProfileSession()
            } else {
                @Suppress("DEPRECATION")
                vpnManager.startProvisionedVpnProfile()
            }
            mainHandler.removeCallbacks(timeoutRunnable)
            mainHandler.postDelayed(timeoutRunnable, 16000L)
        } catch (e: Exception) {
            cancelTimeout()
            _state.value = VpnConnectionState.ERROR
            _errorMessage.value = "امکان شروع سرویس IKEv2 وجود ندارد"
        }
    }

    fun disconnect() {
        cancelTimeout()
        pendingStartAfterConsent = false
        try {
            vpnManager.stopProvisionedVpnProfile()
        } finally {
            _state.value = VpnConnectionState.DISCONNECTED
            _errorMessage.value = null
        }
    }

    private fun cancelTimeout() {
        mainHandler.removeCallbacks(timeoutRunnable)
    }

    fun release() {
        cancelTimeout()
        runCatching {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                appContext.unregisterReceiver(vpnEventReceiver)
            }
        }
    }
}
