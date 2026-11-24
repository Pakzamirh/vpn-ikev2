package com.sepehr.ikev2.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sepehr.ikev2.data.AuthResult
import com.sepehr.ikev2.data.CountryGroupUi
import com.sepehr.ikev2.data.RemoteRepository
import com.sepehr.ikev2.data.ServerNodeUi
import com.sepehr.ikev2.vpn.Ikev2RealPingProber
import com.sepehr.ikev2.vpn.Ikev2VpnController
import com.sepehr.ikev2.vpn.PingResultUi
import com.sepehr.ikev2.vpn.PingStatus
import com.sepehr.ikev2.vpn.VpnConnectionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class MainUiState(
    val isCheckingSession: Boolean = true,
    val isAuthenticated: Boolean = false,
    val loggedInUsername: String = "",
    val isLoggingIn: Boolean = false,
    val loginError: String? = null,
    val isRefreshingServers: Boolean = false,
    val serverLoadError: String? = null,
    val countries: List<CountryGroupUi> = emptyList(),
    val selectedNode: ServerNodeUi? = null,
    val searchQuery: String = "",
    val pingResults: Map<String, PingResultUi> = emptyMap(),
    val isPingingAll: Boolean = false,
    val vpnState: VpnConnectionState = VpnConnectionState.DISCONNECTED,
    val vpnError: String? = null,
    val connectedSeconds: Long = 0L
)

class VpnViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = RemoteRepository(application)
    private val vpnController = Ikev2VpnController(application)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var timerJob: Job? = null
    private var pingJob: Job? = null
    private var statePollJob: Job? = null

    init {
        viewModelScope.launch {
            vpnController.state.collect { state ->
                _uiState.update { it.copy(vpnState = state) }
                if (state == VpnConnectionState.CONNECTED) {
                    stopStatePolling()
                    startTimer()
                } else if (state == VpnConnectionState.CONNECTING) {
                    stopTimer()
                    startStatePolling()
                } else {
                    stopStatePolling()
                    stopTimer()
                }
            }
        }
        viewModelScope.launch {
            vpnController.errorMessage.collect { err ->
                _uiState.update { it.copy(vpnError = err) }
            }
        }
        restoreSession()
    }

    fun syncVpnState() {
        vpnController.syncConnectionState()
    }

    private fun startStatePolling() {
        statePollJob?.cancel()
        statePollJob = viewModelScope.launch {
            while (_uiState.value.vpnState == VpnConnectionState.CONNECTING) {
                delay(700L)
                vpnController.syncConnectionState()
            }
        }
    }

    private fun stopStatePolling() {
        statePollJob?.cancel()
    }

    private fun restoreSession() {
        viewModelScope.launch {
            val savedUser = repository.getSavedUsername()
            if (savedUser != null && repository.verifyUserActive(savedUser)) {
                _uiState.update {
                    it.copy(
                        isCheckingSession = false,
                        isAuthenticated = true,
                        loggedInUsername = savedUser
                    )
                }
                refreshServers()
            } else {
                if (savedUser != null) repository.clearSession()
                _uiState.update { it.copy(isCheckingSession = false, isAuthenticated = false) }
            }
        }
    }

    fun login(username: String, password: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoggingIn = true, loginError = null) }
            when (val result = repository.login(username, password)) {
                is AuthResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoggingIn = false,
                            isAuthenticated = true,
                            loggedInUsername = result.username,
                            loginError = null
                        )
                    }
                    refreshServers()
                }
                AuthResult.InvalidCredentials -> {
                    _uiState.update {
                        it.copy(isLoggingIn = false, loginError = "نام کاربری یا رمز عبور اشتباه است")
                    }
                }
                AuthResult.AccountDisabled -> {
                    _uiState.update {
                        it.copy(isLoggingIn = false, loginError = "حساب کاربری شما غیرفعال شده است")
                    }
                }
                is AuthResult.NetworkError -> {
                    _uiState.update {
                        it.copy(isLoggingIn = false, loginError = result.message)
                    }
                }
            }
        }
    }

    fun refreshServers() {
        if (!_uiState.value.isAuthenticated) return
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshingServers = true, serverLoadError = null) }
            try {
                val groups = repository.fetchCountryGroups()
                _uiState.update { current ->
                    val currentSelectedId = current.selectedNode?.opaqueId
                    val stillExists = groups.flatMap { it.nodes }.firstOrNull { it.opaqueId == currentSelectedId }
                    val fallbackNode = stillExists ?: groups.firstOrNull()?.nodes?.firstOrNull()
                    current.copy(
                        isRefreshingServers = false,
                        countries = groups,
                        selectedNode = fallbackNode,
                        pingResults = emptyMap(),
                        serverLoadError = null
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isRefreshingServers = false,
                        serverLoadError = "خطا در به‌روزرسانی لیست سرورها"
                    )
                }
            }
        }
    }

    fun pingAllServers() {
        val allNodes = _uiState.value.countries.flatMap { it.nodes }
        if (allNodes.isEmpty()) return
        pingNodes(allNodes, markGlobal = true)
    }

    fun pingCountry(group: CountryGroupUi) {
        if (group.nodes.isEmpty()) return
        pingNodes(group.nodes, markGlobal = false)
    }

    private fun pingNodes(nodes: List<ServerNodeUi>, markGlobal: Boolean) {
        if (markGlobal) pingJob?.cancel()
        val job = viewModelScope.launch {
            _uiState.update { current ->
                val updated = current.pingResults.toMutableMap()
                nodes.forEach { updated[it.opaqueId] = PingResultUi(PingStatus.PINGING, null) }
                current.copy(
                    pingResults = updated,
                    isPingingAll = if (markGlobal) true else current.isPingingAll
                )
            }

            val semaphore = Semaphore(8)
            coroutineScope {
                nodes.map { node ->
                    async {
                        semaphore.withPermit {
                            val spec = repository.resolveSecretSpec(node.opaqueId)
                            val res = if (spec != null) {
                                Ikev2RealPingProber.probe(spec.serverHost)
                            } else {
                                PingResultUi(PingStatus.UNREACHABLE, null)
                            }
                            _uiState.update { current ->
                                val nextMap = current.pingResults.toMutableMap()
                                nextMap[node.opaqueId] = res
                                current.copy(pingResults = nextMap)
                            }
                        }
                    }
                }.awaitAll()
            }

            if (markGlobal) {
                _uiState.update { it.copy(isPingingAll = false) }
            }
        }
        if (markGlobal) pingJob = job
    }

    fun selectBestReachableServer(onConsentRequired: (Intent) -> Unit) {
        val best = _uiState.value.countries
            .flatMap { it.nodes }
            .mapNotNull { node ->
                val ping = _uiState.value.pingResults[node.opaqueId]
                if (ping?.status == PingStatus.REACHABLE && ping.latencyMs != null) {
                    node to ping.latencyMs
                } else null
            }
            .minByOrNull { it.second }
            ?.first ?: return

        selectNode(best, onConsentRequired)
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun selectNode(node: ServerNodeUi, onConsentRequired: (Intent) -> Unit) {
        val wasConnected = _uiState.value.vpnState == VpnConnectionState.CONNECTED
        _uiState.update { it.copy(selectedNode = node) }
        if (wasConnected) {
            vpnController.disconnect()
            connectSelected(onConsentRequired)
        }
    }

    fun toggleConnection(onConsentRequired: (Intent) -> Unit) {
        when (_uiState.value.vpnState) {
            VpnConnectionState.CONNECTED, VpnConnectionState.CONNECTING -> {
                vpnController.disconnect()
            }
            VpnConnectionState.DISCONNECTED, VpnConnectionState.ERROR -> {
                connectSelected(onConsentRequired)
            }
        }
    }

    private fun connectSelected(onConsentRequired: (Intent) -> Unit) {
        val node = _uiState.value.selectedNode ?: return
        val spec = repository.resolveSecretSpec(node.opaqueId) ?: return
        val intent = vpnController.connect(spec)
        if (intent != null) {
            onConsentRequired(intent)
        }
    }

    fun onVpnConsentResult(granted: Boolean) {
        vpnController.onConsentResult(granted)
    }

    fun logout() {
        pingJob?.cancel()
        vpnController.disconnect()
        viewModelScope.launch {
            repository.clearSession()
            _uiState.update {
                MainUiState(isCheckingSession = false, isAuthenticated = false)
            }
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            var sec = 0L
            while (true) {
                _uiState.update { it.copy(connectedSeconds = sec) }
                delay(1000L)
                sec++
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        _uiState.update { it.copy(connectedSeconds = 0L) }
    }

    override fun onCleared() {
        super.onCleared()
        pingJob?.cancel()
        statePollJob?.cancel()
        vpnController.release()
    }
}
