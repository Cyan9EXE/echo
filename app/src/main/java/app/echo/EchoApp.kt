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
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.animation.ValueAnimator
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
fun EchoApp(state: AppState) {
    val motionOn = remember { ValueAnimator.areAnimatorsEnabled() }
    val themeAnim = remember { Animatable(C.themeProgress) }
    LaunchedEffect(C.isDay, motionOn) {
        val target = if (C.isDay) 1f else 0f
        val delta = kotlin.math.abs(C.Void.luminance() - C.voidLuminanceFor(C.isDay, C.eyeMode))
        themeAnim.animateTo(
            targetValue = target,
            animationSpec = tween(
                Motion.crossfade(260, delta, motionOn),
                easing = if (delta > 0.35f) Motion.CALM else Motion.STANDARD,
            ),
        ) { C.themeProgress = C.shapeProgress(value) }
    }
    val eyeAnim = remember { Animatable(C.eyeProgress) }
    LaunchedEffect(C.eyeMode, C.isDay, motionOn) {
        val eyeTarget = if (C.isDay) C.eyeMode else 0
        val delta = kotlin.math.abs(C.Void.luminance() - C.voidLuminanceFor(C.isDay, eyeTarget))
        eyeAnim.animateTo(
            targetValue = if (C.isDay && C.eyeMode != 0) 1f else 0f,
            animationSpec = tween(Motion.crossfade(300, delta, motionOn), easing = Motion.STANDARD),
        ) { C.eyeProgress = value }
    }
    val accentAnim = remember { Animatable(state.skinIndex.toFloat()) }
    LaunchedEffect(state.skinIndex, motionOn) {
        val last = C.skins.lastIndex
        val fromIndex = accentAnim.value.toInt()
        val delta = if (fromIndex in 0..last && state.skinIndex in 0..last) {
            kotlin.math.abs(
                C.presetAccent(fromIndex).luminance() - C.presetAccent(state.skinIndex).luminance()
            )
        } else 0f
        accentAnim.animateTo(
            targetValue = state.skinIndex.toFloat(),
            animationSpec = tween(Motion.crossfade(220, delta, motionOn), easing = Motion.STANDARD),
        )
    }
    // 自定义强调色不走预设色阶，直接取用户选的颜色。
    val accent = if (state.customMode) Color(state.customAccent) else C.accentAt(accentAnim.value)
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
    // 阈值放在中间灰附近：过早翻成深色图标会在深色背景上「消失」。
    val darkStatusIcons = C.Void.luminance() > 0.42f
    SideEffect {
        val activity = view.context.findActivity() ?: return@SideEffect
        WindowCompat.getInsetsController(activity.window, view).apply {
            isAppearanceLightStatusBars = darkStatusIcons
            isAppearanceLightNavigationBars = darkStatusIcons
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
            onPrimary = C.Void,
            primaryContainer = C.PanelHigh,
            onPrimaryContainer = C.Ivory,
            secondary = C.Muted,
            onSecondary = C.Void,
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
        Box(Modifier.fillMaxSize().background(C.Void).statusBarsPadding()) {
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
            var contentSortByRatio by remember { mutableStateOf(true) }
            var reviveSort by remember { mutableStateOf(0) }
            AnimatedVisibility(
                visible = state.showDrawer,
                enter = fadeIn(tween(Motion.dur(200, motionOn))),
                exit = fadeOut(tween(Motion.dur(180, motionOn))),
            ) {
                Scrim(alpha = .35f) { state.showDrawer = false }
            }
            AnimatedVisibility(
                visible = state.showDrawer,
                enter = slideInHorizontally(spring(dampingRatio = .86f, stiffness = 320f)) { -it },
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
                Box(
                    Modifier.fillMaxSize().background(C.Scrim),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(color = C.Panel, shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(horizontal = 28.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = accent, modifier = Modifier.size(30.dp))
                            Spacer(Modifier.height(14.dp))
                            Text("正在导入历史对话", color = C.Ivory, fontSize = 14.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(state.importStatus, color = C.Muted, fontSize = 11.sp)
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
                enter = slideInVertically(spring(dampingRatio = 1f, stiffness = 500f)) { it / 3 } + fadeIn(tween(140)),
                exit = slideOutVertically(tween(170)) { it / 3 } + fadeOut(tween(150)),
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
