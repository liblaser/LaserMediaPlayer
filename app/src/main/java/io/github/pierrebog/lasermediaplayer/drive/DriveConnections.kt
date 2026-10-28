package io.github.pierrebog.lasermediaplayer.drive

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import io.github.pierrebog.lasermediaplayer.LASER_TAG

/**
 * One connection per drive, opened at its first classification and kept
 * until the drive is unplugged. Its descriptor is the drive's liblaser token
 * for everything: classification, playback, and following its disc
 * ([LaserDrive]).
 *
 * One connection, not one per use: each would be a token of its own, set up
 * apart and claiming the drive's interface - which only one can hold.
 */
object DriveConnections {

    private val connections = HashMap<String, UsbDeviceConnection>()
    private val playing = HashSet<String>()

    /**
     * [device]'s connection, opened if it has none. Permission must have been
     * granted.
     *
     * @return the connection, owned by this object - read its descriptor,
     *         never close it - or null if the device could not be opened
     */
    @Synchronized
    fun open(context: Context, device: UsbDevice): UsbDeviceConnection? {
        connections[device.deviceName]?.let { return it }
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val connection = manager.openDevice(device) ?: return null
        connections[device.deviceName] = connection
        Log.d(LASER_TAG, "connection for ${device.deviceName} (fd=${connection.fileDescriptor})")
        return connection
    }

    /** [deviceName]'s descriptor, if it has a connection. */
    @Synchronized
    fun fd(deviceName: String): Int? = connections[deviceName]?.fileDescriptor

    /** Playback runs on the drive: classification leaves it alone. */
    @Synchronized
    fun setPlaying(deviceName: String, on: Boolean) {
        if (on) playing += deviceName else playing -= deviceName
    }

    @Synchronized
    fun isPlaying(deviceName: String): Boolean = deviceName in playing

    /**
     * The drive left the bus: its connection is closed. Whoever held a claim
     * on its token must have released it - a released descriptor's number
     * may be reused at once.
     */
    fun close(deviceName: String) {
        val connection = synchronized(this) {
            playing -= deviceName
            connections.remove(deviceName)
        } ?: return
        Log.d(LASER_TAG, "closing connection for $deviceName (fd=${connection.fileDescriptor})")
        connection.close()
    }
}
