package com.sagarsystemslab.nownetwork.experience

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.sagarsystemslab.nownetwork.BuildConfig
import com.sagarsystemslab.nownetwork.designsystem.*
import com.sagarsystemslab.nownetwork.feature.common.*
import com.sagarsystemslab.nownetwork.security.AndroidKeystoreSecretStore
import com.sagarsystemslab.nownetwork.wallet.WalletInteractionHost
import java.io.ByteArrayOutputStream
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
enum class ExperienceDestination { PROFILE, ACCOUNT, NOTIFICATIONS, WALLET, SETTINGS, APPEARANCE, NOTIFICATION_PREFERENCES, PRIVACY_PERMISSIONS, DATA_STORAGE, LANGUAGE_REGION, SECURITY, CONNECTED_SESSIONS, ACCOUNT_RECOVERY, PAYOUT_PREFERENCES, HELP_ABOUT, BROWSE_AREAS }

@Serializable
data class ExperienceRoute(val destination: ExperienceDestination)

@Composable
fun ExperienceScreen(
    destination: ExperienceDestination,
    viewModel: ExperienceViewModel,
    uiPreferencesStore: UiPreferencesStore,
    walletHost: WalletInteractionHost,
    onBack: () -> Unit,
    navigate: (ExperienceDestination) -> Unit,
    onNotification: (JsonObject) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val preferences by uiPreferencesStore.state.collectAsStateWithLifecycle(uiPreferencesStore.initial)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(destination) {
        when (destination) {
            ExperienceDestination.BROWSE_AREAS -> viewModel.areas()
            ExperienceDestination.CONNECTED_SESSIONS -> viewModel.devices()
            ExperienceDestination.NOTIFICATIONS -> viewModel.inbox()
            ExperienceDestination.PROFILE, ExperienceDestination.ACCOUNT, ExperienceDestination.WALLET -> viewModel.refresh()
            else -> Unit
        }
    }
    if (destination == ExperienceDestination.BROWSE_AREAS) {
        BrowseAreasScreen(state, viewModel, onBack); return
    }
    if (destination == ExperienceDestination.NOTIFICATIONS) {
        NotificationsScreen(state, viewModel, onBack, { navigate(ExperienceDestination.NOTIFICATION_PREFERENCES) }, onNotification); return
    }
    val title = when (destination) {
        ExperienceDestination.PROFILE -> "Profile"
        ExperienceDestination.ACCOUNT -> "Account"
        ExperienceDestination.WALLET -> "Wallet & Connections"
        ExperienceDestination.SETTINGS -> "Settings"
        ExperienceDestination.APPEARANCE -> "Appearance"
        ExperienceDestination.NOTIFICATION_PREFERENCES -> "Notifications"
        ExperienceDestination.PRIVACY_PERMISSIONS -> "Privacy & permissions"
        ExperienceDestination.DATA_STORAGE -> "Data & storage"
        ExperienceDestination.LANGUAGE_REGION -> "Language & region"
        ExperienceDestination.SECURITY -> "Security"
        ExperienceDestination.CONNECTED_SESSIONS -> "Connected devices"
        ExperienceDestination.ACCOUNT_RECOVERY -> "Account recovery"
        ExperienceDestination.PAYOUT_PREFERENCES -> "Payout preferences"
        else -> "Help & About"
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("screen-${destination.name.lowercase()}"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ExperienceTopBar(title, onBack)
        ExperienceFeedback(state, viewModel)
        when (destination) {
            ExperienceDestination.PROFILE -> {
                ProfileHero(state)
                MetricStrip(listOf((if (state.profile.isEmpty()) "—" else state.profile.number("verified_contributions").toString()) to "Verified proofs", (if (state.profile.isEmpty()) "—" else state.profile.number("completed_refreshes").toString()) to "Refreshes"))
                NowGlassCard {
                    ExperienceRow("Account", "Your identity and recovery", Icons.Outlined.PersonOutline, { navigate(ExperienceDestination.ACCOUNT) })
                    ExperienceRow("Wallet & Connections", "Manage wallets and future payouts", Icons.Outlined.AccountBalanceWallet, { navigate(ExperienceDestination.WALLET) })
                    ExperienceRow("Notifications", "Your proof and payment updates", Icons.Outlined.Notifications, { navigate(ExperienceDestination.NOTIFICATIONS) })
                }
                NowGlassCard {
                    ExperienceRow("Settings", "Make NOW yours", Icons.Outlined.Settings, { navigate(ExperienceDestination.SETTINGS) })
                    ExperienceRow("Help & About", "Learn how NOW works", Icons.Outlined.HelpOutline, { navigate(ExperienceDestination.HELP_ABOUT) })
                }
            }
            ExperienceDestination.ACCOUNT -> AccountContent(state, viewModel, navigate)
            ExperienceDestination.WALLET, ExperienceDestination.PAYOUT_PREFERENCES -> WalletContent(state, viewModel, walletHost, destination == ExperienceDestination.PAYOUT_PREFERENCES)
            ExperienceDestination.SETTINGS -> {
                ProfileHero(state, compact = true)
                NowGlassCard {
                    listOf(
                        Triple("Appearance", Icons.Outlined.Palette, ExperienceDestination.APPEARANCE),
                        Triple("Notification preferences", Icons.Outlined.Notifications, ExperienceDestination.NOTIFICATION_PREFERENCES),
                        Triple("Privacy & permissions", Icons.Outlined.Shield, ExperienceDestination.PRIVACY_PERMISSIONS),
                        Triple("Data & storage", Icons.Outlined.Storage, ExperienceDestination.DATA_STORAGE),
                        Triple("Language & region", Icons.Outlined.Language, ExperienceDestination.LANGUAGE_REGION),
                    ).forEach { (label, icon, route) -> ExperienceRow(label, icon = icon, onClick = { navigate(route) }) }
                }
                NowGlassCard {
                    ExperienceRow("Security", "Device lock and account access", Icons.Outlined.Lock, { navigate(ExperienceDestination.SECURITY) })
                    ExperienceRow("Connected devices", icon = Icons.Outlined.Devices, onClick = { navigate(ExperienceDestination.CONNECTED_SESSIONS) })
                    ExperienceRow("Account recovery", icon = Icons.Outlined.Restore, onClick = { navigate(ExperienceDestination.ACCOUNT_RECOVERY) })
                    ExperienceRow("Payout preferences", icon = Icons.Outlined.Payments, onClick = { navigate(ExperienceDestination.PAYOUT_PREFERENCES) })
                    ExperienceRow("Help & About", icon = Icons.Outlined.Info, onClick = { navigate(ExperienceDestination.HELP_ABOUT) })
                }
                Text("NOW Network · ${BuildConfig.VERSION_NAME}", style = NowType.BodyS, color = NowColors.Ink500)
            }
            ExperienceDestination.APPEARANCE -> {
                NowSectionTitle("Your preferred look", "Follow your device or choose a theme for NOW.")
                NowGlassCard {
                    ThemeMode.entries.forEach { mode ->
                        ExperienceRow(mode.name.lowercase().replaceFirstChar { it.uppercase() }, icon = when (mode) { ThemeMode.SYSTEM -> Icons.Outlined.SettingsBrightness; ThemeMode.DARK -> Icons.Outlined.DarkMode; ThemeMode.LIGHT -> Icons.Outlined.LightMode }, onClick = { scope.launch { uiPreferencesStore.theme(mode) } }, trailing = { RadioButton(preferences.theme == mode, { scope.launch { uiPreferencesStore.theme(mode) } }) })
                    }
                }
                NowGlassCard { ExperienceRow("Reduce motion", "Keep essential progress and soften decorative animation.", Icons.Outlined.Animation, trailing = { Switch(preferences.reduceMotion, { scope.launch { uiPreferencesStore.motion(it) } }) }) }
                NowGlassCard(emphasized = true) { Text("A little clarity, everywhere.", style = NowType.TitleM, color = NowColors.Ink950); NowStatusChip("LIVE", NowStatusTone.LIVE); Text("This preview follows your selected appearance.", style = NowType.BodyM, color = NowColors.Ink600) }
            }
            ExperienceDestination.NOTIFICATION_PREFERENCES -> NotificationPreferencesContent(state, viewModel)
            ExperienceDestination.PRIVACY_PERMISSIONS -> PermissionsContent()
            ExperienceDestination.DATA_STORAGE -> StorageContent()
            ExperienceDestination.LANGUAGE_REGION -> {
                NowGlassCard { ExperienceRow("App language", "English · available language", Icons.Outlined.Language); Text("More app translations are not available yet.", style = NowType.BodyS, color = NowColors.Ink600) }
                NowGlassCard {
                    listOf("metric" to "Kilometers", "imperial" to "Miles").forEach { (value, label) -> ExperienceRow(label, icon = Icons.Outlined.Straighten, onClick = { scope.launch { uiPreferencesStore.units(value) } }, trailing = { RadioButton(preferences.distanceUnit == value, { scope.launch { uiPreferencesStore.units(value) } }) }) }
                }
                NowGlassCard { ExperienceRow("Browse region", "Choose an area without sharing your device location", Icons.Outlined.Map, { navigate(ExperienceDestination.BROWSE_AREAS) }); ExperienceRow("Timezone", ZoneId.systemDefault().id, Icons.Outlined.Schedule) }
            }
            ExperienceDestination.SECURITY -> SecurityContent { navigate(ExperienceDestination.CONNECTED_SESSIONS) }
            ExperienceDestination.CONNECTED_SESSIONS -> DevicesContent(state, viewModel)
            ExperienceDestination.ACCOUNT_RECOVERY -> RecoveryContent(state, viewModel, walletHost)
            ExperienceDestination.HELP_ABOUT -> HelpContent()
            else -> Unit
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun ExperienceFeedback(state: ExperienceUiState, viewModel: ExperienceViewModel) {
    if (state.loading || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth(), color = NowColors.Blue600)
    state.error?.let { NowNotice(it, title = "Couldn't complete that action", tone = NowNoticeTone.ERROR); NowSecondaryButton("Try again", viewModel::refresh, Modifier.fillMaxWidth(), enabled = !state.loading && !state.saving) }
    state.notice?.let { NowNotice(it, tone = NowNoticeTone.SUCCESS) }
}

@Composable
private fun ProfileHero(state: ExperienceUiState, compact: Boolean = false) {
    NowGlassCard(emphasized = true) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            RemoteAvatar(state.profile.text("avatar_url"), state.profile.text("display_name", "N"), if (compact) 52 else 72)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(state.profile.text("display_name").ifBlank { "Your NOW account" }, style = if (compact) NowType.TitleM else NowType.TitleXL, color = NowColors.Ink950)
                Text(if (state.me == null) "Connect to load your account" else "${state.profile.number("verified_contributions")} verified contributions", style = NowType.BodyM, color = NowColors.Ink600)
                if (!compact) Text("Member since ${displayDate(state.profile.text("created_at"))}", style = NowType.BodyS, color = NowColors.Ink500)
            }
        }
    }
}

@Composable
private fun RemoteAvatar(url: String, name: String, size: Int = 64) {
    val image by produceState<Bitmap?>(null, url) {
        value = null
        if (url.startsWith("https://")) value = withContext(Dispatchers.IO) {
            runCatching {
                val connection = URL(url).openConnection().apply { connectTimeout = 8000; readTimeout = 8000 }
                connection.getInputStream().use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (output.size() <= 524288) {
                        val read = input.read(buffer, 0, minOf(buffer.size, 524289 - output.size()))
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                    val bytes = output.toByteArray()
                    if (bytes.size > 524288) null else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }.getOrNull()
        }
    }
    Surface(Modifier.size(size.dp), shape = CircleShape, color = NowColors.InfoSoft, border = BorderStroke(1.dp, NowColors.Blue600)) {
        if (image != null) Image(image!!.asImageBitmap(), "Profile photo", contentScale = ContentScale.Crop)
        else Box(contentAlignment = Alignment.Center) { Text(name.trim().take(1).uppercase().ifBlank { "N" }, style = NowType.TitleXL, color = NowColors.Blue600) }
    }
}

@Composable
private fun AccountContent(state: ExperienceUiState, viewModel: ExperienceViewModel, navigate: (ExperienceDestination) -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var name by rememberSaveable(state.profile.text("display_name")) { mutableStateOf(state.profile.text("display_name")) }
    var photoError by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            try {
                val data = withContext(Dispatchers.IO) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
                    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Choose a readable image." }
                    val options = BitmapFactory.Options().apply { inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 512).coerceAtLeast(1) }
                    val bitmap = context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) } ?: error("Image unavailable")
                    val ratio = minOf(1f, 512f / maxOf(bitmap.width, bitmap.height))
                    val resized = Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
                    val stream = ByteArrayOutputStream(); resized.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    val bytes = stream.toByteArray(); require(bytes.size <= 524288) { "Choose a simpler or smaller photo (under 512 KB)." }
                    Base64.encodeToString(bytes, Base64.NO_WRAP)
                }
                viewModel.avatar(data); photoError = null
            } catch (cancel: CancellationException) { throw cancel } catch (error: Exception) { photoError = error.message }
        }
    }
    ProfileHero(state)
    NowGlassCard {
        NowSecondaryButton("Change profile photo", { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, Modifier.fillMaxWidth(), enabled = !state.saving)
        if (state.profile.text("avatar_url").isNotBlank()) TextButton({ viewModel.avatar(null) }, enabled = !state.saving) { Text("Remove photo") }
        photoError?.let { NowNotice(it, tone = NowNoticeTone.ERROR) }
        NowTextField(name, { name = it.take(64) }, "Display name")
        NowPrimaryButton("Save changes", { viewModel.saveName(name.trim()) }, Modifier.fillMaxWidth(), enabled = name.isNotBlank() && !state.saving)
    }
    NowGlassCard {
        ExperienceRow("Account ID", state.me?.actorId ?: "Unavailable", Icons.Outlined.Badge)
        ExperienceRow("Recovery wallet", "Prove ownership of a linked wallet to recover this account.", Icons.Outlined.Restore, { navigate(ExperienceDestination.ACCOUNT_RECOVERY) })
        ExperienceRow("Connected devices", icon = Icons.Outlined.Devices, onClick = { navigate(ExperienceDestination.CONNECTED_SESSIONS) })
    }
}

@Composable
private fun WalletContent(state: ExperienceUiState, viewModel: ExperienceViewModel, host: WalletInteractionHost, payout: Boolean) {
    val clipboard = LocalClipboardManager.current
    val wallets = state.me?.walletBindings.orEmpty().filter { it.status == "ACTIVE" }
    val selected = state.preferences.text("payout_wallet_binding_id")
    NowNotice(if (payout) "Your default applies to future claims. Existing claims keep their locked payout wallet." else "Wallet ownership is verified by a signed message. Connecting never transfers funds.")
    wallets.forEach { wallet ->
        NowGlassCard(emphasized = true) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AccountBalanceWallet, null, tint = NowColors.Blue600, modifier = Modifier.size(28.dp))
                Text("  ${wallet.cluster.uppercase()}", Modifier.weight(1f), style = NowType.LabelL, color = NowColors.Ink950)
                NowStatusChip("Ownership verified", NowStatusTone.LIVE)
            }
            Text(wallet.walletAddress, style = NowType.BodyM, color = NowColors.Ink700)
            TextButton({ clipboard.setText(AnnotatedString(wallet.walletAddress)) }) { Text("Copy address") }
            if (payout) {
                NowSecondaryButton(if (selected == wallet.walletBindingId) "Default for future claims" else "Use for future claims", { viewModel.payout(wallet.walletBindingId) }, Modifier.fillMaxWidth(), enabled = selected != wallet.walletBindingId && !state.saving)
            } else {
                state.balances[wallet.walletBindingId]?.let { (sol, token) -> MetricStrip(listOf(sol to "Network balance", token to "Reward balance")) }
                NowSecondaryButton("Refresh balances", { viewModel.balances(wallet) }, Modifier.fillMaxWidth(), enabled = !state.saving)
                Text("Balances are independent of pending earnings.", style = NowType.BodyS, color = NowColors.Ink600)
            }
        }
    }
    if (wallets.isEmpty()) NowGlassCard { Text("Connect your wallet", style = NowType.TitleM, color = NowColors.Ink950); Text("Use a compatible Solana mobile wallet to claim, fund refreshes and recover your account.", style = NowType.BodyM, color = NowColors.Ink600) }
    NowPrimaryButton("Connect wallet", { viewModel.connectWallet(host) }, Modifier.fillMaxWidth(), enabled = !state.saving)
    if (!payout) NowSecondaryButton("Disconnect local wallet session", { viewModel.disconnectWallet(host) }, Modifier.fillMaxWidth(), enabled = !state.saving)
}

@Composable
private fun NotificationPreferencesContent(state: ExperienceUiState, viewModel: ExperienceViewModel) {
    val context = LocalContext.current
    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.devices() }
    NowGlassCard {
        ExperienceRow("Device notifications", if (NowPush.configured) "Android controls delivery on this device" else "Push is not configured for this build", Icons.Outlined.NotificationsActive)
        NowSecondaryButton("Enable device alerts", {
            if (Build.VERSION.SDK_INT >= 33) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            else { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }
        }, Modifier.fillMaxWidth(), enabled = NowPush.configured)
    }
    val source = state.preferences.objectAt("notifications")
    var draft by remember(source) { mutableStateOf(source) }
    fun set(key: String, value: JsonElement) { draft = JsonObject(draft + (key to value)) }
    NowGlassCard {
        listOf("proof" to "Proof & refresh progress", "payments" to "Payments & receipts", "security" to "Account security", "opportunities" to "Nearby opportunities").forEach { (key, title) ->
            ExperienceRow(title, icon = Icons.Outlined.Notifications, trailing = { Switch(draft.flag(key), { set(key, JsonPrimitive(it)) }, enabled = source.isNotEmpty() && !state.saving) })
        }
    }
    LaunchedEffect(Unit) { viewModel.areas() }
    if (draft.flag("opportunities")) NowGlassCard {
        NowSectionTitle("Opportunity areas", "Choose where you want new-task alerts")
        val selectedAreas = (draft["area_ids"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        state.areas.forEach { area ->
            val id = area.text("area_id")
            ExperienceRow(area.text("name"), icon = Icons.Outlined.LocationOn, trailing = { Checkbox(id in selectedAreas, { checked ->
                val ids = if (checked) (selectedAreas + id).distinct().take(20) else selectedAreas - id
                set("area_ids", JsonArray(ids.map(::JsonPrimitive)))
            }) })
        }
        if (state.nextAreaOffset != null) NowSecondaryButton("Load more areas", { viewModel.areas(more = true) })
        if (state.areas.isEmpty()) Text("Choose an area after the catalog loads.", color = NowColors.Ink600)
    }
    NowGlassCard {
        ExperienceRow("Quiet hours", "Times follow the selected timezone", Icons.Outlined.Bedtime, trailing = { Switch(draft.flag("quiet_enabled"), { set("quiet_enabled", JsonPrimitive(it)) }) })
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NowTextField(draft.text("quiet_start", "22:00"), { set("quiet_start", JsonPrimitive(it)) }, "From (HH:mm)", Modifier.weight(1f))
            NowTextField(draft.text("quiet_end", "08:00"), { set("quiet_end", JsonPrimitive(it)) }, "Until (HH:mm)", Modifier.weight(1f))
        }
        NowTextField(draft.text("timezone", "UTC"), { set("timezone", JsonPrimitive(it)) }, "Timezone", supportingText = "For example: Asia/Kolkata")
        NowSecondaryButton("Use device timezone", { set("timezone", JsonPrimitive(ZoneId.systemDefault().id)) }, Modifier.fillMaxWidth())
    }
    if (!state.preferences.flag("push_available")) NowNotice("Push delivery is not configured for this build. Your in-app inbox continues to show real account events.", title = "In-app updates available")
    NowPrimaryButton("Save notification preferences", { viewModel.savePreferences(draft) }, Modifier.fillMaxWidth(), enabled = source.isNotEmpty() && !state.saving)
}

@Composable
private fun PermissionsContent() {
    val context = LocalContext.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) revision++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    @Suppress("UNUSED_VARIABLE") val observedRevision = revision
    fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    NowGlassCard {
        ExperienceRow("Location", if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) "Precise location allowed" else if (granted(Manifest.permission.ACCESS_COARSE_LOCATION)) "Approximate location allowed" else "Not allowed", Icons.Outlined.LocationOn)
        ExperienceRow("Camera", if (granted(Manifest.permission.CAMERA)) "Allowed" else "Not allowed", Icons.Outlined.CameraAlt)
        ExperienceRow("Notifications", if (androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) "Allowed by Android" else "Not allowed by Android", Icons.Outlined.Notifications)
        NowPrimaryButton("Open Android permissions", { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }, Modifier.fillMaxWidth())
    }
    NowGlassCard { Text("Your location, with purpose", style = NowType.TitleM, color = NowColors.Ink950); Text("Browse a chosen area without sharing your device location. Claim evidence uses the location requirements shown before you accept. Evidence photos stay private to authorized participants.", style = NowType.BodyM, color = NowColors.Ink600) }
}

@Composable
private fun StorageContent() {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var usage by remember { mutableStateOf<Long?>(null) }; var busy by remember { mutableStateOf(false) }; var confirm by remember { mutableStateOf(false) }
    suspend fun measure() { usage = withContext(Dispatchers.IO) { context.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } } }
    LaunchedEffect(Unit) { measure() }
    NowGlassCard {
        Text(usage?.let { "${java.text.DecimalFormat("0.0").format(it / 1048576.0)} MB" } ?: "Measuring…", style = NowType.DataLarge, color = NowColors.Ink950)
        Text("Temporary cache on this device", style = NowType.BodyM, color = NowColors.Ink600)
        NowSecondaryButton("Clear temporary cache", { confirm = true }, Modifier.fillMaxWidth(), enabled = !busy && usage != null)
    }
    NowNotice("Clearing temporary cache preserves pending evidence, operation recovery, wallet secrets and receipts.")
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Clear temporary cache?") }, text = { Text("Downloaded temporary files will be loaded again when needed.") }, confirmButton = { TextButton({ confirm = false; scope.launch { busy = true; withContext(Dispatchers.IO) { context.cacheDir.listFiles()?.forEach { file -> file.deleteRecursively() } }; measure(); busy = false } }) { Text("Clear") } }, dismissButton = { TextButton({ confirm = false }) { Text("Keep cache") } })
}

@Composable
private fun SecurityContent(onDevices: () -> Unit) {
    val context = LocalContext.current
    val secrets = remember { AndroidKeystoreSecretStore(context) }
    var enabled by remember { mutableStateOf(secrets.read("app_lock_enabled") == "true") }
    val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    val verify = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) { enabled = !enabled; secrets.write("app_lock_enabled", enabled.toString()) }
    }
    NowGlassCard {
        ExperienceRow("Lock NOW on return", "Unlock using your Android device security", Icons.Outlined.Lock, trailing = { Switch(enabled, {
            val intent = keyguard.createConfirmDeviceCredentialIntent("Secure NOW", "Confirm your device credential to change app lock.")
            if (intent != null) verify.launch(intent) else context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
        }) })
        ExperienceRow("Connected devices", "Review and revoke actual sessions", Icons.Outlined.Devices, onDevices)
    }
    NowNotice("App lock protects this device's interface. Your wallet still approves signatures and transactions separately.")
}

@Composable
private fun DevicesContent(state: ExperienceUiState, viewModel: ExperienceViewModel) {
    var selected by remember { mutableStateOf<JsonObject?>(null) }
    state.installations.forEach { device -> NowGlassCard {
        ExperienceRow(device.text("device_name"), "${if (device.flag("current")) "This device · " else ""}Last active ${displayDate(device.text("last_seen_at"))}", Icons.Outlined.PhoneAndroid)
        if (device.text("revoked_at").isNotBlank()) Text("Access revoked", color = NowColors.StaleText, style = NowType.LabelM)
        else NowSecondaryButton("Revoke access", { selected = device }, Modifier.fillMaxWidth(), enabled = !state.saving)
    } }
    if (state.installations.isEmpty()) NowNotice("No registered devices are available. Register this device to manage its access.")
    NowSecondaryButton("Register & refresh devices", viewModel::devices, Modifier.fillMaxWidth(), enabled = !state.saving)
    selected?.let { device -> AlertDialog(onDismissRequest = { selected = null }, title = { Text("Revoke ${device.text("device_name")}?") }, text = { Text("This session will lose API access immediately. The wallet and finalized receipts remain yours.") }, confirmButton = { TextButton({ selected = null; viewModel.revokeDevice(device.text("installation_id")) }) { Text("Revoke access") } }, dismissButton = { TextButton({ selected = null }) { Text("Keep access") } }) }
}

@Composable
private fun RecoveryContent(state: ExperienceUiState, viewModel: ExperienceViewModel, host: WalletInteractionHost) {
    var confirm by remember { mutableStateOf(false) }
    NowGlassCard {
        Icon(Icons.Outlined.Restore, null, Modifier.size(44.dp), tint = NowColors.Blue600)
        Text("Your wallet is your recovery key", style = NowType.TitleM, color = NowColors.Ink950)
        Text("Connect a wallet already linked to your NOW account and sign the ownership challenge. Never enter a seed phrase in NOW.", style = NowType.BodyM, color = NowColors.Ink600)
        NowPrimaryButton("Recover with a linked wallet", { viewModel.connectWallet(host) }, Modifier.fillMaxWidth(), enabled = !state.saving)
    }
    NowGlassCard {
        Text("Before you sign out", style = NowType.TitleM, color = NowColors.Ink950)
        Text("Keep access to a linked wallet. Pending proof and uncertain payments must be resolved first.", style = NowType.BodyM, color = NowColors.Ink600)
        NowSecondaryButton("Sign out on this device", { confirm = true }, Modifier.fillMaxWidth(), enabled = !state.saving)
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Sign out of NOW?") }, text = { Text("You will need your linked wallet to recover this account.") }, confirmButton = { TextButton({ confirm = false; viewModel.signOut() }) { Text("Sign out") } }, dismissButton = { TextButton({ confirm = false }) { Text("Stay signed in") } })
}

@Composable
private fun HelpContent() {
    val context = LocalContext.current
    listOf("How earning works" to "Find a funded opportunity, review its locked proof requirements, claim with your wallet, and capture fresh evidence on location. Verified evidence proceeds through settlement; earnings become final with a receipt.",
        "Why freshness matters" to "Live, aging and stale reflect the observation's server timestamps. A stale state needs another verified observation.",
        "Payments and receipts" to "The reward pool can be shared by multiple witnesses. Your personal payout is shown separately when settlement is finalized. An uncertain transaction is reconciled before another attempt.").forEach { (title, body) ->
        var expanded by rememberSaveable(title) { mutableStateOf(false) }
        NowGlassCard { ExperienceRow(title, icon = Icons.Outlined.HelpOutline, onClick = { expanded = !expanded }); if (expanded) Text(body, style = NowType.BodyM, color = NowColors.Ink600) }
    }
    NowGlassCard {
        ExperienceRow("NOW Network", "Version ${BuildConfig.VERSION_NAME} · ${BuildConfig.SOLANA_CLUSTER}", Icons.Outlined.Info)
        ExperienceRow("Project & support", "Open the project's GitHub repository", Icons.Outlined.Code, { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/sagar-systems-lab/now-network"))) })
        ExperienceRow("Map attribution", "© OpenStreetMap contributors · OpenFreeMap · MapLibre", Icons.Outlined.Map, { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/copyright"))) })
        Text("Terms and privacy publication are pending. No unapproved policy text is presented as final.", style = NowType.BodyS, color = NowColors.Ink600)
    }
}

private fun displayDate(value: String): String = runCatching { DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.systemDefault()).format(Instant.parse(value)) }.getOrDefault("unavailable")
