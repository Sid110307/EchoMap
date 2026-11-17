package org.echomap.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.hardware.*
import android.media.*
import android.os.*
import android.view.*
import android.widget.*
import java.io.File
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity(), SensorEventListener {
    private lateinit var plot: ScanView
    private lateinit var status: TextView
    private lateinit var scanButton: Button
    private lateinit var clearButton: Button
    private lateinit var exportButton: Button
    private lateinit var pointCount: TextView
    private val hits = mutableListOf<Hit>()
    @Volatile private var running = false
    private var worker: Thread? = null
    @Volatile private var direction: Direction? = null
    @Volatile private var scanId = 0
    private var mapEpoch = 0
    private var pendingStart = false
    private var resumed = false
    private val rotation = FloatArray(9)
    private var orientationSensor: Sensor? = null
    private var sm: SensorManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getFloatArray("map")?.let { saved ->
            for (i in 0 until saved.size - 3 step 4) hits.add(Hit(saved[i], saved[i + 1], saved[i + 2], saved[i + 3]))
        }
        setContentView(R.layout.activity_main)
        val root = findViewById<View>(R.id.root)
        val padding = (20 * resources.displayMetrics.density).toInt()
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(padding + insets.systemWindowInsetLeft, padding + insets.systemWindowInsetTop,
                    padding + insets.systemWindowInsetRight, padding + insets.systemWindowInsetBottom)
            }
            insets
        }
        plot = findViewById(R.id.map)
        status = findViewById(R.id.status)
        pointCount = findViewById(R.id.point_count)
        scanButton = findViewById<Button>(R.id.scan).apply {
            setOnClickListener { if (running) stop() else begin() }
        }
        clearButton = findViewById<Button>(R.id.clear).apply {
            setOnClickListener {
                synchronized(hits) { hits.clear(); mapEpoch++ }
                refreshMap()
                status.setText(R.string.cleared)
            }
        }
        exportButton = findViewById<Button>(R.id.export).apply { setOnClickListener { exportCsv() } }
        findViewById<Button>(R.id.help).setOnClickListener {
            AlertDialog.Builder(this).setTitle(R.string.help_title).setMessage(R.string.help_body)
                .setPositiveButton(R.string.done, null).show()
        }
        refreshMap()
        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        orientationSensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sm?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    }
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor == orientationSensor) {
            SensorManager.getRotationMatrixFromVector(rotation, event.values)
            val screenRotation = if (Build.VERSION.SDK_INT >= 30) display?.rotation ?: 0 else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.rotation
            }
            direction = Direction.screenTop(rotation, screenRotation)
            if (pendingStart && resumed && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                pendingStart = false
                begin()
            }
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                if (pendingStart && resumed && direction != null) { pendingStart = false; begin() }
            } else {
                pendingStart = false
                status.text = "Microphone permission is required. Allow it in app settings, then tap Start scan."
            }
        }
    }

    private fun begin() {
        if (worker?.isAlive == true) { status.text = "Previous scan is stopping. Tap Start scan again shortly."; return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingStart = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
            return
        }
        if (direction == null) {
            pendingStart = orientationSensor != null
            status.text = if (pendingStart) "Waiting for orientation…" else "This phone needs a rotation-vector sensor for mapping."
            return
        }
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        if (audio.isMicrophoneMute) { status.text = "Microphone is muted. Unmute it, then tap Start scan."; return }
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            status.text = "Media volume is muted. Raise it to a moderate level, then tap Start scan."
            return
        }
        val id = ++scanId
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener({ change ->
                if (change < 0 && scanId == id && running) {
                    stop()
                    status.text = "Scan stopped because another app needs audio. Tap Start scan to resume."
                }
            }, Handler(Looper.getMainLooper())).build()
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            status.text = "Audio is busy. Close other audio apps and tap Start scan."
            return
        }
        running = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refreshScanButton()
        status.setText(R.string.scanning)
        worker = Thread({ scanLoop(id, audio, focus) }, "EchoMap scan").also { it.start() }
    }

    private fun stop() {
        running = false
        scanId++ // Invalidate queued results/errors from the old scan.
        worker?.interrupt()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refreshScanButton()
        status.setText(R.string.stopped)
    }

    private fun scanStatus(id: Int, message: String) {
        runOnUiThread {
            if (scanId == id && !isDestroyed) {
                refreshMap()
                status.text = message
            }
        }
    }

    private fun scanLoop(id: Int, audio: AudioManager, focus: AudioFocusRequest) {
        var recorder: AudioRecord? = null
        var player: AudioTrack? = null
        val effects = mutableListOf<android.media.audiofx.AudioEffect>()
        try {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("Microphone permission was revoked. Allow it in app settings.")
            }
            val rate = EchoDetector.RATE
            val pulse = EchoDetector.chirp()
            val n = EchoDetector.CAPTURE_SAMPLES
            val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minBuffer > 0) { "48 kHz microphone not supported" }
            val mic = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            val speaker = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            check(mic != null && speaker != null) { "Built-in speaker and microphone are required" }
            val rawRecorder = if (audio.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true") try {
                AudioRecord(MediaRecorder.AudioSource.UNPROCESSED, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, max(minBuffer, n * 2))
            } catch (_: IllegalArgumentException) { null } else null
            recorder = if (rawRecorder?.state == AudioRecord.STATE_INITIALIZED) rawRecorder else {
                rawRecorder?.release()
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, max(minBuffer, n * 2))
            }
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }
            check(recorder.setPreferredDevice(mic)) { "Cannot select built-in microphone" }
            // Vendor DSP can remove the very echoes we need to measure.
            val session = recorder.audioSessionId
            if (android.media.audiofx.AcousticEchoCanceler.isAvailable())
                android.media.audiofx.AcousticEchoCanceler.create(session)?.let { effects.add(it); it.enabled = false }
            if (android.media.audiofx.NoiseSuppressor.isAvailable())
                android.media.audiofx.NoiseSuppressor.create(session)?.let { effects.add(it); it.enabled = false }
            if (android.media.audiofx.AutomaticGainControl.isAvailable())
                android.media.audiofx.AutomaticGainControl.create(session)?.let { effects.add(it); it.enabled = false }
            player = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(pulse.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
            check(player.setPreferredDevice(speaker)) { "Cannot select built-in speaker" }
            val written = player.write(pulse, 0, pulse.size)
            check(written == pulse.size && player.state == AudioTrack.STATE_INITIALIZED) { "Speaker initialization failed (write=$written)" }
            val capture = ShortArray(n)
            while (running) {
                val epoch = synchronized(hits) { mapEpoch }
                val aim = direction ?: throw IllegalStateException("Orientation unavailable")
                recorder.startRecording()
                check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start" }
                // Prime the input stream before playing, so startup cannot lose the direct pulse.
                var primed = 0
                while (primed < 960 && running) {
                    val count = recorder.read(capture, primed, 960 - primed, AudioRecord.READ_BLOCKING)
                    check(count > 0) { "Microphone warmup failed ($count)" }
                    primed += count
                }
                player.stop()
                check(player.reloadStaticData() == AudioTrack.SUCCESS) { "Could not reload speaker audio" }
                if (!running) break
                player.play()
                var got = 0
                // Small reads bound stop latency rather than blocking for 512 ms.
                while (got < n && running) {
                    val count = recorder.read(capture, got, min(960, n - got), AudioRecord.READ_BLOCKING)
                    check(count > 0) { "Microphone read failed ($count)" }
                    got += count
                }
                // Routed devices are only available while the streams are active.
                val builtInRoute = recorder.routedDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_MIC &&
                    player.routedDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                recorder.stop()
                player.stop()
                if (!running) break
                check(builtInRoute) { "Audio was routed elsewhere. Disconnect headphones/Bluetooth and restart." }
                val detection = EchoDetector.estimate(capture, pulse)
                if (!running) break
                val message = when (detection) {
                    is EchoDetector.Echo -> {
                        synchronized(hits) {
                            if (epoch == mapEpoch && running && scanId == id) {
                                hits.add(aim.hit(detection.distance, detection.strength))
                                if (hits.size > 2500) hits.removeAt(0)
                            }
                        }
                        getString(R.string.last_echo, detection.distance)
                    }
                    EchoDetector.Rejection.NO_PULSE -> "No chirp detected. Check media volume and uncover speaker/mic."
                    EchoDetector.Rejection.NO_ECHO -> "Chirp detected, no clear echo at 0.55–5 m. Aim at a nearby hard surface."
                    EchoDetector.Rejection.CLIPPED -> "Microphone overloaded. Lower media volume and try again."
                    EchoDetector.Rejection.INCOMPLETE -> "Audio arrived too late. Restart scanning and disconnect external audio."
                }
                scanStatus(id, message)
                Thread.sleep(130)
            }
        } catch (_: InterruptedException) {
            // Stop/pause requested.
        } catch (e: Exception) {
            if (running) scanStatus(id, "Audio error: ${e.message}")
        } finally {
            running = false
            runCatching { recorder?.stop() }
            runCatching { player?.stop() }
            effects.forEach { runCatching { it.release() } }
            runCatching { recorder?.release() }
            runCatching { player?.release() }
            audio.abandonAudioFocusRequest(focus)
            runOnUiThread {
                if (scanId == id) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    refreshScanButton()
                }
            }
        }
    }
    private fun exportCsv(){
        try {
            val directory=getExternalFilesDir(null) ?: throw java.io.IOException("Storage unavailable")
            val file=File(directory,"echomap_${System.currentTimeMillis()}.csv")
            val snapshot=synchronized(hits){hits.toList()}
            file.bufferedWriter().use{out->
                out.write("x_m,y_m,z_m,relative_echo_strength");out.newLine()
                snapshot.forEach{out.write("${it.x},${it.y},${it.z},${it.strength}");out.newLine()}
            }
            status.text="Saved: ${file.absolutePath}"
        }catch(e:Exception){status.text="Export failed: ${e.message}"}
    }
    override fun onResume() {
        super.onResume()
        resumed = true
        direction = null
        val registered = orientationSensor?.let { sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } == true
        if (!registered) status.text = "No orientation sensor available; directional mapping is unsupported."
    }
    override fun onPause() {
        resumed = false
        if (running) stop()
        direction = null
        sm?.unregisterListener(this)
        super.onPause()
    }
    override fun onDestroy() {
        if (running) stop()
        pendingStart = false
        sm?.unregisterListener(this)
        super.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        val saved = synchronized(hits) {
            FloatArray(hits.size * 4).also { values ->
                hits.forEachIndexed { i, hit ->
                    values[i * 4] = hit.x; values[i * 4 + 1] = hit.y
                    values[i * 4 + 2] = hit.z; values[i * 4 + 3] = hit.strength
                }
            }
        }
        outState.putFloatArray("map", saved)
        super.onSaveInstanceState(outState)
    }
    private fun refreshMap() {
        val snapshot = synchronized(hits) { hits.toList() }
        plot.points = snapshot
        pointCount.text = resources.getQuantityString(R.plurals.measurements, snapshot.size, snapshot.size)
        for (button in listOf(clearButton, exportButton)) {
            button.isEnabled = snapshot.isNotEmpty()
            button.alpha = if (button.isEnabled) 1f else 0.4f
        }
    }

    private fun refreshScanButton() {
        scanButton.setText(if (running) R.string.stop_scan else R.string.start_scan)
    }
}
