package dev.avijit.orbit.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.avijit.orbit.BuildConfig
import dev.avijit.orbit.ScreensSession
import dev.avijit.orbit.browser.BrowserHub
import dev.avijit.orbit.ui.theme.Orbit
import dev.avijit.orbit.ui.theme.OrbitTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The app library: every app on the phone, as a screen in the glasses (opened from the dock). */
object AppLibrary {
    const val COMPONENT = BuildConfig.APPLICATION_ID + "/dev.avijit.orbit.ui.AppLibraryActivity"
    const val LABEL = "All apps"

    /** Glasses screens Orbit created for the library. It's exported (the helper starts it), so it must check. */
    private val displays = java.util.Collections.synchronizedSet(HashSet<Int>())
    fun expect(displayId: Int) { displays += displayId }
    fun allowed(displayId: Int?) = displayId != null && displayId in displays

    /** Case-insensitive: names that start with [query] first, then names containing it. */
    fun filter(apps: List<AppEntry>, query: String): List<AppEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return apps
        val (starts, rest) = apps.filter { it.label.lowercase().contains(q) }.partition { it.label.lowercase().startsWith(q) }
        return starts + rest
    }
}

class AppLibraryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AppLibrary.allowed(display?.displayId)) { finish(); return }
        window.statusBarColor = android.graphics.Color.BLACK
        val session = ScreensSession.get(this)
        setContent { OrbitTheme { Library(onPick = { session.openFromLibrary(it) }, onClose = { session.closeLibrary() }) } }
    }
}

private class LibraryApp(val entry: AppEntry, val icon: Bitmap?)

@Composable
private fun Library(onPick: (String) -> Unit, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val apps by produceState<List<LibraryApp>?>(null) { value = withContext(Dispatchers.IO) { loadApps(ctx) } }
    var query by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(Orbit.Bg).padding(horizontal = 28.dp, vertical = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("All apps", color = Orbit.Text1, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(20.dp))
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Orbit.Surface2).padding(horizontal = 16.dp, vertical = 11.dp),
            ) {
                if (query.isEmpty()) Text("Type to search", color = Orbit.Text3, fontSize = 16.sp)
                BasicTextField(
                    query, { query = it }, singleLine = true,
                    textStyle = TextStyle(color = Orbit.Text1, fontSize = 16.sp), cursorBrush = SolidColor(Orbit.Accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.size(14.dp))
            Text(
                "Close", color = Orbit.Text1, fontSize = 15.sp,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Orbit.Surface2).clickable(onClick = onClose)
                    .padding(horizontal = 16.dp, vertical = 11.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        val list = apps
        if (list == null) {
            Text("Loading your apps…", color = Orbit.Text3, fontSize = 15.sp)
        } else {
            val shown = remember(list, query) {
                val byEntry = list.associateBy { it.entry }
                AppLibrary.filter(list.map { it.entry }, query).mapNotNull { byEntry[it] }
            }
            if (shown.isEmpty()) Text("No app called \"$query\"", color = Orbit.Text3, fontSize = 15.sp)
            LazyVerticalGrid(GridCells.Adaptive(112.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown, key = { it.entry.component }) { app -> AppTile(app) { onPick(app.entry.component) } }
            }
        }
    }
}

@Composable
private fun AppTile(app: LibraryApp, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val icon = app.icon
        if (icon != null) Image(icon.asImageBitmap(), null, Modifier.size(56.dp))
        else Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(Orbit.Surface2))
        Spacer(Modifier.height(8.dp))
        Text(app.entry.label, color = Orbit.Text1, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

private fun loadApps(ctx: android.content.Context): List<LibraryApp> {
    val pm = ctx.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val apps = pm.queryIntentActivities(launcher, 0)
        .filter { it.activityInfo.packageName != ctx.packageName }
        .map { ri ->
            val entry = AppEntry(ri.loadLabel(pm).toString(), "${ri.activityInfo.packageName}/${ri.activityInfo.name}", ri.activityInfo.packageName)
            LibraryApp(entry, runCatching { iconBitmap(ri.loadIcon(pm)) }.getOrNull())
        }
        .sortedBy { it.entry.label.lowercase() }
    val browser = LibraryApp(AppEntry(BrowserHub.LABEL, BrowserHub.COMPONENT, ctx.packageName), runCatching { iconBitmap(pm.getApplicationIcon(ctx.packageName)) }.getOrNull())
    return listOf(browser) + apps
}

private fun iconBitmap(d: android.graphics.drawable.Drawable): Bitmap =
    Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888).also { b ->
        val c = android.graphics.Canvas(b); d.setBounds(0, 0, 144, 144); d.draw(c)
    }
