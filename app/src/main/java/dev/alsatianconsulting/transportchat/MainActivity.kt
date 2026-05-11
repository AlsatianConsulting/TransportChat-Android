package dev.alsatianconsulting.transportchat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.alsatianconsulting.transportchat.ui.AppController
import dev.alsatianconsulting.transportchat.ui.TransportChatApp
import dev.alsatianconsulting.transportchat.ui.theme.TransportChatTheme

private data class RationaleInfo(val title: String, val message: String, val onConfirm: () -> Unit)

class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val controller: AppController = viewModel()
            val state by controller.uiState.collectAsStateWithLifecycle()

            var rationaleInfo by remember { mutableStateOf<RationaleInfo?>(null) }
            val prefs = remember { getSharedPreferences("perm_rationale", MODE_PRIVATE) }

            fun checkRationale(key: String, title: String, message: String, request: () -> Unit) {
                if (prefs.getBoolean(key, false)) {
                    request()
                } else {
                    rationaleInfo = RationaleInfo(title, message) {
                        prefs.edit().putBoolean(key, true).apply()
                        rationaleInfo = null
                        request()
                    }
                }
            }

            var onboardingPermissionCallback by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
            val onboardingPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { granted ->
                onboardingPermissionCallback?.invoke(granted)
                onboardingPermissionCallback = null
            }

            val biometricLauncher = remember {
                { onResult: (Boolean) -> Unit ->
                    launchBiometricPrompt { authenticated ->
                        if (!authenticated) {
                            onResult(false)
                            return@launchBiometricPrompt
                        }
                        onResult(controller.unlockWithBiometricStore())
                    }
                }
            }

            val pickFileLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenMultipleDocuments()
            ) { uris ->
                if (uris.isNotEmpty()) {
                    controller.sendFileUris(uris)
                }
            }

            val takePhotoLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                contract = ActivityResultContracts.TakePicturePreview()
            ) { bitmap ->
                if (bitmap != null) controller.sendPhotoBitmap(bitmap)
            }

            val cameraPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) {
                    takePhotoLauncher.launch(null)
                }
            }

            val qrScanLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                contract = ScanContract()
            ) { result ->
                val payload = result.contents
                if (payload.isNullOrBlank()) {
                    controller.updateStatus("QR scan canceled")
                } else {
                    controller.verifySelectedContactFromQrPayload(payload)
                }
            }

            val qrCameraPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) {
                    qrScanLauncher.launch(
                        ScanOptions().apply {
                            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            setPrompt("Scan verification QR")
                            setBeepEnabled(false)
                            setOrientationLocked(false)
                        }
                    )
                } else {
                    controller.updateStatus("Camera permission required for QR scan")
                }
            }

            val locationPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) controller.sendOneTimeLocation()
            }

            val audioPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) {
                    controller.startVoiceCall()
                } else {
                    controller.updateStatus("Microphone permission required for voice calls")
                }
            }

            val incomingAudioPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) {
                    controller.acceptIncomingCall()
                } else {
                    controller.updateStatus("Microphone permission required to answer voice calls")
                }
            }

            val videoCallPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { grants ->
                val cameraGranted = grants[Manifest.permission.CAMERA] == true
                val audioGranted = grants[Manifest.permission.RECORD_AUDIO] == true
                if (cameraGranted && audioGranted) {
                    controller.startVideoCall()
                } else {
                    controller.updateStatus("Camera and microphone permissions required for video calls")
                }
            }

            val incomingVideoPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { grants ->
                val cameraGranted = grants[Manifest.permission.CAMERA] == true
                val audioGranted = grants[Manifest.permission.RECORD_AUDIO] == true
                if (cameraGranted && audioGranted) {
                    controller.acceptIncomingCall()
                } else {
                    controller.updateStatus("Camera and microphone permissions required to answer video calls")
                }
            }

            TransportChatTheme(darkTheme = isSystemInDarkTheme()) {
                rationaleInfo?.let { info ->
                    AlertDialog(
                        onDismissRequest = { rationaleInfo = null },
                        title = { Text(info.title) },
                        text = { Text(info.message) },
                        confirmButton = {
                            TextButton(onClick = info.onConfirm) { Text("Continue") }
                        },
                        dismissButton = {
                            TextButton(onClick = { rationaleInfo = null }) { Text("Not now") }
                        }
                    )
                }

                TransportChatApp(
                    state = state,
                    controller = controller,
                    onBiometricUnlock = biometricLauncher,
                    onPickFile = { pickFileLauncher.launch(arrayOf("*/*")) },
                    onCapturePhoto = {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            takePhotoLauncher.launch(null)
                        } else {
                            checkRationale(
                                "camera_photo",
                                "Camera Access",
                                "TransportChat needs camera permission to let you take photos to share in chats. Photos are end-to-end encrypted before leaving your device."
                            ) { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }
                        }
                    },
                    onSendLocation = {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                            controller.sendOneTimeLocation()
                        } else {
                            checkRationale(
                                "location",
                                "Location Access",
                                "TransportChat needs location permission to share your current position with a contact. Your location is sent directly over the local network — no servers or cloud services involved."
                            ) { locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
                        }
                    },
                    onStartVoiceCall = {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            controller.startVoiceCall()
                        } else {
                            checkRationale(
                                "microphone",
                                "Microphone Access",
                                "TransportChat needs microphone permission to make voice calls. All audio is encrypted end-to-end and travels only over your local network."
                            ) { audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                        }
                    },
                    onScanVerificationQr = {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            qrScanLauncher.launch(
                                ScanOptions().apply {
                                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                    setPrompt("Scan verification QR")
                                    setBeepEnabled(false)
                                    setOrientationLocked(false)
                                }
                            )
                        } else {
                            checkRationale(
                                "camera_qr",
                                "Camera Access",
                                "TransportChat needs camera permission to scan your contact's verification QR code and confirm their identity."
                            ) { qrCameraPermissionLauncher.launch(Manifest.permission.CAMERA) }
                        }
                    },
                    onStartVideoCall = {
                        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                        val audioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        if (cameraGranted && audioGranted) {
                            controller.startVideoCall()
                        } else {
                            checkRationale(
                                "video_call",
                                "Camera & Microphone Access",
                                "TransportChat needs camera and microphone permission to make video calls. All video and audio is encrypted end-to-end and stays on your local network."
                            ) {
                                videoCallPermissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.CAMERA,
                                        Manifest.permission.RECORD_AUDIO
                                    )
                                )
                            }
                        }
                    },
                    onAcceptIncomingCall = {
                        val activeCall = state.activeCall
                        if (activeCall == null) {
                            controller.updateStatus("No incoming call to answer")
                        } else if (activeCall.audioOnly) {
                            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                controller.acceptIncomingCall()
                            } else {
                                checkRationale(
                                    "microphone",
                                    "Microphone Access",
                                    "TransportChat needs microphone permission to answer voice calls. All audio is encrypted end-to-end and travels only over your local network."
                                ) { incomingAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                            }
                        } else {
                            val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                            val audioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                            if (cameraGranted && audioGranted) {
                                controller.acceptIncomingCall()
                            } else {
                                checkRationale(
                                    "video_call",
                                    "Camera & Microphone Access",
                                    "TransportChat needs camera and microphone permission to answer video calls. All video and audio is encrypted end-to-end and stays on your local network."
                                ) {
                                    incomingVideoPermissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.CAMERA,
                                            Manifest.permission.RECORD_AUDIO
                                        )
                                    )
                                }
                            }
                        }
                    },
                    onDeclineIncomingCall = { controller.declineIncomingCall() },
                    onEndCall = { controller.endCall() },
                    onRequestPermission = { permission, onResult ->
                        onboardingPermissionCallback = onResult
                        onboardingPermissionLauncher.launch(permission)
                    }
                )
            }
        }
    }

    private fun launchBiometricPrompt(onResult: (Boolean) -> Unit) {
        val manager = BiometricManager.from(this)
        val authenticators =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL

        val canAuth = manager.canAuthenticate(authenticators)
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            onResult(false)
            return
        }

        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onResult(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onResult(false)
                }

                override fun onAuthenticationFailed() {
                    onResult(false)
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock TransportChat")
            .setSubtitle("Authenticate to unlock encrypted chats")
            .setAllowedAuthenticators(authenticators)
            .build()

        prompt.authenticate(promptInfo)
    }
}
