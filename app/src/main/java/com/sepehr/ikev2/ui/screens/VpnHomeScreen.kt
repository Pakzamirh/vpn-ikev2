package com.sepehr.ikev2.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sepehr.ikev2.data.CountryGroupUi
import com.sepehr.ikev2.data.ServerNodeUi
import com.sepehr.ikev2.ui.MainUiState
import com.sepehr.ikev2.ui.components.AnimatedConnectRing
import com.sepehr.ikev2.ui.components.CountryListShimmer
import com.sepehr.ikev2.ui.components.SignalStrengthBars
import com.sepehr.ikev2.ui.theme.*
import com.sepehr.ikev2.vpn.PingResultUi
import com.sepehr.ikev2.vpn.PingStatus
import com.sepehr.ikev2.vpn.VpnConnectionState

@Composable
fun VpnHomeScreen(
    uiState: MainUiState,
    onSearchChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onPingAll: () -> Unit,
    onPingCountry: (CountryGroupUi) -> Unit,
    onPickBest: () -> Unit,
    onSelectNode: (ServerNodeUi) -> Unit,
    onToggleConnection: () -> Unit,
    onLogout: () -> Unit
) {
    var expandedCountryCode by remember { mutableStateOf<String?>(null) }

    val hasReachablePing = remember(uiState.pingResults) {
        uiState.pingResults.values.any { it.status == PingStatus.REACHABLE }
    }

    val filteredCountries = remember(uiState.countries, uiState.searchQuery, uiState.pingResults) {
        val q = uiState.searchQuery.trim()
        val base = if (q.isEmpty()) uiState.countries
        else uiState.countries.filter { group ->
            group.countryNameFa.contains(q, ignoreCase = true) ||
                group.countryCode.contains(q, ignoreCase = true) ||
                group.nodes.any { it.cityLabel.contains(q, ignoreCase = true) }
        }
        base.sortedBy { group ->
            group.nodes.minOfOrNull { node ->
                val p = uiState.pingResults[node.opaqueId]
                if (p?.status == PingStatus.REACHABLE && p.latencyMs != null) p.latencyMs else Int.MAX_VALUE
            } ?: Int.MAX_VALUE
        }
    }

    val ambientGlow = when (uiState.vpnState) {
        VpnConnectionState.CONNECTED -> EmeraldShield.copy(alpha = 0.16f)
        VpnConnectionState.CONNECTING -> CobaltAccent.copy(alpha = 0.18f)
        VpnConnectionState.ERROR -> CrimsonAlert.copy(alpha = 0.14f)
        else -> CobaltAccent.copy(alpha = 0.08f)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(340.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(ambientGlow, Color.Transparent),
                        radius = 560f
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 18.dp)
        ) {
            // Premium Top Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(
                                Brush.linearGradient(listOf(CobaltAccent, EmeraldShield))
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        val initial = uiState.loggedInUsername.firstOrNull()?.uppercaseChar()?.toString() ?: "S"
                        Text(
                            text = initial,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Column {
                        Text(
                            text = "سپهر IKEv2",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "کاربر: ${uiState.loggedInUsername} · ${uiState.countries.size} کشور",
                            fontSize = 11.5.sp,
                            color = TextSecondary
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeaderIconButton(
                        icon = Icons.Rounded.Refresh,
                        contentDescription = "به‌روزرسانی سرورها",
                        enabled = !uiState.isRefreshingServers,
                        tint = if (uiState.isRefreshingServers) CobaltAccent else TextPrimary,
                        onClick = onRefresh
                    )
                    HeaderIconButton(
                        icon = Icons.AutoMirrored.Rounded.Logout,
                        contentDescription = "خروج",
                        enabled = true,
                        tint = TextSecondary,
                        onClick = onLogout
                    )
                }
            }

            // Hero Connection Card (Strictly ZERO IP / Domain / Auth Exposure)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(SurfaceCard, Color(0xFF0B101B))
                        )
                    )
                    .border(
                        width = 1.dp,
                        color = when (uiState.vpnState) {
                            VpnConnectionState.CONNECTED -> EmeraldShield.copy(alpha = 0.45f)
                            VpnConnectionState.CONNECTING -> CobaltAccent.copy(alpha = 0.45f)
                            VpnConnectionState.ERROR -> CrimsonAlert.copy(alpha = 0.45f)
                            else -> BorderHairline
                        },
                        shape = RoundedCornerShape(26.dp)
                    )
                    .padding(vertical = 18.dp, horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AnimatedConnectRing(
                    state = uiState.vpnState,
                    enabled = uiState.selectedNode != null,
                    onClick = onToggleConnection
                )

                Spacer(modifier = Modifier.height(10.dp))

                val statusLabel = when (uiState.vpnState) {
                    VpnConnectionState.CONNECTED -> "متصل و محافظت‌شده"
                    VpnConnectionState.CONNECTING -> "در حال برقراری تونل واقعی IKEv2..."
                    VpnConnectionState.ERROR -> uiState.vpnError ?: "خطا در برقراری اتصال"
                    VpnConnectionState.DISCONNECTED -> "آماده اتصال امن"
                }

                Text(
                    text = statusLabel,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = when (uiState.vpnState) {
                        VpnConnectionState.CONNECTED -> EmeraldShield
                        VpnConnectionState.ERROR -> CrimsonAlert
                        VpnConnectionState.CONNECTING -> CobaltAccent
                        else -> TextPrimary
                    }
                )

                if (uiState.vpnState == VpnConnectionState.CONNECTED) {
                    val mins = uiState.connectedSeconds / 60
                    val secs = uiState.connectedSeconds % 60
                    Text(
                        text = String.format("%02d:%02d", mins, secs),
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontFeatureSettings = "tnum",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = EmeraldShield
                        ),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                uiState.selectedNode?.let { node ->
                    val selectedPing = uiState.pingResults[node.opaqueId]
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(SurfaceInput)
                            .border(1.dp, BorderHairline, RoundedCornerShape(14.dp))
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = node.flagEmoji, fontSize = 20.sp)
                            Text(
                                text = "${node.countryNameFa} · ${node.cityLabel}",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (selectedPing?.status == PingStatus.REACHABLE && selectedPing.latencyMs != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                SignalStrengthBars(latencyMs = selectedPing.latencyMs)
                                Text(
                                    text = "${selectedPing.latencyMs} ms",
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontFeatureSettings = "tnum",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = EmeraldShield
                                    )
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Real IKEv2 Ping Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(SurfaceCard)
                        .border(
                            width = 1.dp,
                            color = if (uiState.isPingingAll) CobaltAccent else BorderBright,
                            shape = RoundedCornerShape(13.dp)
                        )
                        .clickable(enabled = !uiState.isPingingAll && uiState.countries.isNotEmpty()) {
                            onPingAll()
                        }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (uiState.isPingingAll) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
                            color = CobaltAccent,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Speed,
                            contentDescription = null,
                            tint = CobaltAccent,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(7.dp))
                    Text(
                        text = if (uiState.isPingingAll) "در حال تست هندشیک IKEv2..." else "تست پینگ واقعی همه",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                }

                if (hasReachablePing) {
                    Row(
                        modifier = Modifier
                            .height(44.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(
                                Brush.horizontalGradient(listOf(EmeraldShield, Color(0xFF059669)))
                            )
                            .clickable(onClick = onPickBest)
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Bolt,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "بهترین",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Sleek Obsidian Search Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(SurfaceCard)
                    .border(1.dp, BorderHairline, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (uiState.searchQuery.isEmpty()) {
                        Text(
                            text = "جستجوی نام کشور یا شهر...",
                            color = TextMuted,
                            fontSize = 13.sp
                        )
                    }
                    BasicTextField(
                        value = uiState.searchQuery,
                        onValueChange = onSearchChange,
                        singleLine = true,
                        cursorBrush = SolidColor(CobaltAccent),
                        textStyle = TextStyle(color = TextPrimary, fontSize = 13.5.sp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (uiState.searchQuery.isNotEmpty()) {
                    IconButton(
                        onClick = { onSearchChange("") },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "پاک کردن جستجو",
                            tint = TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Country List (Grouped strictly by Flag & Country — zero IP/domain exposure)
            if (uiState.isRefreshingServers && uiState.countries.isEmpty()) {
                CountryListShimmer()
            } else if (uiState.serverLoadError != null && uiState.countries.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(uiState.serverLoadError, color = CrimsonAlert, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onRefresh,
                        colors = ButtonDefaults.buttonColors(containerColor = CobaltAccent)
                    ) {
                        Text("تلاش مجدد")
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 20.dp)
                ) {
                    items(filteredCountries, key = { it.countryCode }) { group ->
                        CountryAccordionCard(
                            group = group,
                            isExpanded = expandedCountryCode == group.countryCode,
                            selectedNodeId = uiState.selectedNode?.opaqueId,
                            pingResults = uiState.pingResults,
                            onToggleExpand = {
                                expandedCountryCode =
                                    if (expandedCountryCode == group.countryCode) null else group.countryCode
                            },
                            onPingCountry = {
                                expandedCountryCode = group.countryCode
                                onPingCountry(group)
                            },
                            onSelectNode = onSelectNode
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    tint: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceCard)
            .border(1.dp, BorderHairline, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(19.dp)
        )
    }
}

@Composable
private fun CountryAccordionCard(
    group: CountryGroupUi,
    isExpanded: Boolean,
    selectedNodeId: String?,
    pingResults: Map<String, PingResultUi>,
    onToggleExpand: () -> Unit,
    onPingCountry: () -> Unit,
    onSelectNode: (ServerNodeUi) -> Unit
) {
    val hasSelectedNode = group.nodes.any { it.opaqueId == selectedNodeId }
    val bestMs = group.nodes
        .mapNotNull { pingResults[it.opaqueId] }
        .filter { it.status == PingStatus.REACHABLE && it.latencyMs != null }
        .minOfOrNull { it.latencyMs!! }
    val isAnyPinging = group.nodes.any { pingResults[it.opaqueId]?.status == PingStatus.PINGING }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clip(RoundedCornerShape(18.dp))
            .background(SurfaceCard)
            .border(
                width = 1.dp,
                color = if (hasSelectedNode) CobaltAccent.copy(alpha = 0.75f) else BorderHairline,
                shape = RoundedCornerShape(18.dp)
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpand)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(SurfaceElevated)
                        .border(1.dp, BorderBright, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = group.flagEmoji, fontSize = 22.sp)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = group.countryNameFa,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "${group.nodes.size} سرور",
                            fontSize = 11.5.sp,
                            color = TextSecondary
                        )
                        if (bestMs != null) {
                            Text(text = "·", fontSize = 11.5.sp, color = TextMuted)
                            SignalStrengthBars(latencyMs = bestMs)
                            Text(
                                text = "${bestMs} ms",
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontFeatureSettings = "tnum",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (bestMs < 180) EmeraldShield else AmberWarn
                                )
                            )
                        } else if (isAnyPinging) {
                            Text(text = "· در حال تست...", fontSize = 11.5.sp, color = CobaltAccent)
                        }
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(SurfaceElevated)
                        .border(1.dp, BorderHairline, RoundedCornerShape(10.dp))
                        .clickable(onClick = onPingCountry),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Speed,
                        contentDescription = "تست پینگ کشور",
                        tint = if (isAnyPinging) CobaltAccent else TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        AnimatedVisibility(visible = isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                group.nodes.forEach { node ->
                    val isSelected = node.opaqueId == selectedNodeId
                    val ping = pingResults[node.opaqueId]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(13.dp))
                            .background(if (isSelected) CobaltAccent.copy(alpha = 0.14f) else SurfaceInput)
                            .border(
                                width = 1.dp,
                                color = if (isSelected) CobaltAccent.copy(alpha = 0.55f) else BorderHairline,
                                shape = RoundedCornerShape(13.dp)
                            )
                            .clickable { onSelectNode(node) }
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .border(
                                        width = if (isSelected) 5.dp else 1.5.dp,
                                        color = if (isSelected) CobaltAccent else TextMuted,
                                        shape = CircleShape
                                    )
                            )
                            Text(
                                text = node.cityLabel,
                                fontSize = 13.sp,
                                color = if (isSelected) TextPrimary else TextSecondary,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            when (ping?.status) {
                                PingStatus.PINGING -> Text(
                                    text = "تست...",
                                    fontSize = 11.sp,
                                    color = CobaltAccent
                                )
                                PingStatus.REACHABLE -> {
                                    SignalStrengthBars(latencyMs = ping.latencyMs)
                                    Text(
                                        text = "${ping.latencyMs} ms",
                                        style = TextStyle(
                                            fontFamily = FontFamily.Monospace,
                                            fontFeatureSettings = "tnum",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if ((ping.latencyMs ?: 999) < 180) EmeraldShield else AmberWarn
                                        )
                                    )
                                }
                                PingStatus.UNREACHABLE -> Text(
                                    text = "عدم پاسخ",
                                    fontSize = 11.sp,
                                    color = CrimsonAlert
                                )
                                else -> Unit
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = EmeraldShield,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
