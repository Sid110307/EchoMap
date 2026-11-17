package org.echomap.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.*
import android.media.*
import android.os.*
import android.view.*
import android.widget.*
import java.io.File
import java.util.Locale
import kotlin.math.*

class MainActivity : Activity(), SensorEventListener {
    private lateinit var plot: ScanView
    private lateinit var status: TextView
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
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(11,19,31)); setPadding(14,18,14,12) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(14 + bars.left, 18 + bars.top, 14 + bars.right, 12 + bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(14 + insets.systemWindowInsetLeft, 18 + insets.systemWindowInsetTop,
                    14 + insets.systemWindowInsetRight, 12 + insets.systemWindowInsetBottom)
            }
            insets
        }
        val title = TextView(this).apply { text="ECHOMAP 3D  •  CAMERA-FREE"; textSize=19f; setTextColor(Color.CYAN); setPadding(4,0,0,8) }
        status = TextView(this).apply { text="Experimental acoustic echo map. Point the top edge of the screen toward a surface; stay in one spot and rotate slowly."; textSize=13f; setTextColor(Color.WHITE); setPadding(4,0,0,12) }
        plot = ScanView()
        root.addView(title); root.addView(status)
        root.addView(plot, LinearLayout.LayoutParams(-1,0,1f))
        val buttons = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        fun button(label:String, click:()->Unit) = Button(this).apply { text=label; setOnClickListener { click() }; buttons.addView(this,LinearLayout.LayoutParams(0,-2,1f)) }
        button("START / STOP") { if(running) stop() else begin() }
        button("CLEAR") { synchronized(hits){hits.clear();mapEpoch++}; plot.invalidate(); status.text="Map cleared" }
        button("EXPORT CSV") { exportCsv() }
        root.addView(buttons)
        root.addView(TextView(this).apply { text="Echo ranges are approximate. The visualization is a directional point cloud, not verified walls or furniture. The audible chirp uses the built-in speaker and microphone. Start at moderate media volume. Stay in one spot."; textSize=11f; setTextColor(Color.LTGRAY) })
        setContentView(root)
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
                status.text = "Microphone permission is required. Allow it in app settings, then tap START."
            }
        }
    }

    private fun begin() {
        if (worker?.isAlive == true) { status.text = "Previous scan is stopping. Tap START again shortly."; return }
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
        if (audio.isMicrophoneMute) { status.text = "Microphone is muted. Unmute it, then tap START."; return }
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            status.text = "Media volume is muted. Raise it to a moderate level, then tap START."
            return
        }
        val id = ++scanId
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener({ change ->
                if (change < 0 && scanId == id && running) {
                    stop()
                    status.text = "Scan stopped because another app needs audio. Tap START to resume."
                }
            }, Handler(Looper.getMainLooper())).build()
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            status.text = "Audio is busy. Close other audio apps and tap START."
            return
        }
        running = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status.text = "Scanning with audible chirps. Keep still and leave speaker/mic unobstructed."
        worker = Thread({ scanLoop(id, audio, focus) }, "EchoMap scan").also { it.start() }
    }

    private fun stop() {
        running = false
        scanId++ // Invalidate queued results/errors from the old scan.
        worker?.interrupt()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val count = synchronized(hits) { hits.size }
        status.text = "Stopped • $count echo candidates"
    }

    private fun scanStatus(id: Int, message: String) {
        runOnUiThread {
            if (scanId == id && !isDestroyed) {
                plot.invalidate()
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
                        val count = synchronized(hits) {
                            if (epoch == mapEpoch && running && scanId == id) {
                                hits.add(aim.hit(detection.distance, detection.strength))
                                if (hits.size > 2500) hits.removeAt(0)
                            }
                            hits.size
                        }
                        String.format(Locale.US, "Scanning • %d candidates • last %.2f m • relative echo strength %.2f",
                            count, detection.distance, detection.strength)
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
                if (scanId == id) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
    inner class ScanView:View(this){
        private val paint=Paint(3)
        override fun onDraw(c:Canvas){
            super.onDraw(c);c.drawColor(Color.rgb(15,27,43))
            val w=width.toFloat();val h=height.toFloat();val scale=min(w,h)/12f
            paint.strokeWidth=1f;paint.color=Color.rgb(48,69,87)
            for(i in -5..5){val x=w/2+i*scale;c.drawLine(x,0f,x,h,paint);val y=h/2+i*scale;c.drawLine(0f,y,w,y,paint)}
            paint.color=Color.WHITE;c.drawCircle(w/2,h/2,7f,paint)
            val snapshot = synchronized(hits) { hits.toList() }
            snapshot.forEach{hit->
                // Oblique projection exposes approximate height; no translation tracking.
                val px=w/2+hit.x*scale+hit.y*scale*.20f
                val py=h/2-hit.y*scale*.65f-hit.z*scale
                paint.color=Color.rgb((55+hit.strength*180).toInt().coerceIn(0,255),220,195)
                c.drawCircle(px,py,3.2f,paint)
            }
            paint.color=Color.LTGRAY;paint.textSize=28f;c.drawText("Echo candidates: ${snapshot.size}",16f,38f,paint)
            paint.textSize=23f;c.drawText("Approx. range 0.55–5 m",16f,70f,paint)
        }
    }
}

