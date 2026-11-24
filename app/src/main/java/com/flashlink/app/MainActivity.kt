package com.flashlink.app

import android.Manifest
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MainActivity:ComponentActivity() {
    private lateinit var preview:PreviewView
    private lateinit var status:TextView
    private lateinit var light:TextView
    private lateinit var progress:TextView
    private lateinit var message:EditText
    private lateinit var thresholdBar:SeekBar
    private lateinit var speed:Spinner
    private lateinit var executor:ExecutorService
    @Volatile private var cam:Camera?=null
    @Volatile private var luma=0
    @Volatile private var threshold=110
    @Volatile private var receiving=false
    @Volatile private var sending=false
    @Volatile private var bitMs=200L
    private val handler=Handler(Looper.getMainLooper())
    private enum class State { LISTEN, WAIT, READ }
    private var state=State.LISTEN
    private var highAt=0L
    private var sampleAt=0L
    private var bits=StringBuilder()
    private var packet=ByteArrayOutputStream()
    private var expected=-1
    private var transferId=-1L
    private var chunksTotal=0
    private val chunks=HashMap<Int,ByteArray>()
    private var incomingName="FlashLink_received.bin"
    private var incomingSize=-1L
    private var saveBytes:ByteArray?=null
    private var saveName="FlashLink_received.bin"

    private val permission=registerForActivityResult(ActivityResultContracts.RequestPermission()){if(it) startCamera() else setStatus("Camera permission required")}
    private val pick=registerForActivityResult(ActivityResultContracts.OpenDocument()){u->if(u!=null) readAndSend(u)}
    private val save=registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")){u->
        val data=saveBytes
        if(u!=null && data!=null) try {contentResolver.openOutputStream(u)?.use{it.write(data)};setStatus("Saved $saveName")}catch(e:Exception){setStatus("Save error: ${e.message}")}
        saveBytes=null
    }

    override fun onCreate(b:Bundle?){super.onCreate(b);executor=Executors.newSingleThreadExecutor();ui()
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)startCamera() else permission.launch(Manifest.permission.CAMERA)
    }

    private fun ui(){
        val col=LinearLayout(this).apply{orientation=1;setPadding(20,18,20,24);setBackgroundColor(0xff0b1220.toInt())}
        val scroll=ScrollView(this)
        col.addView(label("FLASHLINK 2.0",25,0xfff4f7fb.toInt()))
        col.addView(label("OPTICAL FILE TRANSFER  •  MANUAL HALF-DUPLEX",11,0xff9eacc2.toInt()))
        status=label("Starting camera…",14,0xff20c997.toInt());col.addView(status)
        preview=PreviewView(this).apply{layoutParams=LinearLayout.LayoutParams(-1,210);scaleType=PreviewView.ScaleType.FILL_CENTER};col.addView(preview)
        light=label("Camera brightness: --",13,0xffd5deeb.toInt());col.addView(light)
        progress=label("No active transfer",13,0xffaab7ca.toInt());col.addView(progress)
        col.addView(label("RECEIVE",16,0xfff4f7fb.toInt()))
        col.addView(btn("START RECEIVER",0xff176b58.toInt()){startReceiver()})
        col.addView(btn("STOP RECEIVER",0xff35445b.toInt()){stopReceiver()})
        col.addView(label("SEND TEXT",16,0xfff4f7fb.toInt()))
        message=EditText(this).apply{hint="Type a short message";setText("Hello from FlashLink");setTextColor(-1);setHintTextColor(0xff8291a8.toInt());minLines=2}
        col.addView(message)
        col.addView(btn("SEND TEXT MESSAGE",0xff176b58.toInt()){sendPayload("message.txt",message.text.toString().toByteArray())})
        col.addView(btn("CHOOSE ANY FILE",0xff24508a.toInt()){pick.launch(arrayOf("*/*"))})
        col.addView(label("RECEIVER THRESHOLD",13,0xffaab7ca.toInt()))
        thresholdBar=SeekBar(this).apply{max=255;progress=threshold;setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(s:SeekBar?,v:Int,user:Boolean){threshold=v}
            override fun onStartTrackingTouch(s:SeekBar?){};override fun onStopTrackingTouch(s:SeekBar?){}
        })};col.addView(thresholdBar)
        col.addView(label("BIT DURATION — MATCH ON BOTH PHONES",13,0xffaab7ca.toInt()))
        speed=Spinner(this);speed.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,listOf("600 ms • slow/robust","400 ms","300 ms","200 ms • default","150 ms • experimental","100 ms • unreliable"));speed.setSelection(3)
        speed.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
            override fun onNothingSelected(p:AdapterView<*>?){}
            override fun onItemSelected(p:AdapterView<*>?,v:View?,pos:Int,id:Long){bitMs=listOf(600L,400L,300L,200L,150L,100L)[pos]}
        };col.addView(speed)
        col.addView(label("Receiver phone: START RECEIVER first. Sender phone: select text/file and send. Reverse roles manually for the return transfer. Test at 0.5–1 m first.",12,0xff9eacc2.toInt()))
        scroll.addView(col);setContentView(scroll)
    }
    private fun label(t:String,s:Int,c:Int)=TextView(this).apply{text=t;textSize=s.toFloat();setTextColor(c);setPadding(0,6,0,6)}
    private fun btn(t:String,c:Int,fn:()->Unit)=Button(this).apply{text=t;setTextColor(-1);setBackgroundColor(c);setOnClickListener{fn()}}
    private fun setStatus(t:String){runOnUiThread{status.text=t}}

    private fun startCamera(){
        val f=ProcessCameraProvider.getInstance(this)
        f.addListener({try{
            val provider=f.get()
            val p=Preview.Builder().build().also{it.setSurfaceProvider(preview.surfaceProvider)}
            val a=ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            a.setAnalyzer(executor){img->luma=measure(img);img.close()}
            provider.unbindAll();cam=provider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,p,a)
            setStatus("Camera ready • choose SEND or RECEIVE")
            handler.post(object:Runnable{override fun run(){light.text="Camera brightness: $luma / 255";handler.postDelayed(this,300)}})
        }catch(e:Exception){setStatus("Camera error: ${e.message}")}},ContextCompat.getMainExecutor(this))
    }
    private fun measure(im:ImageProxy):Int{
        val pl=im.planes[0];val b:ByteBuffer=pl.buffer;var sum=0L;var n=0
        var y=im.height/3
        while(y<im.height*2/3){var x=im.width/3;while(x<im.width*2/3){val i=y*pl.rowStride+x*pl.pixelStride;if(i<b.limit()){sum+=b.get(i).toInt() and 255;n++};x+=6};y+=6}
        return if(n==0)0 else (sum/n).toInt()
    }

    private val poll=object:Runnable{override fun run(){
        if(!receiving)return
        val now=SystemClock.elapsedRealtime();val lit=luma>=threshold
        when(state){
            State.LISTEN->{if(lit){if(highAt==0L)highAt=now}else{if(highAt!=0L&&now-highAt>=850){state=State.WAIT;sampleAt=now+OpticalProtocol.GUARD_MS+bitMs/2;bits=StringBuilder();packet=ByteArrayOutputStream();expected=-1};highAt=0L}}
            State.WAIT,State.READ->{if(now>=sampleAt){bits.append(if(lit)'1' else '0');sampleAt+=bitMs;state=State.READ;consumeBits()}}
        }
        handler.postDelayed(this,10)
    }}
    private fun consumeBits(){
        while(bits.length>=8){
            val v=bits.substring(0,8).toInt(2);bits.delete(0,8);packet.write(v)
            val b=packet.toByteArray()
            if(b.size==OpticalProtocol.HEADER){
                if(b[0]!=0x46.toByte()||b[1]!=0x4c.toByte()){resetRx();return}
                val len=OpticalProtocol.u16(b,11);if(len !in 0..OpticalProtocol.CHUNK){resetRx();return}
                expected=OpticalProtocol.HEADER+len+2
            }
            if(expected>0&&b.size==expected){OpticalProtocol.parse(b)?.let{handle(it)}?:runOnUiThread{progress.text="CRC error — retry transfer"};resetRx();return}
            if(b.size>OpticalProtocol.HEADER+OpticalProtocol.CHUNK+2){resetRx();return}
        }
    }
    private fun resetRx(){state=State.LISTEN;highAt=0;bits=StringBuilder();packet=ByteArrayOutputStream();expected=-1}
    private fun startReceiver(){val c=cam;if(c==null){setStatus("Camera is not ready");return};c.cameraControl.enableTorch(false);sending=false;receiving=true;resetRx();handler.removeCallbacks(poll);handler.post(poll);setStatus("RECEIVING • waiting for optical start pulse")}
    private fun stopReceiver(){receiving=false;handler.removeCallbacks(poll);resetRx();setStatus("Receiver stopped")}

    private fun readAndSend(uri:Uri){try{
        val name=queryName(uri)?: "FlashLink_file.bin"
        val bytes=contentResolver.openInputStream(uri)?.use{it.readBytes()}?:throw Exception("Cannot read file")
        sendPayload(name,bytes)
    }catch(e:Exception){setStatus("File read error: ${e.message}")}}
    private fun queryName(uri:Uri):String?{var c:Cursor?=null;return try{c=contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null);if(c!=null&&c.moveToFirst())c.getString(0)else null}finally{c?.close()}}

    private fun sendPayload(name:String,data:ByteArray){
        val c=cam;if(c==null){setStatus("Camera is not ready");return}
        if(data.isEmpty()){setStatus("Empty data");return}
        if(data.size>2*1024*1024){setStatus("Prototype limit: 2 MiB");return}
        if(receiving)stopReceiver()
        sending=true;val duration=bitMs;val id=System.currentTimeMillis() and 0xffffffffL
        val total=(data.size+OpticalProtocol.CHUNK-1)/OpticalProtocol.CHUNK
        val meta=name.substringAfterLast('/').take(100).toByteArray()+byteArrayOf(0)+java.nio.ByteBuffer.allocate(8).putLong(data.size.toLong()).array()
        val packets=ArrayList<OpticalProtocol.Packet>()
        packets.add(OpticalProtocol.Packet(OpticalProtocol.META,id,0,total,meta))
        for(i in 0 until total){val s=i*OpticalProtocol.CHUNK;val e=minOf(data.size,s+OpticalProtocol.CHUNK);packets.add(OpticalProtocol.Packet(OpticalProtocol.DATA,id,i,total,data.copyOfRange(s,e)))}
        val estimate=packets.sumOf{OpticalProtocol.bits(it).size}*duration/1000.0+packets.size*1.6
        progress.text="Queued ${data.size} bytes • estimated ${durationText(estimate)}"
        setStatus("TRANSMITTING • receiver phone must be listening")
        thread(name="FlashLink-TX"){
            try{packets.forEachIndexed{idx,pkt->
                if(!sending)throw InterruptedException("Stopped")
                torch(c,true);Thread.sleep(OpticalProtocol.START_MS);torch(c,false);Thread.sleep(OpticalProtocol.GUARD_MS)
                for(bit in OpticalProtocol.bits(pkt)){if(!sending)throw InterruptedException("Stopped");torch(c,bit);Thread.sleep(duration)}
                runOnUiThread{progress.text="Packet ${idx+1}/${packets.size} sent"}
            }
            runOnUiThread{setStatus("Transmission ended • manually reverse roles for reply")}}
            catch(e:Exception){setStatus("TX stopped/error: ${e.message}")}
            finally{sending=false;runOnUiThread{c.cameraControl.enableTorch(false)}}
        }
    }
    private fun torch(c:Camera,on:Boolean){val latch=CountDownLatch(1);runOnUiThread{c.cameraControl.enableTorch(on).addListener({latch.countDown()},ContextCompat.getMainExecutor(this))};if(!latch.await(4,TimeUnit.SECONDS))throw Exception("Torch timeout")}

    private fun handle(p:OpticalProtocol.Packet){
        if(p.type==OpticalProtocol.META){
            transferId=p.id;chunksTotal=p.total;chunks.clear()
            val z=p.payload.indexOf(0);if(z>=0&&p.payload.size>=z+9){incomingName=String(p.payload,0,z);incomingSize=java.nio.ByteBuffer.wrap(p.payload,z+1,8).long}
            runOnUiThread{progress.text="Incoming $incomingName • $chunksTotal chunks";setStatus("Metadata received • keep receiver active")}
        }else if(p.type==OpticalProtocol.DATA&&p.id==transferId&&p.total==chunksTotal&&p.index in 0 until chunksTotal){
            chunks[p.index]=p.payload
            runOnUiThread{progress.text="Receiving $incomingName • ${chunks.size}/$chunksTotal"}
            if(chunks.size==chunksTotal&&chunksTotal>0){
                val out=ByteArrayOutputStream();for(i in 0 until chunksTotal){val ch=chunks[i]?:return;out.write(ch)}
                val all=out.toByteArray();val final=if(incomingSize in 0..all.size.toLong())all.copyOf(incomingSize.toInt())else all
                saveBytes=final;saveName=incomingName;receiving=false;handler.removeCallbacks(poll)
                runOnUiThread{setStatus("File received • choose save location");save.launch(saveName)}
            }
        }
    }
    private fun durationText(s:Double):String{val n=s.toLong();return if(n<60)"${n}s" else if(n<3600)"${n/60}m ${n%60}s" else "${n/3600}h ${(n%3600)/60}m"}
    override fun onDestroy(){receiving=false;sending=false;handler.removeCallbacks(poll);cam?.cameraControl?.enableTorch(false);executor.shutdown();super.onDestroy()}
}
