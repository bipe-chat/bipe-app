// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.calls

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import chat.bipe.app.BipeTheme

/** The call screen (spec call) as its own activity, so it can show over the lock screen later (bipe-chat/bipe#760). */
class CallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val call = CallCenter.active.value
        if (call == null) {
            finish()
            return
        }
        setContent {
            // Compose's launcher, like the other screens: lint refuses registerForActivityResult with the Fragment
            // version that LiveKit brings.
            val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
                val asked = CallCenter.active.value ?: return@rememberLauncherForActivityResult
                val microphone = results[Manifest.permission.RECORD_AUDIO] ?: granted(Manifest.permission.RECORD_AUDIO)
                val camera = results[Manifest.permission.CAMERA] ?: granted(Manifest.permission.CAMERA)
                asked.start(microphoneGranted = microphone, cameraGranted = camera)
            }
            LaunchedEffect(call) { if (!call.started) askPermissions(call) { permissions.launch(it) } }
            BipeTheme {
                val active by CallCenter.active.collectAsState()
                val current = active
                if (current == null) {
                    finish()
                } else {
                    CallScreen(
                        call = current,
                        media = CallCenter.media,
                        onMinimise = { finish() },
                        onClose = {
                            CallCenter.dismissFailed()
                            finish()
                        },
                        onOpenSettings = {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                        },
                    )
                }
            }
        }
    }

    /** RECORD_AUDIO, CAMERA for video, BLUETOOTH_CONNECT for headsets (Android 12 and later), at the first call. */
    private fun askPermissions(call: CallSession, launch: (Array<String>) -> Unit) {
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (call.video) add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        val missing = wanted.filterNot(::granted)
        if (missing.isEmpty()) {
            call.start(microphoneGranted = true, cameraGranted = granted(Manifest.permission.CAMERA))
        } else {
            launch(missing.toTypedArray())
        }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
