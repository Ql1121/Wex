package wex.ui.core

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * 功能设置页（独立 Activity）——从微信加号菜单"WEX 设置"跳转进入。
 * MD3 蓝色风格；开关状态通过 WexStore 写入 media 目录，
 * 微信进程与模块 App 进程共享。
 */
class FeatureSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
        setContent {
            val isDark = isSystemInDarkTheme()
            FeatureSettingsScreen(isDark)
        }
    }
}

@Composable
private fun FeatureSettingsScreen(isDark: Boolean) {
    val bg = if (isDark) Color(0xFF121212) else Color(0xFFFFFFFF)
    val cardBg = if (isDark) Color(0xFF1E1E1E) else Color(0xFFF5F5F5)
    val titleColor = if (isDark) Color(0xFFE0E0E0) else Color(0xFF212121)
    val subColor = if (isDark) Color(0xFF64B5F6) else Color(0xFF1976D2)
    val textColor = if (isDark) Color(0xFFE0E0E0) else Color(0xFF212121)
    val descColor = if (isDark) Color(0xFF888888) else Color(0xFF757575)
    val accent = if (isDark) Color(0xFF64B5F6) else Color(0xFF1976D2)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // 顶部标题
        Text("功能设置", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = titleColor)
        Spacer(Modifier.height(4.dp))
        Text("WeChat Enhance Center", fontSize = 13.sp, color = subColor)
        Spacer(Modifier.height(24.dp))

        // 分组：界面美化
        Text("界面美化", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = subColor,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = cardBg),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            var bottomBarOn by remember {
                mutableStateOf(WexStore.getBool("bottom_bar_enabled", true))
            }
            SwitchRow(
                title = "悬浮圆角底栏",
                desc = "将微信底部导航栏改为悬浮圆角样式，含气泡光晕",
                checked = bottomBarOn,
                textColor = textColor, descColor = descColor, accent = accent
            ) { checked ->
                bottomBarOn = checked
                WexStore.putBool("bottom_bar_enabled", checked)
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    textColor: Color,
    descColor: Color,
    accent: Color,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = textColor)
            Spacer(Modifier.height(2.dp))
            Text(desc, fontSize = 12.sp, color = descColor)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = accent
            )
        )
    }
}