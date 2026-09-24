package com.example.sleepmonitorsync.band

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import com.example.sleepmonitorsync.band.proto.XiaomiProto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID

/**
 * Direct BLE connection to a Xiaomi "miwear" device (Mi Band 8/9, Watch S1 (Active),
 * etc) - auth handshake + (later) periodic activity-history fetch, entirely bypassing
 * Mi Fitness / Health Connect.
 *
 * Current scope (Этап A - see 10-projects/sleep-monitor/task.md): connect, run the
 * 3-step auth handshake, report success/failure, disconnect. Activity/health data
 * fetch (CMD_ACTIVITY_FETCH_PAST) is Этап B and not implemented yet.
 *
 * One instance is single-use: call [authenticate] once, then [disconnect]. Not
 * thread-safe beyond what the Android BLE stack itself serializes.
 */
class XiaomiBandConnection(
    private val context: Context,
    private val credentials: BandCredentials,
) {
    /** One family of Xiaomi/Zepp devices uses one of a handful of GATT UUID schemes - see KNOWN_UUID_SETS. */
    private data class XiaomiUuidSet(
        val service: UUID,
        val commandRead: UUID,
        val commandWrite: UUID,
    )

    companion object {
        private const val TAG = "XiaomiBandConnection"

        private val CCC_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // All GATT UUID schemes known to be used across the Xiaomi/Zepp "miwear" device
        // family (ported from Gadgetbridge's XiaomiUuids - different chipset generations
        // use different base UUIDs). We don't know in advance which one a Mi Band 9 Pro
        // uses, so onServicesDiscovered tries each in turn against what the device
        // actually reports.
        private val KNOWN_UUID_SETS = listOf(
            // Mi Band 8/9(?), Redmi Watch 3 Active, Xiaomi Watch S1 (Active), Redmi Smart Band 2, Redmi Watch 2 Lite
            XiaomiUuidSet(
                service = UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb"),
                commandRead = UUID.fromString("00000051-0000-1000-8000-00805f9b34fb"),
                commandWrite = UUID.fromString("00000052-0000-1000-8000-00805f9b34fb"),
            ),
            // Mi Watch Lite, Redmi Watch
            XiaomiUuidSet(
                service = UUID.fromString("16186f00-0000-1000-8000-00807f9b34fb"),
                commandRead = UUID.fromString("16186f01-0000-1000-8000-00807f9b34fb"),
                commandWrite = UUID.fromString("16186f02-0000-1000-8000-00807f9b34fb"),
            ),
            // Mi Smart Watch 4C, Redmi Band (note: read/write swapped vs the set above)
            XiaomiUuidSet(
                service = UUID.fromString("16187f00-0000-1000-8000-00807f9b34fb"),
                commandRead = UUID.fromString("16187f02-0000-1000-8000-00807f9b34fb"),
                commandWrite = UUID.fromString("16187f01-0000-1000-8000-00807f9b34fb"),
            ),
            // Mi Watch (Color/Sport)
            XiaomiUuidSet(
                service = UUID.fromString("1314f000-1000-9000-7000-301291e21220"),
                commandRead = UUID.fromString("1314f005-1000-9000-7000-301291e21220"),
                commandWrite = UUID.fromString("1314f001-1000-9000-7000-301291e21220"),
            ),
            // Mi Watch CN
            XiaomiUuidSet(
                service = UUID.fromString("7495fe00-a7f3-424b-92dd-4a006a3aef56"),
                commandRead = UUID.fromString("74950002-a7f3-424b-92dd-4a006a3aef56"),
                commandWrite = UUID.fromString("74950001-a7f3-424b-92dd-4a006a3aef56"),
            ),
        )

        private const val CMD_TYPE_AUTH = 1
        private const val CMD_SUBTYPE_NONCE = 26
        private const val CMD_SUBTYPE_AUTH = 27

        private const val HANDSHAKE_TIMEOUT_MS = 25_000L
    }

    private var gatt: BluetoothGatt? = null
    private var commandWriteChar: BluetoothGattCharacteristic? = null
    private var refreshedCacheOnce = false

    private var phoneNonce: ByteArray = ByteArray(0)
    private var watchNonce: ByteArray = ByteArray(0)

    private val outcome = CompletableDeferred<Result<Unit>>()

    /**
     * Connects to [BandCredentials.macAddress] and runs the 3-step auth handshake.
     * Suspends until authenticated, failed, or [HANDSHAKE_TIMEOUT_MS] elapses.
     * Always call [disconnect] afterwards (success or failure) to release the GATT
     * connection.
     */
    @SuppressLint("MissingPermission") // checked manually below so we can return a clear Result instead of crashing
    suspend fun authenticate(): Result<Unit> {
        if (!hasBluetoothConnectPermission()) {
            return Result.failure(SecurityException("BLUETOOTH_CONNECT permission not granted"))
        }

        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return Result.failure(IllegalStateException("No BluetoothManager on this device"))
        if (!adapter.isEnabled) {
            return Result.failure(IllegalStateException("Bluetooth is disabled"))
        }

        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(credentials.macAddress)
        } catch (e: IllegalArgumentException) {
            return Result.failure(IllegalArgumentException("Invalid MAC address: ${credentials.macAddress}", e))
        }

        return try {
            withTimeout(HANDSHAKE_TIMEOUT_MS) {
                Log.i(TAG, "Connecting to ${credentials.macAddress}...")
                gatt = device.connectGatt(context, /* autoConnect = */ false, callback, BluetoothDevice.TRANSPORT_LE)
                outcome.await()
            }
        } catch (e: TimeoutCancellationException) {
            Result.failure(IllegalStateException("Timed out waiting for band handshake (${HANDSHAKE_TIMEOUT_MS}ms)", e))
        } catch (e: SecurityException) {
            Result.failure(e)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.let {
            try {
                it.disconnect()
                it.close()
            } catch (e: SecurityException) {
                Log.w(TAG, "disconnect() denied permission, ignoring", e)
            }
        }
        gatt = null
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true // pre-31: covered by manifest BLUETOOTH perm
        return context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun failAndClose(message: String, cause: Throwable? = null) {
        Log.e(TAG, "❌ $message", cause)
        if (!outcome.isCompleted) {
            outcome.complete(Result.failure(IllegalStateException(message, cause)))
        }
    }

    /**
     * Forces Android to re-read the device's GATT attribute table instead of using its
     * cached copy. Uses the hidden (but stable, widely-relied-on) `BluetoothGatt.refresh()`
     * method via reflection. Needed because Android caches a device's services/
     * characteristics keyed by MAC address; if a previous app (Mi Fitness) discovered a
     * stale/partial table before being uninstalled, this device can keep returning that
     * stale table until explicitly refreshed.
     */
    @SuppressLint("MissingPermission")
    private fun refreshDeviceCache(g: BluetoothGatt): Boolean {
        return try {
            val method = g.javaClass.getMethod("refresh")
            (method.invoke(g) as? Boolean) ?: false
        } catch (e: Exception) {
            Log.w(TAG, "GATT cache refresh() not available: ${e.message}")
            false
        }
    }

    private fun logDiscoveredAttributes(g: BluetoothGatt) {
        Log.w(TAG, "Discovered ${g.services.size} service(s) on device:")
        for (svc in g.services) {
            Log.w(TAG, "  service ${svc.uuid} (${svc.characteristics.size} characteristic(s))")
            for (ch in svc.characteristics) {
                Log.w(TAG, "    char ${ch.uuid} props=${ch.properties}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "GATT connected (status=$status), refreshing cache + discovering services...")
                refreshDeviceCache(g)
                if (!g.discoverServices()) {
                    failAndClose("discoverServices() returned false")
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "GATT disconnected (status=$status)")
                if (!outcome.isCompleted) {
                    failAndClose("Disconnected before handshake completed (status=$status)")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failAndClose("onServicesDiscovered failed with status=$status")
                return
            }

            val match = KNOWN_UUID_SETS.firstNotNullOfOrNull { set ->
                val service = g.getService(set.service) ?: return@firstNotNullOfOrNull null
                val readChar = service.getCharacteristic(set.commandRead) ?: return@firstNotNullOfOrNull null
                val writeChar = service.getCharacteristic(set.commandWrite) ?: return@firstNotNullOfOrNull null
                Triple(set, readChar, writeChar)
            }

            if (match == null) {
                logDiscoveredAttributes(g)
                if (!refreshedCacheOnce) {
                    refreshedCacheOnce = true
                    Log.w(TAG, "No known Xiaomi command service/characteristics found - forcing a cache refresh and retrying once")
                    refreshDeviceCache(g)
                    if (!g.discoverServices()) {
                        failAndClose("discoverServices() retry returned false")
                    }
                    return
                }
                failAndClose(
                    "No known Xiaomi command service found after cache refresh. See logcat above for the " +
                        "full list of services/characteristics this device actually exposes - the Mi Band 9 " +
                        "Pro may use a UUID scheme not yet in KNOWN_UUID_SETS."
                )
                return
            }

            val (set, readChar, writeChar) = match
            Log.i(TAG, "Using Xiaomi UUID set: service=${set.service}")
            commandWriteChar = writeChar

            if (!g.setCharacteristicNotification(readChar, true)) {
                failAndClose("setCharacteristicNotification failed for ${set.commandRead}")
                return
            }
            val ccc = readChar.getDescriptor(CCC_DESCRIPTOR)
            if (ccc == null) {
                failAndClose("CCC descriptor not found on command-read characteristic")
                return
            }
            @Suppress("DEPRECATION")
            ccc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            if (!g.writeDescriptor(ccc)) {
                failAndClose("writeDescriptor(CCC) failed")
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != CCC_DESCRIPTOR) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failAndClose("Failed to enable notifications, status=$status")
                return
            }
            Log.d(TAG, "Notifications enabled, starting auth handshake (step 1: phone nonce)")
            sendPhoneNonce(g)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            // Pre-API33 callback signature; still invoked on newer OSes too for
            // compatibility, so both this and the 3-arg override below funnel into
            // handleNotification(). Whichever fires first for a given notification wins
            // in practice (Android only calls one of the two per platform version).
            handleNotification(g, characteristic.value ?: ByteArray(0))
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotification(g, value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failAndClose("Write to ${characteristic.uuid} failed, status=$status")
            }
        }

        private fun handleNotification(g: BluetoothGatt, value: ByteArray) {
            when (val frame = XiaomiFrame.decode(value)) {
                is XiaomiFrame.Frame.Ack -> {
                    Log.d(TAG, "Got ack, result=${frame.result}")
                    if (frame.result != 0) {
                        failAndClose("Watch NACKed our last command (result=${frame.result})")
                    }
                }
                is XiaomiFrame.Frame.SingleCommand -> {
                    // Per protocol, the phone must ack every single-command notification it receives.
                    writeRaw(g, XiaomiFrame.ACK)
                    handleCommandPayload(g, frame.payload)
                }
                is XiaomiFrame.Frame.Unsupported -> {
                    Log.w(TAG, "Unsupported frame type=${frame.type} (len=${value.size}) - ignoring")
                }
            }
        }

        private fun handleCommandPayload(g: BluetoothGatt, payload: ByteArray) {
            val command = try {
                XiaomiProto.Command.parseFrom(payload)
            } catch (e: Exception) {
                failAndClose("Failed to parse Command protobuf from watch", e)
                return
            }

            if (command.type != CMD_TYPE_AUTH) {
                Log.d(TAG, "Ignoring non-auth command type=${command.type} subtype=${command.subtype}")
                return
            }

            when (command.subtype) {
                CMD_SUBTYPE_NONCE -> handleWatchNonce(g, command)
                CMD_SUBTYPE_AUTH -> handleAuthResult()
                else -> Log.d(TAG, "Ignoring auth subtype=${command.subtype}")
            }
        }

        private fun handleWatchNonce(g: BluetoothGatt, command: XiaomiProto.Command) {
            if (!command.hasAuth() || !command.auth.hasWatchNonce()) {
                failAndClose("Expected WatchNonce in auth step 2 response, got none")
                return
            }
            val watchNonceMsg = command.auth.watchNonce
            watchNonce = watchNonceMsg.nonce.toByteArray()
            val watchHmac = watchNonceMsg.hmac.toByteArray()

            val material = XiaomiCrypto.deriveAuthMaterial(
                secretKey = credentials.authKeyBytes(),
                phoneNonce = phoneNonce,
                watchNonce = watchNonce,
            )
            val expectedHmac = XiaomiCrypto.watchConfirmationHmac(material.decryptionKey, watchNonce, phoneNonce)
            if (!XiaomiCrypto.constantTimeEquals(expectedHmac, watchHmac)) {
                failAndClose("Watch HMAC mismatch - wrong auth key, or band was re-paired with Mi Fitness since the key was extracted")
                return
            }
            Log.i(TAG, "✅ Watch nonce verified, sending device info (step 3)")
            sendAuthStep3(g, material)
        }

        private fun handleAuthResult() {
            // A CMD_AUTH (type=1, subtype=27) response from the watch means it accepted
            // our AuthStep3 - handshake complete. (Some device families also set an
            // explicit auth.status field here; we don't gate success on it since the
            // encrypted-device family generally just replies with this bare command.)
            Log.i(TAG, "✅ Auth handshake complete")
            if (!outcome.isCompleted) {
                outcome.complete(Result.success(Unit))
            }
        }

        private fun sendPhoneNonce(g: BluetoothGatt) {
            phoneNonce = ByteArray(16).also { SecureRandom().nextBytes(it) }

            val command = XiaomiProto.Command.newBuilder()
                .setType(CMD_TYPE_AUTH)
                .setSubtype(CMD_SUBTYPE_NONCE)
                .setAuth(
                    XiaomiProto.Auth.newBuilder()
                        .setPhoneNonce(
                            XiaomiProto.PhoneNonce.newBuilder()
                                .setNonce(com.google.protobuf.ByteString.copyFrom(phoneNonce))
                        )
                )
                .build()

            writeRaw(g, XiaomiFrame.encodeSingle(command.toByteArray(), encrypted = false))
        }

        private fun sendAuthStep3(g: BluetoothGatt, material: XiaomiCrypto.AuthMaterial) {
            val deviceInfo = XiaomiProto.AuthDeviceInfo.newBuilder()
                .setUnknown1(0)
                .setPhoneApiLevel(Build.VERSION.SDK_INT.toFloat())
                .setPhoneName(Build.MODEL ?: "Android")
                .setUnknown3(224)
                .setRegion(Locale.getDefault().country.ifBlank { "US" }.uppercase(Locale.ROOT))
                .build()

            val encryptedDeviceInfo = XiaomiCrypto.aesCcmEncrypt(
                key = material.encryptionKey,
                nonce = XiaomiCrypto.packetNonce(material.encryptionNonce, 0),
                plaintext = deviceInfo.toByteArray(),
            )
            val confirmationHmac = XiaomiCrypto.phoneConfirmationHmac(material.encryptionKey, phoneNonce, watchNonce)

            val command = XiaomiProto.Command.newBuilder()
                .setType(CMD_TYPE_AUTH)
                .setSubtype(CMD_SUBTYPE_AUTH)
                .setAuth(
                    XiaomiProto.Auth.newBuilder()
                        .setAuthStep3(
                            XiaomiProto.AuthStep3.newBuilder()
                                .setEncryptedNonces(com.google.protobuf.ByteString.copyFrom(confirmationHmac))
                                .setEncryptedDeviceInfo(com.google.protobuf.ByteString.copyFrom(encryptedDeviceInfo))
                        )
                )
                .build()

            writeRaw(g, XiaomiFrame.encodeSingle(command.toByteArray(), encrypted = false))
        }

        @Suppress("DEPRECATION")
        private fun writeRaw(g: BluetoothGatt, bytes: ByteArray) {
            val char = commandWriteChar ?: run {
                failAndClose("commandWriteChar not ready")
                return
            }
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            char.value = bytes
            if (!g.writeCharacteristic(char)) {
                failAndClose("writeCharacteristic(${char.uuid}) returned false")
            }
        }
    }
}
