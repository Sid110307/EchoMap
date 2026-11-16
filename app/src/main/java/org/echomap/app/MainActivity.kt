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

// Experimental single-phone acoustic ranging. Not a metric 3D room scanner.
data class Hit(val x: Float, val y: Float, val z: Float, val strength: Float)

class MainActivity : Activity(), SensorEventListener {
    private lateinit var plot: ScanView
    private lateinit var status: TextView
    private val hits = mutableListOf<Hit>()
    @Volatile private var running = false
    private var worker: Thread? = null
    @Volatile private var heading = 0f
    @Volatile private var tilt = 0f
    private var rotation = FloatArray(9)
    private val orientation = FloatArray(3)
    private var sm: SensorManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(11,19,31)); setPadding(14,18,14,12) }
        val title = TextView(this).apply { text="ECHOMAP 3D  •  CAMERA-FREE"; textSize=19f; setTextColor(Color.CYAN); setPadding(4,0,0,8) }
        status = TextView(this).apply { text="Experimental acoustic echo map. Point phone toward a surface and rotate slowly."; textSize=13f; setTextColor(Color.WHITE); setPadding(4,0,0,12) }
        plot = ScanView()
        root.addView(title); root.addView(status)
        root.addView(plot, LinearLayout.LayoutParams(-1,0,1f))
        val buttons = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        fun button(label:String, click:()->Unit) = Button(this).apply { text=label; setOnClickListener { click() }; buttons.addView(this,LinearLayout.LayoutParams(0,-2,1f)) }
        button("START / STOP") { if(running) stop() else begin() }
        button("CLEAR") { synchronized(hits){hits.clear()}; plot.invalidate(); status.text="Map cleared" }
        button("EXPORT CSV") { exportCsv() }
        root.addView(buttons)
        root.addView(TextView(this).apply { text="Echo ranges are approximate. The visualization is a directional point cloud, not verified walls or furniture. Headphones and Bluetooth audio are unsupported."; textSize=11f; setTextColor(Color.LTGRAY) })
        setContentView(root)
        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
    }
    override fun onSensorChanged(event: SensorEvent) {
        if(event.sensor.type==Sensor.TYPE_ROTATION_VECTOR){
            SensorManager.getRotationMatrixFromVector(rotation,event.values)
            SensorManager.getOrientation(rotation,orientation)
            heading=orientation[0]; tilt=orientation[1]
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    private fun begin(){
        if(worker?.isAlive==true){status.text="Previous scan is stopping. Tap START again shortly.";return}
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),101);return}
        running=true
        status.text="Scanning... keep phone speaker unobstructed; turn slowly."
        worker=Thread { scanLoop() }.also { it.start() }
    }
    private fun stop(){running=false; val count=synchronized(hits){hits.size}; status.text="Stopped • $count echo candidates"}
    private fun scanLoop(){
        val rate=48000; val n=8192; val chirpN=1200
        val chirp=ShortArray(n)
        for(i in 0 until chirpN){
            val t=i.toDouble()/rate
            val phase=2*PI*(17500*t + (2000.0/(2*chirpN.toDouble()/rate))*t*t)
            val window=sin(PI*i/chirpN).pow(2)
            chirp[i]=(sin(phase)*window*13000).toInt().toShort()
        }
        val minBuffer=AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
        if(minBuffer<=0){runOnUiThread{status.text="48 kHz microphone not supported"};running=false;return}
        var recorder: AudioRecord?=null; var player: AudioTrack?=null
        try {
            val rawRecorder=try {
                AudioRecord(MediaRecorder.AudioSource.UNPROCESSED,rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,max(minBuffer,n*2))
            }catch(_:IllegalArgumentException){null}
            recorder=if(rawRecorder?.state==AudioRecord.STATE_INITIALIZED) rawRecorder else {
                rawRecorder?.release()
                AudioRecord(MediaRecorder.AudioSource.MIC,rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,max(minBuffer,n*2))
            }
            player=AudioTrack(AudioManager.STREAM_MUSIC,rate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT,n*2,AudioTrack.MODE_STATIC)
            if(recorder.state!=AudioRecord.STATE_INITIALIZED) throw IllegalStateException("Microphone initialization failed")
            // Static tracks become initialized only after their audio data is loaded.
            val written=player.write(chirp,0,n)
            if(written!=n || player.state!=AudioTrack.STATE_INITIALIZED) throw IllegalStateException("Speaker initialization failed (write=$written)")
            while(running){
                val capture=ShortArray(n)
                recorder.startRecording()
                if(recorder.recordingState!=AudioRecord.RECORDSTATE_RECORDING) throw IllegalStateException("Microphone did not start")
                player.stop()
                if(player.reloadStaticData()!=AudioTrack.SUCCESS) throw IllegalStateException("Could not reload speaker audio")
                player.play()
                var got=0
                while(got<n && running){
                    val count=recorder.read(capture,got,n-got)
                    if(count<=0) throw IllegalStateException("Microphone read failed ($count)")
                    got+=count
                }
                recorder.stop(); player.pause()
                if(got==n && running){
                    val candidate=estimate(capture,chirp,rate,chirpN)
                    if(candidate!=null){
                        val (distance,quality)=candidate
                        val az=heading; val el=-tilt
                        val hit=Hit((distance*cos(el)*sin(az)).toFloat(),(distance*cos(el)*cos(az)).toFloat(),(distance*sin(el)).toFloat(),quality)
                        synchronized(hits){hits.add(hit);if(hits.size>2500)hits.removeAt(0)}
                        val count=synchronized(hits){hits.size}
                        runOnUiThread { if(running){plot.invalidate();status.text=String.format(Locale.US,"Scanning • %d candidates • last %.2f m • relative echo strength %.2f",count,distance,quality)} }
                    }
                }
                Thread.sleep(130)
            }
        }catch(e:Exception){if(running) runOnUiThread{if(!isDestroyed) status.text="Audio error: ${e.message}"}}
        finally{running=false;try{recorder?.stop()}catch(_:Exception){};try{player?.stop()}catch(_:Exception){};recorder?.release();player?.release()}
    }
    private fun estimate(signal:ShortArray,chirp:ShortArray,rate:Int,chirpN:Int):Pair<Double,Float>?{
        // Correlation peak at direct speaker-to-mic arrival, then a later reflection.
        // Relative delay cancels most playback/record scheduling latency.
        val step=8
        val scores=DoubleArray((signal.size-chirpN)/step)
        for(k in scores.indices){
            val lag=k*step; var s=0.0
            var i=0
            while(i<chirpN){s+=signal[lag+i].toDouble()*chirp[i];i+=4}
            scores[k]=abs(s)
        }
        val directEnd=min(scores.size, (0.035*rate/step).toInt())
        if(directEnd<2)return null
        val direct=(0 until directEnd).maxByOrNull{scores[it]}?:return null
        val directScore=scores[direct]
        if(directScore<1e7)return null
        val start=direct+ceil(0.55*2/343.0*rate/step).toInt()
        val end=min(scores.size,direct+(5.0*2/343.0*rate/step).toInt())
        if(start>=end)return null
        val peak=(start until end).maxByOrNull{scores[it]}?:return null
        val quality=(scores[peak]/directScore).toFloat()
        if(quality<0.045f)return null
        val distance=(peak-direct)*step.toDouble()/rate*343.0/2
        return distance to quality.coerceAtMost(1f)
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
    override fun onResume(){
        super.onResume()
        sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sm?.registerListener(this,it,SensorManager.SENSOR_DELAY_GAME) }
    }
    override fun onPause(){
        if(running) stop()
        sm?.unregisterListener(this)
        super.onPause()
    }
    override fun onDestroy(){running=false;sm?.unregisterListener(this);super.onDestroy()}
    inner class ScanView:View(this){
        private val paint=Paint(3)
        override fun onDraw(c:Canvas){
            super.onDraw(c);c.drawColor(Color.rgb(15,27,43))
            val w=width.toFloat();val h=height.toFloat();val scale=min(w,h)/12f
            paint.strokeWidth=1f;paint.color=Color.rgb(48,69,87)
            for(i in -5..5){val x=w/2+i*scale;c.drawLine(x,0f,x,h,paint);val y=h/2+i*scale;c.drawLine(0f,y,w,y,paint)}
            paint.color=Color.WHITE;c.drawCircle(w/2,h/2,7f,paint)
            synchronized(hits){hits.forEach{hit->
                // Oblique projection exposes approximate height; no translation tracking.
                val px=w/2+hit.x*scale+hit.y*scale*.20f
                val py=h/2-hit.y*scale*.65f-hit.z*scale
                paint.color=Color.rgb((55+hit.strength*180).toInt().coerceIn(0,255),220,195)
                c.drawCircle(px,py,3.2f,paint)
            }}
            paint.color=Color.LTGRAY;paint.textSize=28f;c.drawText("Echo candidates: ${hits.size}",16f,38f,paint)
            paint.textSize=23f;c.drawText("Approx. range 0.55–5 m",16f,70f,paint)
        }
    }
}
