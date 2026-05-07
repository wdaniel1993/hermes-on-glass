package dev.wallner.hermesonglass.phone.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * The permission set the Rokid CXR-M flow needs at runtime.
 *
 * The phone-app's Bluetooth + Wi-Fi P2P paths can't get off the ground
 * without these, and several of them are runtime-grant on API 31+ /
 * 33+ regardless of what the manifest declares. Centralised here so the
 * activity has a single launch + check pair.
 *
 * | Permission                  | Required from |
 * |-----------------------------|---------------|
 * | BLUETOOTH_CONNECT           | API 31 (S)    |
 * | BLUETOOTH_SCAN              | API 31 (S)    |
 * | ACCESS_FINE_LOCATION        | always (BLE)  |
 * | NEARBY_WIFI_DEVICES         | API 33 (T)    |
 *
 * Pre-API-31 devices already get BLUETOOTH/BLUETOOTH_ADMIN at install.
 */
object BluetoothPermissions {

    fun required(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        }
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }.toTypedArray()

    fun allGranted(context: Context): Boolean = required().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}
