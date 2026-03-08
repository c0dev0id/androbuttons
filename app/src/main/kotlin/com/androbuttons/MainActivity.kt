package com.androbuttons

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    // Android 15 (API 35) only: startForegroundService() throws ForegroundServiceStartNotAllowed
    // when called from an ActivityResultLauncher callback, because the callback fires before
    // onResume() and the activity is only STARTED (not RESUMED) at that point.
    // On Android 14 and below we keep the original direct-call behaviour so that the overlay
    // slide-in animation is not affected by any additional delay.
    private var pendingServiceStart = false
    private var resumed = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        checkAndRequestPermissions()
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        checkAndRequestPermissions()
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        checkAndRequestPermissions()
    }

    private val contactsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        checkAndRequestPermissions()
    }

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        checkAndRequestPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (pendingServiceStart) {
            pendingServiceStart = false
            startOverlayService()
            finish()
        }
    }

    private fun checkAndRequestPermissions() {
        // Stage 1: POST_NOTIFICATIONS (API 33+ only)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        // Stage 2: SYSTEM_ALERT_WINDOW overlay permission
        if (!Settings.canDrawOverlays(this)) {
            overlayPermissionLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }

        // Stage 3: Location (needed for GPS speedometer and compass bearing)
        val fineLocation = Manifest.permission.ACCESS_FINE_LOCATION
        val coarseLocation = Manifest.permission.ACCESS_COARSE_LOCATION
        if (ContextCompat.checkSelfPermission(this, fineLocation) != PackageManager.PERMISSION_GRANTED) {
            locationPermissionLauncher.launch(arrayOf(fineLocation, coarseLocation))
            return
        }

        // Stage 4: Contacts + Call Phone + Send SMS (needed for Phone and Message panes)
        val readContacts = Manifest.permission.READ_CONTACTS
        val callPhone = Manifest.permission.CALL_PHONE
        val sendSms = Manifest.permission.SEND_SMS
        if (ContextCompat.checkSelfPermission(this, readContacts) != PackageManager.PERMISSION_GRANTED
            || ContextCompat.checkSelfPermission(this, callPhone) != PackageManager.PERMISSION_GRANTED
            || ContextCompat.checkSelfPermission(this, sendSms) != PackageManager.PERMISSION_GRANTED) {
            contactsPermissionLauncher.launch(arrayOf(readContacts, callPhone, sendSms))
            return
        }

        // Stage 5: Nearby Devices — BLUETOOTH_CONNECT + BLUETOOTH_SCAN (API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val btConnect = Manifest.permission.BLUETOOTH_CONNECT
            val btScan = Manifest.permission.BLUETOOTH_SCAN
            if (ContextCompat.checkSelfPermission(this, btConnect) != PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, btScan) != PackageManager.PERMISSION_GRANTED) {
                bluetoothPermissionLauncher.launch(arrayOf(btConnect, btScan))
                return
            }
        }

        // All permissions granted — start the overlay service and exit.
        // Android 15+ (API 35): defer to onResume() to satisfy foreground state requirement.
        // Android 14 and below: call directly, as in v0.0.62, preserving animation timing.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            pendingServiceStart = true
            if (resumed) {
                pendingServiceStart = false
                startOverlayService()
                finish()
            }
        } else {
            startOverlayService()
            finish()
        }
    }

    private fun startOverlayService() {
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
