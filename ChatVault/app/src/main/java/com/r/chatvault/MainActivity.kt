package com.r.chatvault

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.r.chatvault.ui.ChatVaultTheme
import com.r.chatvault.ui.MainScreen
import com.r.chatvault.util.AppLocale
import com.r.chatvault.vm.AppViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun attachBaseContext(newBase: Context) {
        // 应用内语言：资源用包装后的 Context 加载；切语言后 recreate() 即可
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            ChatVaultTheme(state.themeMode, state.fontScale) {
                val folderLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocumentTree()
                ) { uri -> if (uri != null) vm.importPackage(uri) }

                val zipLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri -> if (uri != null) vm.importPackage(uri) }

                val mdLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenMultipleDocuments()
                ) { uris -> if (uris.isNotEmpty()) vm.importMarkdown(uris) }

                val backupLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/zip")
                ) { uri ->
                    if (uri != null) vm.backupTo(uri)
                }

                val restoreLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri != null) vm.restoreFrom(uri)
                }

                val snackbar = remember { SnackbarHostState() }
                LaunchedEffect(state.toast) {
                    val msg = state.toast
                    if (msg != null) {
                        snackbar.showSnackbar(msg)
                        vm.consumeToast()
                    }
                }

                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
                    snackbarHost = { SnackbarHost(snackbar) }
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        MainScreen(
                            vm = vm,
                            state = state,
                            onPickFolder = { folderLauncher.launch(null) },
                            onPickZip = { zipLauncher.launch(arrayOf("*/*")) },
                            onPickMarkdown = {
                                mdLauncher.launch(
                                    arrayOf("text/markdown", "text/plain", "application/octet-stream")
                                )
                            },
                            onBackup = {
                                val name = "ChatVault-backup-" +
                                    SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(Date()) + ".zip"
                                backupLauncher.launch(name)
                            },
                            onRestore = { restoreLauncher.launch(arrayOf("application/zip", "*/*")) }
                        )
                        if (state.importing) {
                            ImportOverlay(state.progressKind, state.phase, state.progress)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportOverlay(kind: Int, phase: String, progress: Float) {
    val title = stringResource(
        when (kind) {
            1 -> R.string.progress_backing_up
            2 -> R.string.progress_restoring
            else -> R.string.progress_importing
        }
    )
    Box(
        Modifier.fillMaxSize().background(Color(0x99000000)),
        contentAlignment = Alignment.Center
    ) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    phase.ifBlank { stringResource(R.string.progress_preparing) },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    stringResource(R.string.progress_keep_foreground),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
