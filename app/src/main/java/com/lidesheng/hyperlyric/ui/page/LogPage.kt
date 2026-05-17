@file:OptIn(ExperimentalScrollBarApi::class)

package com.lidesheng.hyperlyric.ui.page

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lidesheng.hyperlyric.R
import com.lidesheng.hyperlyric.ui.component.SearchBarFake
import com.lidesheng.hyperlyric.ui.component.SearchBox
import com.lidesheng.hyperlyric.ui.component.SearchPager
import com.lidesheng.hyperlyric.ui.component.SearchStatus
import com.lidesheng.hyperlyric.ui.navigation.LocalNavigator
import com.lidesheng.hyperlyric.ui.utils.BlurredBar
import com.lidesheng.hyperlyric.ui.utils.pageScrollModifiers
import com.lidesheng.hyperlyric.ui.utils.rememberBlurBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text

import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi
import top.yukonga.miuix.kmp.window.WindowCascadingListPopup
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Stable
data class LogEntry(
    val timestamp: String,
    val level: String,
    val tag: String,
    val message: String,
    val isSystemInfo: Boolean = false,
    val source: String = "com.lidesheng.hyperlyric",
    val rawLog: String = ""
) {
    val displaySource: String
        get() = when {
            source == "com.lidesheng.hyperlyric" -> "HyperLyric"
            source.contains("systemui", ignoreCase = true) -> "SystemUI"
            source.startsWith("io.github.proify.lyricon.") -> source.substringAfterLast('.')
            else -> source
        }
    val displayLevel: String
        get() = when (level) {
            "C" -> "CRASH"
            "E" -> "ERROR"
            "W" -> "WARN"
            "I" -> "INFO"
            "D" -> "DEBUG"
            else -> level
        }

    val levelColorBg: Color
        get() = when (level) {
            "C" -> Color(0xFFD32F2F)
            "E" -> Color(0x40F44336)
            "W" -> Color(0x40FFC107)
            "I" -> Color(0x404CAF50)
            "D" -> Color(0x402196F3)
            else -> Color(0x40909090)
        }

    val levelColorText: Color
        get() = when (level) {
            "C" -> Color(0xFFFFFFFF)
            "E" -> Color(0xFFF44336)
            "W" -> Color(0xFFFF8F00)
            "I" -> Color(0xFF388E3C)
            "D" -> Color(0xFF1976D2)
            else -> Color(0xFF757575)
        }
}

private fun formatTimestamp(raw: String): String {
    val dotIndex = raw.lastIndexOf('.')
    return if (dotIndex != -1 && raw.length - dotIndex == 4) raw.substring(0, dotIndex) else raw
}

@Composable
fun LogPage() {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val coroutineScope = rememberCoroutineScope()
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface
    val allLogs = remember { mutableStateListOf<LogEntry>() }
    var isLoading by remember { mutableStateOf(true) }
    val searchLabel = stringResource(id = R.string.search)
    var searchStatus by remember { mutableStateOf(SearchStatus(label = searchLabel)) }
    var selectedLevel by remember { mutableStateOf("ALL") }
    val density = LocalDensity.current
    val pullToRefreshState = rememberPullToRefreshState()
    var showMorePopup by remember { mutableStateOf(false) }

    val filteredLogs by remember {
        derivedStateOf {
            val q = searchStatus.searchText.lowercase(Locale.getDefault())
            allLogs.filter {
                val matchLevel = selectedLevel == "ALL" || it.level == selectedLevel
                val matchQuery = q.isEmpty() || it.message.lowercase(Locale.getDefault()).contains(q) || it.tag.lowercase(Locale.getDefault()).contains(q)
                matchLevel && matchQuery
            }.distinct()
        }
    }

    val exportHeader = stringResource(id = R.string.export_header)
    val exportTimeFormat = stringResource(id = R.string.format_export_time)
    val exportSuccessMsg = stringResource(id = R.string.export_success)
    val exportFailedMsg = stringResource(id = R.string.format_export_failed)
    val copiedMsg = stringResource(id = R.string.copied)

    val filterLabel = stringResource(id = R.string.module_logs_level)
    val exportLabel = stringResource(id = R.string.export_all)
    val allLabel = stringResource(id = R.string.all)
    val levelDebug = stringResource(id = R.string.level_debug)
    val levelInfo = stringResource(id = R.string.level_info)
    val levelWarn = stringResource(id = R.string.level_warn)
    val levelError = stringResource(id = R.string.level_error)
    val levelCrash = stringResource(id = R.string.level_crash)

    val snackbarHostState = remember { SnackbarHostState() }

    val reloadLogs = remember {
        {
            coroutineScope.launch {
                isLoading = true
                val logs = com.lidesheng.hyperlyric.utils.LogManager.collectAllLogs(context)
                allLogs.clear()
                allLogs.addAll(logs)
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        reloadLogs()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
        onResult = { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val sb = StringBuilder()
                    sb.appendLine(exportHeader)
                    sb.appendLine(String.format(exportTimeFormat, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))))
                    sb.appendLine()
                    val logsToExport = filteredLogs.toList()
                    logsToExport.forEach {
                        sb.appendLine(it.rawLog)
                        sb.appendLine()
                    }
                    val output = context.contentResolver.openOutputStream(uri)
                    if (output != null) {
                        output.use {
                            it.write(sb.toString().toByteArray(Charsets.UTF_8))
                            it.flush()
                        }
                        withContext(Dispatchers.Main) {
                            snackbarHostState.showSnackbar(
                                message = exportSuccessMsg,
                                duration = SnackbarDuration.Custom(2000L)
                            )
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        snackbarHostState.showSnackbar(
                            message = String.format(exportFailedMsg, e.message),
                            duration = SnackbarDuration.Custom(2000L)
                        )
                    }
                }
            }
        }
    )
    val logEntries = remember(selectedLevel, filterLabel, exportLabel, allLabel, levelDebug, levelInfo, levelWarn, levelError, levelCrash) {
        val levels = listOf("ALL", "D", "I", "W", "E", "C")
        val levelNames = listOf(allLabel, levelDebug, levelInfo, levelWarn, levelError, levelCrash)
        listOf(
            DropdownEntry(
                items = listOf(
                    DropdownItem(
                        text = filterLabel,
                        children = levels.mapIndexed { index, level ->
                            DropdownItem(
                                text = levelNames[index],
                                selected = selectedLevel == level,
                                onClick = {
                                    selectedLevel = level
                                }
                            )
                        }
                    )
                )
            ),
            DropdownEntry(
                items = listOf(
                    DropdownItem(
                        text = exportLabel,
                        onClick = {
                            val dateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"))
                            exportLauncher.launch("hyperlyric_debug_$dateTime.txt")
                        }
                    )
                )
            )
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(state = snackbarHostState) },
        topBar = {
            BlurredBar(backdrop, blurActive) {
                searchStatus.TopAppBarAnim(backgroundColor = barColor) {
                    TopAppBar(
                        color = barColor,
                        title = stringResource(id = R.string.title_module_logs),
                        scrollBehavior = topAppBarScrollBehavior,
                        navigationIcon = {
                            IconButton(
                                onClick = { navigator.pop() }
                            ) {
                                Icon(imageVector = MiuixIcons.Back, contentDescription = stringResource(id = R.string.back))
                            }
                        },
                        actions = {
                            Box {
                                IconButton(onClick = { showMorePopup = true }, holdDownState = showMorePopup) {
                                    Icon(imageVector = MiuixIcons.More, contentDescription = stringResource(id = R.string.more))
                                }
                                WindowCascadingListPopup(
                                    show = showMorePopup,
                                    entries = logEntries,
                                    alignment = PopupPositionProvider.Align.TopEnd,
                                    popupPositionProvider = ListPopupDefaults.ContextMenuPositionProvider,
                                    onDismissRequest = { showMorePopup = false }
                                )
                            }
                        },
                        bottomContent = {
                            Box(
                                modifier = Modifier
                                    .alpha(if (searchStatus.isCollapsed()) 1f else 0f)
                                    .onGloballyPositioned { coordinates ->
                                        with(density) {
                                            searchStatus = searchStatus.copy(offsetY = coordinates.positionInWindow().y.toDp())
                                        }
                                    }
                                    .then(
                                        if (searchStatus.isCollapsed()) {
                                            Modifier.pointerInput(Unit) {
                                                detectTapGestures {
                                                    searchStatus = searchStatus.copy(current = SearchStatus.Status.EXPANDING)
                                                }
                                            }
                                        } else Modifier
                                    )
                            ) {
                                SearchBarFake(stringResource(id = R.string.search))
                            }
                        }
                    )
                }
            }
        },
        popupHost = {
            searchStatus.SearchPager(
                onSearchStatusChange = { searchStatus = it },
                offsetY = searchStatus.offsetY,
                defaultResult = {},
            ) {
                if (searchStatus.searchText.isNotBlank()) {
                    if (isLoading) {
                        item {
                            Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    } else if (filteredLogs.isEmpty()) {
                        item {
                            Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                Text(stringResource(id = R.string.no_logs_found), color = MiuixTheme.colorScheme.onSurfaceSecondary)
                            }
                        }
                    } else {
                        items(filteredLogs, key = { "${it.timestamp}_${it.level}_${it.tag}_${it.message}" }) { entry ->
                            LogItem(entry = entry, copiedMsg = copiedMsg, snackbarHostState = snackbarHostState)
                        }
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    ) { padding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            searchStatus.SearchBox {
                PullToRefresh(
                    isRefreshing = isLoading,
                    onRefresh = { reloadLogs() },
                    pullToRefreshState = pullToRefreshState,
                    contentPadding = PaddingValues(top = padding.calculateTopPadding()),
                    topAppBarScrollBehavior = topAppBarScrollBehavior,
                    refreshTexts = listOf(
                        stringResource(id = R.string.refresh_pull_down),
                        stringResource(id = R.string.refresh_release),
                        stringResource(id = R.string.refreshing),
                        stringResource(id = R.string.refresh_success)
                    ),
                    modifier = Modifier.fillMaxSize()
                ) {
                    val lazyListState = rememberLazyListState()
                    val top = padding.calculateTopPadding()
                    val bottom = padding.calculateBottomPadding()
                    val contentPadding = remember(top, bottom) {
                        PaddingValues(top = top, bottom = bottom + 16.dp)
                    }
                    Box {
                        LazyColumn(
                            state = lazyListState,
                            modifier = Modifier.pageScrollModifiers(
                                enableScrollEndHaptic = true,
                                showTopAppBar = true,
                                topAppBarScrollBehavior = topAppBarScrollBehavior
                            ),
                            contentPadding = contentPadding
                        ) {
                            if (isLoading) {
                                item { Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                            } else if (filteredLogs.isEmpty()) {
                                item { Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(id = R.string.no_logs_found), color = MiuixTheme.colorScheme.onSurfaceSecondary) } }
                            } else {
                                items(filteredLogs, key = { "${it.timestamp}_${it.level}_${it.tag}_${it.message}" }) { entry -> LogItem(entry = entry, copiedMsg = copiedMsg, snackbarHostState = snackbarHostState) }
                            }
                        }
                        VerticalScrollBar(
                            adapter = rememberScrollBarAdapter(lazyListState),
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                            trackPadding = contentPadding,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LogItem(
    entry: LogEntry,
    copiedMsg: String,
    snackbarHostState: SnackbarHostState
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        insideMargin = PaddingValues(12.dp),
        showIndication = true,
        onClick = { expanded = !expanded },
        onLongPress = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("HyperLyric Log", entry.rawLog)
            clipboard.setPrimaryClip(clip)
            scope.launch {
                snackbarHostState.showSnackbar(
                    message = copiedMsg,
                    duration = SnackbarDuration.Custom(2000L)
                )
            }
        }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .background(entry.levelColorBg, RoundedCornerShape(3.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(
                    text = entry.displayLevel,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = entry.levelColorText
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = entry.displaySource,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = formatTimestamp(entry.timestamp),
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceSecondary
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            modifier = Modifier.animateContentSize(),
            text = entry.message,
            fontSize = 13.sp,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            color = if (entry.level == "E" || entry.level == "C") Color(0xFFF44336) else MiuixTheme.colorScheme.onSurface
        )
    }
}
