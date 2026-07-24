package com.example.admuteforspotify

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.absoluteValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle

// ── App color constants ─────────────────────────────────────────────────────
// Each target app gets its own brand accent for visual identity.

private val BgPrimary = Color(0xFF0D0D0D)
private val BgCard = Color(0xFF1A1A1A)
private val BgCardBorder = Color(0xFF2A2A2A)
private val TextPrimary = Color(0xFFFFFFFF)
private val TextSecondary = Color(0xFFAAAAAA)
private val TextMuted = Color(0xFF666666)

/** Brand accent colors for each target app. */
private val YouTubeRed = Color(0xFFFF0033)
private val SpotifyGreen = Color(0xFF1DB954)
private val FacebookBlue = Color(0xFF1877F2)
private val InstagramPink = Color(0xFFE1306C)

// ── Dark color scheme ───────────────────────────────────────────────────────

private val AdMuteDarkScheme = darkColorScheme(
    primary = SpotifyGreen,
    onPrimary = BgPrimary,
    surface = BgCard,
    onSurface = TextPrimary,
    background = BgPrimary,
    onBackground = TextPrimary,
    surfaceVariant = BgCardBorder,
    onSurfaceVariant = TextSecondary,
)

// ── Data model for each pager page ──────────────────────────────────────────

/**
 * Holds the display properties for a single target app page.
 *
 * @param appName      Display name shown as the page title.
 * @param packageName  Android package name (for future integration).
 * @param accent       Brand accent color for highlights and gradients.
 * @param emoji        Visual icon alongside the app name.
 * @param statsKey     Key used to look up counters in [StatsManager]
 *                     (e.g. "youtube", "facebook").
 */
data class TargetApp(
    val appName: String,
    val packageName: String,
    val accent: Color,
    val emoji: String,
    val statsKey: String,
)

private val TARGET_APPS = listOf(
    TargetApp("Spotify",   "com.spotify.music",          SpotifyGreen,  "♫", StatsManager.APP_SPOTIFY),
)

// ─────────────────────────────────────────────────────────────────────────────
// MainActivity
// ─────────────────────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── Permission gate ─────────────────────────────────────────────────
        // If any of the required permissions is missing, redirect to setup.
        if (!isAllPermissionsGranted()) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        checkForCrashReport()

        setContent {
            MaterialTheme(colorScheme = AdMuteDarkScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    var showSettings by remember { mutableStateOf(false) }
                    if (showSettings) {
                        BackHandler { showSettings = false }
                        SettingsScreen(onBack = { showSettings = false })
                    } else {
                        AdMuteDashboard(onSettingsClick = { showSettings = true })
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        // Re-check permissions on every resume (user may have revoked them).
        if (!isAllPermissionsGranted()) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
        }
    }

    // ── Permission helpers ──────────────────────────────────────────────────

    private fun isAllPermissionsGranted(): Boolean {
        return isNotificationListenerEnabled() &&
               isDndGranted() &&
               isBatteryOptimizationIgnored()
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        val component = ComponentName(this, AdMuteService::class.java).flattenToString()
        return flat.contains(component)
    }

    private fun isDndGranted(): Boolean {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.isNotificationPolicyAccessGranted
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun isAppNotificationEnabled(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.areNotificationsEnabled()
    }

    // ── Crash report ────────────────────────────────────────────────────────

    private fun checkForCrashReport() {
        val legacyPrefs = getSharedPreferences("AdMutePrefs", Context.MODE_PRIVATE)
        val crashReport = legacyPrefs.getString("crash_report", null) ?: return

        AlertDialog.Builder(this)
            .setTitle("App Error Detected")
            .setMessage(crashReport)
            .setPositiveButton("Copy & Dismiss") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Crash Report", crashReport))
                Toast.makeText(this, "Report copied", Toast.LENGTH_LONG).show()
                legacyPrefs.edit().remove("crash_report").apply()
            }
            .setNegativeButton("Dismiss") { _, _ ->
                legacyPrefs.edit().remove("crash_report").apply()
            }
            .setCancelable(false)
            .show()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Composable UI
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Root dashboard composable.
 * Contains the app header, a swipeable [HorizontalPager] with one page per
 * target app, and a pager indicator row.
 */
@Composable
fun AdMuteDashboard(onSettingsClick: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { TARGET_APPS.size })

    // ── Live stats from SharedPreferences ────────────────────────────────
    val context = LocalContext.current
    val stats by StatsManager.statsFlow(context).collectAsStateWithLifecycle(
        initialValue = emptyMap()
    )

    // Animate the header accent color to match the current page's brand.
    val currentAccent by animateColorAsState(
        targetValue = TARGET_APPS[pagerState.currentPage].accent,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "headerAccent",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgPrimary)
            .padding(top = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ── Top Bar ───────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(onClick = onSettingsClick) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = TextSecondary
                )
            }
        }

        // ── Header ────────────────────────────────────────────────────────
        Text(
            text = "AdMute",
            style = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.Black,
                letterSpacing = (-1).sp,
            ),
            color = currentAccent,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Swipe to switch apps",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
        )

        Spacer(modifier = Modifier.height(28.dp))

        // ── Pager indicator dots ────────────────────────────────────────
        PagerIndicator(
            pageCount = TARGET_APPS.size,
            currentPage = pagerState.currentPage,
            currentPageOffset = pagerState.currentPageOffsetFraction,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // ── Swipeable pages ─────────────────────────────────────────────
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            pageSpacing = 16.dp,
            beyondViewportPageCount = 1,
        ) { pageIndex ->
            Box(
                modifier = Modifier.padding(horizontal = 24.dp),
            ) {
                AppPage(app = TARGET_APPS[pageIndex], stats = stats)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Single app page
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A single page inside the pager showing the app name, mute/skip counters,
 * and a service toggle. Uses [app]'s accent color for branding.
 */
@Composable
fun AppPage(app: TargetApp, stats: Map<String, Int>) {
    val context = LocalContext.current
    val prefsHelper = remember { PrefsHelper(context) }

    // ── Live counters from StatsManager ──────────────────────────────────
    val adsMuted = stats["${app.statsKey}_muted_count"] ?: 0
    val adsSkipped = stats["${app.statsKey}_skipped_count"] ?: 0
    val timeSaved = stats["${app.statsKey}_time_saved"] ?: 0

    // Initialize state from SharedPreferences
    var serviceEnabled by remember { mutableStateOf(prefsHelper.isAppEnabled(app.statsKey)) }

    Card(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── App icon + name ─────────────────────────────────────────
            Spacer(modifier = Modifier.height(8.dp))

            // Circular badge with the app's emoji/icon
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                app.accent.copy(alpha = 0.3f),
                                app.accent.copy(alpha = 0.05f),
                            )
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = app.emoji,
                    fontSize = 28.sp,
                    color = app.accent,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = app.appName,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                ),
                color = TextPrimary,
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )

            Spacer(modifier = Modifier.height(32.dp))

            // ── Counter cards ───────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CounterCard(
                    label = "Ads Muted",
                    count = adsMuted,
                    accent = app.accent,
                    modifier = Modifier.weight(1f),
                )
                
                if (app.appName != "Spotify") {
                    CounterCard(
                        label = "Ads Skipped",
                        count = adsSkipped,
                        accent = app.accent,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "Total Time Saved: ${timeSaved}s",
                style = MaterialTheme.typography.titleMedium,
                color = TextSecondary,
            )

            Spacer(modifier = Modifier.weight(1f))

            // ── Service toggle ──────────────────────────────────────────
            ServiceToggle(
                enabled = serviceEnabled,
                accent = app.accent,
                onToggle = { isEnabled -> 
                    serviceEnabled = isEnabled
                    prefsHelper.setAppEnabled(app.statsKey, isEnabled)
                },
            )

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Counter card
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A compact metric card that displays a single counter (e.g. "Ads Muted: 12").
 * The count animates smoothly when the value changes.
 */
@Composable
fun CounterCard(
    label: String,
    count: Int,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.height(120.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BgCardBorder),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.displaySmall.copy(
                    fontWeight = FontWeight.Black,
                ),
                color = accent,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Service toggle
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A labeled switch to enable/disable the ad-muter for a specific app.
 * Uses the app's brand accent as the checked-track color.
 */
@Composable
fun ServiceToggle(
    enabled: Boolean,
    accent: Color,
    onToggle: (Boolean) -> Unit,
    title: String = "Service Enabled",
    description: String = if (enabled) "Actively monitoring" else "Monitoring paused"
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = BgCardBorder),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = TextPrimary,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) accent.copy(alpha = 0.8f) else TextMuted,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = accent,
                    uncheckedThumbColor = TextMuted,
                    uncheckedTrackColor = BgCard,
                    uncheckedBorderColor = TextMuted,
                ),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Pager indicator
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A row of animated dots indicating the current page.
 * The active dot stretches wider and takes the current page's accent color.
 */
@Composable
fun PagerIndicator(
    pageCount: Int,
    currentPage: Int,
    currentPageOffset: Float,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { index ->
            val isSelected = index == currentPage

            val dotWidth by animateFloatAsState(
                targetValue = if (isSelected) 24f else 8f,
                animationSpec = tween(durationMillis = 300),
                label = "dotWidth",
            )
            val dotColor by animateColorAsState(
                targetValue = if (isSelected) TARGET_APPS[index].accent else TextMuted.copy(alpha = 0.4f),
                animationSpec = tween(durationMillis = 300),
                label = "dotColor",
            )

            Box(
                modifier = Modifier
                    .width(dotWidth.dp)
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Settings Screen
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefsHelper = remember { PrefsHelper(context) }

    var masterEnabled by remember { mutableStateOf(prefsHelper.isMasterAppEnabled()) }
    var bootEnabled by remember { mutableStateOf(prefsHelper.isBootReceiverEnabled()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BgPrimary,
                    titleContentColor = TextPrimary,
                    navigationIconContentColor = TextPrimary
                )
            )
        },
        containerColor = BgPrimary
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Master App Enable Toggle
            ServiceToggle(
                enabled = masterEnabled,
                accent = SpotifyGreen,
                onToggle = { 
                    masterEnabled = it
                    prefsHelper.setMasterAppEnabled(it)
                },
                title = "Master App Enable",
                description = if (masterEnabled) "All services active" else "App globally paused"
            )

            // Start on Boot Toggle
            ServiceToggle(
                enabled = bootEnabled,
                accent = SpotifyGreen,
                onToggle = { 
                    bootEnabled = it
                    prefsHelper.setBootReceiverEnabled(it)
                },
                title = "Start on Device Restart",
                description = "Automatically launch service when phone boots"
            )
        }
    }
}