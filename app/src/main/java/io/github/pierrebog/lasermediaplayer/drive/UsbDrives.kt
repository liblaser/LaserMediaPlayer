package io.github.pierrebog.lasermediaplayer.drive

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import io.github.pierrebog.lasermediaplayer.LASER_TAG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/*
 * Finding the USB devices that may be optical drives, without disturbing the
 * others, and asking permission to open them.
 *
 * Ported from VLC-Android's LaserOpticalDrive.kt.
 */

/** A USB Mass Storage interface speaking Bulk-Only Transport: the only
 * transport liblaser implements. Whether the device is an optical drive is
 * answered natively, by INQUIRY, or beforehand by sysfs. */
private const val USB_CLASS_MASS_STORAGE = 0x08
private const val USB_PROTOCOL_BULK_ONLY = 0x50

private const val ACTION_USB_PERMISSION = "io.github.pierrebog.lasermediaplayer.USB_PERMISSION"

private fun UsbDevice.isMassStorageCandidate(): Boolean =
    (0 until interfaceCount).any { i ->
        getInterface(i).let {
            it.interfaceClass == USB_CLASS_MASS_STORAGE &&
                    it.interfaceProtocol == USB_PROTOCOL_BULK_ONLY
        }
    }

/* ============================================================================
 * What the kernel already knows about a device
 *
 * Telling an optical drive from a USB key takes an INQUIRY, which liblaser can
 * only send after claiming the interface - detaching the kernel driver, which
 * makes Android unmount the key. But when usb-storage bound the device it sent
 * its own INQUIRY, and the answer is in sysfs, one "type" file per logical
 * unit: 5 for a CD/DVD/BD drive, 0 for a disk. Reading it needs no claim.
 * ========================================================================== */

private const val SCSI_TYPE_ROM = 5

/** PROBING: usb-storage is bound but has not scanned the logical units yet -
 * it waits a second after attach (delay_use). */
private enum class SysfsKind { OPTICAL, NOT_OPTICAL, PROBING, UNKNOWN }

private val SCSI_ADDRESS = Regex("""\d+:\d+:\d+:\d+""")

/* Drivers whose binding means the kernel will soon record the SCSI types.
 * Not usbfs, which is what a claim by liblaser binds. */
private val USB_STORAGE_DRIVERS = setOf("usb-storage", "uas")

/* How long a device may stay PROBING before INQUIRY decides after all. */
private const val PROBE_GRACE_MS = 5000L
private const val PROBE_POLL_MS = 250L

object UsbDrives {

    /* When each device was first seen PROBING, by deviceName. */
    private val probingSince = ConcurrentHashMap<String, Long>()

    /* deviceNames whose permission request the user refused: not asked again
     * automatically, only on an explicit refresh. */
    private val permissionDenied: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /**
     * The attached devices worth offering to classification: Mass Storage /
     * Bulk-Only devices that sysfs shows to be optical drives, or cannot tell
     * about. A USB key, card reader or disk the kernel identified is left
     * alone, and so is a device it is still probing (see [isProbing]).
     */
    fun candidates(context: Context): List<UsbDevice> {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = manager.deviceList.values
        probingSince.keys.retainAll(devices.map { it.deviceName }.toSet())
        permissionDenied.retainAll(devices.map { it.deviceName }.toSet())
        return devices.filter {
            it.isMassStorageCandidate() &&
                    sysfsKind(it).let { k -> k == SysfsKind.OPTICAL || k == SysfsKind.UNKNOWN }
        }
    }

    fun byName(context: Context, deviceName: String): UsbDevice? =
        (context.getSystemService(Context.USB_SERVICE) as UsbManager).deviceList[deviceName]

    /** A device was left out of the last candidates only because the kernel
     * is still probing it: look again shortly, see [awaitProbe]. */
    fun isProbing(): Boolean = probingNames().isNotEmpty()

    private fun probingNames(): List<String> {
        val now = SystemClock.elapsedRealtime()
        return probingSince.filterValues { now - it < PROBE_GRACE_MS }.keys.toList()
    }

    /** Returns once a device the kernel was probing is identified, has run
     * out of grace or is gone. */
    suspend fun awaitProbe(context: Context): Unit = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        while (true) {
            val names = probingNames()
            if (names.isEmpty()) return@withContext
            delay(PROBE_POLL_MS)
            val devices = manager.deviceList
            if (names.any { name -> devices[name]?.let { sysfsKind(it) != SysfsKind.PROBING } ?: true })
                return@withContext
        }
    }

    fun isPermissionDenied(deviceName: String): Boolean = deviceName in permissionDenied

    fun forgetPermissionDenials() = permissionDenied.clear()

    /**
     * Suspends until [device] may be opened: returns at once if permission was
     * granted earlier, otherwise shows the system dialog and waits for the
     * answer. A refusal is recorded, see [isPermissionDenied].
     */
    suspend fun requestPermission(context: Context, device: UsbDevice): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (manager.hasPermission(device)) return true

        val appContext = context.applicationContext
        val granted = suspendCancellableCoroutine<Boolean> { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != ACTION_USB_PERMISSION) return
                    /* Several requests may be in flight on the same action:
                     * match the device the system echoes back. */
                    @Suppress("DEPRECATION")
                    val d = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (d != null && d.deviceName != device.deviceName) return
                    appContext.unregisterReceiver(this)
                    if (cont.isActive)
                        cont.resume(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                }
            }

            /* FLAG_MUTABLE: the system writes the answer into this intent, and
             * an immutable one comes back empty, reading as a denial. The
             * request code is per device, so that two requests do not share
             * one PendingIntent. */
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE else 0
            val intent = PendingIntent.getBroadcast(
                appContext, device.deviceId,
                Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName), flags)

            registerReceiverCompat(appContext, receiver, IntentFilter(ACTION_USB_PERMISSION))
            cont.invokeOnCancellation {
                try { appContext.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) {}
            }
            manager.requestPermission(device, intent)
        }
        if (!granted) {
            Log.w(LASER_TAG, "USB permission denied for ${device.deviceName}")
            permissionDenied.add(device.deviceName)
        }
        return granted
    }

    /**
     * Reads [device]'s state from sysfs: the SCSI types of its logical units,
     * as usb-storage recorded them, and whether usb-storage is bound at all.
     * The device is found by the bus and device numbers its deviceName,
     * "/dev/bus/usb/BBB/DDD", carries.
     */
    private fun sysfsKind(device: UsbDevice): SysfsKind {
        val parts = device.deviceName.split('/')
        val bus = parts.getOrNull(parts.size - 2)?.toIntOrNull() ?: return SysfsKind.UNKNOWN
        val dev = parts.lastOrNull()?.toIntOrNull() ?: return SysfsKind.UNKNOWN

        var types = emptyList<Int>()
        var storageBound = false
        try {
            val usbDir = File("/sys/bus/usb/devices").listFiles()?.firstOrNull { dir ->
                ':' !in dir.name &&
                        File(dir, "busnum").readTextOrNull()?.trim()?.toIntOrNull() == bus &&
                        File(dir, "devnum").readTextOrNull()?.trim()?.toIntOrNull() == dev
            }
            if (usbDir != null) {
                /* <device>/<device>:C.I/hostN/targetN:C:T/N:C:T:L/type, walked
                 * by name only: sysfs is full of symlinks to other devices. */
                fun File.subdirs(match: (String) -> Boolean): List<File> =
                    listFiles()?.filter { it.isDirectory && match(it.name) }.orEmpty()

                val interfaces = usbDir.subdirs { it.startsWith("${usbDir.name}:") }
                types = interfaces
                    .flatMap { it.subdirs { name -> name.startsWith("host") } }
                    .flatMap { it.subdirs { name -> name.startsWith("target") } }
                    .flatMap { it.subdirs { name -> SCSI_ADDRESS.matches(name) } }
                    .mapNotNull { File(it, "type").readTextOrNull()?.trim()?.toIntOrNull() }
                storageBound = interfaces.any {
                    File(it, "driver").let { link ->
                        link.exists() && link.canonicalFile.name in USB_STORAGE_DRIVERS
                    }
                }
            }
        } catch (_: SecurityException) {
            /* Unreadable: UNKNOWN, INQUIRY decides. */
        } catch (_: java.io.IOException) {
        }

        if (types.isNotEmpty() || !storageBound) {
            probingSince.remove(device.deviceName)
            return when {
                SCSI_TYPE_ROM in types -> SysfsKind.OPTICAL
                types.isNotEmpty() -> SysfsKind.NOT_OPTICAL
                else -> SysfsKind.UNKNOWN
            }
        }
        val since = probingSince.getOrPut(device.deviceName) { SystemClock.elapsedRealtime() }
        return if (SystemClock.elapsedRealtime() - since < PROBE_GRACE_MS) SysfsKind.PROBING
               else SysfsKind.UNKNOWN
    }

    private fun File.readTextOrNull(): String? =
        try { readText() } catch (_: java.io.IOException) { null }
}

internal fun registerReceiverCompat(context: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
        @Suppress("UnspecifiedRegisterReceiverFlag")
        context.registerReceiver(receiver, filter)
    }
}
