package com.warpgate.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.warpgate.app.data.Profile
import com.warpgate.app.ui.AppState
import com.warpgate.app.ui.screens.GeneratorScreen
import com.warpgate.app.ui.screens.HomeScreen
import com.warpgate.app.ui.screens.ProfilesScreen
import com.warpgate.app.ui.screens.SettingsScreen
import com.warpgate.app.ui.theme.Accent
import com.warpgate.app.ui.theme.Bg0
import com.warpgate.app.ui.theme.Bg1
import com.warpgate.app.ui.theme.Stroke0
import com.warpgate.app.ui.theme.Surface0
import com.warpgate.app.ui.theme.Surface1
import com.warpgate.app.ui.theme.Text0
import com.warpgate.app.ui.theme.Text1
import com.warpgate.app.ui.theme.WarpGateTheme
import com.warpgate.app.vpn.VpnSession
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private var pendingConnectProfileId: String? = null
    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == RESULT_OK) {
                pendingConnectProfileId?.let { VpnSession.start(this, it) }
            } else {
                AppState.showToast("Разрешение VPN не выдано")
            }
            pendingConnectProfileId = null
        }

    /** Connects after ensuring the system VPN permission is granted. */
    private fun connectWithPermission(profile: Profile) {
        val intent = android.net.VpnService.prepare(this)
        if (intent == null) {
            VpnSession.start(this, profile.id)
        } else {
            pendingConnectProfileId = profile.id
            vpnPermission.launch(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppState.init(this)

        // The app draws edge to edge: the system bars are transparent and the
        // UI has to handle the insets itself (see the bottom navigation).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )

        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        setContent {
            WarpGateTheme {
                MainApp(onConnectRequest = { connectWithPermission(it) })
            }
        }
    }
}

private enum class Tab { HOME, KEYS, GENERATOR, SETTINGS }

@Composable
fun MainApp(onConnectRequest: (Profile) -> Unit) {
    var tab by remember { mutableStateOf(Tab.HOME) }
    var showAddSheet by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    LaunchedEffect(AppState.toast) {
        if (AppState.toast != null) {
            kotlinx.coroutines.delay(2600)
            AppState.clearToast()
        }
    }

    Box(Modifier.fillMaxSize().background(Bg0)) {
        when (tab) {
            Tab.HOME -> HomeScreen(
                onConnect = onConnectRequest,
                onDisconnect = { VpnSession.stop(ctx) },
                onOptimize = { profile ->
                    scope.launch {
                        AppState.optimize(profile.id)
                            .onFailure { AppState.showToast(it.message ?: "Ошибка оптимизации") }
                            .onSuccess { AppState.showToast("Лучший эндпоинт: ${it.endpointLabel}") }
                    }
                },
                onAddClick = { showAddSheet = true }
            )
            Tab.KEYS -> ProfilesScreen(
                onConnect = onConnectRequest,
                onDisconnect = { VpnSession.stop(ctx) },
                onOptimize = { profile ->
                    scope.launch {
                        AppState.optimize(profile.id)
                            .onFailure { AppState.showToast(it.message ?: "Ошибка оптимизации") }
                            .onSuccess { AppState.showToast("Лучший эндпоинт: ${it.endpointLabel}") }
                    }
                },
                onAddClick = { showAddSheet = true }
            )
            Tab.GENERATOR -> GeneratorScreen(
                onGenerate = {
                    scope.launch {
                        AppState.generateConfig()
                            .onSuccess {
                                AppState.showToast("Конфиг готов: ${it.fileName}")
                            }
                            .onFailure {
                                AppState.showToast(it.message ?: "Не удалось получить конфиг")
                            }
                    }
                }
            )
            Tab.SETTINGS -> SettingsScreen()
        }

        // Bottom navigation.
        //
        // The insets matter: without navigationBarsPadding the pill is drawn
        // behind the system navigation bar (or the gesture handle), so the
        // lower items swallow the taps instead of switching the tab.
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 18.dp, vertical = 10.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Bg1.copy(alpha = 0.96f))
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            NavItem(Icons.Filled.Home, "Главная", tab == Tab.HOME) { tab = Tab.HOME }
            NavItem(Icons.Filled.Key, "Ключи", tab == Tab.KEYS) { tab = Tab.KEYS }
            NavItem(Icons.Filled.Tune, "Генератор", tab == Tab.GENERATOR) { tab = Tab.GENERATOR }
            NavItem(Icons.Filled.Settings, "Настройки", tab == Tab.SETTINGS) { tab = Tab.SETTINGS }
        }

        if (showAddSheet) {
            AddSheet(
                onDismiss = { showAddSheet = false },
                onCreateWarp = {
                    showAddSheet = false
                    scope.launch {
                        AppState.createWarpProfile(ctx)
                            .onSuccess { AppState.showToast("WARP-профиль создан") }
                            .onFailure { AppState.showToast(it.message ?: "Ошибка регистрации") }
                    }
                },
                onCreateMasque = {
                    showAddSheet = false
                    scope.launch {
                        AppState.createMasqueProfile()
                            .onSuccess { AppState.showToast("MASQUE-профиль создан") }
                            .onFailure { AppState.showToast(it.message ?: "Ошибка регистрации MASQUE") }
                    }
                },
                onImportAwg = { text, name ->
                    showAddSheet = false
                    scope.launch {
                        AppState.importAwg(text, name)
                            .onSuccess { AppState.showToast("AmneziaWG профиль добавлен") }
                            .onFailure { AppState.showToast(it.message ?: "Не удалось разобрать конфиг") }
                    }
                },
                onImportLinks = { text ->
                    showAddSheet = false
                    scope.launch {
                        AppState.importContent(text)
                            .onSuccess { AppState.showToast("Импортировано узлов: $it") }
                            .onFailure { AppState.showToast(it.message ?: "Ошибка импорта") }
                    }
                }
            )
        }

        AppState.toast?.let { msg ->
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(bottom = 92.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Surface1)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(msg, color = Text0, fontSize = 13.sp)
            }
        }
    }
}

/**
 * Пункт нижней навигации.
 *
 * The touch target is at least 48 dp tall so the item stays comfortable to hit
 * with the navigation bar inset applied.
 */
@Composable
private fun NavItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val c = if (selected) Accent else Text1
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Icon(icon, label, tint = c, modifier = Modifier.size(22.dp))
        Text(
            label,
            color = c,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

/** Happ-style "add key" sheet: WARP / AmneziaWG / MASQUE / subscriptions. */
@Composable
private fun AddSheet(
    onDismiss: () -> Unit,
    onCreateWarp: () -> Unit,
    onCreateMasque: () -> Unit,
    onImportAwg: (text: String, name: String?) -> Unit,
    onImportLinks: (text: String) -> Unit
) {
    var mode by remember { mutableStateOf(AddMode.MENU) }
    var awgText by remember { mutableStateOf("") }
    var awgName by remember { mutableStateOf("") }
    var linkText by remember { mutableStateOf("") }
    val scroll = rememberScrollState()

    Box(
        Modifier
            .fillMaxSize()
            .background(ComposeColor.Black.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .background(Bg1)
                .clickable(enabled = false) {}
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(scroll)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (mode) {
                AddMode.MENU -> {
                    Text("Добавить ключ", color = Text0, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    AddOption(
                        Icons.Filled.Bolt, "WARP-профиль",
                        "Регистрация в Cloudflare + выбор лучшего эндпоинта"
                    ) { onCreateWarp() }
                    AddOption(
                        Icons.Filled.Key, "AmneziaWG",
                        "Вставить конфиг (.conf) с обфускацией Jc/S1/H1…"
                    ) { mode = AddMode.AWG }
                    AddOption(
                        Icons.Filled.Lock, "MASQUE",
                        "WARP поверх HTTP/3 (экспериментально)"
                    ) { onCreateMasque() }
                    AddOption(
                        Icons.Filled.Link, "Подписка / ссылка",
                        "URL подписки или vless:// vmess:// ss:// trojan:// hy2://"
                    ) { mode = AddMode.LINKS }
                }
                AddMode.AWG -> {
                    Text("AmneziaWG конфиг", color = Text0, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = awgName,
                        onValueChange = { awgName = it },
                        label = { Text("Имя (необязательно)") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = fieldColors()
                    )
                    OutlinedTextField(
                        value = awgText,
                        onValueChange = { awgText = it },
                        label = { Text("Содержимое .conf ([Interface]/[Peer], Jc, H1…)") },
                        modifier = Modifier.fillMaxWidth().height(180.dp),
                        colors = fieldColors()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DialogButton("Сохранить", true) { onImportAwg(awgText, awgName) }
                        DialogButton("Отмена", false) { mode = AddMode.MENU }
                    }
                }
                AddMode.LINKS -> {
                    Text("Подписка / ссылки", color = Text0, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = linkText,
                        onValueChange = { linkText = it },
                        label = {
                            Text("URL https://… или список ссылок vless:// vmess:// ss:// trojan:// hy2://")
                        },
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                        colors = fieldColors()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DialogButton("Импортировать", true) { onImportLinks(linkText) }
                        DialogButton("Отмена", false) { mode = AddMode.MENU }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

private enum class AddMode { MENU, AWG, LINKS }

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Text0,
    unfocusedTextColor = Text0,
    focusedBorderColor = Accent,
    unfocusedBorderColor = Stroke0,
    focusedLabelColor = Accent,
    unfocusedLabelColor = Text1,
    cursorColor = Accent
)

@Composable
private fun AddOption(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Surface0)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Accent, modifier = Modifier.size(24.dp))
        Spacer(Modifier.size(12.dp))
        Column {
            Text(title, color = Text0, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(subtitle, color = Text1, fontSize = 12.sp)
        }
    }
}

@Composable
private fun DialogButton(text: String, filled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (filled) Accent else Surface0)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        Text(
            text,
            color = if (filled) ComposeColor.White else Text0,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp
        )
    }
}
