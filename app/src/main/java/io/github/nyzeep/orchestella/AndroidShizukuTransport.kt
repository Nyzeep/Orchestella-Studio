package io.github.nyzeep.orchestella

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import io.github.nyzeep.orchestella.runtime.capability.ShizukuClientController
import rikka.shizuku.Shizuku

/** Ordinary client SDK adapter. No shell, Android grant, UserService, or VM operations. */
class AndroidShizukuTransport(context: Context) : ShizukuClientController.Transport {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    override fun observe(events: ShizukuClientController.Events): ShizukuClientController.Registration {
        var active = true
        val received = Shizuku.OnBinderReceivedListener { if (active) events.binderReceived() }
        val dead = Shizuku.OnBinderDeadListener { if (active) events.binderDied() }
        val permission = Shizuku.OnRequestPermissionResultListener { code, result ->
            // Forward the original SDK code. Never label a historical result with the current epoch.
            if (active) events.permissionResult(code, result == PackageManager.PERMISSION_GRANTED)
        }
        val close = ShizukuClientController.Registration {
            active = false
            Shizuku.removeBinderReceivedListener(received)
            Shizuku.removeBinderDeadListener(dead)
            Shizuku.removeRequestPermissionResultListener(permission)
        }
        try {
            Shizuku.addBinderDeadListener(dead, main)
            Shizuku.addRequestPermissionResultListener(permission, main)
            Shizuku.addBinderReceivedListenerSticky(received, main)
        } catch (failure: RuntimeException) {
            close.close()
            throw failure
        }
        return close
    }

    override fun read(): ShizukuClientController.Facts {
        val installation = try {
            context.packageManager.getApplicationInfo(MANAGER_PACKAGE, 0)
            ShizukuClientController.Installation.INSTALLED
        } catch (_: PackageManager.NameNotFoundException) {
            ShizukuClientController.Installation.MISSING
        } catch (_: RuntimeException) {
            ShizukuClientController.Installation.UNKNOWN
        }
        if (installation != ShizukuClientController.Installation.INSTALLED || !Shizuku.pingBinder()) {
            return ShizukuClientController.Facts(installation, false, -1, -1, false, false)
        }
        val version = Shizuku.getVersion()
        val uid = Shizuku.getUid()
        if (Shizuku.isPreV11() || version < 11) {
            return ShizukuClientController.Facts(installation, true, uid, version, false, false)
        }
        check(uid == 0 || uid == 2000) { "Shizuku identity unavailable" }
        val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        val remembered = !granted && Shizuku.shouldShowRequestPermissionRationale()
        check(Shizuku.pingBinder()) { "Shizuku disconnected while reading" }
        return ShizukuClientController.Facts(installation, true, uid, version, granted, remembered)
    }

    override fun requestPermission(requestCode: Int) {
        check(Shizuku.pingBinder()) { "Shizuku is not connected" }
        Shizuku.requestPermission(requestCode)
    }

    companion object {
        const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"
        const val DOWNLOAD_URL = "https://shizuku.rikka.app/download/"
        const val SETUP_URL = "https://shizuku.rikka.app/guide/setup/"
    }
}
