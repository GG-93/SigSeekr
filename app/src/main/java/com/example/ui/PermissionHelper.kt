package com.example.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Computes Android 13/14+ and GrapheneOS runtime permission requirements
 * matching the manifest declarations (BLUETOOTH_SCAN without neverForLocation).
 */
object PermissionHelper {

    fun requiredRuntimePermissions(): Array<String> {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            perms.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        return perms.toTypedArray()
    }

    fun missingPermissions(context: Context): List<String> {
        return requiredRuntimePermissions().filter { perm ->
            ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasCoreRfPermissions(context: Context): Boolean {
        val hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFine) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasBt = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasBt) return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNearbyWifi = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasNearbyWifi) return false
        }
        return true
    }

    fun hasActivityRecognitionPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun friendlyName(permission: String): String = when (permission) {
        Manifest.permission.ACCESS_FINE_LOCATION -> "Fine Location (RF Positioning)"
        Manifest.permission.ACCESS_COARSE_LOCATION -> "Coarse Location"
        Manifest.permission.BLUETOOTH_SCAN -> "Bluetooth Scan (No Location Strip)"
        Manifest.permission.BLUETOOTH_CONNECT -> "Bluetooth Device Names"
        Manifest.permission.NEARBY_WIFI_DEVICES -> "Nearby Wi-Fi / RTT Ranging"
        Manifest.permission.ACTIVITY_RECOGNITION -> "Step Detector (IMU Dead-Reckoning)"
        else -> permission.substringAfterLast('.')
    }
}
