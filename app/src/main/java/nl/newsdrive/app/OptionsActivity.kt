package nl.newsdrive.app

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar

/** Playback options and the auto-start settings. */
class OptionsActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var speedValue: TextView
    private lateinit var deviceValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_options)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        list = findViewById(R.id.optionList)

        section(R.string.section_playback)
        switchRow(R.string.opt_skip_heard, R.string.opt_skip_heard_desc, AppSettings.skipHeard(this)) {
            AppSettings.setSkipHeard(this, it)
        }
        switchRow(R.string.opt_announce, R.string.opt_announce_desc, AppSettings.announce(this)) {
            AppSettings.setAnnounce(this, it)
        }
        speedValue = valueRow(R.string.opt_speed, R.string.opt_speed_desc, AppSettings.speedLabel(AppSettings.speed(this))) {
            chooseSpeed()
        }

        section(R.string.section_car)
        switchRow(R.string.opt_autostart, R.string.opt_autostart_desc, AppSettings.autoStart(this)) {
            AppSettings.setAutoStart(this, it)
        }
        deviceValue = valueRow(R.string.opt_bt_device, R.string.opt_bt_device_desc, deviceLabel()) {
            pickDevice()
        }
    }

    // ------------------------------------------------------------ rows

    private fun section(title: Int) {
        val v = LayoutInflater.from(this).inflate(R.layout.item_section, list, false) as TextView
        v.setText(title)
        list.addView(v)
    }

    private fun switchRow(title: Int, desc: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_option, list, false)
        row.findViewById<TextView>(R.id.optTitle).setText(title)
        row.findViewById<TextView>(R.id.optDesc).setText(desc)
        val sw = row.findViewById<MaterialSwitch>(R.id.optSwitch)
        sw.visibility = View.VISIBLE
        sw.isChecked = checked
        sw.setOnCheckedChangeListener { _, c -> onChange(c) }
        row.setOnClickListener { sw.toggle() }
        list.addView(row)
    }

    private fun valueRow(title: Int, desc: Int, value: String, onClick: () -> Unit): TextView {
        val row = LayoutInflater.from(this).inflate(R.layout.item_option, list, false)
        row.findViewById<TextView>(R.id.optTitle).setText(title)
        row.findViewById<TextView>(R.id.optDesc).setText(desc)
        val v = row.findViewById<TextView>(R.id.optValue)
        v.visibility = View.VISIBLE
        v.text = value
        row.setOnClickListener { onClick() }
        list.addView(row)
        return v
    }

    // ------------------------------------------------------------ speed

    private fun chooseSpeed() {
        val speeds = AppSettings.SPEEDS
        val labels = speeds.map { AppSettings.speedLabel(it) }.toTypedArray()
        val current = speeds.indexOfFirst { it == AppSettings.speed(this) }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.opt_speed)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                AppSettings.setSpeed(this, speeds[which])
                speedValue.text = AppSettings.speedLabel(speeds[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------ Bluetooth device

    private fun deviceLabel(): String = AppSettings.btName(this) ?: getString(R.string.none)

    private fun hasBluetoothPermission() =
        Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun pickDevice() {
        if (!hasBluetoothPermission()) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_BT)
            return
        }
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val devices: List<BluetoothDevice> = try {
            adapter?.bondedDevices?.toList().orEmpty()
        } catch (e: SecurityException) {
            emptyList()
        }
        if (devices.isEmpty()) {
            Snackbar.make(list, getString(R.string.no_devices), Snackbar.LENGTH_LONG).show()
            return
        }
        val names = mutableListOf(getString(R.string.none))
        val addresses = mutableListOf<String?>(null)
        devices.forEach {
            val name = try { it.name } catch (e: SecurityException) { null }
            names.add(name ?: it.address)
            addresses.add(it.address)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_device)
            .setItems(names.toTypedArray()) { _, which ->
                val address = addresses[which]
                AppSettings.setBtDevice(this, address, if (address == null) null else names[which])
                deviceValue.text = deviceLabel()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BT) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) pickDevice()
            else Snackbar.make(list, getString(R.string.bt_permission_denied), Snackbar.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val REQUEST_BT = 7
    }
}
