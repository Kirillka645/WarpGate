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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.warpgate.app.ui.theme.Bg0
import com.warpgate.app.ui.theme.BottomNavContentPadding
import com.warpgate.app.ui.theme.Surface0
import com.warpgate.app.ui.theme.Text0
import com.warpgate.app.ui.theme.Text1

@Composable
fun SettingsScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg0)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            // Keep the last row reachable: the floating bottom navigation is
            // drawn on top of the content.
            .padding(bottom = BottomNavContentPadding)
    ) {
        Spacer(Modifier.height(10.dp))
        Text("Настройки", color = Text0, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

        SettingsCard("О приложении") {
            InfoLine("WarpGate", "клиент WARP / AmneziaWG / MASQUE")
            InfoLine("Ядро", "amneziawg-go (Go, gomobile)")
            InfoLine("WARP", "регистрация через Cloudflare API, выбор лучшего эндпоинта")
            InfoLine("AmneziaWG", "обфускация Jc/Jmin/Jmax/S1/S2/H1–H4")
            InfoLine("Подписки", "vless · vmess · trojan · ss · hysteria2")
        }
        Spacer(Modifier.height(12.dp))
        SettingsCard("Примечание") {
            InfoLine(
                "MASQUE",
                "WARP поверх HTTP/3 — экспериментальный режим"
            )
            InfoLine(
                "Прокси-профили",
                "подписки импортируются и хранятся; туннелирование прокси-протоколов добавляется в следующем обновлении"
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Surface0)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, color = Text0, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        content()
    }
}

@Composable
private fun InfoLine(k: String, v: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(k, color = Text1, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
        Text(v, color = Text1, fontSize = 13.sp)
    }
}
