package com.yossefjoo.aman

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Process
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity: FlutterActivity() {
    private val CHANNEL = "com.yossefjoo.aman/device_control"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            try {
                when (call.method) {
                    "getDeviceTelemetry" -> {
                        result.success(getDeviceTelemetry())
                    }
                    "getInstalledApps" -> {
                        result.success(getInstalledApps())
                    }
                    "getCurrentLocation" -> {
                        result.success(getCurrentLocation())
                    }
                    "triggerAlarmSiren" -> {
                        triggerAlarmSiren()
                        result.success(true)
                    }
                    "openPermissionSettings" -> {
                        val type = call.argument<String>("type") ?: "usage"
                        openPermissionSettings(type)
                        result.success(true)
                    }
                    else -> result.notImplemented()
                }
            } catch (e: Exception) {
                result.error("DEVICE_CONTROL_ERROR", e.message, null)
            }
        }
    }

    private fun getDeviceTelemetry(): Map<String, Any> {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: 85
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: 100
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else 85
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val tempTenths = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 310) ?: 310
        val tempCelsius = tempTenths / 10.0

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val gpsEnabled = try {
            locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
            locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
        } catch (_: Exception) {
            true
        }

        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }

        return mapOf(
            "batteryLevel" to batteryPct,
            "isCharging" to isCharging,
            "batteryTemp" to tempCelsius,
            "deviceModel" to "${Build.MANUFACTURER.uppercase()} ${Build.MODEL}",
            "androidVersion" to "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            "localIp" to getLocalIpAddress(),
            "hasUsagePermission" to hasUsageStatsPermission(),
            "hasOverlayPermission" to hasOverlay,
            "isLocationEnabled" to gpsEnabled
        )
    }

    private fun hasUsageStatsPermission(): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "192.168.1.100"
                    }
                }
            }
        } catch (_: Exception) {}
        return "192.168.1.100"
    }

    private fun getInstalledApps(): List<Map<String, Any>> {
        val resultList = mutableListOf<Map<String, Any>>()
        try {
            val pm = context.packageManager
            val usageMap = mutableMapOf<String, Long>()
            if (hasUsageStatsPermission()) {
                val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                val endTime = System.currentTimeMillis()
                val startTime = endTime - 1000L * 60L * 60L * 24L
                val stats = usm?.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
                stats?.forEach { stat ->
                    usageMap[stat.packageName] = (usageMap[stat.packageName] ?: 0L) + (stat.totalTimeInForeground / 60000L)
                }
            }

            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in apps) {
                val launchIntent = pm.getLaunchIntentForPackage(app.packageName)
                if (launchIntent != null && app.packageName != context.packageName) {
                    val label = pm.getApplicationLabel(app).toString()
                    val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val usedMinutes = (usageMap[app.packageName] ?: 0L).toInt()
                    resultList.add(
                        mapOf(
                            "packageName" to app.packageName,
                            "appName" to label,
                            "isSystemApp" to isSystem,
                            "usedMinutesToday" to usedMinutes
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return resultList
    }

    @Suppress("MissingPermission")
    private fun getCurrentLocation(): Map<String, Any>? {
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
            val providers = lm.getProviders(true)
            var bestLocation: Location? = null
            for (provider in providers) {
                val l = lm.getLastKnownLocation(provider) ?: continue
                if (bestLocation == null || l.accuracy < bestLocation.accuracy) {
                    bestLocation = l
                }
            }
            if (bestLocation != null) {
                val isMock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    bestLocation.isMock
                } else {
                    @Suppress("DEPRECATION")
                    bestLocation.isFromMockProvider
                }
                return mapOf(
                    "latitude" to bestLocation.latitude,
                    "longitude" to bestLocation.longitude,
                    "accuracy" to bestLocation.accuracy.toDouble(),
                    "altitude" to bestLocation.altitude,
                    "speedKmh" to (bestLocation.speed * 3.6),
                    "heading" to bestLocation.bearing.toDouble(),
                    "provider" to (bestLocation.provider ?: "GPS"),
                    "isMock" to isMock
                )
            }
        } catch (_: Exception) {}
        return null
    }

    private fun triggerAlarmSiren() {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            toneGen.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 3500)
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 200, 600, 200, 800), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(2500)
            }
        } catch (_: Exception) {}
    }

    private fun openPermissionSettings(type: String) {
        try {
            val intent = when (type) {
                "usage" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                "overlay" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                } else {
                    Intent(Settings.ACTION_SETTINGS)
                }
                "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Exception) {}
    }
}
