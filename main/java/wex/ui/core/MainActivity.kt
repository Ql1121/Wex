package wex.ui.core

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import wex.ui.core.ui.MainScreen
import wex.ui.core.ui.SettingsPage
import wex.ui.core.ui.theme.MyApplicationTheme
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.setNavigationBarContrastEnforced(false)
        }

        // 强制底层系统窗口完全透明，干掉四角的底色托
        window.setBackgroundDrawableResource(android.R.color.transparent)

        super.onCreate(savedInstanceState)

        // 申请"所有文件访问权限"（访问微信 media 目录读写日志/配置）
        ensureAllFilesAccess()

        // App 侧启动日志（强制写，保证打开 App 就有新鲜内容）
        try {
            wex.ui.core.WexStore.logStatus("WEX 控制台已启动")
            val logState = if (wex.ui.core.WexStore.getBool("log_enabled", true)) "开启" else "关闭"
            wex.ui.core.WexStore.logStatus("当前日志记录状态：$logState")
        } catch (_: Exception) {}

        setContent {
            MyApplicationTheme {
                var selectedTab by remember { mutableStateOf(0) }
                val tabs = listOf("首页" to Icons.Filled.Home, "我的" to Icons.Filled.Person)
                val isDark = isSystemInDarkTheme()
                
                val surfaceOn = if (isDark) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.2f)
                val bubbleSurface = if (isDark) Color.White.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.5f)
                val tint = if (isDark) Color(0xFF90CAF9) else Color(0xFF1976D2)
                val tintUnsel = if (isDark) Color(0xFF666666) else Color.Gray

                // 纯抓取底层内容，不画底色块
                val backdrop = rememberLayerBackdrop { drawContent() }

                val bubbleOffset = remember { Animatable(0f) }
                val coroutineScope = rememberCoroutineScope()
                var barWidth by remember { mutableStateOf(0) }

                Box(Modifier.fillMaxSize()) {
                    // 主内容层：自适应撑在顶部，给下方留出虚空
                    Box(Modifier.align(Alignment.TopCenter)) {
                        when (selectedTab) { 
                            0 -> MainScreen(isModuleActivated = true, isDark = isDark) 
                            1 -> SettingsPage(isDark = isDark) 
                        }
                    }

                    // 【液态玻璃底座】：加强模糊和折射量
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(24.dp)
                            .height(48.dp)
                            .fillMaxWidth()
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { RoundedCornerShape(28.dp) },
                                effects = { 
                                    vibrancy() 
                                    blur(48f.dp.toPx())             // 模糊加倍，让背景更“毛”
                                    lens(32f.dp.toPx(), 80f.dp.toPx()) // 显著提高液态玻璃的折射感
                                },
                                onDrawSurface = { drawRect(surfaceOn) }
                            )
                            .onSizeChanged { barWidth = it.width }
                            .pointerInput(Unit) {
                                val bubbleHalfWidth = 40.dp.toPx()
                                val dragThreshold = 20f.dp.toPx()

                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    val startX = down.position.x
                                    var isDragging = false
                                    var currentX = startX

                                    coroutineScope.launch {
                                        bubbleOffset.snapTo(startX.coerceIn(bubbleHalfWidth, barWidth - bubbleHalfWidth))
                                    }

                                    do {
                                        val event = awaitPointerEvent()
                                        currentX = event.changes.first().position.x
                                        
                                        if (abs(currentX - startX) > dragThreshold) {
                                            isDragging = true
                                        }

                                        val clampedX = currentX.coerceIn(bubbleHalfWidth, barWidth - bubbleHalfWidth)
                                        coroutineScope.launch { bubbleOffset.snapTo(clampedX) }

                                    } while (event.changes.any { it.pressed })

                                    if (isDragging) {
                                        val finalX = bubbleOffset.value
                                        val targetIndex = if (finalX < barWidth / 2f) 0 else 1
                                        val targetCenter = (barWidth / tabs.size) * (targetIndex + 0.5f)

                                        if (selectedTab != targetIndex) {
                                            selectedTab = targetIndex
                                        }

                                        coroutineScope.launch {
                                            bubbleOffset.animateTo(targetCenter, spring(0.4f, 350f))
                                        }
                                    } else {
                                        val targetIndex = if (startX < barWidth / 2f) 0 else 1
                                        if (selectedTab != targetIndex) {
                                            selectedTab = targetIndex
                                        } else {
                                            val center = (barWidth / tabs.size) * (targetIndex + 0.5f)
                                            coroutineScope.launch {
                                                bubbleOffset.snapTo(center)
                                            }
                                        }
                                    }
                                }
                            }
                    ) {
                        LaunchedEffect(selectedTab, barWidth) {
                            if (barWidth > 0) {
                                bubbleOffset.animateTo(
                                    (barWidth / tabs.size) * (selectedTab + 0.5f),
                                    spring(0.4f, 350f)
                                )
                            }
                        }

                        // 【液态高亮气泡】：同步增加模糊和液态量
                        Box(
                            Modifier
                                .offset {
                                    IntOffset(bubbleOffset.value.roundToInt() - 40.dp.roundToPx(), 4.dp.roundToPx())
                                }
                                .width(80.dp)
                                .height(40.dp)
                                .drawBackdrop(
                                    backdrop = backdrop,
                                    shape = { RoundedCornerShape(20.dp) },
                                    effects = { 
                                        vibrancy() 
                                        blur(24f.dp.toPx())             // 内部气泡也稍微加强
                                        lens(20f.dp.toPx(), 48f.dp.toPx()) 
                                    },
                                    onDrawSurface = { drawRect(bubbleSurface) }
                                )
                        )

                        // 图标与文字
                        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                            tabs.forEachIndexed { index, (label, icon) ->
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp),
                                            tint = if (selectedTab == index) tint else tintUnsel)
                                        Text(label, fontSize = 10.sp, color = if (selectedTab == index) tint else tintUnsel)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 检查并引导用户开启"所有文件访问权限"
    private fun ensureAllFilesAccess() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                if (!android.os.Environment.isExternalStorageManager()) {
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                }
            }
        } catch (e: Exception) {
            try {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: Exception) {}
        }
    }
}