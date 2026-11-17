package com.warpgate.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.warpgate.app.data.Profile
import com.warpgate.app.ui.AppState
import com.warpgate.app.ui.components.ConnectButton
import com.warpgate.app.ui.components.InfoCard
import com.warpgate.app.ui.components.ProfileCard
import com.warpgate.app.ui.theme.Accent
import com.warpgate.app.ui.theme.Bg0
import com.warpgate.app.ui.theme.BottomNavContentPadding
import com.warpgate.app.ui.theme.Green
import com.warpgate.app.ui.theme.Surface0
import com.warpgate.app.ui.theme.Text0
import com.warpgate.app.ui.theme.Text1
import com.warpgate.app.vpn.VpnSession

@Composable
fun HomeScreen(
    onConnect: (Profile) -> Unit,
    onDisconnect: () -> Unit,
    onOptimize: (Profile) -> Unit,
    onAddClick: () -> Unit
) {
    val active = AppState.profiles
        .firstOrNull { it.id == VpnSession.activeProfileId }
        ?: AppState.profiles.firstOrNull()
    val connected = VpnSession.connected
    val connecting = VpnSession.connecting
    val best = active?.let { AppState.bestEndpoint(it) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg0)
            .systemBarsPadding()
            .padding(horizontal = 18.dp)
            // Keep the last row reachable: the floating bottom navigation is
            // drawn on top of the content.
            .padding(bottom = BottomNavContentPadding)
    ) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Shield, null, tint = Accent, modifier = Modifier.size(26.dp))
            Spacer(Modifier.size(8.dp))
            Text("WarpGate", color = Text0, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onAddClick) {
                Icon(Icons.Filled.Add, "Добавить", tint = Text0)
            }
        }

        Spacer(Modifier.height(6.dp))
        // Status strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Surface0)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                when {
                    connecting -> "Подключение…"
                    connected -> "Защищено"
                    else -> "Не защищено"
                },
                color = if (connected) Green else Text1,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            )
            Text(
                active?.let { "${it.name} · ${it.endpointLabel}" } ?: "Нет профиля",
                color = Text1, fontSize = 12.sp
            )
        }

        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ConnectButton(
                connected = connected,
                connecting = connecting,
                enabled = active != null && !connecting,
                onClick = {
                    if (connected) onDisconnect() else active?.let(onConnect)
                }
            )
        }
        Spacer(Modifier.weight(1f))

        if (active != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoCard("ПРОФИЛЬ", active.name, Accent, Modifier.weight(1f))
                InfoCard("ЭНДПОИНТ", active.endpointLabel, Accent, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoCard(
                    "ПИНГ",
                    best?.let { "${it.ms} мс" } ?: "—",
                    if (best != null) Green else Text1,
                    Modifier.weight(1f)
                )
                InfoCard(
                    "ТОП-АЛЬТЕРНАТИВА",
                    active.endpoints.filter { it.ok }
                        .getOrNull(1)?.let { "${it.endpoint} · ${it.ms} мс" } ?: "—",
                    Text0,
                    Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(16.dp))
        } else {
            EmptyHint()
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun EmptyHint() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(Accent.copy(alpha = 0.10f), Accent.copy(alpha = 0.02f))
                )
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("Начните с WARP-профиля", color = Text0, fontWeight = FontWeight.SemiBold)
        Text(
            "Нажмите «+», создайте WARP — приложение зарегистрирует устройство " +
                "и выберет самый быстрый эндпоинт.",
            color = Text1, fontSize = 13.sp
        )
    }
}

/** Compact profile picker shown under the connect button. */
@Composable
fun ProfilePicker(
    profiles: List<Profile>,
    activeId: String?,
    onSelect: (Profile) -> Unit
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(profiles, key = { _, p -> p.id }) { _, p ->
            val selected = p.id == activeId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (selected) Surface0.copy(alpha = 1f) else Surface0.copy(alpha = 0.5f)
                    )
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(p.name, color = Text0, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
