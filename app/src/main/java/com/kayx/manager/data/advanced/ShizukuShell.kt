package com.kayx.manager.data.advanced

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import com.kayx.manager.IUserService
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shizuku backend. Uses the official Shizuku API: the user starts Shizuku (ADB / wireless debugging / root), grants KayX
 * the Shizuku permission in the system dialog, and KayX then runs shell commands through a Shizuku "user service".
 * Nothing here bypasses Android: without Shizuku running and the permission granted every call fails.
 */
object ShizukuShell {
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val REQ_CODE = 7001

    enum class State(val label: String) {
        NOT_INSTALLED("Shizuku app not installed"),
        NOT_RUNNING("Shizuku is not running"),
        OUTDATED("Shizuku is too old (update it)"),
        NEEDS_PERMISSION("Running – permission not granted"),
        DENIED("Permission denied (allow KayX inside the Shizuku app)"),
        READY("Ready")
    }

    private var appContext: Context? = null
    @Volatile private var service: IUserService? = null
    private val bindLock = Any()

    fun init(context: Context) { appContext = context.applicationContext }

    fun state(context: Context = requireNotNull(appContext)): State {
        val installed = runCatching { context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0); true }.getOrDefault(false)
        if (!Shizuku.pingBinder()) return if (installed) State.NOT_RUNNING else State.NOT_INSTALLED
        if (Shizuku.isPreV11()) return State.OUTDATED
        return when {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> State.READY
            Shizuku.shouldShowRequestPermissionRationale() -> State.DENIED
            else -> State.NEEDS_PERMISSION
        }
    }

    fun isReady(): Boolean = runCatching { state() == State.READY }.getOrDefault(false)

    /** Shows Shizuku's own permission dialog. [onResult] gets true when the user allowed it. */
    fun requestPermission(onResult: (Boolean) -> Unit) {
        val l = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != REQ_CODE) return
                Shizuku.removeRequestPermissionResultListener(this)
                onResult(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        Shizuku.addRequestPermissionResultListener(l)
        runCatching { Shizuku.requestPermission(REQ_CODE) }.onFailure {
            Shizuku.removeRequestPermissionResultListener(l); onResult(false)
        }
    }

    /** Calls [onChange] when the Shizuku server starts/stops. Returns a function that removes the listeners. */
    fun addStateListener(onChange: () -> Unit): () -> Unit {
        val a = Shizuku.OnBinderReceivedListener { onChange() }
        val b = Shizuku.OnBinderDeadListener { service = null; onChange() }
        Shizuku.addBinderReceivedListenerSticky(a)
        Shizuku.addBinderDeadListener(b)
        return { Shizuku.removeBinderReceivedListener(a); Shizuku.removeBinderDeadListener(b) }
    }

    /** Opens the Shizuku app if installed, otherwise its download page. */
    fun openShizuku(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
        runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder?.takeIf { it.pingBinder() }?.let { IUserService.Stub.asInterface(it) }
            latch?.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }
    @Volatile private var latch: CountDownLatch? = null

    /** Blocking – call from a background thread only. */
    private fun ensureService(timeoutMs: Long): IUserService? {
        service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        val ctx = appContext ?: return null
        synchronized(bindLock) {
            service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
            val l = CountDownLatch(1)
            latch = l
            val args = Shizuku.UserServiceArgs(ComponentName(ctx.packageName, ShizukuUserService::class.java.name))
                .processNameSuffix("shizuku")
                .daemon(false)
                .debuggable(false)
                .version(1)
            runCatching { Shizuku.bindUserService(args, connection) }.onFailure { return null }
            l.await(timeoutMs, TimeUnit.MILLISECONDS)
            return service
        }
    }

    fun run(cmd: String, timeoutMs: Long = 20_000): RootShell.Res {
        if (!isReady()) return RootShell.Res(-1, "", runCatching { state().label }.getOrDefault("Shizuku unavailable"))
        val svc = ensureService(8_000) ?: return RootShell.Res(-1, "", "Could not start the Shizuku service")
        return try {
            val parts = svc.exec(cmd).split('\u0000', limit = 3)
            RootShell.Res(parts.getOrNull(0)?.toIntOrNull() ?: -1, parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" })
        } catch (e: Exception) {
            service = null
            RootShell.Res(-1, "", e.message ?: "Shizuku call failed")
        }
    }
}
