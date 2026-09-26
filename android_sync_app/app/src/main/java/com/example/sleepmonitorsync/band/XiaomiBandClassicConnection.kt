package com.example.sleepmonitorsync.band

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import android.util.Log
import com.example.sleepmonitorsync.band.activity.ActivitySample
import com.example.sleepmonitorsync.band.activity.DailyDetailsParser
import com.example.sleepmonitorsync.band.activity.DailySummary
import com.example.sleepmonitorsync.band.activity.DailySummaryParser
import com.example.sleepmonitorsync.band.activity.SleepDetailsParser
import com.example.sleepmonitorsync.band.activity.XiaomiActivityFileId
import com.example.sleepmonitorsync.band.proto.XiaomiProto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.OutputStream
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32

/**
 * Bluetooth Classic (SPP/RFCOMM) connection to a Xiaomi "miwear" device, using SPP
 * protocol V2 ([XiaomiSppFrameV2]) - confirmed to be what this Mi Band 9 Pro actually
 * speaks by capturing and decoding a real Mi Fitness session via HCI snoop log (see
 * 10-projects/sleep-monitor/task.md). The device must already be paired at the Android
 * OS level (Settings > Bluetooth).
 *
 * Auth handshake sequence (Этап A, verified against the real capture):
 *   1. Phone sends SESSION_CONFIG(start request) with fixed version/window params.
 *   2. Watch replies SESSION_CONFIG(start response) - params echoed back, not enforced here.
 *   3. Phone sends DATA(channel=Protobuf, plaintext, PhoneNonce) seq=0.
 *   4. Watch sends ACK(seq=0) + DATA(channel=Protobuf, plaintext, WatchNonce) seq=0.
 *      Phone must ACK(seq=0) back immediately upon receiving any DATA packet.
 *   5. Phone derives keys, sends DATA(..., AuthStep3) seq=1.
 *   6. Watch sends ACK(seq=1) + DATA(..., AuthStep4/CMD_AUTH) seq=1 - handshake done.
 *      Phone ACKs(seq=1) back.
 *
 * Activity fetch (Этап B): a real Mi Fitness capture showed the band's file offer
 * (`Health{subtype=1, activityRequestFileIds=...}`) arriving only ~0.3s after Mi
 * Fitness sends a specific trigger: `Health{subtype=1, activitySyncRequestToday{unknown1=0}}`
 * (note: subtype=1 is reused for BOTH the phone's "get today" request AND the band's
 * file-offer reply, with different Health sub-fields populated depending on direction -
 * NOT subtype=2 as first assumed). Without sending this trigger, the band never
 * volunteers the offer on its own (confirmed - two live tests with 25-90s of passive
 * waiting both got 0 files). Our trimmed .proto doesn't declare the
 * ActivitySyncRequestToday submessage, so the trigger is sent as a raw verified-working
 * byte literal ([HEALTH_GET_TODAY_TRIGGER_BYTES]) rather than built via the protobuf
 * API. See 10-projects/sleep-monitor/task.md, "Этап B: real trigger found" entry.
 *   1. Phone authenticates, then sends the raw trigger bytes (AES-CTR encrypted).
 *   2. Watch replies with DATA(..., Health{subtype=1, activityRequestFileIds=<7-byte fileIds...>}).
 *   3. Phone replies DATA(..., Health{subtype=3, activityRequestFileIds=<same fileIds>})
 *      requesting them.
 *   4. Watch streams each file's raw bytes as one or more DATA(channel=Activity(5),
 *      ENCRYPTED) chunks, each chunk payload prefixed with [total:u16 LE][num:u16 LE].
 *      When num==total the accumulated bytes are one complete file: 7-byte fileId + 1
 *      padding byte + a version-specific header + body + trailing 4-byte CRC32 (the
 *      *standard* CRC32, not the SPP frame's own CRC16 - both are checked separately).
 *      Confirmed real file types seen so far beyond the two we parse: type=0 subtype=6
 *      (version 2) and type=0 subtype=8 detail=1 (version 4) - likely sleep-related,
 *      not yet reverse-engineered (see task.md, "Этап B: unsupported file types").
 *   5. Phone sends DATA(..., Health{subtype=5, activitySyncAckFileIds=<that fileId>})
 *      to ack each file as it's fully received and parsed (ack'd regardless of whether
 *      we could parse it, so the band doesn't keep re-offering it forever).
 * All channel=1 (Protobuf) and channel=5 (Activity) traffic after auth is AES-CTR
 * encrypted with the key itself as the IV (see [XiaomiCrypto.ctrCryptV2] - ported from
 * Gadgetbridge's encryptV2/decryptV2, upstream comment: "I wish I was kidding").
 *
 * One instance is single-use: call [authenticate] once, optionally [fetchActivityData]
 * once (reusing the same open socket), then [disconnect].
 */
class XiaomiBandClassicConnection(
    private val context: Context,
    private val credentials: BandCredentials,
) {
    companion object {
        private const val TAG = "XiaomiBandClassic"

        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

        private const val CMD_TYPE_AUTH = 1
        private const val CMD_TYPE_HEALTH = 8
        private const val CMD_SUBTYPE_NONCE = 26
        private const val CMD_SUBTYPE_AUTH = 27
        private const val HEALTH_SUBTYPE_FILES_OFFERED = 1
        private const val HEALTH_SUBTYPE_REQUEST_FILES = 3
        private const val HEALTH_SUBTYPE_ACK_FILE = 5

        /**
         * Verified-working raw bytes for `Command{type=8, subtype=1,
         * health{activitySyncRequestToday{unknown1=0}}}` - captured directly from a
         * real Mi Fitness session (see class doc). Sent as a literal rather than built
         * via the protobuf API because our trimmed .proto doesn't declare the
         * ActivitySyncRequestToday submessage (Health field 5) - only what Этап A/B
         * actually needed so far.
         */
        private val HEALTH_GET_TODAY_TRIGGER_BYTES = byteArrayOf(
            0x08, 0x08, 0x10, 0x01, 0x52, 0x04, 0x2a, 0x02, 0x08, 0x00
        )

        private const val HANDSHAKE_TIMEOUT_MS = 60_000L
        private const val SESSION_CONFIG_TIMEOUT_MS = 5_000L
        private const val POST_CONNECT_SETTLE_MS = 300L
        private const val MAX_CONNECT_ROUNDS = 3
        private const val CONNECT_ROUND_BACKOFF_MS = 2_500L

        /**
         * How long to wait, after the last ANY inbound packet, before deciding the
         * fetch is done. In the real capture the offer arrives ~0.3s after the trigger
         * and the whole file transfer completes within ~1s, but we keep a 5-second
         * margin for real-world variance (weaker signal, more accumulated data, etc),
         * while avoiding a long idle period before the deferred backend ACK.
         */
        private const val FETCH_IDLE_TIMEOUT_MS = 5_000L
        /** Overall cap on a single fetchActivityData() call. */
        private const val FETCH_OVERALL_TIMEOUT_MS = 60_000L

        // The Mi Band supports only one Classic SPP session reliably. Serialize every
        // operation in this process, including manual debug actions, so a second socket
        // cannot invalidate the first connection while it is uploading/ACKing files.
        private val sppOperationMutex = Mutex()

        suspend fun <T> withExclusiveSppOperation(block: suspend () -> T): T =
            sppOperationMutex.withLock { block() }
    }

    /**
     * Result of [fetchActivityData]. [filesFailed] counts genuine problems (bad CRC,
     * truncated data, or a recognized-but-unsupported format version); files whose
     * type/subtype/detailType combo we simply haven't reverse-engineered a parser for
     * yet are counted separately in [filesUnsupported]/[unsupportedFileDescriptions]
     * rather than lumped in with real failures.
     */
    data class FetchResult(
        val perMinuteSamples: List<ActivitySample>,
        val dailySummaries: List<DailySummary>,
        val sleepSummaries: List<SleepDetailsParser.SleepSummary>,
        val filesReceived: Int,
        val filesFailed: Int,
        val filesUnsupported: Int,
        val unsupportedFileDescriptions: List<String>,
    )

    private var socket: BluetoothSocket? = null
    @Volatile private var keepReading = true
    @Volatile private var sessionConfigHandled = false

    private val assembler = XiaomiSppFrameV2.Assembler()
    private var outgoingSeq = 0
    private var phoneNonce: ByteArray = ByteArray(0)
    private var watchNonce: ByteArray = ByteArray(0)
    private var authMaterial: XiaomiCrypto.AuthMaterial? = null

    private val authOutcome = CompletableDeferred<Result<Unit>>()

    // --- Activity-fetch state (only used during fetchActivityData()) ---
    private var fetchActive = false
    private val perMinuteSamples = mutableListOf<ActivitySample>()
    private val dailySummaries = mutableListOf<DailySummary>()
    private val sleepSummaries = mutableListOf<SleepDetailsParser.SleepSummary>()
    private var filesReceived = 0
    private var filesFailed = 0
    private var filesUnsupported = 0
    private val unsupportedFileDescriptions = mutableListOf<String>()
    private val pendingFileAcks = mutableListOf<ByteArray>()
    private var currentChunkBuffer = ByteArray(0)
    private var currentChunkTotal = 0
    private var fetchIdleDeferred: CompletableDeferred<Unit>? = null

    // ============================== Auth ==============================

    suspend fun authenticate(): Result<Unit> {
        val cleanAuthKey = credentials.authKeyHex.trim().removePrefix("0x").removePrefix("0X")
        if (cleanAuthKey.length != 32 || cleanAuthKey.any { it.digitToIntOrNull(16) == null }) {
            return Result.failure(IllegalStateException("Ключ авторизации браслета не задан. Откройте Настройки → Xiaomi Band и введите 32 hex-символа."))
        }
        if (!hasBluetoothConnectPermission()) {
            return Result.failure(SecurityException("BLUETOOTH_CONNECT permission not granted"))
        }
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return Result.failure(IllegalStateException("No BluetoothManager on this device"))
        if (!adapter.isEnabled) {
            return Result.failure(IllegalStateException("Bluetooth is disabled"))
        }

        return try {
            withTimeout(HANDSHAKE_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { openSocketAndStartHandshake(adapter) }
                authOutcome.await()
            }
        } catch (e: TimeoutCancellationException) {
            Result.failure(IllegalStateException("Timed out waiting for band handshake over SPP (${HANDSHAKE_TIMEOUT_MS}ms)", e))
        } catch (e: SecurityException) {
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(IllegalStateException("SPP connect failed: ${e.message}. Is the band paired in Android Bluetooth settings?", e))
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        keepReading = false
        try {
            socket?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing SPP socket (ignoring): ${e.message}")
        }
        socket = null
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun hasBluetoothScanPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun failAndClose(message: String, cause: Throwable? = null) {
        Log.e(TAG, "❌ $message", cause)
        if (!authOutcome.isCompleted) {
            authOutcome.complete(Result.failure(IllegalStateException(message, cause)))
        }
    }

    @SuppressLint("MissingPermission")
    private fun openSocketAndStartHandshake(adapter: BluetoothAdapter) {
        if (hasBluetoothScanPermission()) {
            adapter.cancelDiscovery()
        } else {
            Log.w(TAG, "BLUETOOTH_SCAN not granted, skipping cancelDiscovery() (non-fatal)")
        }

        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(credentials.macAddress)
        } catch (e: IllegalArgumentException) {
            failAndClose("Invalid MAC address: ${credentials.macAddress}", e)
            return
        }

        var sock: BluetoothSocket? = null
        for (round in 1..MAX_CONNECT_ROUNDS) {
            sock = openSocket(device, insecure = true)
            if (sock == null) {
                Log.w(TAG, "[round $round] Insecure RFCOMM connect failed, retrying with a secure socket")
                sock = openSocket(device, insecure = false)
            }
            if (sock != null) break
            if (round < MAX_CONNECT_ROUNDS) {
                Log.w(TAG, "[round $round] Both insecure and secure failed, backing off ${CONNECT_ROUND_BACKOFF_MS}ms before retrying")
                Thread.sleep(CONNECT_ROUND_BACKOFF_MS)
            }
        }
        if (sock == null) {
            failAndClose("SPP socket.connect() failed after $MAX_CONNECT_ROUNDS rounds (insecure+secure each) - is the band paired in Android Bluetooth settings?")
            return
        }

        socket = sock
        Log.i(TAG, "✅ SPP socket connected")
        Thread.sleep(POST_CONNECT_SETTLE_MS)

        startReaderThread(sock)
        sendSessionConfigRequest(sock)
        scheduleSessionConfigFallback(sock)
    }

    @SuppressLint("MissingPermission")
    private fun openSocket(device: BluetoothDevice, insecure: Boolean): BluetoothSocket? {
        val sock = try {
            if (insecure) device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
            else device.createRfcommSocketToServiceRecord(SPP_UUID)
        } catch (e: IOException) {
            Log.w(TAG, "Failed to create ${if (insecure) "insecure" else "secure"} RFCOMM socket: ${e.message}")
            return null
        }
        return try {
            Log.i(TAG, "Connecting ${if (insecure) "insecure" else "secure"} SPP socket to ${device.address}...")
            sock.connect()
            sock
        } catch (e: IOException) {
            Log.w(TAG, "${if (insecure) "Insecure" else "Secure"} SPP connect() failed: ${e.message}")
            try { sock.close() } catch (_: IOException) {}
            null
        }
    }

    private fun startReaderThread(sock: BluetoothSocket) {
        Thread({
            val input = sock.inputStream
            val buf = ByteArray(4096)
            try {
                while (keepReading) {
                    val n = input.read(buf)
                    if (n < 0) {
                        if (!authOutcome.isCompleted) failAndClose("SPP socket closed by remote device")
                        return@Thread
                    }
                    if (n == 0) continue
                    val frames = assembler.feed(buf.copyOf(n))
                    for (frame in frames) handleFrame(sock, frame)
                }
            } catch (e: IOException) {
                if (keepReading && !authOutcome.isCompleted) {
                    failAndClose("SPP read error: ${e.message}", e)
                }
            }
        }, "XiaomiSppReader").apply {
            isDaemon = true
            start()
        }
    }

    private fun scheduleSessionConfigFallback(sock: BluetoothSocket) {
        Thread({
            Thread.sleep(SESSION_CONFIG_TIMEOUT_MS)
            if (!sessionConfigHandled) {
                sessionConfigHandled = true
                Log.w(TAG, "No session-config response within ${SESSION_CONFIG_TIMEOUT_MS}ms, proceeding with auth anyway")
                sendPhoneNonce(sock)
            }
        }, "XiaomiSppSessionTimeout").apply {
            isDaemon = true
            start()
        }
    }

    private fun sendSessionConfigRequest(sock: BluetoothSocket) {
        val frame = XiaomiSppFrameV2.encode(
            packetType = XiaomiSppFrameV2.PACKET_TYPE_SESSION_CONFIG,
            sequenceNumber = 0,
            payload = XiaomiSppFrameV2.buildSessionStartRequestPayload(),
        )
        writeRaw(sock, frame)
    }

    private fun handleFrame(sock: BluetoothSocket, frame: XiaomiSppFrameV2.Frame) {
        when (frame.packetType) {
            XiaomiSppFrameV2.PACKET_TYPE_SESSION_CONFIG -> {
                if (!sessionConfigHandled) {
                    sessionConfigHandled = true
                    Log.i(TAG, "Session config response: ${frame.payload.joinToString(" ") { "%02x".format(it) }}")
                    sendPhoneNonce(sock)
                }
            }
            XiaomiSppFrameV2.PACKET_TYPE_DATA -> {
                sendAck(sock, frame.sequenceNumber)
                if (fetchActive) resetFetchIdleTimer() // any inbound traffic during a fetch means the band is still active with us
                handleDataPayload(sock, frame.payload)
            }
            XiaomiSppFrameV2.PACKET_TYPE_ACK -> {
                Log.d(TAG, "Got ack for seq=${frame.sequenceNumber}")
            }
            else -> Log.d(TAG, "Ignoring unknown packet type=${frame.packetType}")
        }
    }

    private fun handleDataPayload(sock: BluetoothSocket, payload: ByteArray) {
        if (payload.size < 2) return
        val rawChannel = payload[0].toInt() and 0xF
        val opCode = payload[1].toInt() and 0xFF
        val inner = payload.copyOfRange(2, payload.size)

        when (rawChannel) {
            XiaomiSppFrameV2.CHANNEL_PROTOBUF -> {
                val protobufBytes = if (opCode == XiaomiSppFrameV2.OPCODE_SEND_ENCRYPTED) {
                    val key = authMaterial?.decryptionKey
                    if (key == null) {
                        Log.w(TAG, "Got encrypted protobuf before auth material was ready - ignoring")
                        return
                    }
                    XiaomiCrypto.ctrCryptV2(key, inner)
                } else inner
                handleCommandPayload(sock, protobufBytes)
            }
            5 /* XiaomiSppFrameV2.CHANNEL_ACTIVITY */ -> {
                if (!fetchActive) return
                val plain = if (opCode == XiaomiSppFrameV2.OPCODE_SEND_ENCRYPTED) {
                    val key = authMaterial?.decryptionKey ?: return
                    XiaomiCrypto.ctrCryptV2(key, inner)
                } else inner
                handleActivityChunk(sock, plain)
            }
            else -> Log.d(TAG, "Ignoring DATA on channel $rawChannel")
        }
    }

    private fun handleCommandPayload(sock: BluetoothSocket, payload: ByteArray) {
        if (payload.isEmpty()) return
        val command = try {
            XiaomiProto.Command.parseFrom(payload)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Command protobuf from watch: ${e.message}")
            return
        }

        when (command.type) {
            CMD_TYPE_AUTH -> when (command.subtype) {
                CMD_SUBTYPE_NONCE -> handleWatchNonce(sock, command)
                CMD_SUBTYPE_AUTH -> handleAuthResult()
                else -> Log.d(TAG, "Ignoring auth subtype=${command.subtype}")
            }
            CMD_TYPE_HEALTH -> if (fetchActive) handleHealthCommand(sock, command)
            else -> Log.d(TAG, "Ignoring command type=${command.type} subtype=${command.subtype}${if (fetchActive) " (waiting for file offer)" else ""}")
        }
    }

    private fun handleWatchNonce(sock: BluetoothSocket, command: XiaomiProto.Command) {
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
        authMaterial = material
        Log.i(TAG, "✅ Watch nonce verified, sending device info (step 3)")
        sendAuthStep3(sock, material)
    }

    private fun handleAuthResult() {
        Log.i(TAG, "✅ Auth handshake complete")
        if (!authOutcome.isCompleted) {
            authOutcome.complete(Result.success(Unit))
        }
    }

    private fun sendPhoneNonce(sock: BluetoothSocket) {
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
        sendPlaintextAuthCommand(sock, command)
    }

    private fun sendAuthStep3(sock: BluetoothSocket, material: XiaomiCrypto.AuthMaterial) {
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
        sendPlaintextAuthCommand(sock, command)
    }

    /** Auth-phase commands (type=1) go out plaintext at the DATA-frame level, each with its own incrementing sequence number. */
    private fun sendPlaintextAuthCommand(sock: BluetoothSocket, command: XiaomiProto.Command) {
        val inner = command.toByteArray()
        val dataPayload = ByteArray(2 + inner.size)
        dataPayload[0] = XiaomiSppFrameV2.CHANNEL_PROTOBUF.toByte()
        dataPayload[1] = XiaomiSppFrameV2.OPCODE_SEND_PLAINTEXT.toByte()
        inner.copyInto(dataPayload, 2)
        val frame = XiaomiSppFrameV2.encode(
            packetType = XiaomiSppFrameV2.PACKET_TYPE_DATA,
            sequenceNumber = outgoingSeq++,
            payload = dataPayload,
        )
        writeRaw(sock, frame)
    }

    // ========================= Activity fetch =========================

    /**
     * Sends the "get today" trigger, then waits for and downloads today's activity
     * data (steps/HR/spO2/stress + any daily summaries the band offers). Must be
     * called after [authenticate] succeeds, on the SAME instance (reuses the open
     * socket). Returns whatever was collected even on partial failure/timeout.
     */
    suspend fun fetchActivityData(): Result<FetchResult> {
        val sock = socket ?: return Result.failure(IllegalStateException("Not connected - call authenticate() first"))
        val material = authMaterial ?: return Result.failure(IllegalStateException("Not authenticated"))

        fetchActive = true
        perMinuteSamples.clear()
        dailySummaries.clear()
        sleepSummaries.clear()
        filesReceived = 0
        filesFailed = 0
        filesUnsupported = 0
        unsupportedFileDescriptions.clear()
        pendingFileAcks.clear()
        currentChunkBuffer = ByteArray(0)
        currentChunkTotal = 0

        return try {
            withTimeout(FETCH_OVERALL_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { sendHealthGetTodayTrigger(sock, material) }
                resetFetchIdleTimer()
                while (true) {
                    val timedOut = withTimeoutOrNull(FETCH_IDLE_TIMEOUT_MS) { fetchIdleDeferred?.await() }
                    if (timedOut == null) break // no activity for FETCH_IDLE_TIMEOUT_MS - assume done
                }
            }
            Result.success(currentResult())
        } catch (e: TimeoutCancellationException) {
            Result.success(currentResult())
        } finally {
            fetchActive = false
        }
    }

    private fun currentResult() = FetchResult(
        perMinuteSamples.toList(), dailySummaries.toList(), sleepSummaries.toList(),
        filesReceived, filesFailed, filesUnsupported, unsupportedFileDescriptions.toList(),
    )

    private fun resetFetchIdleTimer() {
        fetchIdleDeferred?.let { if (!it.isCompleted) it.complete(Unit) }
        fetchIdleDeferred = CompletableDeferred()
    }

    private fun sendHealthGetTodayTrigger(sock: BluetoothSocket, material: XiaomiCrypto.AuthMaterial) {
        val cipherBytes = XiaomiCrypto.ctrCryptV2(material.encryptionKey, HEALTH_GET_TODAY_TRIGGER_BYTES)
        val dataPayload = ByteArray(2 + cipherBytes.size)
        dataPayload[0] = XiaomiSppFrameV2.CHANNEL_PROTOBUF.toByte()
        dataPayload[1] = XiaomiSppFrameV2.OPCODE_SEND_ENCRYPTED.toByte()
        cipherBytes.copyInto(dataPayload, 2)
        val frame = XiaomiSppFrameV2.encode(
            packetType = XiaomiSppFrameV2.PACKET_TYPE_DATA,
            sequenceNumber = outgoingSeq++,
            payload = dataPayload,
        )
        writeRaw(sock, frame)
    }

    private fun handleHealthCommand(sock: BluetoothSocket, command: XiaomiProto.Command) {
        if (!command.hasHealth()) return
        when (command.subtype) {
            HEALTH_SUBTYPE_FILES_OFFERED -> {
                val ids = command.health.activityRequestFileIds
                if (ids.size() % 7 != 0 || ids.isEmpty) {
                    Log.d(TAG, "Health message subtype=1 with no/invalid fileIds (${ids.size()} bytes) - probably our own echoed trigger, ignoring")
                    return
                }
                Log.i(TAG, "Band offered ${ids.size() / 7} file(s), requesting them")
                val requestCmd = XiaomiProto.Command.newBuilder()
                    .setType(CMD_TYPE_HEALTH)
                    .setSubtype(HEALTH_SUBTYPE_REQUEST_FILES)
                    .setHealth(
                        XiaomiProto.Health.newBuilder().setActivityRequestFileIds(ids)
                    )
                    .build()
                sendEncryptedProtobufCommand(sock, requestCmd)
            }
            else -> Log.d(TAG, "Ignoring health subtype=${command.subtype}")
        }
    }

    private fun handleActivityChunk(sock: BluetoothSocket, chunk: ByteArray) {
        if (chunk.size < 4) return
        val total = (chunk[0].toInt() and 0xFF) or ((chunk[1].toInt() and 0xFF) shl 8)
        val num = (chunk[2].toInt() and 0xFF) or ((chunk[3].toInt() and 0xFF) shl 8)
        val chunkPayload = chunk.copyOfRange(4, chunk.size)

        if (num == 1) {
            currentChunkBuffer = ByteArray(0)
            currentChunkTotal = total
        }
        currentChunkBuffer += chunkPayload

        if (num != currentChunkTotal) return // wait for more chunks

        val data = currentChunkBuffer
        currentChunkBuffer = ByteArray(0)

        if (data.size < 13) {
            Log.w(TAG, "Activity file too short (${data.size} bytes), skipping")
            filesFailed++
            return
        }

        val bodyForCrc = data.copyOfRange(0, data.size - 4)
        val crc32 = CRC32().apply { update(bodyForCrc) }.value.toInt()
        val expectedCrc32 = ((data[data.size - 4].toInt() and 0xFF)) or
            ((data[data.size - 3].toInt() and 0xFF) shl 8) or
            ((data[data.size - 2].toInt() and 0xFF) shl 16) or
            ((data[data.size - 1].toInt() and 0xFF) shl 24)

        if (crc32 != expectedCrc32) {
            Log.w(TAG, "Activity file CRC32 mismatch (got ${"%08x".format(crc32)}, expected ${"%08x".format(expectedCrc32)})")
            filesFailed++
            return
        }

        val fileId = XiaomiActivityFileId.from(data.copyOfRange(0, 7))
        Log.i(TAG, "Received file $fileId (${data.size} bytes)")

        val isDailyCombo = fileId.type == XiaomiActivityFileId.TYPE_ACTIVITY &&
            fileId.subtype == XiaomiActivityFileId.SUBTYPE_ACTIVITY_DAILY &&
            (fileId.detailType == XiaomiActivityFileId.DETAIL_TYPE_DETAILS || fileId.detailType == XiaomiActivityFileId.DETAIL_TYPE_SUMMARY)
        val isSleepCombo = fileId.type == XiaomiActivityFileId.TYPE_ACTIVITY &&
            fileId.subtype == XiaomiActivityFileId.SUBTYPE_ACTIVITY_SLEEP &&
            (fileId.detailType == XiaomiActivityFileId.DETAIL_TYPE_DETAILS || fileId.detailType == XiaomiActivityFileId.DETAIL_TYPE_SUMMARY)
        val isKnownCombo = isDailyCombo || isSleepCombo

        if (!isKnownCombo) {
            filesUnsupported++
            unsupportedFileDescriptions.add("type=${fileId.type}/subtype=${fileId.subtype}/detail=${fileId.detailType}/v${fileId.version} (${data.size}б)")
        } else {
            val parsedOk = when {
                isDailyCombo && fileId.detailType == XiaomiActivityFileId.DETAIL_TYPE_DETAILS -> {
                    val samples = DailyDetailsParser.parse(fileId, data)
                    if (samples != null) { perMinuteSamples.addAll(samples); true } else false
                }
                isDailyCombo && fileId.detailType == XiaomiActivityFileId.DETAIL_TYPE_SUMMARY -> {
                    val summary = DailySummaryParser.parse(fileId, data)
                    if (summary != null) { dailySummaries.add(summary); true } else false
                }
                isSleepCombo -> {
                    val sleep = SleepDetailsParser.parse(fileId, data)
                    if (sleep != null) {
                        sleepSummaries.add(sleep)
                        Log.i(TAG, "Parsed Xiaomi sleep: bed=${sleep.bedTimeSeconds}, wake=${sleep.wakeupTimeSeconds}, duration=${sleep.sleepDurationMinutes} min, awakenings=${sleep.wakeCount}")
                        true
                    } else {
                        Log.w(TAG, "❌ Xiaomi sleep parser returned null for v${fileId.version} (${data.size} bytes)")
                        false
                    }
                }
                else -> false
            }
            if (parsedOk) {
                filesReceived++
            } else {
                // Known type/subtype/detail combo, but this specific file VERSION isn't
                // supported by our parser (e.g. DailySummaryParser only handles v5) -
                // that's a gap in our coverage, not a corrupted transfer, so it's
                // "unsupported" rather than a hard failure.
                filesUnsupported++
                unsupportedFileDescriptions.add("type=${fileId.type}/subtype=${fileId.subtype}/detail=${fileId.detailType}/v${fileId.version} (неизвестная версия, ${data.size}б)")
            }
        }

        // Do NOT acknowledge the file yet. The caller must first persist/upload the
        // decoded data successfully. If the backend is unavailable, closing the socket
        // without an ACK makes the band offer the file again on the next sync instead of
        // losing it after a successful Bluetooth transfer.
        if (parsedOkForAck(fileId, isKnownCombo)) {
            pendingFileAcks.add(fileId.raw)
        }
    }

    private fun parsedOkForAck(fileId: XiaomiActivityFileId, isKnownCombo: Boolean): Boolean {
        if (!isKnownCombo) return false
        return when {
            fileId.subtype == XiaomiActivityFileId.SUBTYPE_ACTIVITY_SLEEP -> fileId.version in 1..4
            fileId.detailType == XiaomiActivityFileId.DETAIL_TYPE_DETAILS -> DailyDetailsParser.headerSizeForVersion(fileId.version) != null
            fileId.detailType == XiaomiActivityFileId.DETAIL_TYPE_SUMMARY -> fileId.version == 5
            else -> false
        }
    }

    /**
     * Acknowledge successfully decoded activity files after the caller has uploaded
     * their data to the backend. Until this method is called, those files remain
     * unacknowledged on the band and can be downloaded again on the next connection.
     */
    suspend fun acknowledgeFetchedFiles(): Boolean {
        val sock = socket ?: return false
        if (pendingFileAcks.isEmpty()) return true
        return withContext(Dispatchers.IO) {
            try {
                val ids = pendingFileAcks.toList()
                ids.forEach { rawId ->
                    val ackCmd = XiaomiProto.Command.newBuilder()
                        .setType(CMD_TYPE_HEALTH)
                        .setSubtype(HEALTH_SUBTYPE_ACK_FILE)
                        .setHealth(
                            XiaomiProto.Health.newBuilder()
                                .setActivitySyncAckFileIds(com.google.protobuf.ByteString.copyFrom(rawId))
                        )
                        .build()
                    if (!sendEncryptedProtobufCommand(sock, ackCmd)) {
                        Log.e(TAG, "❌ Failed to send ACK for activity file")
                        return@withContext false
                    }
                }
                pendingFileAcks.clear()
                Log.i(TAG, "✅ Acknowledged ${ids.size} activity file(s) after backend upload")
                true
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to acknowledge activity files: ${e.message}", e)
                false
            }
        }
    }
    /** Post-auth Health/etc commands (type != 1) go out AES-CTR encrypted. */
    private fun sendEncryptedProtobufCommand(sock: BluetoothSocket, command: XiaomiProto.Command): Boolean {
        val material = authMaterial ?: return false
        val plain = command.toByteArray()
        val cipherBytes = XiaomiCrypto.ctrCryptV2(material.encryptionKey, plain)
        val dataPayload = ByteArray(2 + cipherBytes.size)
        dataPayload[0] = XiaomiSppFrameV2.CHANNEL_PROTOBUF.toByte()
        dataPayload[1] = XiaomiSppFrameV2.OPCODE_SEND_ENCRYPTED.toByte()
        cipherBytes.copyInto(dataPayload, 2)
        val frame = XiaomiSppFrameV2.encode(
            packetType = XiaomiSppFrameV2.PACKET_TYPE_DATA,
            sequenceNumber = outgoingSeq++,
            payload = dataPayload,
        )
        return writeRaw(sock, frame)
    }

    private fun sendAck(sock: BluetoothSocket, sequenceNumberToAck: Int) {
        val frame = XiaomiSppFrameV2.encode(
            packetType = XiaomiSppFrameV2.PACKET_TYPE_ACK,
            sequenceNumber = sequenceNumberToAck,
            payload = ByteArray(0),
        )
        writeRaw(sock, frame)
    }

    private fun writeRaw(sock: BluetoothSocket, bytes: ByteArray): Boolean {
        return try {
            val out: OutputStream = sock.outputStream
            out.write(bytes)
            out.flush()
            true
        } catch (e: IOException) {
            Log.e(TAG, "SPP write failed: ${e.message}", e)
            false
        }
    }
}
