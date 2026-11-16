package com.warpgate.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.warpgate.app.data.EndpointStat
import com.warpgate.app.data.Profile
import com.warpgate.app.data.ProfileType
import com.warpgate.app.ui.theme.Accent
import com.warpgate.app.ui.theme.Accent2
import com.warpgate.app.ui.theme.Green
import com.warpgate.app.ui.theme.Stroke0
import com.warpgate.app.ui.theme.Surface0
import com.warpgate.app.ui.theme.Surface1
import com.warpgate.app.ui.theme.Text0
import com.warpgate.app.ui.theme.Text1

@Composable
fun typeIcon(type: ProfileType): ImageVector = when (type) {
    ProfileType.WARP -> Icons.Filled.Bolt
    ProfileType.AWG -> Icons.Filled.Key
    ProfileType.MASQUE -> Icons.Filled.Lock
    ProfileType.PROXY -> Icons.Filled.Link
}

@Composable
fun typeLabel(type: ProfileType): String = when (type) {
    ProfileType.WARP -> "WARP"
    ProfileType.AWG -> "AmneziaWG"
    ProfileType.MASQUE -> "MASQUE"
    ProfileType.PROXY -> "ПРОКСИ"
}

fun typeColor(type: ProfileType): Color = when (type) {
    ProfileType.WARP -> Accent
    ProfileType.AWG -> Accent2
    ProfileType.MASQUE -> Color(0xFF48C9B0)
    ProfileType.PROXY -> Color(0xFFF0985C)
}

@Composable
fun TypeBadge(type: ProfileType) {
    val c = typeColor(type)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(c.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(typeIcon(type), null, tint = c, modifier = Modifier.size(13.dp))
        Text(
            typeLabel(type),
            color = c,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp
        )
    }
}

@Composable
fun ProfileCard(
    profile: Profile,
    selected: Boolean,
    connected: Boolean,
    best: EndpointStat?,
    onClick: () -> Unit,
    onOptimize: () -> Unit,
    optimizing: Boolean
) {
    val border = when {
        connected -> Green
        selected -> Accent.copy(alpha = 0.6f)
        else -> Stroke0
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clip(RoundedCornerShape(18.dp))
            .background(Surface0)
            .border(1.dp, border, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TypeBadge(profile.type)
            SpacerW(8.dp)
            Text(
                profile.name,
                color = Text0,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (connected) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Green)
                    )
                    Text("ВКЛ", color = Green, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(Icons.Filled.NetworkCheck, null, tint = Text1, modifier = Modifier.size(14.dp))
            Text(
                profile.endpointLabel,
                color = Text1, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        best?.let {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Filled.Bolt, null, tint = Green, modifier = Modifier.size(14.dp))
                Text(
                    "Лучший: ${it.endpoint} — ${it.ms} мс",
                    color = Green, fontSize = 12.sp
                )
            }
        }
        if (profile.type == ProfileType.WARP || profile.type == ProfileType.AWG) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton(
                    text = if (optimizing) "Сканирую…" else "Оптимизировать",
                    enabled = !optimizing,
                    filled = true,
                    onClick = onOptimize
                )
            }
        }
    }
}

@Composable
fun SmallButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    filled: Boolean = false
) {
    val shape = RoundedCornerShape(12.dp)
    val bg: Brush =
        if (filled) Brush.horizontalGradient(listOf(Accent, Accent2))
        else Brush.horizontalGradient(listOf(Surface1, Surface1))
    Box(
        modifier = Modifier
            .clip(shape)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text,
            color = if (enabled) Color.White else Text1,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** Big gradient connect button (Happ-style). */
@Composable
fun ConnectButton(
    connected: Boolean,
    connecting: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val size = 210.dp
    val inner = size - 26.dp
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(
                            (if (connected) Green else Accent).copy(alpha = 0.22f),
                            Color.Transparent
                        )
                    )
                )
        )
        if (connecting) {
            CircularProgressIndicator(
                color = Accent,
                strokeWidth = 3.dp,
                modifier = Modifier.size(inner)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(inner)
                    .clip(CircleShape)
                    .background(
                        if (connected)
                            Brush.linearGradient(listOf(Green.copy(alpha = 0.9f), Color(0xFF0E9F6E)))
                        else Brush.linearGradient(listOf(Accent, Accent2))
                    )
                    .clickable(enabled = enabled, onClick = onClick),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        Icons.Filled.Bolt,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(44.dp)
                    )
                    Text(
                        if (connected) "ОТКЛЮЧИТЬ" else "ПОДКЛЮЧИТЬСЯ",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        letterSpacing = 1.2.sp
                    )
                }
            }
        }
    }
}

@Composable
fun InfoCard(
    title: String,
    value: String,
    accent: Color = Accent,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Surface0)
            .border(1.dp, Stroke0, RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Text(title, color = Text1, fontSize = 11.sp)
        SpacerH(4.dp)
        Text(
            value, color = accent, fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun SpacerW(w: Dp) = Spacer(Modifier.width(w))

@Composable
fun SpacerH(h: Dp) = Spacer(Modifier.height(h))
