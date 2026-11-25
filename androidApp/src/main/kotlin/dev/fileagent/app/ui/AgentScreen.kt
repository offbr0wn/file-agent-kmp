package dev.fileagent.app.ui

import android.net.Uri
import androidx.compose.ui.res.painterResource
import dev.fileagent.app.R
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import android.speech.tts.TextToSpeech
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fileagent.agent.Plan
import dev.fileagent.agent.PlannedAction
import dev.fileagent.app.AgentViewModel
import dev.fileagent.app.ChatItem
import dev.fileagent.app.FoundNas
import dev.fileagent.app.PlaceConfig
import dev.fileagent.app.Provider
import dev.fileagent.app.UiState
import kotlinx.coroutines.launch

private val SheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

// ---- entry --------------------------------------------------------------------------

@Composable
fun AgentScreen(vm: AgentViewModel, hasAccess: Boolean, onGrantAccess: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val c = LocalSui.current
    val d = LocalDensity.current
    // Reading size from Settings scales every text in the app.
    CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale * s.textScale)) {
        Box(Modifier.fillMaxSize().background(c.page)) {
            when {
                !s.onboarded -> Welcome(vm, s, hasAccess, onGrantAccess)
                !hasAccess -> Gate(onGrantAccess)
                s.showSettings -> SettingsScreen(vm, s)
                s.showFiles -> FilesScreen(vm, s)
                else -> ChatShell(vm, s)
            }
            // The built-in viewer sits over whatever screen opened it.
            s.viewer?.let { ViewerScreen(vm, it) }
            s.crash?.let { CrashNote(it, onClose = vm::clearCrash) }
        }
    }
}

// ---- first run ----------------------------------------------------------------------

@Composable
private fun Gate(onGrant: () -> Unit) {
    val c = LocalSui.current
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = SuiSpace.page + 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Image(painterResource(R.drawable.ic_logo), contentDescription = "File Agent logo",
            modifier = Modifier.size(96.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF161616)))
        Spacer(Modifier.height(SuiSpace.section))
        Text("Allow file access", style = SuiType.display, color = c.text)
        Spacer(Modifier.height(8.dp))
        Text("File Agent needs it to sort your files.", style = SuiType.body, color = c.textSub)
        Spacer(Modifier.height(SuiSpace.section))
        GateLine(Icons.Outlined.Folder, "Only the folders you pick")
        GateLine(Icons.Outlined.Check, "Nothing moves until you say yes")
        GateLine(Icons.Outlined.Undo, "Undo any change")
        GateLine(Icons.Outlined.DeleteOutline, "Deletes go to a bin first")
        GateLine(Icons.Outlined.Lock, "Files stay on your phone")
        Spacer(Modifier.height(SuiSpace.section + 8.dp))
        PillButton("Grant access", onGrant, Modifier.fillMaxWidth(), primary = true)
    }
}

@Composable
private fun GateLine(icon: ImageVector, text: String) {
    val c = LocalSui.current
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon)
        Spacer(Modifier.width(SuiSpace.inner))
        Text(text, style = SuiType.label, color = c.text)
    }
}

// ---- chat shell --------------------------------------------------------------------

@Composable
private fun ChatShell(vm: AgentViewModel, s: UiState) {
    val c = LocalSui.current
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showFolders by remember { mutableStateOf(false) }
    val speak = rememberSpeaker()
    fun close() = scope.launch { drawer.close() }

    BackHandler(drawer.isOpen) { close() }

    // No folders yet: ask first. No AI key yet: open Settings (the key is optional for Quick sort).
    fun send(text: String, images: List<Uri>) {
        when {
            s.chatPlaces.isEmpty() -> showFolders = true
            !s.hasApiKey -> vm.showSettings(true)
            else -> vm.send(text, images)
        }
    }

    // The picked model can't see pictures: move to one that can, or open Settings.
    fun switchToVision() {
        val m = vm.modelsFor(s.provider).firstOrNull { it.vision == true }
        if (m != null) vm.setModel(s.provider, m.id) else vm.showSettings(true)
    }

    fun quick() {
        if (!vm.quickSort()) showFolders = true
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = c.sidebar,
                drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
                modifier = Modifier.width(300.dp),
            ) {
                Sidebar(
                    s,
                    onNew = { vm.newChat(); close() },
                    onOpen = { vm.openChat(it); close() },
                    onDelete = vm::deleteChat,
                    onFiles = { vm.showFiles(true); close() },
                    onSettings = { vm.showSettings(true); close() },
                )
            }
        },
    ) {
        Column(Modifier.fillMaxSize().background(c.page).safeDrawingPadding().imePadding()) {
            TopBar(
                title = if (s.items.isEmpty()) "" else s.title,
                onMenu = { scope.launch { drawer.open() } },
                onNew = vm::newChat,
                newEnabled = !s.busy && s.items.isNotEmpty(),
            )
            Box(Modifier.weight(1f)) {
                if (s.items.isEmpty() && !s.busy) {
                    Home(vm, s, onSend = { send(it, emptyList()) }, onQuick = ::quick, onFolders = { showFolders = true })
                } else Thread(vm, s, speak)
            }
            Composer(
                s = s,
                placeholder = if (s.plan != null) "Ask about this plan" else "Ask File Agent",
                onSend = ::send,
                onFolders = { showFolders = true },
                onSwitchModel = ::switchToVision,
            )
        }
    }

    if (showFolders) FoldersSheet(vm, s, onClose = { showFolders = false })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Sidebar(
    s: UiState,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onFiles: () -> Unit,
    onSettings: () -> Unit,
) {
    val c = LocalSui.current
    var confirm by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxHeight().safeDrawingPadding().padding(horizontal = SuiSpace.inner, vertical = 8.dp)) {
        Row(Modifier.padding(SuiSpace.inner), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_logo), contentDescription = null,
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF161616)))
            Spacer(Modifier.width(SuiSpace.inner))
            Text("File Agent", style = SuiType.title, color = c.text)
        }
        SideRow(Icons.Outlined.Add, "New chat", enabled = !s.busy, onClick = onNew)
        SideRow(Icons.Outlined.Folder, "Files", onClick = onFiles)
        Spacer(Modifier.height(SuiSpace.section))
        Text("Recents", style = SuiType.sub, color = c.textSoft, modifier = Modifier.padding(horizontal = SuiSpace.inner))
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f)) {
            if (s.chats.isEmpty()) item {
                Text("Your chats will show up here.", style = SuiType.bodySm, color = c.textSoft, modifier = Modifier.padding(SuiSpace.inner))
            }
            items(s.chats, key = { it.id }) { chat ->
                val active = chat.id == s.chatId
                Column(
                    Modifier.fillMaxWidth().clip(SuiShape.row)
                        .background(if (active) c.sidebarItem else Color.Transparent)
                        .combinedClickable(enabled = !s.busy, onClick = { onOpen(chat.id) }, onLongClick = { confirm = chat.id })
                        .padding(horizontal = SuiSpace.inner, vertical = 10.dp)
                ) {
                    Text(chat.title, style = SuiType.label, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(DateUtils.getRelativeTimeSpanString(chat.updated).toString(), style = SuiType.bodySm, color = c.textSoft)
                }
            }
        }
        Text("Long-press a chat to delete it.", style = SuiType.bodySm, color = c.textSoft, modifier = Modifier.padding(SuiSpace.inner))
        SideRow(Icons.Outlined.Settings, "Settings", onClick = onSettings)
    }

    confirm?.let { id ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = c.card,
            title = { Text("Delete this chat?", style = SuiType.title, color = c.text) },
            text = { Text("Its messages are removed. Files are not touched.", style = SuiType.body, color = c.textSub) },
            confirmButton = { PillButton("Delete", { onDelete(id); confirm = null }, primary = true, small = true) },
            dismissButton = { PillButton("Cancel", { confirm = null }, small = true) },
        )
    }
}

@Composable
private fun SideRow(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = LocalSui.current
    val tint = if (enabled) c.text else c.textSoft
    Row(
        Modifier.fillMaxWidth().clip(SuiShape.row).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = SuiSpace.inner, vertical = SuiSpace.inner),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(SuiSpace.inner))
        Text(label, style = SuiType.label, color = tint)
    }
}

@Composable
private fun IconButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = LocalSui.current
    Box(
        Modifier.size(SuiSpace.tap).clip(CircleShape).clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = label, tint = if (enabled) c.textSub else c.textSoft, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun TopBar(title: String, onMenu: () -> Unit, onNew: () -> Unit, newEnabled: Boolean) {
    val c = LocalSui.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(Icons.Outlined.Menu, "Open chats", onClick = onMenu)
        Text(
            title, style = SuiType.label, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        IconButton(Icons.Outlined.Edit, "New chat", enabled = newEnabled, onClick = onNew)
    }
}

// ---- home (empty chat) ----------------------------------------------------------------

@Composable
private fun Home(vm: AgentViewModel, s: UiState, onSend: (String) -> Unit, onQuick: () -> Unit, onFolders: () -> Unit) {
    val c = LocalSui.current
    val place = s.chatPlaces.firstOrNull()
    val cfg = s.places.firstOrNull { it.name == place }
    val nas = s.nasFound.filter { f -> s.places.none { it.type == "smb" && it.host == f.host } }
    var connect by remember { mutableStateOf<FoundNas?>(null) }

    LaunchedEffect(Unit) { vm.startNasScan() }
    DisposableEffect(Unit) { onDispose { vm.stopNasScan() } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = SuiSpace.page, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(SuiSpace.section),
    ) {
        // Where we are
        Column(Modifier.clickable(onClick = onFolders), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (cfg?.type == "smb") Icons.Outlined.Dns else Icons.Outlined.PhoneAndroid, null, tint = c.textSoft, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (cfg?.type == "smb") "NAS" else "Phone", style = SuiType.bodySm, color = c.textSoft)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(place ?: "Pick a folder", style = SuiType.display, color = c.text)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Outlined.KeyboardArrowDown, null, tint = c.textSoft, modifier = Modifier.size(24.dp).padding(bottom = 2.dp))
            }
        }

        s.update?.let { UpdateCard(vm, s) }

        // Things to try
        Column {
            SectionLabel("Worth a look")
            ListCard {
                ListRow("Quick sort by type", "Free, no AI", leading = { IconTile(Icons.Outlined.Bolt, c.brand, c.brandLighter) },
                    onClick = onQuick, trailing = { PillButton("Go", onQuick, small = true) })
                ListDivider()
                ListRow("Old installers", "Older copies of apps", leading = { IconTile(iconForExtension("apk"), c.successDark, c.successLighter) },
                    onClick = { onSend("Find old or duplicate APK installers I can delete") },
                    trailing = { PillButton("Review", { onSend("Find old or duplicate APK installers I can delete") }, small = true) })
                ListDivider()
                ListRow("Duplicates", "Same file saved twice", leading = { IconTile(Icons.Outlined.ContentCopy, c.brandDark, c.brandLighter) },
                    onClick = { onSend("Find duplicate files") },
                    trailing = { PillButton("Review", { onSend("Find duplicate files") }, small = true) })
                ListDivider()
                ListRow("Big files", "What takes up the most space", leading = { IconTile(Icons.Outlined.Search, c.infoDark, c.infoLighter) },
                    onClick = { onSend("What's taking up the most space?") },
                    trailing = { PillButton("Review", { onSend("What's taking up the most space?") }, small = true) })
            }
        }

        // NAS on the Wi-Fi
        if (nas.isNotEmpty() || s.nasScanning) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = SuiSpace.inner)) {
                    Text("On your Wi-Fi", style = SuiType.sub, color = c.textSoft)
                    if (s.nasScanning) {
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(Modifier.size(14.dp), color = c.textSoft, strokeWidth = 2.dp)
                    }
                }
                if (nas.isNotEmpty()) ListCard {
                    nas.forEachIndexed { i, f ->
                        if (i > 0) ListDivider()
                        ListRow(f.name, f.host, leading = { IconTile(Icons.Outlined.Dns, c.successDark, c.successLighter) },
                            onClick = { connect = f }, trailing = { PillButton("Connect", { connect = f }, small = true) })
                    }
                }
            }
        }

        if (!s.hasApiKey) {
            PillButton("Set up AI to chat", { vm.showSettings(true) }, icon = Icons.Outlined.ChatBubbleOutline)
        }
    }

    connect?.let { f -> NasConnectSheet(vm, s, f.name, f.host, onClose = { connect = null }) }
}

// ---- thread --------------------------------------------------------------------------

@Composable
private fun Thread(vm: AgentViewModel, s: UiState, speak: ((String) -> Unit)?) {
    val list = rememberLazyListState()
    val pendingIndex = s.items.indexOfLast { it.kind == "plan" && it.status == "pending" }
    val openPlan = s.plan
    var expanded by remember(openPlan) { mutableStateOf(false) }
    val lastBinNote = s.items.indexOfLast { it.kind == "note" && "in bin" in it.text }
    val binEmptiedAfter = lastBinNote >= 0 && s.items.drop(lastBinNote + 1).any { it.kind == "note" && it.text.startsWith("Bin emptied") }

    LaunchedEffect(s.items.size, s.busy, s.items.lastOrNull()?.text?.length) {
        val total = list.layoutInfo.totalItemsCount
        if (total > 0) list.animateScrollToItem(total - 1)
    }

    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = SuiSpace.page, vertical = SuiSpace.inner),
        verticalArrangement = Arrangement.spacedBy(SuiSpace.pad),
    ) {
        s.items.forEachIndexed { index, item ->
            when (item.kind) {
                "user" -> item { UserBubble(item.text, item.images) }
                "assistant" -> item { AssistantText(item.text, speak) }
                "activity" -> item { Steps(item) }
                "error" -> item { ErrorRow(item.text, onNewChat = vm::newChat, onGetMessages = { vm.showSettings(true) }) }
                "files" -> item { FilesGrid(vm, item.text, item.images, item.picked.toSet(), enabled = !s.busy) { vm.setPicked(index, it.toList()) } }
                "note" -> item {
                    val canEmpty = "in bin" in item.text && index == lastBinNote && !binEmptiedAfter
                    NoteRow(item.text, onEmptyBin = if (canEmpty) ({ vm.emptyBin() }) else null)
                }
                "plan" -> if (index == pendingIndex && openPlan != null) {
                    item { PlanCard(vm, openPlan, s.selected, enabled = !s.busy && s.applying == null, applying = s.applying, expanded = expanded, onExpand = { expanded = !expanded }) }
                } else item { PlanClosed(item) }
            }
        }
        if (s.busy && s.items.lastOrNull()?.kind != "assistant") item { Thinking() }
    }
}

@Composable
private fun UserBubble(text: String, images: List<String> = emptyList()) {
    val c = LocalSui.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (images.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                images.forEach { PictureThumb(java.io.File(it), 120.dp) }
            }
        }
        if (text.isNotBlank()) {
            Box(Modifier.widthIn(max = 300.dp).clip(SuiShape.bubble).background(c.tile).padding(horizontal = SuiSpace.pad, vertical = SuiSpace.inner)) {
                Text(text, style = SuiType.body, color = c.text)
            }
        }
    }
}

/** A sent picture, from the app's storage. */
@Composable
private fun PictureThumb(file: java.io.File, size: androidx.compose.ui.unit.Dp) {
    val c = LocalSui.current
    val ctx = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, file) { value = loadFileThumb(ctx, file, px) }
    Box(Modifier.size(size).clip(SuiShape.row).background(c.tile), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it, contentDescription = "Picture", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            ?: Icon(Icons.Outlined.Image, null, tint = c.textSoft)
    }
}

/** A picked picture waiting to be sent, with a small x to remove it. */
@Composable
private fun PickedThumb(uri: Uri, onRemove: () -> Unit) {
    val c = LocalSui.current
    val ctx = LocalContext.current
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, uri) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { ctx.contentResolver.loadThumbnail(uri, android.util.Size(240, 240), null).asImageBitmap() }.getOrNull()
        }
    }
    Box(Modifier.size(64.dp)) {
        Box(Modifier.fillMaxSize().clip(SuiShape.row).background(c.tile), contentAlignment = Alignment.Center) {
            bmp?.let { Image(it, contentDescription = "Picked picture", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                ?: Icon(Icons.Outlined.Image, null, tint = c.textSoft)
        }
        Box(
            Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).clip(CircleShape).background(c.text)
                .clickable(onClickLabel = "Remove picture", onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Close, null, tint = c.page, modifier = Modifier.size(14.dp)) }
    }
}

@Composable
private fun AssistantText(text: String, speak: ((String) -> Unit)?) {
    val c = LocalSui.current
    Column(Modifier.fillMaxWidth()) {
        Text(text, style = SuiType.body, color = c.text)
        if (speak != null) {
            Box(
                Modifier.padding(top = 4.dp).size(SuiSpace.tapSm).clip(CircleShape).clickable(onClickLabel = "Read aloud") { speak(text) },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.VolumeUp, "Read aloud", tint = c.textSoft, modifier = Modifier.size(20.dp)) }
        }
    }
}

/** Phone's text-to-speech, for reading replies aloud. Null until it is ready. */
@Composable
private fun rememberSpeaker(): ((String) -> Unit)? {
    val ctx = LocalContext.current
    var ready by remember { mutableStateOf(false) }
    val tts = remember { TextToSpeech(ctx.applicationContext) { ready = it == TextToSpeech.SUCCESS } }
    DisposableEffect(Unit) { onDispose { tts.stop(); tts.shutdown() } }
    return if (ready) { text -> tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply") } else null
}

@Composable
private fun Thinking() {
    val c = LocalSui.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(c.brand))
        Spacer(Modifier.width(10.dp))
        Text("Thinking", style = SuiType.bodySm, color = c.textSoft)
    }
}

/** The AI's tool steps: one quiet line, open on tap. */
@Composable
private fun Steps(item: ChatItem) {
    val c = LocalSui.current
    var open by remember { mutableStateOf(false) }
    val lines = item.text.lines().filter { it.isNotBlank() }
    val label = when (item.status) {
        "running" -> lines.lastOrNull() ?: "Working"
        "failed" -> "Stopped after ${lines.size} steps"
        else -> "Worked in ${lines.size} steps"
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.clip(SuiShape.control).clickable(enabled = lines.isNotEmpty()) { open = !open }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (item.status) {
                "running" -> CircularProgressIndicator(Modifier.size(14.dp), color = c.textSoft, strokeWidth = 2.dp)
                "failed" -> Icon(Icons.Outlined.Close, null, tint = c.errorDark, modifier = Modifier.size(16.dp))
                else -> Icon(Icons.Outlined.Check, null, tint = c.textSoft, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(label, style = SuiType.bodySm, color = c.textSoft, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (lines.isNotEmpty()) Icon(if (open) Icons.Outlined.ExpandMore else Icons.Outlined.KeyboardArrowRight, null, tint = c.textSoft, modifier = Modifier.size(18.dp))
        }
        if (open) {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().clip(SuiShape.row).background(c.tile).heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(SuiSpace.inner)) {
                Column(Modifier.horizontalScroll(rememberScrollState())) {
                    lines.takeLast(60).forEach { Text(it, style = SuiType.mono, color = c.textSub) }
                }
            }
        }
    }
}

/**
 * Files the AI showed, as a picture grid. Tap a picture to open or play it,
 * tick the round mark to pick it, then move the picked ones to the bin.
 */
@Composable
private fun FilesGrid(
    vm: AgentViewModel,
    title: String,
    paths: List<String>,
    picks: Set<String>,
    enabled: Boolean,
    onPicked: (Set<String>) -> Unit,
) {
    val c = LocalSui.current
    val ctx = LocalContext.current
    // Ticks live in the chat (saved, and sent to the AI), not just on screen.
    val picked = picks
    fun pick(v: Set<String>) = onPicked(paths.filter { it in v }.toSet())
    var all by remember(paths) { mutableStateOf(false) }
    val shown = if (all) paths else paths.take(12)

    Column(
        Modifier.fillMaxWidth().clip(SuiShape.card).background(c.card).padding(SuiSpace.inner),
        verticalArrangement = Arrangement.spacedBy(SuiSpace.inner),
    ) {
        Row(Modifier.padding(start = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title.ifBlank { "${paths.size} files" }, style = SuiType.label, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (picked.isEmpty()) "Tap to open. Tick to pick." else "${picked.size} picked. Ask me to move them.", style = SuiType.bodySm, color = c.textSoft)
            }
            Text(if (picked.size == paths.size) "None" else "All", style = SuiType.labelSm, color = c.textSub,
                modifier = Modifier.clip(SuiShape.pill).clickable(enabled = enabled) {
                    pick(if (picked.size == paths.size) emptySet() else paths.toSet())
                }.padding(horizontal = 8.dp, vertical = 4.dp))
        }
        shown.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { path ->
                    FileCell(vm, path, on = path in picked, modifier = Modifier.weight(1f),
                        onOpen = { vm.viewFile(path) },
                        onTick = { if (enabled) pick(if (path in picked) picked - path else picked + path) })
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (paths.size > 12) {
            Text(if (all) "Show fewer" else "Show all ${paths.size}", style = SuiType.labelSm, color = c.textSub, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clip(SuiShape.pill).clickable { all = !all }.padding(vertical = SuiSpace.inner))
        }
        PillButton("Move to bin", { vm.binPicked(paths.filter { it in picked }) }, Modifier.fillMaxWidth(),
            icon = Icons.Outlined.DeleteOutline, primary = true, enabled = enabled && picked.isNotEmpty())
    }
}

@Composable
private fun FileCell(vm: AgentViewModel, path: String, on: Boolean, modifier: Modifier, onOpen: () -> Unit, onTick: () -> Unit) {
    val c = LocalSui.current
    val ctx = LocalContext.current
    val name = path.substringAfterLast('/')
    val ext = name.substringAfterLast('.', "").lowercase()
    val file = remember(path) { vm.localFileFor(path) }
    val px = with(LocalDensity.current) { 160.dp.roundToPx() }
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, path) {
        value = file?.let { loadFileThumb(ctx, it, px) }
    }
    val video = ext in setOf("mp4", "mkv", "mov", "webm", "3gp", "avi", "m4v")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(SuiShape.row).background(c.tile)
                .border(if (on) 3.dp else 0.dp, if (on) c.brand else Color.Transparent, SuiShape.row)
                .clickable(onClickLabel = "Open $name", onClick = onOpen),
            contentAlignment = Alignment.Center,
        ) {
            val img = bmp
            if (img != null) Image(img, contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Icon(iconForExtension(ext), null, tint = c.textSoft, modifier = Modifier.size(32.dp))
            if (video) Box(
                Modifier.size(36.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(22.dp)) }
            Box(
                Modifier.align(Alignment.TopEnd).size(SuiSpace.tapSm).clickable(onClickLabel = if (on) "Unpick" else "Pick", onClick = onTick),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.clip(CircleShape).background(if (on) Color.Transparent else Color.Black.copy(alpha = 0.35f))) { RoundTick(on) }
            }
        }
        Text(name, style = SuiType.bodySm, color = c.textSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ErrorRow(text: String, onNewChat: () -> Unit, onGetMessages: () -> Unit = {}) {
    val c = LocalSui.current
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(SuiShape.card).background(c.errorLighter).padding(SuiSpace.pad),
        verticalArrangement = Arrangement.spacedBy(SuiSpace.inner),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Close, null, tint = c.errorDark, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(SuiSpace.inner))
            Text("Stopped", style = SuiType.label, color = c.errorDark, modifier = Modifier.weight(1f))
            Text(if (open) "Hide" else "Details", style = SuiType.labelSm, color = c.errorDark,
                modifier = Modifier.clip(SuiShape.pill).clickable { open = !open }.padding(horizontal = 8.dp, vertical = 4.dp))
        }
        // Full message, selectable so it can be copied.
        if (open) androidx.compose.foundation.text.selection.SelectionContainer {
            Text(text, style = SuiType.bodySm, color = c.errorDark)
        }
        if ("no AI messages left" in text) {
            PillButton("Get messages", onGetMessages, small = true, primary = true, icon = Icons.Outlined.AutoAwesome)
        } else Row(horizontalArrangement = Arrangement.spacedBy(SuiSpace.inner)) {
            Text("Send again to retry, or", style = SuiType.bodySm, color = c.errorDark, modifier = Modifier.align(Alignment.CenterVertically))
            PillButton("New chat", onNewChat, small = true, icon = Icons.Outlined.Edit)
        }
    }
}

/** Status after Apply / Discard / Undo, with the "Delete for good" card when files are in the bin. */
@Composable
private fun NoteRow(text: String, onEmptyBin: (() -> Unit)? = null) {
    val c = LocalSui.current
    var confirm by remember { mutableStateOf(false) }
    val (icon, tint) = when {
        "failed" in text -> Icons.Outlined.Close to c.errorDark
        text.startsWith("Applied") || text.startsWith("Moved") || "in bin" in text -> Icons.Outlined.Check to c.successDark
        text.startsWith("Undid") -> Icons.Outlined.Undo to c.textSub
        text.startsWith("Bin emptied") -> Icons.Outlined.DeleteOutline to c.errorDark
        else -> Icons.Outlined.Close to c.textSoft
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SuiSpace.inner)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Row(Modifier.clip(SuiShape.pill).background(c.tile).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(text.removeSuffix("."), style = SuiType.labelSm, color = c.textSub)
            }
        }
        if (onEmptyBin != null) {
            Column(Modifier.fillMaxWidth().clip(SuiShape.card).background(c.card).padding(SuiSpace.pad), verticalArrangement = Arrangement.spacedBy(SuiSpace.inner)) {
                Text("Space you can free", style = SuiType.label, color = c.text)
                Text("The bin clears itself in 7 days. Or free it now.", style = SuiType.bodySm, color = c.textSoft)
                PillButton("Delete for good", { confirm = true }, Modifier.fillMaxWidth(), icon = Icons.Outlined.DeleteOutline, danger = true)
            }
        }
    }
    if (confirm && onEmptyBin != null) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = c.card,
            title = { Text("Delete for good?", style = SuiType.title, color = c.text) },
            text = { Text("Empties the bin. You can't undo this.", style = SuiType.body, color = c.textSub) },
            confirmButton = { PillButton("Delete", { onEmptyBin(); confirm = false }, primary = true, small = true) },
            dismissButton = { PillButton("Cancel", { confirm = false }, small = true) },
        )
    }
}

// ---- plan --------------------------------------------------------------------------

private fun PlannedAction.path(): String = when (this) {
    is PlannedAction.Move -> from
    is PlannedAction.Delete -> path
    is PlannedAction.Mkdir -> path
}

private fun parentName(path: String): String = path.trim('/').substringBeforeLast('/', "").substringAfterLast('/').ifBlank { "place" }

private fun PlannedAction.destination(): String? = when (this) {
    is PlannedAction.Move -> to.substringBeforeLast('/', "").substringAfterLast('/')
    else -> null
}

/**
 * The plan as a real file list: each file with its own icon or logo, where it goes,
 * and a tick. One big button that says exactly what will happen.
 */
@Composable
private fun PlanCard(vm: AgentViewModel, plan: Plan, selected: Set<Int>, enabled: Boolean, applying: String?, expanded: Boolean, onExpand: () -> Unit) {
    val c = LocalSui.current
    val files = plan.actions.withIndex().filter { it.value !is PlannedAction.Mkdir }
    // A plan with only new folders still shows them, so the card is never "0 of 0".
    val foldersOnly = files.isEmpty()
    val rows = if (foldersOnly) plan.actions.withIndex().toList() else files
    val deletes = rows.count { it.value is PlannedAction.Delete }
    val moves = rows.size - deletes
    val ticked = rows.count { it.index in selected }
    val shown = if (expanded) rows else rows.take(4)
    // Short labels so the button never wraps. The count lives in the card header.
    val verb = when {
        foldersOnly -> if (rows.size == 1) "Make folder" else "Make folders"
        deletes > 0 && moves == 0 -> "Move to bin"
        deletes == 0 -> "Move files"
        else -> "Apply"
    }
    val noun = if (foldersOnly) (if (rows.size == 1) "folder" else "folders") else "files"

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SuiSpace.inner)) {
        ListCard {
            ListRow(
                "$ticked of ${rows.size} $noun",
                when { foldersOnly -> "New"; deletes > 0 && moves == 0 -> "Go to the bin, undo any time"; else -> "Will move" },
                leading = { IconTile(if (deletes > 0) Icons.Outlined.DeleteOutline else Icons.Outlined.FolderOpen, if (deletes > 0) c.errorDark else c.textSub, if (deletes > 0) c.errorLighter else c.tile) },
                trailing = {
                    Text(if (ticked == rows.size) "None" else "All", style = SuiType.labelSm, color = c.textSub,
                        modifier = Modifier.clip(SuiShape.pill).clickable(enabled = enabled) {
                            vm.toggleGroup(rows.map { it.index })
                        }.padding(horizontal = 8.dp, vertical = 4.dp))
                },
            )
            shown.forEach { (i, a) ->
                ListDivider()
                val path = a.path()
                val name = path.substringAfterLast('/')
                val meta = when (a) {
                    is PlannedAction.Delete -> "To bin"
                    is PlannedAction.Move -> a.destination()?.let { "To $it" } ?: ""
                    is PlannedAction.Mkdir -> "New folder in ${parentName(path)}"
                }
                ListRow(name, meta, leading = {
                    if (a is PlannedAction.Mkdir) IconTile(Icons.Outlined.Folder) else FileTile(name, vm.localFileFor(path))
                },
                    onClick = if (enabled) ({ vm.toggle(i) }) else null, trailing = { RoundTick(i in selected) })
            }
            if (rows.size > 4) {
                ListDivider(inset = 0.dp)
                Text(
                    if (expanded) "Show fewer" else "Show ${rows.size - 4} more",
                    style = SuiType.labelSm, color = c.textSub, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onExpand).padding(vertical = SuiSpace.inner + 2.dp),
                )
            }
        }
        if (applying != null) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Big videos to a NAS take a while: show it is working.
            Text(applying, style = SuiType.label, color = c.text)
            LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp).clip(SuiShape.pill), color = c.brand, trackColor = c.tile)
            Text("Keep the app open until it's done.", style = SuiType.bodySm, color = c.textSoft)
        } else Row(horizontalArrangement = Arrangement.spacedBy(SuiSpace.inner)) {
            PillButton("Not now", vm::discardPlan, enabled = enabled)
            PillButton(verb, vm::applyPlan, Modifier.weight(1f), icon = if (deletes > 0) Icons.Outlined.DeleteOutline else Icons.Outlined.Check,
                primary = true, enabled = enabled && ticked > 0)
        }
    }
}

@Composable
private fun PlanClosed(item: ChatItem) {
    val c = LocalSui.current
    val label = when (item.status) { "applied" -> "Applied"; "discarded" -> "Discarded"; else -> "Pending" }
    Row(Modifier.fillMaxWidth().clip(SuiShape.card).border(1.dp, c.stroke, SuiShape.card).padding(SuiSpace.pad), verticalAlignment = Alignment.CenterVertically) {
        Text(item.text.ifBlank { "Plan" }, style = SuiType.bodySm, color = c.textSub, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(SuiSpace.inner))
        Text(label, style = SuiType.labelSm, color = if (item.status == "applied") c.successDark else c.textSoft)
    }
}

// ---- composer ---------------------------------------------------------------------

@Composable
private fun Composer(
    s: UiState,
    placeholder: String,
    onSend: (String, List<Uri>) -> Unit,
    onFolders: () -> Unit,
    onSwitchModel: () -> Unit,
) {
    val c = LocalSui.current
    var text by remember { mutableStateOf("") }
    var images by remember { mutableStateOf(listOf<Uri>()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris ->
        images = (images + uris).distinct().take(4)
    }
    val enabled = !s.busy
    // Only block when we know for sure the model can't see pictures.
    val blind = images.isNotEmpty() && s.modelVision == false
    val ready = enabled && (text.isNotBlank() || images.isNotEmpty()) && !blind
    fun send() {
        if (!ready) return
        onSend(text, images)
        if (s.chatPlaces.isNotEmpty() && s.hasApiKey) { text = ""; images = emptyList() }
    }
    val chipLabel = when {
        s.chatPlaces.isEmpty() -> "Choose folders"
        s.chatPlaces.size == 1 -> s.chatPlaces.first()
        else -> "${s.chatPlaces.first()} +${s.chatPlaces.size - 1}"
    }
    val shape = RoundedCornerShape(28.dp)
    Column(Modifier.fillMaxWidth().padding(start = SuiSpace.inner, end = SuiSpace.inner, bottom = SuiSpace.pad, top = 8.dp)) {
        if (blind) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(SuiShape.row).background(c.brandLighter)
                    .padding(start = SuiSpace.pad, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Image, null, tint = c.brandDark, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("${s.modelName} can't see pictures", style = SuiType.labelSm, color = c.brandDark,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                PillButton("Switch", onSwitchModel, small = true)
            }
        }
        Column(
            Modifier.fillMaxWidth().clip(shape).background(c.card).border(1.dp, c.stroke, shape)
                .padding(start = SuiSpace.page, end = 8.dp, top = SuiSpace.pad, bottom = 8.dp)
        ) {
            if (images.isNotEmpty()) {
                Row(Modifier.padding(bottom = SuiSpace.inner).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    images.forEach { u -> PickedThumb(u) { images = images - u } }
                }
            }
            Box(Modifier.fillMaxWidth().padding(end = 8.dp)) {
                if (text.isEmpty()) Text(if (images.isNotEmpty()) "Ask about the picture" else placeholder, style = SuiType.body, color = c.textSoft)
                BasicTextField(value = text, onValueChange = { text = it }, enabled = enabled, textStyle = SuiType.body.copy(color = c.text),
                    cursorBrush = SolidColor(c.text), maxLines = 6, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(SuiSpace.inner))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.size(SuiSpace.tapSm).clip(CircleShape).border(1.dp, c.stroke, CircleShape)
                        .clickable(enabled = enabled && images.size < 4, onClickLabel = "Add a picture") {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.AddPhotoAlternate, "Add a picture", tint = c.textSub, modifier = Modifier.size(20.dp)) }
                Row(
                    Modifier.height(SuiSpace.tapSm).clip(SuiShape.pill).border(1.dp, c.stroke, SuiShape.pill)
                        .clickable(enabled = enabled, onClick = onFolders).padding(start = 12.dp, end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Folder, null, tint = if (s.chatPlaces.isEmpty()) c.brand else c.textSub, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(chipLabel, style = SuiType.labelSm, color = c.textSub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
                    Icon(Icons.Outlined.KeyboardArrowDown, null, tint = c.textSoft, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(SuiSpace.tapSm).clip(CircleShape).background(if (ready) c.primary else c.tile)
                        .clickable(enabled = ready, onClickLabel = "Send", onClick = ::send),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.ArrowUpward, "Send", tint = if (ready) c.onStrong else c.textSoft, modifier = Modifier.size(20.dp)) }
            }
        }
    }
}

// ---- folders sheet ---------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FoldersSheet(vm: AgentViewModel, s: UiState, onClose: () -> Unit) {
    val c = LocalSui.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var page by remember { mutableStateOf("list") } // list, phone, nas
    var connect by remember { mutableStateOf<FoundNas?>(null) }
    val nas = s.nasFound.filter { f -> s.places.none { it.type == "smb" && it.host == f.host } }

    LaunchedEffect(Unit) { vm.startNasScan() }
    DisposableEffect(Unit) { onDispose { vm.stopNasScan() } }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet, containerColor = c.card, shape = SheetShape) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = SuiSpace.page + 4.dp).padding(bottom = SuiSpace.section),
            verticalArrangement = Arrangement.spacedBy(SuiSpace.section),
        ) {
            when (page) {
                "phone" -> {
                    SheetHeader("Add a phone folder", onBack = { page = "list" })
                    PhoneFolderPicker(vm) { page = "list" }
                }
                "nas" -> {
                    SheetHeader("Local network", "Looking for drives on your Wi-Fi", onBack = { page = "list" })
                    NasScanList(s, nas, onPick = { connect = it }, onManual = { connect = FoundNas("", "") })
                }
                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Folders for this chat", style = SuiType.title, color = c.text)
                        Text("File Agent only sees what you tick.", style = SuiType.bodySm, color = c.textSoft)
                    }
                    // A NAS that is off, or on another network, is hidden unless this chat uses it.
                    fun offline(p: PlaceConfig) = p.type == "smb" && p.host in s.nasOffline
                    val shownPlaces = s.places.filter { !offline(it) || it.name in s.chatPlaces }
                    val hidden = s.places.size - shownPlaces.size
                    if (shownPlaces.isNotEmpty()) Column {
                        SectionLabel("Saved")
                        shownPlaces.forEachIndexed { i, p ->
                            if (i > 0) ListDivider(inset = 40.dp + SuiSpace.inner)
                            val on = p.name in s.chatPlaces
                            PlainRow(p.name, if (offline(p)) "Offline. Not on this network." else p.description,
                                leading = { IconTile(if (p.type == "smb") Icons.Outlined.Dns else Icons.Outlined.PhoneAndroid) },
                                onClick = { vm.toggleChatPlace(p.name) }, trailing = { RoundTick(on) })
                        }
                    }
                    if (hidden > 0) {
                        Text("$hidden NAS ${if (hidden == 1) "folder" else "folders"} hidden. They show when you're on that Wi-Fi.",
                            style = SuiType.bodySm, color = c.textSoft)
                    }
                    if (nas.isNotEmpty() || s.nasScanning) Column {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = SuiSpace.inner)) {
                            Text("On your Wi-Fi", style = SuiType.sub, color = c.textSoft)
                            if (s.nasScanning) { Spacer(Modifier.width(8.dp)); CircularProgressIndicator(Modifier.size(14.dp), color = c.textSoft, strokeWidth = 2.dp) }
                        }
                        nas.forEachIndexed { i, f ->
                            if (i > 0) ListDivider(inset = 40.dp + SuiSpace.inner)
                            PlainRow(f.name, f.host, leading = { IconTile(Icons.Outlined.Dns, c.successDark, c.successLighter) },
                                onClick = { connect = f }, trailing = { PillButton("Connect", { connect = f }, small = true) })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(SuiSpace.inner)) {
                        PillButton("Phone folder", { page = "phone" }, icon = Icons.Outlined.Add)
                        PillButton("NAS", { page = "nas" }, icon = Icons.Outlined.Dns)
                    }
                    PillButton("Done · ${s.chatPlaces.size} ${if (s.chatPlaces.size == 1) "folder" else "folders"}", onClose, Modifier.fillMaxWidth(), primary = true)
                }
            }
        }
    }

    connect?.let { f ->
        NasConnectSheet(vm, s, f.name, f.host, onClose = { connect = null; if (f.host.isNotEmpty()) page = "list" })
    }
}

@Composable
private fun SheetHeader(title: String, sub: String? = null, onBack: () -> Unit) {
    val c = LocalSui.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(Icons.Outlined.ArrowBack, "Back", onClick = onBack)
        Spacer(Modifier.width(4.dp))
        Column {
            Text(title, style = SuiType.title, color = c.text)
            if (sub != null) Text(sub, style = SuiType.bodySm, color = c.textSoft)
        }
    }
}

/** A list row with no card around it (inside sheets). */
@Composable
private fun PlainRow(title: String, meta: String?, leading: @Composable () -> Unit, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    val c = LocalSui.current
    var m = Modifier.fillMaxWidth().heightIn(min = SuiSpace.row).clip(SuiShape.row)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Row(m.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        leading()
        Spacer(Modifier.width(SuiSpace.inner))
        Column(Modifier.weight(1f)) {
            Text(title, style = SuiType.label, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!meta.isNullOrBlank()) Text(meta, style = SuiType.bodySm, color = c.textSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(SuiSpace.inner))
        trailing()
    }
}

@Composable
private fun NasScanList(s: UiState, nas: List<FoundNas>, onPick: (FoundNas) -> Unit, onManual: () -> Unit) {
    val c = LocalSui.current
    Column(verticalArrangement = Arrangement.spacedBy(SuiSpace.section)) {
        if (s.nasScanning) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp), color = c.successDark, trackColor = c.tile)
        Column {
            SectionLabel(if (nas.isEmpty() && !s.nasScanning) "Nothing found" else "Found")
            nas.forEachIndexed { i, f ->
                if (i > 0) ListDivider(inset = 40.dp + SuiSpace.inner)
                PlainRow(f.name, "${f.host} · file sharing", leading = { IconTile(Icons.Outlined.Dns, c.successDark, c.successLighter) },
                    onClick = { onPick(f) }, trailing = { Icon(Icons.Outlined.KeyboardArrowRight, null, tint = c.textSoft) })
            }
        }
        Text("Not listed? Check it's on the same Wi-Fi, or type its address.", style = SuiType.bodySm, color = c.textSoft)
        PillButton("Type address", onManual, Modifier.fillMaxWidth())
    }
}

/** Sign in to a NAS, tick the shares to use, save them as places. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NasConnectSheet(vm: AgentViewModel, s: UiState, foundName: String, foundHost: String, onClose: () -> Unit) {
    val c = LocalSui.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf(foundName.ifBlank { "NAS" }) }
    var host by remember { mutableStateOf(foundHost) }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var manualShare by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val shares = s.nasShares
    val signedIn = shares != null

    DisposableEffect(Unit) { onDispose { vm.clearShares() } }
    // One share only: tick it for them.
    LaunchedEffect(shares) { if (shares?.size == 1) picked = shares.toSet() }

    // Changing the login means signing in again.
    fun edited() { if (signedIn || s.nasError != null) vm.clearShares(); picked = emptySet(); error = null }

    fun save() {
        val chosen = if (picked.isNotEmpty()) picked.toList() else listOf(manualShare.trim()).filter { it.isNotBlank() }
        if (chosen.isEmpty()) { error = "Pick a share"; return }
        // The NAS's own name (like SHASHANK-NAS), never a bare address when we can help it.
        val isAddress = name.trim().all { it.isDigit() || it == '.' }
        val server = s.nasServerName ?: name.trim().takeIf { !isAddress && it != "NAS" }.orEmpty()
        chosen.forEach { share ->
            // Folders are called by their share name. Add the NAS name only if that name is taken.
            val clash = s.places.any { it.name == share && !(it.type == "smb" && it.host == host.trim() && it.share == share) }
            val placeName = if (clash) "${server.ifBlank { host.trim() }} $share" else share
            error = vm.addPlace(PlaceConfig(name = placeName, type = "smb", host = host.trim(), share = share, user = user.trim(), password = password, server = server))
            if (error != null) return
        }
        onClose()
    }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet, containerColor = c.card, shape = SheetShape) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = SuiSpace.page + 4.dp).padding(bottom = SuiSpace.section),
            verticalArrangement = Arrangement.spacedBy(SuiSpace.section),
        ) {
            SheetHeader(if (foundName.isNotBlank()) foundName else "Connect a NAS", foundHost.ifBlank { null }, onBack = onClose)
            Column(verticalArrangement = Arrangement.spacedBy(SuiSpace.inner)) {
                if (foundHost.isBlank()) {
                    Field(name, { name = it }, "Name")
                    Field(host, { host = it; edited() }, "Address, e.g. 192.168.1.20")
                }
                Field(user, { user = it; edited() }, "Username")
                Field(password, { password = it; edited() }, "Password", secret = true)
                Text("Blank username signs in as guest.", style = SuiType.bodySm, color = c.textSoft)
            }

            // Why sign-in failed, in plain words.
            s.nasError?.let { msg ->
                Row(
                    Modifier.fillMaxWidth().clip(SuiShape.row).background(c.errorLighter).padding(SuiSpace.inner),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Close, null, tint = c.errorDark, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(msg, style = SuiType.labelSm, color = c.errorDark)
                }
            }

            if (signedIn) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Check, null, tint = c.successDark, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Signed in", style = SuiType.labelSm, color = c.successDark)
                }
                if (shares.isNullOrEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(SuiSpace.inner)) {
                        Text("Type the share name.", style = SuiType.bodySm, color = c.textSoft)
                        Field(manualShare, { manualShare = it }, "Share name, e.g. Photos")
                    }
                } else Column {
                    SectionLabel("Pick shares")
                    shares.forEachIndexed { i, sh ->
                        if (i > 0) ListDivider(inset = 40.dp + SuiSpace.inner)
                        val on = sh in picked
                        PlainRow(sh, null, leading = { IconTile(Icons.Outlined.Folder) },
                            onClick = { picked = if (on) picked - sh else picked + sh }, trailing = { RoundTick(on) })
                    }
                }
            }
            error?.let { Text(it, style = SuiType.bodySm, color = c.errorDark) }

            if (!signedIn) {
                PillButton(
                    if (s.nasSigningIn) "Signing in" else "Sign in",
                    { vm.loadShares(host.trim(), user.trim(), password) },
                    Modifier.fillMaxWidth(), primary = true, enabled = host.isNotBlank() && !s.nasSigningIn,
                )
                if (s.nasSigningIn) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp), color = c.brand, trackColor = c.tile)
            } else {
                val n = if (picked.isNotEmpty()) picked.size else if (manualShare.isNotBlank()) 1 else 0
                PillButton(if (n > 1) "Connect $n shares" else "Connect", ::save, Modifier.fillMaxWidth(), primary = true, enabled = n > 0)
            }
        }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, secret: Boolean = false, modifier: Modifier = Modifier.fillMaxWidth()) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true, label = { Text(label) }, shape = SuiShape.control, modifier = modifier,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
    )
}

@Composable
private fun PhoneFolderPicker(vm: AgentViewModel, onAdded: () -> Unit) {
    val c = LocalSui.current
    var custom by remember { mutableStateOf("") }
    val folders = remember { vm.phoneFolders() }
    Column(verticalArrangement = Arrangement.spacedBy(SuiSpace.inner)) {
        PlainRow("Whole phone", "All folders. App data stays blocked.", leading = { IconTile(Icons.Outlined.PhoneAndroid) },
            onClick = { vm.addPlace(PlaceConfig(name = "Phone", type = "local", localPath = "")); onAdded() },
            trailing = { Icon(Icons.Outlined.Add, null, tint = c.textSoft) })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(custom, { custom = it }, "Nested folder, e.g. DCIM/Camera", modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            PillButton("Add", {
                val path = custom.trim().trim('/')
                vm.addPlace(PlaceConfig(name = path.substringAfterLast('/'), type = "local", localPath = path)); onAdded()
            }, small = true, enabled = custom.isNotBlank())
        }
        folders.forEach { folder ->
            PlainRow(folder, null, leading = { IconTile(Icons.Outlined.Folder) },
                onClick = { vm.addPlace(PlaceConfig(name = folder, type = "local", localPath = folder)); onAdded() },
                trailing = { Icon(Icons.Outlined.Add, null, tint = c.textSoft) })
        }
    }
}

// ---- settings -------------------------------------------------------------------------

@Composable
private fun SettingsScreen(vm: AgentViewModel, s: UiState) {
    val c = LocalSui.current
    var showKey by remember { mutableStateOf(false) }
    var showFolders by remember { mutableStateOf(false) }
    var showMemory by remember { mutableStateOf(false) }
    var confirmBin by remember { mutableStateOf(false) }
    val (files, bytes) = s.bin ?: (0 to 0L)
    BackHandler { vm.showSettings(false) }

    Column(Modifier.fillMaxSize().background(c.page).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(Icons.Outlined.ArrowBack, "Back") { vm.showSettings(false) }
            Text("Settings", style = SuiType.title, color = c.text, modifier = Modifier.padding(start = 4.dp))
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = SuiSpace.page, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(SuiSpace.section),
        ) {
            Column {
                SectionLabel("AI (optional)")
                ListCard {
                    SettingRow("AI", s.provider.label) { showKey = true }
                    ListDivider(inset = SuiSpace.pad)
                    if (s.provider != Provider.Hosted) SettingRow("Model", s.modelName) { showKey = true }
                    ListDivider(inset = SuiSpace.pad)
                    if (s.provider == Provider.Hosted) {
                        SettingRow("Messages left", if (s.hosted.signedUp) "${s.hosted.left}" else "") { showKey = true }
                    } else if (s.provider == Provider.Local) {
                        SettingRow("Model file", when (s.localModel.status) { "ready" -> "Ready"; "downloading" -> "${s.localModel.percent}%"; else -> "Not downloaded" }) { showKey = true }
                    } else SettingRow("API key", if (s.hasApiKey) "Saved" else "Not set") { showKey = true }
                }
                Text("Needed for chat. Quick sort works without it.", style = SuiType.bodySm, color = c.textSoft, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
            }
            Column {
                SectionLabel("Look and feel")
                ListCard {
                    Column(Modifier.padding(SuiSpace.pad), verticalArrangement = Arrangement.spacedBy(SuiSpace.inner)) {
                        Text("Theme", style = SuiType.body, color = c.text)
                        Segmented(listOf("system", "light", "dark"), s.theme, { it.replaceFirstChar { ch -> ch.uppercase() } }) { vm.setTheme(it) }
                    }
                    ListDivider(inset = SuiSpace.pad)
                    Column(Modifier.padding(SuiSpace.pad), verticalArrangement = Arrangement.spacedBy(SuiSpace.inner)) {
                        Text("Text size", style = SuiType.body, color = c.text)
                        Segmented(listOf(1f, 1.15f, 1.3f), s.textScale, { if (it < 1.1f) "A" else if (it < 1.2f) "A+" else "A++" }) { vm.setTextScale(it) }
                    }
                    ListDivider(inset = SuiSpace.pad)
                    Column(Modifier.padding(SuiSpace.pad), verticalArrangement = Arrangement.spacedBy(SuiSpace.inner)) {
                        Text("AI replies", style = SuiType.body, color = c.text)
                        Segmented(listOf("short", "detailed"), s.replyStyle, { if (it == "short") "Short and simple" else "More detail" }) { vm.setReplyStyle(it) }
                    }
                }
            }
            Column {
                SectionLabel("Storage")
                ListCard {
                    SettingRow("Folders", "${s.places.size}") { showFolders = true }
                    ListDivider(inset = SuiSpace.pad)
                    SettingRow("Bin", if (s.bin == null) "" else if (files == 0) "Empty" else "$files files · ${sizeLabel(bytes)}", chevron = false)
                    ListDivider(inset = SuiSpace.pad)
                    SettingRow("Clear bin after", "7 days", chevron = false)
                    ListDivider(inset = SuiSpace.pad)
                    SettingRow("Empty bin now", "", chevron = false, color = c.errorDark, enabled = files > 0) { confirmBin = true }
                }
            }
            Column {
                SectionLabel("Automation")
                ListCard {
                    Row(Modifier.fillMaxWidth().heightIn(min = SuiSpace.row).padding(horizontal = SuiSpace.pad, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Daily auto-sort", style = SuiType.body, color = c.text)
                            Text("Sorts new Downloads once a day.", style = SuiType.bodySm, color = c.textSoft)
                        }
                        Switch(checked = s.autoSort, onCheckedChange = vm::setAutoSort, colors = SwitchDefaults.colors(
                            checkedTrackColor = c.brand, checkedThumbColor = Color.White, checkedBorderColor = c.brand,
                            uncheckedTrackColor = c.tile, uncheckedThumbColor = c.textSoft, uncheckedBorderColor = c.stroke))
                    }
                }
            }
            Column {
                SectionLabel("App")
                if (s.update != null) UpdateCard(vm, s)
                else ListCard {
                    SettingRow("Version", s.appVersion, chevron = false)
                    ListDivider(inset = SuiSpace.pad)
                    SettingRow("Check for updates", s.updateStatus.orEmpty(), enabled = !s.updateBusy) { vm.checkUpdate() }
                }
            }
            Column {
                SectionLabel("Memory")
                ListCard { SettingRow("Things File Agent remembers", "${s.memory.size}") { showMemory = true } }
            }
            if (s.undo.isNotEmpty()) PillButton("Undo last change", vm::undoLast, icon = Icons.Outlined.Undo)
        }
    }

    if (showKey) KeySheet(vm, s) { showKey = false }
    if (showFolders) FoldersSheet(vm, s) { showFolders = false }
    if (showMemory) MemorySheet(vm, s) { showMemory = false }
    if (confirmBin) {
        AlertDialog(
            onDismissRequest = { confirmBin = false },
            containerColor = c.card,
            title = { Text("Empty the bin?", style = SuiType.title, color = c.text) },
            text = { Text("Files in the bin go for good. This can't be undone.", style = SuiType.body, color = c.textSub) },
            confirmButton = { PillButton("Empty", { vm.emptyBin(); confirmBin = false }, primary = true, small = true) },
            dismissButton = { PillButton("Cancel", { confirmBin = false }, small = true) },
        )
    }
}

@Composable
private fun SettingRow(label: String, value: String, chevron: Boolean = true, color: Color? = null, enabled: Boolean = true, onClick: (() -> Unit)? = null) {
    val c = LocalSui.current
    var m = Modifier.fillMaxWidth().heightIn(min = SuiSpace.row)
    if (onClick != null) m = m.clickable(enabled = enabled, onClick = onClick)
    Row(m.padding(horizontal = SuiSpace.pad), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = SuiType.body, color = if (!enabled) c.textSoft else color ?: c.text, modifier = Modifier.weight(1f))
        Text(value, style = SuiType.bodySm.copy(fontSize = 15.sp), color = c.textSoft)
        if (chevron) { Spacer(Modifier.width(4.dp)); Icon(Icons.Outlined.KeyboardArrowRight, null, tint = c.textSoft, modifier = Modifier.size(20.dp)) }
    }
}

@Composable
private fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    val c = LocalSui.current
    Row(Modifier.fillMaxWidth().clip(SuiShape.control).background(c.tile).padding(4.dp)) {
        options.forEach { o ->
            val on = o == selected
            Box(
                Modifier.weight(1f).clip(SuiShape.control).background(if (on) c.page else Color.Transparent).clickable { onPick(o) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label(o), style = SuiType.labelSm.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium), color = if (on) c.text else c.textSub) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeySheet(vm: AgentViewModel, s: UiState, onClose: () -> Unit) {
    val c = LocalSui.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var provider by remember(s.provider) { mutableStateOf(s.provider) }
    var key by remember { mutableStateOf("") }
    var model by remember(provider) { mutableStateOf(vm.modelFor(provider)) }
    var showOther by remember(provider) { mutableStateOf(false) }
    val hasKey = vm.hasKeyFor(provider)
    val fetched = s.modelLists[provider] ?: vm.modelsFor(provider)
    // Keep a typed model in the list so it shows as picked.
    val models = if (fetched.any { it.id == model }) fetched else fetched + vm.modelInfo(provider, model)

    // Ask the provider for its models: on open, and once a new key looks complete.
    LaunchedEffect(provider) { if (hasKey) vm.refreshModels(provider) }
    LaunchedEffect(provider, key) {
        if (key.trim().length >= 20) { kotlinx.coroutines.delay(700); vm.refreshModels(provider, key.trim()) }
    }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet, containerColor = c.card, shape = SheetShape) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = SuiSpace.page + 4.dp).padding(bottom = SuiSpace.section),
            verticalArrangement = Arrangement.spacedBy(SuiSpace.section),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("AI", style = SuiType.title, color = c.text)
                Text("Pick how File Agent thinks.", style = SuiType.bodySm, color = c.textSoft)
            }
            // Three ways to get AI: ours (paid), your own key, or on the phone.
            Column {
                AiChoice(Icons.Outlined.AutoAwesome, "File Agent AI", "No setup. Monthly plan or top-ups.", provider == Provider.Hosted) {
                    provider = Provider.Hosted; vm.refreshHosted(create = true)
                }
                ListDivider(inset = 40.dp + SuiSpace.inner)
                AiChoice(Icons.Outlined.Key, "Your own key", "Claude or DeepSeek. You pay them.", provider == Provider.Claude || provider == Provider.DeepSeek) {
                    if (provider != Provider.Claude && provider != Provider.DeepSeek) { provider = Provider.DeepSeek; key = "" }
                }
                ListDivider(inset = 40.dp + SuiSpace.inner)
                AiChoice(Icons.Outlined.PhoneAndroid, "On phone", "Free and private. Big download.", provider == Provider.Local) {
                    provider = Provider.Local
                }
            }
            if (provider == Provider.Hosted) {
                HostedPanel(vm, s)
                PillButton(
                    "Use File Agent AI",
                    { vm.setModel(provider, model); vm.saveKey(provider, ""); onClose() },
                    Modifier.fillMaxWidth(), primary = true, enabled = s.hosted.signedUp,
                )
            } else if (provider == Provider.Local) {
                LocalAiPanel(vm, s)
                PillButton(
                    "Use on-phone AI",
                    { vm.setModel(provider, model); vm.saveKey(provider, ""); onClose() },
                    Modifier.fillMaxWidth(), primary = true, enabled = s.localModel.status == "ready",
                )
            } else {
            Segmented(listOf(Provider.Claude, Provider.DeepSeek), provider, { it.label }) { provider = it; key = "" }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(key, { key = it }, if (hasKey) "Key saved. Paste to replace." else "${provider.label} API key", secret = true)
                Text("Get a key at ${provider.keySite}", style = SuiType.bodySm, color = c.textSoft)
            }
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = SuiSpace.inner)) {
                    Text("Model", style = SuiType.sub, color = c.textSoft)
                    if (s.modelsLoading) {
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(Modifier.size(14.dp), color = c.textSoft, strokeWidth = 2.dp)
                    }
                }
                if (!hasKey && key.isBlank()) {
                    Text("Add your key to see every model.", style = SuiType.bodySm, color = c.textSoft, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                }
                models.forEachIndexed { i, m ->
                    if (i > 0) ListDivider(inset = 40.dp + SuiSpace.inner)
                    PlainRow(
                        m.name,
                        when (m.vision) { true -> "Sees pictures"; false -> "Text only"; null -> null },
                        leading = { IconTile(if (m.vision == true) Icons.Outlined.Image else Icons.Outlined.ChatBubbleOutline) },
                        onClick = { model = m.id },
                        trailing = { RoundTick(m.id == model) },
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (showOther) {
                    Field(if (models.any { it.id == model && it in fetched }) "" else model, { if (it.isNotBlank()) model = it.trim() }, "Model id")
                } else {
                    Text("Other model", style = SuiType.labelSm, color = c.textSub,
                        modifier = Modifier.clip(SuiShape.pill).clickable { showOther = true }.padding(horizontal = 4.dp, vertical = 8.dp))
                }
            }
            PillButton(
                if (key.isBlank() && hasKey) "Use ${provider.label}" else "Save",
                { vm.setModel(provider, model); vm.saveKey(provider, key); onClose() },
                Modifier.fillMaxWidth(), primary = true, enabled = key.isNotBlank() || hasKey,
            )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemorySheet(vm: AgentViewModel, s: UiState, onClose: () -> Unit) {
    val c = LocalSui.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var fact by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet, containerColor = c.card, shape = SheetShape) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = SuiSpace.page + 4.dp).padding(bottom = SuiSpace.section),
            verticalArrangement = Arrangement.spacedBy(SuiSpace.section),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Memory", style = SuiType.title, color = c.text)
                Text("Facts File Agent keeps across chats. Say \"remember that\" in a chat to add one.", style = SuiType.bodySm, color = c.textSoft)
            }
            Column {
                if (s.memory.isEmpty()) Text("Nothing saved yet.", style = SuiType.bodySm, color = c.textSoft)
                s.memory.forEachIndexed { i, m ->
                    if (i > 0) ListDivider(inset = 0.dp)
                    Row(Modifier.fillMaxWidth().heightIn(min = SuiSpace.row), verticalAlignment = Alignment.CenterVertically) {
                        Text(m, style = SuiType.body, color = c.text, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                        IconButton(Icons.Outlined.Close, "Forget") { vm.removeMemory(m) }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Field(fact, { fact = it }, "Add a fact", modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                PillButton("Add", { vm.addMemory(fact); fact = "" }, small = true, enabled = fact.isNotBlank())
            }
        }
    }
}

private fun sizeLabel(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/** Shown once after a crash, so the reason can be screenshotted or copied. */
@Composable
private fun CrashNote(text: String, onClose: () -> Unit) {
    val c = LocalSui.current
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = c.card,
        title = { Text("The app closed last time", style = SuiType.title, color = c.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Send this to get it fixed.", style = SuiType.bodySm, color = c.textSub)
                Box(Modifier.heightIn(max = 220.dp).clip(SuiShape.row).background(c.tile).verticalScroll(rememberScrollState()).padding(SuiSpace.inner)) {
                    Text(text.lines().take(14).joinToString("\n"), style = SuiType.mono, color = c.textSub)
                }
            }
        },
        confirmButton = {
            PillButton("Copy", {
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("File Agent crash", text))
                onClose()
            }, primary = true, small = true)
        },
        dismissButton = { PillButton("Close", onClose, small = true) },
    )
}

// ---- first run: a short guide for anyone new ------------------------------------------------

/** Five small steps: hello, file access, folders, how it looks and talks, and the optional AI key. */
@Composable
private fun Welcome(vm: AgentViewModel, s: UiState, hasAccess: Boolean, onGrantAccess: () -> Unit) {
    val c = LocalSui.current
    var step by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }
    var showKey by remember { mutableStateOf(false) }
    val last = 4
    BackHandler(step > 0) { step-- }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = SuiSpace.page + 4.dp, vertical = SuiSpace.pad)) {
        // Progress dots
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = SuiSpace.inner)) {
            repeat(last + 1) { i ->
                Box(Modifier.height(4.dp).weight(1f).clip(SuiShape.pill).background(if (i <= step) c.brand else c.tile))
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(SuiSpace.section, Alignment.CenterVertically),
        ) {
            when (step) {
                0 -> {
                    Image(painterResource(R.drawable.ic_logo), contentDescription = "File Agent logo",
                        modifier = Modifier.size(96.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF161616)))
                    WelcomeTitle("File Agent", "Tidies your files. You say yes to every change.")
                    Column {
                        GateLine(Icons.Outlined.Bolt, "Sort a messy folder in one tap")
                        GateLine(Icons.Outlined.ChatBubbleOutline, "Or ask in plain words")
                        GateLine(Icons.Outlined.Undo, "Undo any change")
                        GateLine(Icons.Outlined.Lock, "Your files stay on your phone")
                    }
                }
                1 -> {
                    IconTile(Icons.Outlined.Folder, c.brandDark, c.brandLighter, size = 72.dp)
                    WelcomeTitle("Allow file access", "File Agent needs it to move and sort your files.")
                    Column {
                        GateLine(Icons.Outlined.Check, "Nothing moves until you say yes")
                        GateLine(Icons.Outlined.DeleteOutline, "Deletes go to a bin first")
                    }
                    if (hasAccess) Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Check, null, tint = c.successDark, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Access allowed", style = SuiType.label, color = c.successDark)
                    }
                }
                2 -> {
                    WelcomeTitle("Pick your folders", "File Agent only looks inside the ones you tick. You can change this any time.")
                    val folders = remember(hasAccess) { vm.phoneFolders() }
                    Column {
                        if (folders.isEmpty()) Text("No folders found yet.", style = SuiType.bodySm, color = c.textSoft)
                        folders.forEachIndexed { i, folder ->
                            if (i > 0) ListDivider(inset = 40.dp + SuiSpace.inner)
                            val on = s.places.any { it.type == "local" && it.localPath == folder }
                            PlainRow(folder, null, leading = { IconTile(Icons.Outlined.Folder) },
                                onClick = { vm.togglePhoneFolder(folder) }, trailing = { RoundTick(on) })
                        }
                    }
                    Text("Have a NAS? Add it later from the folder button in a chat.", style = SuiType.bodySm, color = c.textSoft)
                }
                3 -> {
                    WelcomeTitle("Make it yours", "Change these any time in Settings.")
                    Column(verticalArrangement = Arrangement.spacedBy(SuiSpace.inner)) {
                        Text("Theme", style = SuiType.label, color = c.text)
                        Segmented(listOf("system", "light", "dark"), s.theme, { it.replaceFirstChar { ch -> ch.uppercase() } }) { vm.setTheme(it) }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(SuiSpace.inner)) {
                        Text("Text size", style = SuiType.label, color = c.text)
                        Segmented(listOf(1f, 1.15f, 1.3f), s.textScale, { if (it < 1.1f) "A" else if (it < 1.2f) "A+" else "A++" }) { vm.setTextScale(it) }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(SuiSpace.inner)) {
                        Text("AI replies", style = SuiType.label, color = c.text)
                        Segmented(listOf("short", "detailed"), s.replyStyle, { if (it == "short") "Short and simple" else "More detail" }) { vm.setReplyStyle(it) }
                    }
                }
                else -> {
                    IconTile(Icons.Outlined.ChatBubbleOutline, c.brandDark, c.brandLighter, size = 72.dp)
                    WelcomeTitle("Chat with AI (optional)", "Quick sort and Files work without it. To chat, pick an AI.")
                    Column {
                        GateLine(Icons.Outlined.PhoneAndroid, "Free AI that runs on your phone")
                        GateLine(Icons.Outlined.Check, "Or Claude or DeepSeek with your own key")
                    }
                    if (s.hasApiKey) Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Check, null, tint = c.successDark, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("AI is ready", style = SuiType.label, color = c.successDark)
                    } else PillButton("Set up AI", { showKey = true }, icon = Icons.Outlined.Add)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(SuiSpace.inner), modifier = Modifier.padding(top = SuiSpace.pad)) {
            if (step > 0) PillButton("Back", { step-- })
            when {
                step == 1 && !hasAccess -> PillButton("Grant access", onGrantAccess, Modifier.weight(1f), primary = true)
                step == 2 -> PillButton("Next", { step++ }, Modifier.weight(1f), primary = true, enabled = s.places.isNotEmpty())
                step == last -> PillButton(if (s.hasApiKey) "Start" else "Skip for now", vm::finishOnboarding, Modifier.weight(1f), primary = true)
                else -> PillButton(if (step == 0) "Get started" else "Next", { step++ }, Modifier.weight(1f), primary = true)
            }
        }
    }
    if (showKey) KeySheet(vm, s) { showKey = false }
}

@Composable
private fun WelcomeTitle(title: String, sub: String) {
    val c = LocalSui.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = SuiType.display, color = c.text)
        Text(sub, style = SuiType.body, color = c.textSub)
    }
}

/** A newer build is ready: what changed, and one button to get it. */
@Composable
private fun UpdateCard(vm: AgentViewModel, s: UiState) {
    val c = LocalSui.current
    val info = s.update ?: return
    Column(
        Modifier.fillMaxWidth().clip(SuiShape.card).background(c.brandLighter).padding(SuiSpace.pad),
        verticalArrangement = Arrangement.spacedBy(SuiSpace.inner),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ArrowUpward, null, tint = c.brandDark, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Update ready", style = SuiType.label, color = c.brandDark, modifier = Modifier.weight(1f))
            Text("Build ${info.build} · ${info.bytes / (1024 * 1024)} MB", style = SuiType.bodySm, color = c.brandDark)
        }
        val firstLine = info.notes.lineSequence().firstOrNull { it.isNotBlank() }
        if (firstLine != null) Text(firstLine, style = SuiType.bodySm, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        s.updateStatus?.let { Text(it, style = SuiType.labelSm, color = c.textSub) }
        if (s.updateBusy) LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp).clip(SuiShape.pill), color = c.brand, trackColor = c.tile)
        PillButton("Update", vm::installUpdate, Modifier.fillMaxWidth(), primary = true, enabled = !s.updateBusy)
    }
}

/** The on-phone AI: what it is, the one-time download, and its state. */
@Composable
private fun LocalAiPanel(vm: AgentViewModel, s: UiState) {
    val c = LocalSui.current
    val m = s.localModel
    var problem by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(SuiSpace.inner)) {
        Column {
            GateLine(Icons.Outlined.Lock, "Private. Nothing leaves your phone.")
            GateLine(Icons.Outlined.Check, "Free. No key. Works offline.")
            GateLine(Icons.Outlined.Bolt, "Best for simple jobs. Slower than cloud AI.")
        }
        if (s.ramGb in 1..5) {
            Text("This phone has ${s.ramGb} GB of memory. The AI may be slow or may not run.", style = SuiType.bodySm, color = c.errorDark,
                modifier = Modifier.fillMaxWidth().clip(SuiShape.row).background(c.errorLighter).padding(SuiSpace.inner))
        }
        when (m.status) {
            "ready" -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Check, null, tint = c.successDark, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Downloaded and ready", style = SuiType.label, color = c.successDark, modifier = Modifier.weight(1f))
                Text("Delete", style = SuiType.labelSm, color = c.errorDark,
                    modifier = Modifier.clip(SuiShape.pill).clickable { vm.removeLocalModel() }.padding(horizontal = 12.dp, vertical = 8.dp))
            }
            "downloading" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.note ?: "Downloading ${m.percent}%", style = SuiType.label, color = c.text, modifier = Modifier.weight(1f))
                    Text("Cancel", style = SuiType.labelSm, color = c.textSub,
                        modifier = Modifier.clip(SuiShape.pill).clickable { vm.removeLocalModel() }.padding(horizontal = 12.dp, vertical = 8.dp))
                }
                LinearProgressIndicator(progress = m.percent / 100f, modifier = Modifier.fillMaxWidth().height(6.dp).clip(SuiShape.pill), color = c.brand, trackColor = c.tile)
                Text("You can leave the app. It keeps going.", style = SuiType.bodySm, color = c.textSoft)
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (problem ?: m.note)?.let { Text(it, style = SuiType.bodySm, color = c.errorDark) }
                PillButton(if (m.status == "failed") "Try again" else "Download · 2.6 GB", { problem = vm.downloadLocalModel() },
                    Modifier.fillMaxWidth(), icon = Icons.Outlined.Download)
                Text("One time only. Use Wi-Fi.", style = SuiType.bodySm, color = c.textSoft)
            }
        }
    }
}

/** One way to get AI, as a tickable row. */
@Composable
private fun AiChoice(icon: ImageVector, title: String, meta: String, on: Boolean, onClick: () -> Unit) {
    val c = LocalSui.current
    PlainRow(title, meta, leading = { IconTile(icon, if (on) c.brand else null, if (on) c.brandLighter else null) },
        onClick = onClick, trailing = { RoundTick(on) })
}

/** File Agent AI: messages left, the plan and top-up, and the account backup. */
@Composable
private fun HostedPanel(vm: AgentViewModel, s: UiState) {
    val c = LocalSui.current
    val h = s.hosted
    val ctx = LocalContext.current
    var restore by remember { mutableStateOf(false) }
    var restoreKey by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    // Coming back from the payment page: load the new balance.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) vm.refreshHosted() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Unit) { vm.refreshHosted(create = true) }

    Column(verticalArrangement = Arrangement.spacedBy(SuiSpace.inner)) {
        when {
            h.loading && !h.signedUp -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), color = c.brand, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Setting up", style = SuiType.label, color = c.textSub)
            }
            h.error != null && !h.signedUp -> Text(h.error, style = SuiType.bodySm, color = c.errorDark)
            else -> Column(
                Modifier.fillMaxWidth().clip(SuiShape.card).background(c.tile).padding(SuiSpace.pad),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("${h.left}", style = SuiType.display, color = c.text)
                Text("messages left", style = SuiType.bodySm, color = c.textSoft)
                if (h.plan == "plus") {
                    val until = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(h.planUntil * 1000))
                    Text("Plus · renews $until", style = SuiType.labelSm, color = c.successDark)
                }
                h.error?.let { Text(it, style = SuiType.bodySm, color = c.errorDark) }
            }
        }
        if (h.signedUp) {
            if (h.plan != "plus") PillButton("Get Plus · £4.99 a month", { vm.openPayment(h.planLink) }, Modifier.fillMaxWidth(),
                primary = true, icon = Icons.Outlined.AutoAwesome, enabled = h.planLink.isNotBlank())
            if (h.plan != "plus") Text("${h.planMessages} messages every month. Cancel any time.", style = SuiType.bodySm, color = c.textSoft)
            PillButton("Top up · £2.99 for ${h.packMessages}", { vm.openPayment(h.packLink) }, Modifier.fillMaxWidth(),
                enabled = h.packLink.isNotBlank())
            Text("Top-ups never expire. One message can take a few AI steps; it counts as one.", style = SuiType.bodySm, color = c.textSoft)
            if (h.planLink.isBlank()) Text("Payments open soon.", style = SuiType.bodySm, color = c.textSoft)

            // Backup, so a reinstall or a new phone keeps what was paid for.
            Row(horizontalArrangement = Arrangement.spacedBy(SuiSpace.inner)) {
                PillButton("Copy account key", {
                    val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("File Agent account key", vm.hostedKey()))
                    android.widget.Toast.makeText(ctx, "Copied. Keep it somewhere safe.", android.widget.Toast.LENGTH_SHORT).show()
                }, small = true)
                PillButton("Restore", { restore = !restore }, small = true)
            }
            Text("Save your account key. You need it to keep your plan after a reinstall.", style = SuiType.bodySm, color = c.textSoft)
            if (restore) {
                Field(restoreKey, { restoreKey = it; problem = null }, "Paste account key", secret = true)
                problem?.let { Text(it, style = SuiType.bodySm, color = c.errorDark) }
                PillButton("Restore account", { problem = vm.restoreHosted(restoreKey); if (problem == null) restore = false },
                    Modifier.fillMaxWidth(), enabled = restoreKey.isNotBlank())
            }
        }
    }
}
