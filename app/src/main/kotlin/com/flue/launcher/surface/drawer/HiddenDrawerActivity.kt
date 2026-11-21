package com.flue.launcher.surface.drawer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.flue.launcher.FlueApplication
import com.flue.launcher.R
import com.flue.launcher.data.model.AppInfo
import com.flue.launcher.i18n.FlueComponentActivity
import com.flue.launcher.surface.SurfaceHome
import com.flue.launcher.surface.SurfaceRuntime
import com.flue.launcher.surface.apps.HanLatin
import com.flue.launcher.surface.apps.HiddenDrawerCatalog
import com.flue.launcher.surface.apps.PublicAppCandidate
import com.flue.launcher.surface.mode.ConfigScope
import com.flue.launcher.surface.ui.FfluePageScaffold
import com.flue.launcher.ui.style.FlueTextField
import com.flue.launcher.ui.theme.LauncherTheme
import com.flue.launcher.ui.theme.ProvideGlobalUiScale
import com.flue.launcher.ui.theme.WatchLauncherTheme
import com.flue.launcher.viewmodel.LauncherViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator

/**
 * 隐藏完整抽屉（R10、R14）：列出全部已安装应用（不经公开筛选、不带公开名称覆盖），
 * 首字母分组、检索、长按置顶，点击直接启动。
 *
 * 伪装模式下只有管理会话或一次性授权（[SurfaceRuntime.consumeHiddenDrawerAccess]）能打开；
 * 熄屏或模式改变时关闭，返回回到表盘。不导出，外部应用打不开。P3 接控制中心入口。
 */
class HiddenDrawerActivity : FlueComponentActivity() {

    private val vm: LauncherViewModel by viewModels()

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) finishToFace()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runtime = SurfaceRuntime.get(this)
        // 重建（旋转、改 DPI）时沿用本次已通过的访问，不再消耗授权。
        if (savedInstanceState == null && !runtime.consumeHiddenDrawerAccess()) {
            finish()
            return
        }
        ContextCompat.registerReceiver(
            this,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        lifecycleScope.launch { runtime.controller.mode.drop(1).collect { finish() } }
        val repository = FlueApplication.repositories(this).appRepository
        setContent {
            val themeMode by vm.themeMode.collectAsStateWithLifecycle()
            val uiStyle by vm.uiStyle.collectAsStateWithLifecycle()
            val globalUiScalePercent by vm.globalUiScalePercent.collectAsStateWithLifecycle()
            val screenShape by vm.screenShapeOverride.collectAsStateWithLifecycle()
            // allApps 只含顶层条目，文件夹里的应用不在里面；这里要每个已安装应用。
            val apps by repository.allSelectableApps.collectAsStateWithLifecycle()
            WatchLauncherTheme(themeMode = themeMode, uiStyle = uiStyle, screenShape = screenShape) {
                ProvideGlobalUiScale(globalUiScalePercent) {
                    HiddenDrawerPage(
                        apps = apps,
                        onBack = ::finishToFace,
                        onLaunch = { app ->
                            if (repository.launchApp(app)) {
                                finish()
                            } else {
                                Toast.makeText(this, R.string.surface_hidden_drawer_launch_failed, Toast.LENGTH_SHORT)
                                    .show()
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOffReceiver) }
        super.onDestroy()
    }

    private fun finishToFace() {
        if (SurfaceRuntime.get(this).controller.configScope.value == ConfigScope.DISGUISE) {
            runCatching { startActivity(SurfaceHome.returnToFaceIntent(this)) }
        }
        finish()
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, HiddenDrawerActivity::class.java)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HiddenDrawerPage(apps: List<AppInfo>, onBack: () -> Unit, onLaunch: (AppInfo) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val runtime = remember(context) { SurfaceRuntime.get(context) }
    val scope = rememberCoroutineScope()
    val style = LauncherTheme.style
    var query by rememberSaveable { mutableStateOf("") }
    var pinned by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(runtime) { pinned = runtime.hiddenDrawerPinned() }

    val launchable = remember(apps) { apps.filterNot { it.isFolder || it.isBuiltInSettingsEntry || it.isAppListShortcut } }
    val byKey = remember(launchable) { launchable.associateBy { it.componentKey } }
    val candidates by produceState(initialValue = emptyList<PublicAppCandidate>(), launchable) {
        value = withContext(Dispatchers.Default) {
            val latin = HanLatin.create()
            launchable.mapIndexed { index, app ->
                PublicAppCandidate(
                    key = app.componentKey,
                    label = app.label,
                    packageName = app.packageName,
                    desktopIndex = index,
                    latin = latin?.invoke(app.label),
                )
            }
        }
    }
    val collator = remember { Collator.getInstance() }
    val layout = remember(candidates, query, pinned) {
        HiddenDrawerCatalog.arrange(candidates, query, pinned, collator::compare)
    }
    var jumpTarget by remember { mutableStateOf<Int?>(null) }
    // 系统返回键／返回手势也回表盘，和页头返回、右滑一致。
    BackHandler(onBack = onBack)

    fun togglePin(key: String) {
        val next = HiddenDrawerCatalog.togglePin(pinned, key)
        pinned = next
        scope.launch { runCatching { runtime.saveHiddenDrawerPinned(next) } }
    }

    @Composable
    fun DrawerRow(candidate: PublicAppCandidate) {
        val app = byKey[candidate.key] ?: return
        val isPinned = candidate.key in pinned
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(style.cardShape)
                .background(style.cardColor)
                .combinedClickable(onClick = { onLaunch(app) }, onLongClick = { togglePin(candidate.key) })
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Image(bitmap = app.cachedIcon, contentDescription = null, modifier = Modifier.size(30.dp).clip(CircleShape))
            Text(
                text = app.label,
                color = style.titleColor,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (isPinned) {
                Text(stringResource(R.string.surface_hidden_drawer_pinned_mark), color = style.accentColor, fontSize = 11.sp)
            }
        }
    }

    FfluePageScaffold(
        stateKey = "hidden_drawer",
        title = stringResource(R.string.surface_hidden_drawer_title),
        subtitle = stringResource(R.string.surface_hidden_drawer_subtitle),
        onBack = onBack,
        scrollTo = jumpTarget,
        onScrolled = { jumpTarget = null },
    ) {
        item(key = "drawer_search") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FlueTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = stringResource(R.string.surface_public_apps_search),
                    supportingText = stringResource(
                        if (HanLatin.supported) {
                            R.string.surface_public_apps_search_scope_full
                        } else {
                            R.string.surface_public_apps_search_scope_basic
                        },
                    ),
                )
                if (query.isBlank() && layout.letters.size > 1) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(layout.letters, key = { it }) { letter ->
                            Text(
                                text = letter.toString(),
                                color = style.bodyColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clip(style.smallControlShape)
                                    .background(style.topBarChipColor)
                                    .clickable {
                                        // 列表前两项是页头和这块搜索区。
                                        jumpTarget = HiddenDrawerCatalog.indexOfLetter(layout, letter)?.plus(2)
                                    }
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                            )
                        }
                    }
                }
            }
        }
        if (candidates.isNotEmpty() && layout.isEmpty) {
            item(key = "drawer_empty") {
                Text(
                    text = stringResource(R.string.surface_public_apps_empty),
                    color = style.secondaryTextColor,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
        drawerItems(layout, { DrawerRow(it) }) { letter ->
            Text(
                text = letter.toString(),
                color = style.secondaryTextColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 8.dp, top = 6.dp),
            )
        }
    }
}

/** 与 [HiddenDrawerCatalog.indexOfLetter] 的位置计算一致：置顶项，然后每组一个标题加组内条目。 */
private fun LazyListScope.drawerItems(
    layout: com.flue.launcher.surface.apps.HiddenDrawerLayout,
    row: @Composable (PublicAppCandidate) -> Unit,
    header: @Composable (Char) -> Unit,
) {
    items(layout.pinned, key = { "pin:${it.key}" }) { row(it) }
    layout.sections.forEach { section ->
        item(key = "letter:${section.letter}") { header(section.letter) }
        items(section.entries, key = { it.key }) { row(it) }
    }
}
