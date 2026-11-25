package app.echo

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.animation.ValueAnimator
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
fun EchoApp(state: AppState) {
    // 系统动画开关会在运行中改变（开发者选项/省电模式），0.5s 轮询让「关闭动画」
    // 立即生效，而不是等下一次重组（P2-6）。
    var motionOn by remember { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
    LaunchedEffect(Unit) {
        while (true) {
            val enabled = ValueAnimator.areAnimatorsEnabled()
            if (enabled != motionOn) motionOn = enabled
            Motion.animationsEnabled = enabled
            delay(500)
        }
    }
    SideEffect { Motion.animationsEnabled = motionOn }
    // 主题色是全局静态 token，若逐帧插值，每一帧都会让读取颜色的整棵树重组（P2-6）。
    // 这里改为一次性换色，再用只在绘制阶段取值的 alpha 做 220ms 交叉淡入。
    val themeFade = remember { Animatable(1f) }
    var statusBarDark by remember { mutableStateOf(!C.isDarkBackground) }
    var themeInitialized by remember { mutableStateOf(false) }
    // 自定义底色的明暗是可变状态：把它读进组合并作为 key，换底色/调明度时系统栏图标才会跟着翻。
    val darkBackground = C.isDarkBackground
    LaunchedEffect(
        C.isDay, C.eyeMode, C.customThemeOn, state.skinIndex, motionOn, darkBackground,
    ) {
        val wantDay = if (C.isDay) 1f else 0f
        val wantEye = if (C.isDay && C.eyeMode != 0) 1f else 0f
        val animate = themeInitialized && motionOn
        C.themeProgress = wantDay
        C.eyeProgress = wantEye
        val newDark = !darkBackground
        if (animate) {
            themeFade.snapTo(0f)
            // 系统栏图标只有明暗两态：在淡入中点翻转，避免动画结束后再跳一次（R8-P2-4）。
            themeFade.animateTo(0.5f, tween(Motion.dur(110, motionOn), easing = Motion.CALM))
            statusBarDark = newDark
            themeFade.animateTo(1f, tween(Motion.dur(110, motionOn), easing = Motion.CALM))
        } else {
            statusBarDark = newDark
            themeFade.snapTo(1f)
        }
        themeInitialized = true
    }
    // 自定义强调色不走预设色阶，直接取用户选的颜色。
    val accent = if (state.customMode) Color(state.customAccent) else C.presetAccent(state.skinIndex)
    val baseDensity = LocalDensity.current
    val textDensity = Density(baseDensity.density, baseDensity.fontScale * state.fontScale)
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) state.importZip(uri)
    }
    var importGuidePage by remember { mutableStateOf(-1) }
    val launchImport: () -> Unit = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }
    val onImport: () -> Unit = {
        if (state.importGuideOff) launchImport() else importGuidePage = 0
    }
    val view = LocalView.current
    // 在组合里读一次，statusBarDark 变化才会触发重组让 SideEffect 真正把标志写进窗口。
    val barsDark = statusBarDark
    // 阈值放在中间灰附近：过早翻成深色图标会在深色背景上「消失」；
    // statusBarDark 在交叉淡入中点翻转，背景与图标只跳一次（R9-P2-5）。
    SideEffect {
        val activity = view.context.findActivity() ?: return@SideEffect
        WindowCompat.getInsetsController(activity.window, view).apply {
            isAppearanceLightStatusBars = barsDark
            isAppearanceLightNavigationBars = barsDark
        }
    }
    val entrance = remember { Animatable(1f) }
    LaunchedEffect(motionOn) {
        if (!motionOn) {
            entrance.snapTo(1f)
        } else {
            entrance.snapTo(0f)
            entrance.animateTo(1f, tween(Motion.HERO, easing = LinearEasing))
        }
    }
    val drawerParallax by animateFloatAsState(
        targetValue = if (state.showDrawer) 1f else 0f,
        animationSpec = tween(Motion.dur(240, motionOn), easing = Motion.STANDARD),
        label = "drawerParallax",
    )
    BackHandler(enabled = state.current != null) { state.backHome() }
    BackHandler(enabled = state.showDrawer) { state.showDrawer = false }
    BackHandler(enabled = state.showSettings) { state.closeSettings() }
    BackHandler(enabled = state.showHelp) { state.closeHelp() }
    BackHandler(enabled = state.showUsage) { state.closeUsage() }
    BackHandler(enabled = state.showTheme) { state.closeTheme() }
    CompositionLocalProvider(LocalDensity provides textDensity) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = accent,
            onPrimary = C.onAccent(accent),
            primaryContainer = C.PanelHigh,
            onPrimaryContainer = C.Ivory,
            secondary = C.Muted,
            onSecondary = C.onAccent(C.Muted),
            background = C.Void,
            onBackground = C.Ivory,
            surface = C.Panel,
            onSurface = C.Ivory,
            surfaceVariant = C.PanelHigh,
            onSurfaceVariant = C.Muted,
            outline = C.Hair,
            outlineVariant = C.Hair,
            error = C.Danger,
            onError = C.Void,
            scrim = Color.Black,
        ),
    ) {
        // 整屏一起淡入淡出：旧底色全屏垫底（含状态栏/导航栏），新底色与内容在第二层
        // 全屏淡入，最后才是安全区内边距。旧底色在主题变更前由 AppState 捕获（R9-P2-5）。
        Box(Modifier.fillMaxSize().background(state.themePrevVoid)) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = themeFade.value }
                .background(C.Void),
        ) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            AnimatedContent(
                targetState = state.current != null,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    translationX = 12.dp.toPx() * drawerParallax
                },
                transitionSpec = {
                    (fadeIn(tween(Motion.dur(200, motionOn))) +
                        slideInVertically(tween(Motion.dur(200, motionOn))) { it / 60 }) togetherWith
                        (fadeOut(tween(Motion.dur(120, motionOn))) +
                            slideOutVertically(tween(Motion.dur(180, motionOn))) { -it / 80 })
                },
                label = "screen",
            ) { inChat ->
                Box(Modifier.fillMaxSize().background(C.Void), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = 840.dp).fillMaxSize()) {
                        if (inChat) ChatScreen(state, accent) else Welcome(state, accent, onImport, entrance)
                    }
                }
            }
            var contentSortByRatio by rememberSaveable { mutableStateOf(true) }
            var reviveSort by rememberSaveable { mutableStateOf(0) }
            AnimatedVisibility(
                visible = state.showDrawer,
                enter = fadeIn(tween(Motion.dur(200, motionOn))),
                exit = fadeOut(tween(Motion.dur(180, motionOn))),
            ) {
                Scrim(alpha = .35f) { state.showDrawer = false }
            }
            AnimatedVisibility(
                visible = state.showDrawer,
                enter = slideInHorizontally(tween(Motion.dur(240, motionOn), easing = Motion.STANDARD)) { -it },
                exit = slideOutHorizontally(tween(Motion.dur(220, motionOn), easing = Motion.EXIT)) { -it },
            ) {
                DrawerPanel(
                    state = state,
                    accent = accent,
                    contentSortByRatio = contentSortByRatio,
                    onContentSortChange = { contentSortByRatio = it },
                    reviveSort = reviveSort,
                    onReviveSortChange = { reviveSort = it },
                )
            }
            AnimatedVisibility(
                visible = state.showSettings,
                enter = fadeIn(tween(Motion.dur(Motion.BASE, motionOn))),
                exit = fadeOut(tween(Motion.dur(Motion.BASE, motionOn))),
            ) {
                Scrim(alpha = .46f) { state.closeSettings() }
            }
            AnimatedVisibility(
                visible = state.showSettings,
                enter = slideInVertically(tween(Motion.dur(Motion.SLOW, motionOn), easing = Motion.STANDARD)) { it } +
                    fadeIn(tween(Motion.dur(Motion.BASE, motionOn))),
                exit = slideOutVertically(tween(Motion.dur(Motion.SLOW, motionOn), easing = Motion.EXIT)) { it } +
                    fadeOut(tween(Motion.dur(Motion.BASE, motionOn))),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                SettingsSheet(state, accent, onImport)
            }
            AnimatedVisibility(
                visible = state.showTheme,
                enter = fadeIn(tween(Motion.dur(Motion.BASE, motionOn))),
                exit = fadeOut(tween(Motion.dur(Motion.BASE, motionOn))),
            ) {
                Scrim(alpha = .46f) { state.closeTheme() }
            }
            AnimatedVisibility(
                visible = state.showTheme,
                enter = slideInVertically(tween(Motion.dur(Motion.SLOW, motionOn), easing = Motion.STANDARD)) { it } +
                    fadeIn(tween(Motion.dur(Motion.BASE, motionOn))),
                exit = slideOutVertically(tween(Motion.dur(Motion.SLOW, motionOn), easing = Motion.EXIT)) { it } +
                    fadeOut(tween(Motion.dur(Motion.BASE, motionOn))),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                ThemeSheet(state, accent)
            }
            if (importGuidePage >= 0) {
                ImportGuide(
                    page = importGuidePage,
                    accent = accent,
                    skip = state.importGuideOff,
                    onSkipChange = state::updateImportGuide,
                    onClose = { importGuidePage = -1 },
                    onPage = { importGuidePage = it },
                    onPick = { importGuidePage = -1; launchImport() },
                )
            }
            if (state.showHelp) HelpCenter(state, accent)
            AnimatedVisibility(
                visible = state.showUsage,
                enter = fadeIn(tween(Motion.dur(Motion.BASE, motionOn))),
                exit = fadeOut(tween(Motion.dur(Motion.BASE, motionOn))),
            ) {
                Scrim(alpha = .46f) { state.closeUsage() }
            }
            AnimatedVisibility(
                visible = state.showUsage,
                enter = slideInVertically(tween(Motion.dur(Motion.SLOW, motionOn), easing = Motion.STANDARD)) { it } +
                    fadeIn(tween(Motion.dur(Motion.BASE, motionOn))),
                exit = slideOutVertically(tween(Motion.dur(Motion.SLOW, motionOn), easing = Motion.EXIT)) { it } +
                    fadeOut(tween(Motion.dur(Motion.BASE, motionOn))),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                UsageSheet(state, accent)
            }
            if (state.importing) {
                // 站内全屏遮罩：BackHandler 吃掉返回键；pointerInput 吞掉全部触摸，
                // 导入进行中只有「取消导入」能结束，点击/滑动都不会穿透到背后页面。
                BackHandler {}
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(C.Scrim)
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent().changes.forEach { it.consume() }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                        Surface(color = C.Panel, shape = RoundedCornerShape(18.dp)) {
                            Column(Modifier.padding(horizontal = 28.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = accent, modifier = Modifier.size(30.dp))
                                Spacer(Modifier.height(14.dp))
                                Text("正在导入历史对话", color = C.Ivory, fontSize = 14.sp)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    (state.importProgress * 100).toInt().toString() + "% · " + state.importStatus,
                                    color = C.Muted, fontSize = 11.sp,
                                )
                                Spacer(Modifier.height(12.dp))
                                Pressable(onClick = { state.cancelImport() }) {
                                    Text(
                                        "取消导入",
                                        color = C.Muted, fontSize = 12.sp,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    )
                                }
                            }
                        }
                }
            }
            LaunchedEffect(state.notice) {
                val current = state.notice ?: return@LaunchedEffect
                delay(4000)
                if (state.notice == current) state.notice = null
            }
            var lastNotice by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(state.notice) { state.notice?.let { lastNotice = it } }
            AnimatedVisibility(
                visible = state.notice != null,
                enter = slideInVertically(tween(Motion.dur(240, motionOn), easing = Motion.STANDARD)) { it / 3 } +
                    fadeIn(tween(Motion.dur(140, motionOn))),
                exit = slideOutVertically(tween(Motion.dur(170, motionOn), easing = Motion.EXIT)) { it / 3 } +
                    fadeOut(tween(Motion.dur(150, motionOn))),
                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp).padding(bottom = 96.dp),
            ) {
                Surface(
                    color = C.PanelHigh,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.clickable { state.notice = null },
                ) {
                    Text(
                        lastNotice ?: "",
                        color = C.Ivory,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    )
                }
            }
        }
        }
        }
    }
    }
}
