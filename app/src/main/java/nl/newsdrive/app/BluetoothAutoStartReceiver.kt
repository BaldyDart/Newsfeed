package nl.newsdrive.app

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken

/** Starts the news when the phone connects to the Bluetooth device chosen in Options (if auto-start is on). */
class BluetoothAutoStartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        if (!AppSettings.autoStart(context)) return
        val wanted = AppSettings.btAddress(context) ?: return

        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        val address = try { device?.address } catch (e: SecurityException) { null }
        if (address != wanted) return

        val pending = goAsync()
        val app = context.applicationContext
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        future.addListener({
            try {
                val controller = future.get()
                controller.sendCustomCommand(SessionCommand(NewsConfig.ACTION_AUTOSTART, Bundle.EMPTY), Bundle.EMPTY)
                Handler(Looper.getMainLooper()).postDelayed({
                    MediaController.releaseFuture(future)
                    pending.finish()
                }, 10_000)
            } catch (e: Exception) {
                pending.finish()
            }
        }, ContextCompat.getMainExecutor(app))
    }
}
