package com.warpgate.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import android.content.ContentValues
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.warpgate.app.core.GeneratedConfig
import com.warpgate.app.core.GenFormat
import com.warpgate.app.core.WarpGenEngine
import com.warpgate.app.ui.AppState
import com.warpgate.app.ui.components.SmallButton
import com.warpgate.app.ui.theme.Accent
import com.warpgate.app.ui.theme.Accent2
import com.warpgate.app.ui.theme.Bg0
import com.warpgate.app.ui.theme.BottomNavContentPadding
import com.warpgate.app.ui.theme.Green
import com.warpgate.app.ui.theme.ScreenPadding
import com.warpgate.app.ui.theme.Stroke0
import com.warpgate.app.ui.theme.Surface0
import com.warpgate.app.ui.theme.Surface1
import com.warpgate.app.ui.theme.Text0
import com.warpgate.app.ui.theme.Text1

/**
 * Генератор экспортируемых WARP-конфигов.
 *
 * Регистрирует устройство в Cloudflare WARP и собирает готовый .conf
 * (AmneziaWG) или .yaml (Clash) — в том же формате, что и генератор
 * warp-config-ru.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneratorScreen(onGenerate: () -> Unit) {
    val ctx = LocalContext.current
    val result = AppState.genResult
    val scroll = rememberScrollState()

    // The result card is at the bottom of the screen: bring it into view as
    // soon as the config is ready, otherwise it stays hidden behind the
    // floating navigation.
    LaunchedEffect(result) {
        if (result != null) {
            scroll.animateScrollTo(scroll.maxValue)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg0)
            .systemBarsPadding()
            .verticalScroll(scroll)
            .padding(horizontal = ScreenPadding)
            .padding(bottom = BottomNavContentPadding)
    ) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Bolt, null, tint = Accent, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Text("Генератор", color = Text0, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Свежая регистрация в Cloudflare WARP и готовый конфиг для AmneziaVPN или Clash.",
            color = Text1, fontSize = 13.sp
        )
        Spacer(Modifier.height(16.dp))

        SectionLabel("ФОРМАТ")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GenFormat.entries.forEach { format ->
                FormatCard(
                    format = format,
                    selected = AppState.genFormat == format,
                    modifier = Modifier.weight(1f),
                    onClick = { AppState.genFormat = format }
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        SectionLabel("ЭНДПОИНТ")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            EndpointDropdown(
                label = "Хост",
                displayValue = AppState.genHost ?: "Авто",
                options = listOf<String?>(null) + WarpGenEngine.hosts,
                optionLabel = { it ?: "Авто" },
                modifier = Modifier.weight(1f),
                onSelect = { AppState.genHost = it }
            )
            EndpointDropdown(
                label = "Порт",
                displayValue = AppState.genPort?.toString() ?: "Авто",
                options = listOf<Int?>(null) + WarpGenEngine.ports,
                optionLabel = { it?.toString() ?: "Авто" },
                modifier = Modifier.weight(1f),
                onSelect = { AppState.genPort = it }
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "«Авто» берёт случайный адрес и порт из пула WARP — разбоз нужных адресов.",
            color = Text1, fontSize = 11.sp
        )
        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Surface0)
                .border(1.dp, Stroke0, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("IPv6", color = Text0, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    "Двухстековый конфиг. По умолчанию выключено.",
                    color = Text1, fontSize = 11.sp
                )
            }
            Switch(
                checked = AppState.genIpv6,
                onCheckedChange = { AppState.genIpv6 = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Accent,
                    uncheckedThumbColor = Text1,
                    uncheckedTrackColor = Surface1,
                    uncheckedBorderColor = Stroke0
                )
            )
        }
        Spacer(Modifier.height(18.dp))

        GenerateButton(busy = AppState.genBusy, onClick = onGenerate)

        if (result != null) {
            Spacer(Modifier.height(20.dp))
            ResultSection(result, ctx)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = Text1,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun FormatCard(
    format: GenFormat,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val border = if (selected) Accent else Stroke0
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) Accent.copy(alpha = 0.12f) else Surface0)
            .border(1.dp, border, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                format.title,
                color = Text0,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f)
            )
            if (selected) {
                Icon(Icons.Filled.Check, "Выбрано", tint = Accent, modifier = Modifier.size(18.dp))
            }
        }
        Text(
            format.subtitle,
            color = Text1,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> EndpointDropdown(
    label: String,
    displayValue: String,
    options: List<T?>,
    optionLabel: (T?) -> String,
    modifier: Modifier = Modifier,
    onSelect: (T?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = displayValue,
            onValueChange = {},
            readOnly = true,
            label = { Text(label, fontSize = 12.sp) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Text0,
                unfocusedTextColor = Text0,
                focusedBorderColor = Accent,
                unfocusedBorderColor = Stroke0,
                focusedLabelColor = Accent,
                unfocusedLabelColor = Text1,
                cursorColor = Accent
            ),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            singleLine = true
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun GenerateButton(busy: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (busy) Brush.horizontalGradient(listOf(Surface1, Surface1))
                else Brush.horizontalGradient(listOf(Accent, Accent2))
            )
            .clickable(enabled = !busy, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    color = Accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Регистрирую в Cloudflare…",
                    color = Text0,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Bolt,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Сгенерировать конфиг",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        }
    }
}

@Composable
private fun ResultSection(result: GeneratedConfig, ctx: Context) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Surface0)
            .border(1.dp, Stroke0, RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Check, null, tint = Green, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                result.fileName,
                color = Text0,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            "${result.endpoint} · ${result.sizeLabel}",
            color = Text1,
            fontSize = 12.sp
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Bg0)
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            Text(
                result.text,
                color = Text1,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton(
                text = "Копировать",
                filled = true,
                onClick = { copyToClipboard(ctx, result) }
            )
            SmallButton(
                text = "Поделиться",
                onClick = { shareConfig(ctx, result) }
            )
            SmallButton(
                text = "Сохранить",
                onClick = { saveToDownloads(ctx, result) }
            )
        }
    }
}

/** Копирует конфиг в буфер обмена. */
fun copyToClipboard(ctx: Context, config: GeneratedConfig) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.setPrimaryClip(ClipData.newPlainText(config.fileName, config.text))
    AppState.showToast("Конфиг скопирован")
}

/** Открывает системный лист «поделиться», чтобы сохранить конфиг в файл. */
fun shareConfig(ctx: Context, config: GeneratedConfig) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, config.fileName)
        putExtra(Intent.EXTRA_TEXT, config.text)
    }
    runCatching {
        ctx.startActivity(Intent.createChooser(send, "Поделиться конфигом"))
    }.onFailure { AppState.showToast("Не удалось открыть меню «Поделиться»") }
}

/**
 * Сохраняет конфиг в «Загрузки».
 *
 * На Android 10+ запись идёт через MediaStore и не требует разрешений; на
 * более старых версиях файл сохраняется через системный диалог выбора.
 */
fun saveToDownloads(ctx: Context, config: GeneratedConfig) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, config.fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            AppState.showToast("Не удалось сохранить файл")
            return
        }
        val ok = runCatching {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(config.text.toByteArray()) }
        }.isSuccess
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        ctx.contentResolver.update(uri, values, null, null)
        AppState.showToast(if (ok) "Сохранено в «Загрузки»" else "Не удалось записать файл")
    } else {
        shareConfig(ctx, config)
    }
}
