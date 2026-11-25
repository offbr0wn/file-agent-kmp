package dev.fileagent.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.fileagent.app.ui.AgentScreen
import dev.fileagent.app.ui.SuiTheme

class MainActivity : ComponentActivity() {
    private val vm: AgentViewModel by viewModels()
    private var hasAccess by mutableStateOf(false)
    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // If the app ever crashes, keep the reason so it can be shown (and sent) next time.
        val crashFile = java.io.File(filesDir, "last-crash.txt")
        val before = Thread.getDefaultUncaughtExceptionHandler()
        if (before !is CrashSaver) Thread.setDefaultUncaughtExceptionHandler(CrashSaver(crashFile, before))
        enableEdgeToEdge()
        // Lets the background run show "Planning…" and "Plan ready" notifications.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            val ui by vm.state.collectAsStateWithLifecycle()
            SuiTheme(ui.theme) {
                AgentScreen(vm = vm, hasAccess = hasAccess, onGrantAccess = ::requestAllFilesAccess)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasAccess = Environment.isExternalStorageManager()
    }

    private fun requestAllFilesAccess() {
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        runCatching { startActivity(intent) }
            .onFailure { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
    }
}

/** Writes the crash reason to a file, then lets Android close the app as usual. */
private class CrashSaver(
    private val file: java.io.File,
    private val next: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(t: Thread, e: Throwable) {
        runCatching { file.writeText(android.util.Log.getStackTraceString(e).take(6000)) }
        next?.uncaughtException(t, e)
    }
}
