package wex.ui.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import wex.ui.core.WexStore
import wex.ui.core.R
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.Handler
import android.os.Looper
import wex.ui.core.ui.theme.MyApplicationTheme

// ============================================================
// 主页面
// ============================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    isModuleActivated: Boolean = false,
    isDark: Boolean = false,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val backgroundColor = if (isDark) Color(0xFF121212) else Color(0xFFFFFFFF)
    val cardBg = if (isDark) Color(0xFF1E1E1E) else Color(0xFFF5F5F5)
    val titleColor = if (isDark) Color(0xFFE0E0E0) else Color(0xFF212121)
    val subtitleColor = if (isDark) Color(0xFF888888) else Color(0xFF9E9E9E)
    val cardTitleColor = if (isDark) Color(0xFF64B5F6) else Color(0xFF1976D2)
    val textColor = if (isDark) Color(0xFFE0E0E0) else Color(0xFF212121)
    val labelColor = if (isDark) Color(0xFF888888) else Color(0xFF757575)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(scrollState)
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 顶部与状态栏之间保留小间隙（整体上移，但不贴状态栏）
            Spacer(modifier = Modifier.height(4.dp))
            // ========== 标题区（横幅插画，缩小至 75% 宽度，圆角） ==========
            Image(
                painter = painterResource(id = wex.ui.core.R.drawable.header_banner),
                contentDescription = "本剑不才 曾以此身破万军",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth(0.75f)
                    .clip(RoundedCornerShape(20.dp))
                    .padding(bottom = 16.dp)
            )

            // ========== 模块激活状态卡片 ==========
            ActivatedStatusCard(
                isActivated = isModuleActivated,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ========== 运行日志区 ==========
            LogSection(isDark = isDark, cardBg = cardBg, titleColor = cardTitleColor,
                textColor = textColor, subtitleColor = subtitleColor)

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ============================================================
// 运行日志区（头部：标题 + 排序按钮 + 记录开关；下方可滚动日志）
// ============================================================
@Composable
fun LogSection(
    isDark: Boolean,
    cardBg: Color,
    titleColor: Color,
    textColor: Color,
    subtitleColor: Color
) {
    var logEnabled by remember { mutableStateOf(WexStore.getBool("log_enabled", true)) }
    var descending by remember { mutableStateOf(false) } // false=正序(早的在上，从上往下读)
    var logLines by remember { mutableStateOf(WexStore.readLogTail(100)) }
    val accent = if (isDark) Color(0xFF64B5F6) else Color(0xFF1976D2)
    val logBg = if (isDark) Color(0xFF0D0D0D) else Color(0xFFFAFAFA)
    val logText = if (isDark) Color(0xFFB0BEC5) else Color(0xFF455A64)

    // 实时刷新：页面在前台组合期间每 1.5 秒重读日志与开关状态
    // （小窗同屏时实时更新；进后台回前台协程仍在，自然显示最新）
    LaunchedEffect(Unit) {
        while (true) {
            logLines = WexStore.readLogTail(100)
            logEnabled = WexStore.getBool("log_enabled", true)
            kotlinx.coroutines.delay(1500)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            // 头部一行：标题 | 排序按钮 | 开关
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "日志区",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = titleColor,
                    modifier = Modifier.weight(1f)
                )
                // 清空日志按钮
                IconButton(onClick = {
                    try {
                        WexStore.clearLog()
                        logLines = emptyList()
                    } catch (_: Exception) {}
                }) {
                    Text("✕", fontSize = 16.sp, color = subtitleColor)
                }
                // 排序按钮
                IconButton(onClick = {
                    descending = !descending
                }) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_sort),
                        contentDescription = "排序",
                        tint = accent,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                // 记录开关
                Switch(
                    checked = logEnabled,
                    onCheckedChange = { checked ->
                        logEnabled = checked
                        WexStore.putBool("log_enabled", checked)
                        // 强制写一条状态提醒（不受开关拦截）
                        WexStore.logStatus(if (checked) "日志记录已开启" else "日志记录已关闭")
                        logLines = WexStore.readLogTail(100)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = accent
                    )
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 日志内容区：固定高度，超出滚动
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(logBg)
                    .padding(12.dp)
            ) {
                val shown = if (descending) logLines.reversed() else logLines
                if (shown.isEmpty()) {
                    Text(
                        text = "暂无日志，打开微信后将自动记录",
                        fontSize = 12.sp,
                        color = subtitleColor,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        shown.forEach { line ->
                            Text(
                                text = line,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = logText,
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============================================================
// 激活状态卡片
// ============================================================
@Composable
fun ActivatedStatusCard(
    isActivated: Boolean,
    modifier: Modifier = Modifier
) {
    val bgGradient = if (isActivated) {
        Brush.horizontalGradient(
            colors = listOf(Color(0xFFFFFFFF), Color(0xFFF8F8F8))
        )
    } else {
        Brush.horizontalGradient(
            colors = listOf(Color(0xFFFFF3E0), Color(0xFFFFE0B2))
        )
    }
    val statusText = if (isActivated) "模块已激活" else "模块未激活"
    val statusColor = if (isActivated) Color(0xFF4CAF50) else Color(0xFFE65100)
    val accentColor = if (isActivated) Color(0xFF4CAF50) else Color(0xFFFF9800)
    val lsposedColor = if (isActivated) Color(0xFF212121) else Color(0xFFFF9800)

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(brush = bgGradient, shape = RoundedCornerShape(16.dp))
                .padding(horizontal = 20.dp, vertical = 20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(
                        text = statusText,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = accentColor
                    )
                    Text(
                        text = "LSPosed",
                        fontSize = 13.sp,
                        color = lsposedColor.copy(alpha = 0.85f)
                    )
                }
            }
        }
    }
}

// ============================================================
// 通用信息卡片
// ============================================================
@Composable
fun SectionCard(
    icon: ImageVector,
    title: String,
    items: List<Pair<String, String>>,
    shape: RoundedCornerShape = RoundedCornerShape(16.dp),
    modifier: Modifier = Modifier,
    cardBg: Color = Color.White,
    titleColor: Color = Color(0xFF1976D2),
    textColor: Color = Color(0xFF212121),
    labelColor: Color = Color(0xFF757575)
) {
    Card(
        modifier = modifier,
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = cardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = titleColor,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = titleColor
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider(color = Color(0xFFE0E0E0), thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(12.dp))

            items.forEach { (label, value) ->
                InfoRow(label = label, value = value, textColor = textColor, labelColor = labelColor)
                if (label != items.last().first) {
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }
        }
    }
}

// ============================================================
// 单行信息
// ============================================================
@Composable
fun InfoRow(label: String, value: String, textColor: Color = Color(0xFF212121), labelColor: Color = Color(0xFF757575)) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = labelColor,
            modifier = Modifier.weight(0.3f)
        )
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = textColor,
            modifier = Modifier.weight(0.7f)
        )
    }
}

// ============================================================
// 预览
// ============================================================
@Preview(showBackground = true, backgroundColor = 0xFFF5F5F5)
@Composable
fun MainScreenPreview() {
    MyApplicationTheme {
        MainScreen(isModuleActivated = true)
    }
}