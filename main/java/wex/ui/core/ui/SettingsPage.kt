package wex.ui.core.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsPage(isDark: Boolean = false, modifier: Modifier = Modifier) {
    val bg = if (isDark) Color(0xFF121212) else Color(0xFFFFFFFF)
    val cardBg = if (isDark) Color(0xFF1E1E1E) else Color(0xFFF5F5F5)
    val titleColor = if (isDark) Color(0xFFE0E0E0) else Color(0xFF212121)
    val cardTitleColor = if (isDark) Color(0xFF64B5F6) else Color(0xFF1976D2)
    val textColor = if (isDark) Color(0xFFE0E0E0) else Color(0xFF212121)
    val labelColor = if (isDark) Color(0xFF888888) else Color(0xFF757575)

    val context = LocalContext.current
    // 宿主(微信)版本：查不到则显示未安装
    val hostVersion = remember {
        try {
            context.packageManager.getPackageInfo("com.tencent.mm", 0).versionName ?: "未知"
        } catch (e: Exception) { "未安装" }
    }
    // 系统版本
    val sysVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    // 构建时间：读本模块 APK 的最后更新时间
    val buildTime = remember {
        try {
            val t = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(t))
        } catch (e: Exception) { "未知" }
    }

    Column(modifier = modifier.fillMaxSize().background(bg).verticalScroll(rememberScrollState()).padding(horizontal =20.dp, vertical =16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("关于", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = titleColor)
        Spacer(Modifier.height(20.dp))

        SectionCard(
            icon = Icons.Filled.Person,
            title = "作者信息",
            items = listOf(
                Pair("作者", "祁龍"),
                Pair("版本", wex.ui.core.BuildConfig.VERSION_NAME),
                Pair("框架", "LSPosed / Xposed"),
                Pair("目标", "微信 (com.tencent.mm)"),
            ),
            cardBg = cardBg, titleColor = cardTitleColor, textColor = textColor, labelColor = labelColor
        )
        Spacer(Modifier.height(16.dp))

        SectionCard(
            icon = Icons.Filled.Info,
            title = "环境信息",
            items = listOf(
                Pair("宿主版本", hostVersion),
                Pair("系统版本", sysVersion),
                Pair("构建时间", buildTime),
            ),
            cardBg = cardBg, titleColor = cardTitleColor, textColor = textColor, labelColor = labelColor
        )
        Spacer(Modifier.height(16.dp))

        // TG + GitHub 图标
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(id = wex.ui.core.R.drawable.ic_tg),
                contentDescription = "Telegram",
                modifier = Modifier
                    .size(48.dp)
                    .padding(8.dp)
                    .clickable {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/Wex_Core"))
                        context.startActivity(intent)
                    }
            )
            Image(
                painter = painterResource(id = wex.ui.core.R.drawable.ic_github),
                contentDescription = "GitHub",
                modifier = Modifier
                    .size(48.dp)
                    .padding(8.dp)
                    .clickable {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Ql1121/Wex"))
                        context.startActivity(intent)
                    }
            )
        }
        Spacer(Modifier.height(32.dp))
    }
}