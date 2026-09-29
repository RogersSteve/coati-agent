package bot.coati.fixture.manual

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class LabPage(val title: String) {
    HOME("首页"),
    CATALOG("资料库"),
    FAVORITES("收藏"),
    PROFILE("我的"),
    DETAIL("条目详情"),
    SETTINGS("设置"),
    ADVANCED("高级设置"),
    HELP("帮助与关于"),
    ARCHIVE("实验档案"),
    TOOLS("工具"),
    WORKBENCH("Canvas Workbench"),
    ORDER("Mixed Order"),
    CONTACTS("Import Contacts"),
}

private val isLayoutV2: Boolean
    get() = BuildConfig.VERSION_CODE >= 2

private val rootPages: List<LabPage>
    get() = if (isLayoutV2) {
        listOf(LabPage.HOME, LabPage.FAVORITES, LabPage.TOOLS)
    } else {
        listOf(LabPage.HOME, LabPage.CATALOG, LabPage.FAVORITES, LabPage.PROFILE)
    }

@Composable
internal fun VersionedManualLabApp() {
    val context = LocalContext.current
    val preferences = remember {
        context.getSharedPreferences("manual_lab_state", android.content.Context.MODE_PRIVATE)
    }
    var pageName by rememberSaveable { mutableStateOf(LabPage.HOME.name) }
    var selectedEntryId by rememberSaveable { mutableStateOf("travel") }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var travelFavorite by remember {
        mutableStateOf(preferences.getBoolean("travel_favorite", false))
    }
    var completionHints by remember {
        mutableStateOf(preferences.getBoolean("completion_hints", true))
    }
    var compactCards by remember {
        mutableStateOf(preferences.getBoolean("compact_cards", false))
    }
    var showResetDialog by remember { mutableStateOf(false) }
    val backStack = remember { mutableStateListOf<LabPage>() }
    val currentPage = LabPage.valueOf(pageName)

    fun navigate(target: LabPage) {
        if (target == currentPage) return
        backStack.add(currentPage)
        pageName = target.name
    }

    fun selectRoot(target: LabPage) {
        backStack.clear()
        pageName = target.name
    }

    fun navigateToEntry(id: String) {
        selectedEntryId = id
        navigate(LabPage.DETAIL)
    }

    fun goBack() {
        pageName = if (backStack.isEmpty()) {
            LabPage.HOME.name
        } else {
            backStack.removeAt(backStack.lastIndex).name
        }
    }

    BackHandler(enabled = currentPage != LabPage.HOME || backStack.isNotEmpty()) {
        goBack()
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("恢复初始状态") },
            text = { Text("将清除收藏、搜索词和本地显示偏好，并返回首页。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        preferences.edit().clear().apply()
                        travelFavorite = false
                        completionHints = true
                        compactCards = false
                        searchQuery = ""
                        showResetDialog = false
                        selectRoot(LabPage.HOME)
                    },
                ) {
                    Text("确认复位")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    if (currentPage == LabPage.WORKBENCH) {
        // Full-screen painted page without native chrome: no accessibility tree at all.
        CanvasWorkbenchPage(preferences = preferences, onBack = ::goBack)
        return
    }

    Scaffold(
        topBar = {
            LabTopBar(
                page = currentPage,
                canGoBack = backStack.isNotEmpty(),
                onBack = ::goBack,
            )
        },
        bottomBar = {
            LabBottomBar(
                selectedPage = currentPage,
                onSelect = ::selectRoot,
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .semantics { contentDescription = "页面：${currentPage.title}" }
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (currentPage) {
                LabPage.HOME -> HomePage(
                    onCatalog = { navigate(LabPage.CATALOG) },
                    onFavorites = { navigate(LabPage.FAVORITES) },
                    onHelp = { navigate(LabPage.HELP) },
                    onFeatured = { navigateToEntry("travel") },
                    onTools = { navigate(LabPage.TOOLS) },
                    onWorkbench = { navigate(LabPage.WORKBENCH) },
                    onOrder = { navigate(LabPage.ORDER) },
                    onContacts = { navigate(LabPage.CONTACTS) },
                )

                LabPage.CATALOG -> CatalogPage(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    compactCards = compactCards,
                    onEntry = ::navigateToEntry,
                )

                LabPage.FAVORITES -> FavoritesPage(
                    travelFavorite = travelFavorite,
                    onEntry = { navigateToEntry("travel") },
                    onCatalog = { navigate(LabPage.CATALOG) },
                )

                LabPage.PROFILE -> ProfilePage(
                    onSettings = { navigate(LabPage.SETTINGS) },
                    onHelp = { navigate(LabPage.HELP) },
                )

                LabPage.DETAIL -> DetailPage(
                    entry = ManualLabCatalog.find(selectedEntryId),
                    isFavorite = selectedEntryId == "travel" && travelFavorite,
                    onFavoriteChange = { favorite ->
                        if (selectedEntryId == "travel") {
                            travelFavorite = favorite
                            preferences.edit().putBoolean("travel_favorite", favorite).apply()
                        }
                    },
                )

                LabPage.SETTINGS -> SettingsPage(
                    completionHints = completionHints,
                    onCompletionHintsChange = { enabled ->
                        completionHints = enabled
                        preferences.edit().putBoolean("completion_hints", enabled).apply()
                    },
                    onAdvanced = { navigate(LabPage.ADVANCED) },
                    onReset = { showResetDialog = true },
                )

                LabPage.ADVANCED -> AdvancedPage(
                    compactCards = compactCards,
                    onCompactCardsChange = { enabled ->
                        compactCards = enabled
                        preferences.edit().putBoolean("compact_cards", enabled).apply()
                    },
                    onArchive = { navigate(LabPage.ARCHIVE) },
                )

                LabPage.WORKBENCH -> Unit
                LabPage.ORDER -> MixedOrderPage(preferences)
                LabPage.CONTACTS -> ContactsImportPage(preferences)
                LabPage.HELP -> HelpPage()
                LabPage.ARCHIVE -> ArchivePage()
                LabPage.TOOLS -> ToolsPage(
                    onCatalog = { navigate(LabPage.CATALOG) },
                    onSettings = { navigate(LabPage.SETTINGS) },
                    onProfile = { navigate(LabPage.PROFILE) },
                )
            }
        }
    }
}

@Composable
private fun LabTopBar(page: LabPage, canGoBack: Boolean, onBack: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canGoBack) {
                TextButton(onClick = onBack) {
                    Text("返回")
                }
            } else {
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = page.title,
                modifier = Modifier.weight(1f),
                fontSize = 21.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = "V${BuildConfig.VERSION_CODE}",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun LabBottomBar(selectedPage: LabPage, onSelect: (LabPage) -> Unit) {
    Surface(tonalElevation = 4.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            rootPages.forEach { page ->
                val isSelected = selectedPage == page
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .semantics {
                            selected = isSelected
                            role = Role.Tab
                            contentDescription = "${page.title}标签"
                        }
                        .clickable { onSelect(page) },
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Box(
                        modifier = Modifier.padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = page.title,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun PageIntro(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HomePage(
    onCatalog: () -> Unit,
    onFavorites: () -> Unit,
    onHelp: () -> Unit,
    onFeatured: () -> Unit,
    onTools: () -> Unit,
    onWorkbench: () -> Unit,
    onOrder: () -> Unit,
    onContacts: () -> Unit,
) {
    PageIntro("欢迎使用手册实验室", "离线浏览资料、保存收藏并调整本地偏好。")
    SectionCard(
        title = "精选条目",
        body = "离线旅行清单",
        supporting = "包含证件、地图与充电设备等出发前事项。",
        actionLabel = "查看详情",
        onAction = onFeatured,
    )
    Text("快捷入口", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    if (isLayoutV2) {
        NavigationCard("工具中心", "从新的工具页进入离线资料与偏好设置。", onTools)
    } else {
        NavigationCard("资料库", "浏览和搜索全部离线条目。", onCatalog)
    }
    NavigationCard("收藏", "查看已经收藏的条目。", onFavorites)
    NavigationCard("帮助与关于", "查看使用说明和版本信息。", onHelp)
    NavigationCard("Canvas Workbench", "Painted canvas: adjust brush and layers, export artwork.", onWorkbench)
    NavigationCard("Mixed Order", "Choose an item, set print copies and delivery.", onOrder)
    NavigationCard("Import Contacts", "Share checklists with your contacts.", onContacts)
}

@Composable
private fun CatalogPage(
    query: String,
    onQueryChange: (String) -> Unit,
    compactCards: Boolean,
    onEntry: (String) -> Unit,
) {
    PageIntro("资料库", "按标题、类别或简介搜索三条固定离线资料。")
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("搜索资料") },
        placeholder = { Text("例如：旅行") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    )
    val results = ManualLabCatalog.search(query)
    Text("搜索结果：${results.size} 条", fontWeight = FontWeight.Medium)
    if (results.isEmpty()) {
        InfoCard("没有匹配的离线资料。清空搜索词后可查看全部条目。")
    } else {
        results.forEach { entry ->
            EntryCard(entry, compactCards, onEntry)
        }
    }
}

@Composable
private fun EntryCard(entry: LabEntry, compact: Boolean, onEntry: (String) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 9.dp),
        ) {
            Text(entry.title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Text("类别：${entry.category}", color = MaterialTheme.colorScheme.primary)
            if (!compact) Text(entry.summary)
            TextButton(onClick = { onEntry(entry.id) }) {
                Text("查看详情")
            }
        }
    }
}

@Composable
private fun DetailPage(
    entry: LabEntry,
    isFavorite: Boolean,
    onFavoriteChange: (Boolean) -> Unit,
) {
    PageIntro(entry.title, "${entry.category} · 离线条目")
    Text(entry.summary, fontSize = 18.sp)
    HorizontalDivider()
    Text("检查项目", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    entry.details.forEachIndexed { index, item ->
        Text("${index + 1}. $item")
    }
    if (entry.id == "travel") {
        if (isFavorite) {
            OutlinedButton(onClick = { onFavoriteChange(false) }) {
                Text("取消收藏")
            }
        } else {
            Button(onClick = { onFavoriteChange(true) }) {
                Text("加入收藏")
            }
        }
    }
}

@Composable
private fun FavoritesPage(
    travelFavorite: Boolean,
    onEntry: () -> Unit,
    onCatalog: () -> Unit,
) {
    PageIntro("收藏", "这里保存用户主动收藏的离线条目。")
    if (travelFavorite) {
        SectionCard(
            title = "已收藏",
            body = "离线旅行清单",
            supporting = "点击查看完整检查项目。",
            actionLabel = "查看详情",
            onAction = onEntry,
        )
    } else {
        InfoCard("暂时没有收藏。可以进入资料库，将“离线旅行清单”加入收藏。")
        Button(onClick = onCatalog) {
            Text("前往资料库")
        }
    }
}

@Composable
private fun ProfilePage(onSettings: () -> Unit, onHelp: () -> Unit) {
    PageIntro("我的", "管理本地偏好并查看应用信息。")
    NavigationCard("设置", "调整提示方式和高级显示选项。", onSettings)
    NavigationCard("帮助与关于", "查看离线说明和当前版本。", onHelp)
}

@Composable
private fun ToolsPage(
    onCatalog: () -> Unit,
    onSettings: () -> Unit,
    onProfile: () -> Unit,
) {
    PageIntro("工具", "V2 将资料与偏好入口集中到工具页。")
    NavigationCard("离线资料", "进入资料库并浏览固定条目。", onCatalog)
    NavigationCard("偏好设置", "直接进入本地设置页面。", onSettings)
    NavigationCard("我的资料", "查看应用说明和账户占位信息。", onProfile)
}

@Composable
private fun SettingsPage(
    completionHints: Boolean,
    onCompletionHintsChange: (Boolean) -> Unit,
    onAdvanced: () -> Unit,
    onReset: () -> Unit,
) {
    PageIntro("设置", "所有设置只保存在本机，不需要网络。")
    SwitchRow(
        title = "显示完成提示",
        supporting = "完成条目操作后显示简短提示。",
        checked = completionHints,
        onCheckedChange = onCompletionHintsChange,
    )
    NavigationCard("高级设置", "调整资料卡片并进入实验档案。", onAdvanced)
    HorizontalDivider()
    OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
        Text("恢复测试初始状态")
    }
}

@Composable
private fun AdvancedPage(
    compactCards: Boolean,
    onCompactCardsChange: (Boolean) -> Unit,
    onArchive: () -> Unit,
) {
    PageIntro("高级设置", "控制资料库的显示密度并访问低频功能。")
    SwitchRow(
        title = "紧凑资料卡片",
        supporting = "开启后资料库隐藏条目简介。",
        checked = compactCards,
        onCheckedChange = onCompactCardsChange,
    )
    NavigationCard("实验档案", "查看不常使用的固定维护记录。", onArchive)
}

@Composable
private fun HelpPage() {
    PageIntro("帮助与关于", "Coati 手册实验室是一款完全离线的确定性测试应用。")
    InfoCard(
        if (isLayoutV2) {
            "主要入口位于底部：首页、收藏和工具。资料库与设置入口位于“工具”页面。"
        } else {
            "主要入口位于底部：首页、资料库、收藏和我的。设置入口位于“我的”页面。"
        },
    )
    SectionCard(
        title = "版本信息",
        body = "界面版本 V${BuildConfig.VERSION_CODE}",
        supporting = "包名：bot.coati.fixture.manual",
    )
}

@Composable
private fun ArchivePage() {
    PageIntro("实验档案", "保存少量不常访问的离线维护记录。")
    SectionCard(
        title = "航行日志 07",
        body = "离线导航检查",
        supporting = "状态：已归档",
    )
    SectionCard(
        title = "维护记录 12",
        body = "资料索引校验",
        supporting = "状态：已归档",
    )
}

@Composable
private fun NavigationCard(title: String, supporting: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(17.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Text(supporting)
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    body: String,
    supporting: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(17.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(title, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
            Text(body, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Text(supporting)
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun InfoCard(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(16.dp),
    ) {
        Text(text, modifier = Modifier.padding(17.dp))
    }
}

@Composable
private fun SwitchRow(
    title: String,
    supporting: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(17.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(supporting)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}
