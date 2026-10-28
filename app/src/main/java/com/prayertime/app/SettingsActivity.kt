package com.prayertime.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var masterSwitch: Switch
    private lateinit var soundGroup: RadioGroup
    private lateinit var prayerContainer: LinearLayout
    private lateinit var latitudeInput: EditText
    private lateinit var longitudeInput: EditText
    private lateinit var methodSpinner: Spinner
    private lateinit var fajrAdjustmentSpinner: Spinner
    private lateinit var zoneLine: TextView
    private lateinit var exactStatus: TextView
    private lateinit var exactButton: Button
    private lateinit var notificationStatus: TextView
    private lateinit var notificationButton: Button
    private lateinit var batteryStatus: TextView

    private val prayerSwitches = LinkedHashMap<Prayer, Switch>()
    private val handler = Handler(Looper.getMainLooper())
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null

    private val locationTimeout = Runnable {
        if (locationListener != null) {
            stopLocationUpdates()
            Toast.makeText(this, getString(R.string.gps_no_fix), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_settings)

        masterSwitch = findViewById(R.id.masterSwitch)
        soundGroup = findViewById(R.id.soundGroup)
        prayerContainer = findViewById(R.id.prayerSwitches)
        latitudeInput = findViewById(R.id.latitudeInput)
        longitudeInput = findViewById(R.id.longitudeInput)
        methodSpinner = findViewById(R.id.methodSpinner)
        fajrAdjustmentSpinner = findViewById(R.id.fajrAdjustmentSpinner)
        zoneLine = findViewById(R.id.zoneLine)
        exactStatus = findViewById(R.id.exactStatus)
        exactButton = findViewById(R.id.exactButton)
        notificationStatus = findViewById(R.id.notificationStatus)
        notificationButton = findViewById(R.id.notificationButton)
        batteryStatus = findViewById(R.id.batteryStatus)

        loadValues()
        buildPrayerSwitches()

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.gpsButton).setOnClickListener { useGps() }
        findViewById<View>(R.id.previewButton).setOnClickListener {
            AdhanService.play(this, getString(R.string.preview), selectedSound())
        }
        findViewById<View>(R.id.stopButton).setOnClickListener { stopPreview() }
        findViewById<View>(R.id.saveButton).setOnClickListener { save() }
        exactButton.setOnClickListener { openExactAlarmSettings() }
        notificationButton.setOnClickListener { requestNotificationPermission() }
        findViewById<View>(R.id.batteryButton).setOnClickListener { openBatterySettings() }
        hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        updatePermissionStatus()
        updateZoneLine()
        AdhanScheduler.scheduleAll(this)
    }

    override fun onPause() {
        stopLocationUpdates()
        super.onPause()
    }

    private fun loadValues() {
        masterSwitch.isChecked = prefs.adhanEnabled

        when (prefs.sound) {
            AdhanSound.FULL -> soundGroup.check(R.id.soundFull)
            AdhanSound.MID -> soundGroup.check(R.id.soundMid)
            AdhanSound.SHORT -> soundGroup.check(R.id.soundShort)
        }

        if (prefs.locationSet) {
            latitudeInput.setText(String.format(Locale.US, "%.5f", prefs.latitude))
            longitudeInput.setText(String.format(Locale.US, "%.5f", prefs.longitude))
        }

        val methods = CalcMethod.entries
        methodSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            methods.map { it.label }
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        methodSpinner.setSelection(methods.indexOf(prefs.method).coerceAtLeast(0))

        val adjustments = (0..30 step 5).toList()
        fajrAdjustmentSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            adjustments.map { getString(R.string.minute_option, it) }
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        fajrAdjustmentSpinner.setSelection(adjustments.indexOf(prefs.fajrAdjustmentMinutes).coerceAtLeast(0))
        updateZoneLine()
    }

    private fun buildPrayerSwitches() {
        for (prayer in Prayer.entries) {
            val switch = Switch(this).apply {
                text = prayer.label
                textSize = 15f
                isChecked = prefs.isPrayerEnabled(prayer)
                setPadding(0, 6, 0, 6)
            }
            prayerSwitches[prayer] = switch
            prayerContainer.addView(switch)
        }
    }

    private fun save() {
        val latitude = parseCoordinate(latitudeInput.text.toString(), -90.0, 90.0)
        if (latitude == null) {
            latitudeInput.error = getString(R.string.latitude_error)
            latitudeInput.requestFocus()
            return
        }
        val longitude = parseCoordinate(longitudeInput.text.toString(), -180.0, 180.0)
        if (longitude == null) {
            longitudeInput.error = getString(R.string.longitude_error)
            longitudeInput.requestFocus()
            return
        }

        prefs.latitude = latitude
        prefs.longitude = longitude
        prefs.locationSet = true
        prefs.adhanEnabled = masterSwitch.isChecked
        prefs.sound = selectedSound()
        prefs.method = selectedMethod()
        prefs.fajrAdjustmentMinutes = (0..30 step 5).elementAtOrElse(
            fajrAdjustmentSpinner.selectedItemPosition
        ) { 20 }
        for ((prayer, switch) in prayerSwitches) {
            prefs.setPrayerEnabled(prayer, switch.isChecked)
        }

        AdhanScheduler.scheduleAll(this)
        Toast.makeText(this, getString(R.string.saved), Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun selectedSound(): AdhanSound = when (soundGroup.checkedRadioButtonId) {
        R.id.soundMid -> AdhanSound.MID
        R.id.soundShort -> AdhanSound.SHORT
        else -> AdhanSound.FULL
    }

    private fun selectedMethod(): CalcMethod {
        val methods = CalcMethod.entries
        val index = methodSpinner.selectedItemPosition
        return if (index in methods.indices) methods[index] else CalcMethod.MWL
    }

    private fun parseCoordinate(text: String, min: Double, max: Double): Double? =
        text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in min..max }

    private fun stopPreview() {
        startService(
            Intent(this, AdhanService::class.java).setAction(AdhanService.ACTION_STOP)
        )
    }

    private fun useGps() {
        val hasCoarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val hasFine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasCoarse && !hasFine) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ),
                REQUEST_LOCATION
            )
            return
        }

        val manager = getSystemService(LocationManager::class.java)
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        ).filter { provider ->
            runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
        }
        val lastKnown = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ).mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.filter { location ->
            val age = System.currentTimeMillis() - location.time
            age in 0..(2 * 60 * 60 * 1000L)
        }.maxByOrNull { it.time }

        if (lastKnown != null) {
            fillLocation(lastKnown)
            Toast.makeText(this, getString(R.string.gps_last_known), Toast.LENGTH_LONG).show()
            return
        }

        if (providers.isEmpty()) {
            Toast.makeText(this, getString(R.string.gps_off), Toast.LENGTH_LONG).show()
            return
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                fillLocation(location)
                stopLocationUpdates()
                Toast.makeText(this@SettingsActivity, getString(R.string.gps_found), Toast.LENGTH_LONG).show()
            }

            override fun onProviderEnabled(provider: String) {}

            override fun onProviderDisabled(provider: String) {}

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }

        var requestedProvider = false
        try {
            for (provider in providers) {
                try {
                    manager.requestLocationUpdates(
                        provider,
                        1000L,
                        0f,
                        listener,
                        Looper.getMainLooper()
                    )
                    requestedProvider = true
                } catch (_: SecurityException) {
                    // Coarse-only permission can reject GPS but still permit the network provider.
                }
            }
            if (!requestedProvider) {
                Toast.makeText(this, getString(R.string.location_permission_needed), Toast.LENGTH_LONG).show()
                return
            }
            locationManager = manager
            locationListener = listener
            Toast.makeText(this, getString(R.string.gps_waiting), Toast.LENGTH_SHORT).show()
            handler.postDelayed(locationTimeout, 120_000L)
        } catch (e: SecurityException) {
            stopLocationUpdates()
            Toast.makeText(this, getString(R.string.location_permission_needed), Toast.LENGTH_LONG).show()
        }
    }

    private fun fillLocation(location: Location) {
        latitudeInput.setText(String.format(Locale.US, "%.5f", location.latitude))
        longitudeInput.setText(String.format(Locale.US, "%.5f", location.longitude))
        latitudeInput.error = null
        longitudeInput.error = null
    }

    private fun stopLocationUpdates() {
        val manager = locationManager
        val listener = locationListener
        if (manager != null && listener != null) {
            try {
                manager.removeUpdates(listener)
            } catch (_: SecurityException) {
                // Permission was revoked while waiting; nothing else to do.
            }
        }
        locationManager = null
        locationListener = null
        handler.removeCallbacks(locationTimeout)
    }

    private fun updateZoneLine() {
        val offset = ZoneId.systemDefault().rules.getOffset(Instant.now())
        val utcOffset = if (offset.totalSeconds == 0) "+00:00" else offset.id
        zoneLine.text = getString(R.string.timezone_device, utcOffset)
    }

    private fun updatePermissionStatus() {
        val exact = AdhanScheduler.exactAlarmsAllowed(this)
        exactStatus.text = if (exact) {
            getString(R.string.exact_allowed)
        } else {
            getString(R.string.exact_not_allowed)
        }
        exactStatus.setTextColor(getColor(if (exact) R.color.text_secondary else R.color.warning))
        exactButton.visibility =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !exact) View.VISIBLE else View.GONE

        val notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        notificationStatus.text = if (notifications) {
            getString(R.string.notifications_allowed)
        } else {
            getString(R.string.notifications_off)
        }
        notificationStatus.setTextColor(
            getColor(if (notifications) R.color.text_secondary else R.color.warning)
        )
        notificationButton.visibility = if (notifications) View.GONE else View.VISIBLE

        val batteryAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        batteryStatus.text = getString(
            if (batteryAllowed) R.string.battery_allowed else R.string.battery_restricted
        )
        batteryStatus.setTextColor(getColor(if (batteryAllowed) R.color.text_secondary else R.color.warning))
        findViewById<View>(R.id.batteryButton).visibility = if (batteryAllowed) View.GONE else View.VISIBLE
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Exception) {
            openAppSettings()
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun openBatterySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
                return
            } catch (_: Exception) {
                // Fall through to the system battery settings list.
            }
        }
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: Exception) {
            openAppSettings()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATIONS
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                useGps()
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.location_permission_denied),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        if (requestCode == REQUEST_NOTIFICATIONS) {
            updatePermissionStatus()
        }
    }

    private companion object {
        const val REQUEST_LOCATION = 200
        const val REQUEST_NOTIFICATIONS = 201
    }
}
