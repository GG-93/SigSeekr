package com.example

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.example.data.ExportFormat
import com.example.ui.PermissionHelper
import com.example.ui.RadarScreen
import com.example.ui.RadarViewModel
import com.example.ui.theme.SigSeekerTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val viewModel: RadarViewModel by viewModels {
        RadarViewModel.Factory(application)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshHardwareAndPermissionState()
    }

    /**
     * Strictly on-demand READ_PHONE_STATE launcher triggered ONLY when the user
     * taps "Grant Phone State for 2G Downgrade Alert" inside the Cell Towers tab.
     * Degrades gracefully on GrapheneOS if denied.
     */
    private val phoneStatePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        viewModel.refreshCellularMonitor()
    }

    private val createJsonDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument(ExportFormat.JSON.mimeType)
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.exportSnapshots(uri, ExportFormat.JSON)
        }
    }

    private val createCsvDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument(ExportFormat.CSV.mimeType)
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.exportSnapshots(uri, ExportFormat.CSV)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )

        setContent {
            SigSeekerTheme {
                RadarScreen(
                    viewModel = viewModel,
                    onRequestPermissions = { requestAllRequiredPermissions() },
                    onRequestPhoneStatePermission = {
                        phoneStatePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                    },
                    onOpenAppSettings = { openAppSettings() },
                    onRequestExportFile = { format -> launchDocumentExporter(format) }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onActivityStarted()
    }

    override fun onStop() {
        super.onStop()
        viewModel.onActivityStopped()
    }

    private fun requestAllRequiredPermissions() {
        val missing = PermissionHelper.missingPermissions(this)
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            viewModel.refreshHardwareAndPermissionState()
        }
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        )
        startActivity(intent)
    }

    private fun launchDocumentExporter(format: ExportFormat) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "sigseeker_log_$stamp.${format.fileExtension}"
        when (format) {
            ExportFormat.JSON -> createJsonDocumentLauncher.launch(filename)
            ExportFormat.CSV -> createCsvDocumentLauncher.launch(filename)
        }
    }
}
