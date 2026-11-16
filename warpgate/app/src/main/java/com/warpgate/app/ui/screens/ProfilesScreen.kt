package com.warpgate.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.warpgate.app.data.Profile
import com.warpgate.app.ui.AppState
import com.warpgate.app.ui.components.ProfileCard
import com.warpgate.app.ui.components.SmallButton
import com.warpgate.app.ui.theme.Bg0
import com.warpgate.app.ui.theme.Text0
import com.warpgate.app.ui.theme.Text1
import com.warpgate.app.vpn.VpnSession

@Composable
fun ProfilesScreen(
    onConnect: (Profile) -> Unit,
    onDisconnect: () -> Unit,
    onOptimize: (Profile) -> Unit,
    onAddClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg0)
            .systemBarsPadding()
            .padding(horizontal = 18.dp)
    ) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Ключи", color = Text0, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            SmallButton(
                text = "+ Добавить",
                filled = true,
                onClick = onAddClick
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "WARP · AmneziaWG · MASQUE · Подписки",
            color = Text1, fontSize = 12.sp
        )
        Spacer(Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(AppState.profiles, key = { _, p -> p.id }) { _, profile ->
                val connected =
                    VpnSession.connected && profile.id == VpnSession.activeProfileId
                Column {
                    ProfileCard(
                        profile = profile,
                        selected = profile.id == VpnSession.activeProfileId,
                        connected = connected,
                        best = AppState.bestEndpoint(profile),
                        onClick = { if (connected) onDisconnect() else onConnect(profile) },
                        onOptimize = { onOptimize(profile) },
                        optimizing = AppState.optimizing
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { AppState.deleteProfile(profile.id) }) {
                            Icon(
                                Icons.Filled.Delete, "Удалить",
                                tint = Text1, modifier = Modifier.width(22.dp)
                            )
                        }
                    }
                }
            }
            item {
                if (AppState.profiles.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Пока пусто", color = Text0, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Добавьте WARP, AmneziaWG, MASQUE\nили вставьте подписку как в Happ",
                            color = Text1, fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

