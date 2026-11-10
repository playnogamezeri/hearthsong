package com.hearthsong.app

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.view.View
import android.animation.ObjectAnimator
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlin.math.ln

class MainActivity : Activity() {
    private val teal = Color.rgb(22,143,136)
    private val prefs by lazy { getSharedPreferences("hearthsong_local", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var titleBox: EditText
    private lateinit var lyricsBox: EditText
    private lateinit var lyricView: TextView
    private lateinit var lyricScroll: ScrollView
    private lateinit var status: TextView
    private lateinit var pitchView: TextView
    private lateinit var library: LinearLayout
    private lateinit var recordButton: Button
    private lateinit var editorPage: LinearLayout
    private lateinit var libraryPage: LinearLayout
    private var player: MediaPlayer? = null
    private var playingFile: String? = null
    private var editingSongTitle: String? = null
    private var popupLyricScroll: ScrollView? = null
    private var popupLyricText: TextView? = null
    private var popupStatus: TextView? = null
    private var popupFollowButton: Button? = null
    private var popupRecordButton: Button? = null
    private var popupTimer: TextView? = null
    private var recordingStartedAt = 0L
    private val timerTick = object : Runnable {
        override fun run() {
            if (recorder != null) {
                val seconds = ((System.currentTimeMillis() - recordingStartedAt) / 1000).toInt()
                popupTimer?.text = String.format(java.util.Locale.US, "REC  %02d:%02d", seconds / 60, seconds % 60)
                handler.postDelayed(this, 500)
            }
        }
    }
    private var lyricLines: List<String> = emptyList()
    private var currentLineIndex = 0
    private var speech: SpeechRecognizer? = null
    private var following = false
    private var recorder: MediaRecorder? = null
    private var audioRecord: AudioRecord? = null
    @Volatile private var pitchRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val page = ScrollView(this).apply { isFillViewport=true; isVerticalScrollBarEnabled=true }
        val root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(16,18,16,20); setBackgroundColor(Color.WHITE) }
        page.addView(root)
        root.addView(TextView(this).apply { text="♫ Hearthsong"; textSize=26f; setTextColor(teal) })
        root.addView(TextView(this).apply { text="PRIVATE SONGWRITING STUDIO"; textSize=10f; letterSpacing=.14f; setTextColor(Color.GRAY) })
        val tabs=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        tabs.addView(button("Write"){showPage(false)},LinearLayout.LayoutParams(0,-2,1f))
        tabs.addView(button("My songs"){showPage(true)},LinearLayout.LayoutParams(0,-2,1f))
        root.addView(tabs)
        editorPage=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        libraryPage=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;visibility=View.GONE}
        root.addView(editorPage);root.addView(libraryPage)
        titleBox=EditText(this).apply { hint="Song title"; setSingleLine(true) }; editorPage.addView(titleBox)
        lyricsBox=EditText(this).apply { hint="Write your original lyrics here…"; minLines=5; gravity=android.view.Gravity.TOP; textSize=17f }
        editorPage.addView(lyricsBox,LinearLayout.LayoutParams(-1,220))
        lyricsBox.isVerticalScrollBarEnabled=true
        lyricsBox.setPadding(12,12,12,12)
        editorPage.addView(button("Save song"){ saveSong(); toast("Saved on this phone.") })
        editorPage.addView(button("New song"){editingSongTitle=null;titleBox.setText("");lyricsBox.setText("");titleBox.requestFocus();toast("Ready for a new song.")})
        editorPage.addView(button("Open lyric-following screen"){ showLyrics() })
        lyricScroll=ScrollView(this).apply { visibility=View.GONE; setBackgroundColor(Color.rgb(248,252,251)) }
        lyricView=TextView(this).apply { textSize=27f; setTextColor(Color.rgb(20,35,33)); gravity=android.view.Gravity.CENTER; setPadding(14,28,14,300); setLineSpacing(12f,1f) }
        lyricScroll.addView(lyricView)
        pitchView=TextView(this).apply { text="Pitch monitor: waiting"; textSize=12f; setTextColor(Color.GRAY) }; editorPage.addView(pitchView)
        status=TextView(this).apply { textSize=12f; setTextColor(teal); setPadding(0,6,0,6) }; editorPage.addView(status)
        val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        row.addView(button("Follow singing"){ if(following) stopFollowing() else startFollowing() },LinearLayout.LayoutParams(0,-2,1f))
        recordButton=button("Record / stop"){ if(recorder==null) startRecording() else stopRecording() }
        row.addView(recordButton,LinearLayout.LayoutParams(0,-2,1f))
        updateRecordButton(false)
        editorPage.addView(row)
        editorPage.addView(button("Back to writing"){ stopFollowing(); lyricScroll.visibility=View.GONE; lyricsBox.visibility=View.VISIBLE })
        libraryPage.addView(TextView(this).apply { text="MY SONGS & TAKES"; textSize=18f; setTextColor(teal); setPadding(0,12,0,8) })
        library=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        val listScroll=ScrollView(this).apply { isNestedScrollingEnabled=true; isVerticalScrollBarEnabled=true }
        listScroll.addView(library); libraryPage.addView(listScroll,LinearLayout.LayoutParams(-1,-2))
        setContentView(page)
        renderLibrary()
        status.text=if(Build.VERSION.SDK_INT>=31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) "On-device speech recognition is available. No online fallback will be used." else "On-device speech recognition is unavailable. Hearthsong will not send your voice online."
    }

    private fun showPage(showLibrary:Boolean){
        if(!::editorPage.isInitialized)return
        editorPage.visibility=if(showLibrary)View.GONE else View.VISIBLE
        libraryPage.visibility=if(showLibrary)View.VISIBLE else View.GONE
        if(showLibrary)renderLibrary()
    }

    private fun button(label:String, action:()->Unit)=Button(this).apply { text=label; isAllCaps=false; setTextColor(teal); setOnClickListener{action()} }
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_SHORT).show()

    private fun updateRecordButton(isRecording:Boolean) {
        if(!::recordButton.isInitialized)return
        recordButton.text=if(isRecording) "● RECORDING — STOP" else "Record / stop"
        recordButton.setTextColor(if(isRecording) Color.WHITE else teal)
        recordButton.backgroundTintList=android.content.res.ColorStateList.valueOf(if(isRecording) Color.rgb(220,45,55) else Color.rgb(235,248,246))
        recordButton.isActivated=isRecording
    }

    private fun saveSong() {
        val title=titleBox.text.toString().trim().ifBlank{"Untitled song"}
        val all=JSONArray(prefs.getString("songs","[]")); val item=JSONObject().put("title",title).put("lyrics",lyricsBox.text.toString())
        var found=false
        for(i in 0 until all.length()) if(all.getJSONObject(i).optString("title")==editingSongTitle || all.getJSONObject(i).optString("title")==title){all.put(i,item);found=true;break}
        if(!found) all.put(item)
        editingSongTitle=title
        prefs.edit().putString("songs",all.toString()).apply(); renderLibrary()
    }

    private fun renderLibrary() {
        if(!::library.isInitialized)return
        library.removeAllViews()
        val songs=JSONArray(prefs.getString("songs","[]"))
        for(i in 0 until songs.length()){
            val song=songs.getJSONObject(i)
            val songTitle=song.optString("title")
            val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
            row.addView(button("Edit: "+songTitle){titleBox.setText(songTitle);lyricsBox.setText(song.optString("lyrics"));editingSongTitle=songTitle;showPage(false);toast("Edit your lyrics, then tap Save song.")},LinearLayout.LayoutParams(0,-2,2f))
            row.addView(button("Delete"){AlertDialog.Builder(this@MainActivity).setTitle("Delete song?").setMessage("Delete this song and its lyrics?").setNegativeButton("Cancel",null).setPositiveButton("Delete"){_,_->deleteSong(songTitle)}.show()},LinearLayout.LayoutParams(0,-2,1f))
            library.addView(row)
        }
        val takes=filesDir.listFiles()?.filter{it.name.startsWith("take_")&&it.extension=="m4a"}?: emptyList()
        library.addView(TextView(this).apply{text="Saved recordings: "+takes.size; textSize=12f; setTextColor(Color.GRAY)})
        takes.sortedByDescending{it.lastModified()}.forEach{file->
            library.addView(button(if(playingFile==file.absolutePath && player?.isPlaying==true) "Ⅱ  "+file.name else "▶  "+file.name){playTake(file)})
        }
    }

    private fun deleteSong(title:String){
        val old=JSONArray(prefs.getString("songs","[]"));val kept=JSONArray()
        for(i in 0 until old.length())if(old.getJSONObject(i).optString("title")!=title)kept.put(old.getJSONObject(i))
        prefs.edit().putString("songs",kept.toString()).apply();renderLibrary();toast("Song deleted.")
    }

    private fun playTake(file:java.io.File){
        try{
            if(playingFile==file.absolutePath && player?.isPlaying==true){
                player?.pause();renderLibrary();return
            }
            player?.release();player=null
            player=MediaPlayer().apply{
                setDataSource(file.absolutePath)
                setOnCompletionListener{it.release();player=null;playingFile=null;renderLibrary()}
                prepare();start()
            }
            playingFile=file.absolutePath
            renderLibrary()
        }catch(e:Exception){player?.release();player=null;playingFile=null;toast("Could not play this recording.")}
    }

    private fun showLyrics(){
        if(lyricsBox.text.isBlank()){toast("Write lyrics first.");return}
        lyricLines=lyricsBox.text.toString().lines()
        currentLineIndex=0
        val dialog=Dialog(this)
        val shell=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(250,252,251));setPadding(20,18,20,18)}
        val heading=TextView(this).apply{text=titleBox.text.toString().ifBlank{"Your song"};textSize=22f;setTypeface(null,Typeface.BOLD);setTextColor(Color.rgb(25,55,52));setPadding(4,10,4,14)}
        shell.addView(heading)
        val scroll=ScrollView(this).apply{isFillViewport=true;setBackgroundColor(Color.rgb(250,252,251));clipToPadding=false}
        val display=TextView(this).apply{textSize=26f;gravity=android.view.Gravity.CENTER;setTextColor(Color.rgb(145,158,155));setPadding(20,190,20,250);setLineSpacing(20f,1.08f);text=lyricLines.joinToString("\n\n")}
        scroll.addView(display,android.widget.FrameLayout.LayoutParams(-1,-2))
        shell.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val liveStatus=TextView(this).apply{text="Ready. Start following or record your performance.";textSize=12f;setTextColor(teal);setPadding(4,8,4,4)}
        shell.addView(liveStatus)
        val liveTimer=TextView(this).apply{text="REC  00:00";textSize=16f;setTypeface(null,Typeface.BOLD);setTextColor(Color.rgb(190,45,50));gravity=android.view.Gravity.CENTER;setPadding(4,6,4,6)}
        shell.addView(liveTimer)
        val controls=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        val followControl=button("Start following"){
            if(following){stopFollowing()}else{startFollowing()}
        }
        popupFollowButton=followControl
        controls.addView(followControl,LinearLayout.LayoutParams(0,-2,1f))
        val popupRecord=button("● Record"){if(recorder==null)startRecording() else stopRecording()}
        popupRecordButton=popupRecord
        controls.addView(popupRecord,LinearLayout.LayoutParams(0,-2,1f))
        controls.addView(button("Close"){if(recorder!=null)stopRecording();stopFollowing();dialog.dismiss()},LinearLayout.LayoutParams(0,-2,1f))
        shell.addView(controls)
        dialog.setContentView(shell)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(-1,-1)
        popupLyricScroll=scroll
        popupLyricText=display
        popupStatus=liveStatus
        popupTimer=liveTimer
        dialog.setOnDismissListener{if(recorder!=null)stopRecording();stopFollowing();popupLyricScroll=null;popupLyricText=null;popupStatus=null;popupTimer=null;popupFollowButton=null;popupRecordButton=null}
        renderActiveLyricLine(false)
    }

    private fun renderActiveLyricLine(animate:Boolean=true){
        val display=popupLyricText?:return
        val scroll=popupLyricScroll?:return
        if(lyricLines.isEmpty())return
        val full=lyricLines.joinToString("\n\n")
        val span=SpannableString(full)
        var offset=0
        lyricLines.forEachIndexed{index,line->
            if(index==currentLineIndex && line.isNotBlank()){
                span.setSpan(BackgroundColorSpan(Color.rgb(220,244,239)),offset,offset+line.length,0)
                span.setSpan(android.text.style.ForegroundColorSpan(Color.rgb(15,105,98)),offset,offset+line.length,0)
                span.setSpan(android.text.style.StyleSpan(Typeface.BOLD),offset,offset+line.length,0)
            }
            offset+=line.length+2
        }
        display.text=span
        display.post{
            val layout=display.layout?:return@post
            val start=lyricLines.take(currentLineIndex).sumOf{it.length+2}
            val line=layout.getLineForOffset(start.coerceAtMost((display.text?.length?:1)-1))
            val target=(layout.getLineTop(line)+display.paddingTop-scroll.height/2+layout.getLineBottom(line)/2).coerceAtLeast(0)
            if(animate)ObjectAnimator.ofInt(scroll,"scrollY",scroll.scrollY,target).apply{duration=900;start()}
            else scroll.scrollTo(0,target)
        }
    }
    private fun startFollowing(){
        if(recorder!=null){stopRecording();popupStatus?.text="Recording stopped so the microphone can listen for lyrics."}
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),41);return}
        if(Build.VERSION.SDK_INT<31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)){status.text="On-device speech recognition unavailable. No online service will be used.";return}
        if(popupLyricText==null)showLyrics()
        try{
            speech=SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            speech?.setRecognitionListener(object:RecognitionListener{
                override fun onReadyForSpeech(params:Bundle?){status.text="Listening on this phone…"}
                override fun onBeginningOfSpeech(){status.text="I hear you singing…"}
                override fun onRmsChanged(rmsdB:Float){}
                override fun onBufferReceived(buffer:ByteArray?){}
                override fun onEndOfSpeech(){}
                override fun onError(error:Int){if(following && error!=SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS && error!=SpeechRecognizer.ERROR_CLIENT)restartSpeech() else if(error!=SpeechRecognizer.ERROR_CLIENT){popupStatus?.text="Voice following stopped. Check microphone permission.";stopFollowing()}}
                override fun onResults(results:Bundle?){updateLyrics(results);restartSpeech()}
                override fun onPartialResults(results:Bundle?){updateLyrics(results)}
                override fun onEvent(eventType:Int,params:Bundle?){}
            })
            following=true; popupStatus?.text="Listening on this phone. Sing clearly; guitar strumming may affect recognition.";popupFollowButton?.text="Stop following";pitchView.text="Pitch monitor pauses while lyric following uses the microphone."; listen()
        }catch(e:Exception){following=false;status.text="Could not start local speech recognition."}
    }

    private fun listen(){
        if(!following)return
        val intent=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE,"en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true)
        }
        try{speech?.startListening(intent)}catch(_:Exception){restartSpeech()}
    }
    private fun restartSpeech(){handler.postDelayed({if(following)listen()},400)}

    private fun updateLyrics(bundle:Bundle?){
        val spoken=bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?:return
        if(lyricLines.isEmpty())lyricLines=lyricsBox.text.toString().lines()
        val tokens=spoken.lowercase().replace(Regex("[^\\p{L}\\p{N} ]")," ").split(Regex("\\s+")).filter{it.length>1}.takeLast(8)
        if(tokens.isEmpty()||lyricLines.isEmpty())return
        val first=(currentLineIndex-1).coerceAtLeast(0)
        val last=(currentLineIndex+5).coerceAtMost(lyricLines.lastIndex)
        var best=currentLineIndex
        var bestScore=0
        for(index in first..last){
            val words=lyricLines[index].lowercase().replace(Regex("[^\\p{L}\\p{N} ]")," ").split(Regex("\\s+")).filter{it.isNotBlank()}
            val score=tokens.count{token->words.any{word->word==token||word.startsWith(token)||token.startsWith(word)}}
            val adjusted=score*10-(index-currentLineIndex).coerceAtLeast(0)
            if(adjusted>bestScore){bestScore=adjusted;best=index}
        }
        if(bestScore>=20 && best>currentLineIndex){
            currentLineIndex=best
            runOnUiThread{renderActiveLyricLine(true);popupStatus?.text="Following your singing…"}
        }
    }
    private fun stopFollowing(){
        following=false
        try{speech?.stopListening();speech?.destroy()}catch(_:Exception){}
        speech=null; status.text="Lyric following stopped.";popupStatus?.text="Lyric following stopped.";popupFollowButton?.text="Start following"
    }

    private fun startPitchMonitor(){
        if(pitchRunning)return
        val rate=16000
        val size=AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
        if(size<=0){pitchView.text="Pitch monitor unavailable";return}
        try{
            audioRecord=AudioRecord(MediaRecorder.AudioSource.MIC,rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,maxOf(size,4096))
            audioRecord?.startRecording();pitchRunning=true
            thread(name="HearthsongPitch"){
                val data=ShortArray(4096)
                while(pitchRunning){
                    val n=audioRecord?.read(data,0,data.size)?:0
                    if(n>1024){
                        val hz=estimatePitch(data,n,rate)
                        if(hz>0){
                            val midi=(69+12*(ln(hz/440.0)/ln(2.0))).toInt()
                            val names=arrayOf("C","C♯","D","D♯","E","F","F♯","G","G♯","A","A♯","B")
                            val note=names[((midi%12)+12)%12]+(midi/12-1)
                            runOnUiThread{pitchView.text="Approx. pitch: "+note+" · "+hz.toInt()+" Hz"}
                        }
                    }
                }
            }
        }catch(_:Exception){pitchView.text="Pitch monitor unavailable"}
    }

    private fun estimatePitch(data:ShortArray,count:Int,sampleRate:Int):Double{
        val minLag=sampleRate/800;val maxLag=minOf(sampleRate/70,count/2)
        var bestLag=0;var best=0.0
        for(lag in minLag..maxLag step 2){
            var sum=0.0
            for(i in 0 until minOf(count-lag,1800))sum+=data[i].toDouble()*data[i+lag].toDouble()
            if(sum>best){best=sum;bestLag=lag}
        }
        return if(bestLag>0)sampleRate.toDouble()/bestLag else 0.0
    }
    private fun stopPitchMonitor(){
        pitchRunning=false
        try{audioRecord?.stop();audioRecord?.release()}catch(_:Exception){}
        audioRecord=null;pitchView.text="Pitch monitor stopped"
    }

    private fun startRecording(){
        if(following){stopFollowing();popupStatus?.text="Voice following paused so recording can use the microphone. Voice following and recording cannot run together in this version."}
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),42);return}
        try{
            val file=java.io.File(filesDir,"take_"+System.currentTimeMillis()+".m4a")
            val mr=if(Build.VERSION.SDK_INT>=31)MediaRecorder(this) else MediaRecorder()
            mr.setAudioSource(MediaRecorder.AudioSource.MIC);mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);mr.setOutputFile(file.absolutePath);mr.prepare();mr.start()
            recorder=mr;recordingStartedAt=System.currentTimeMillis();updateRecordButton(true);popupRecordButton?.text="■ Stop recording";popupStatus?.text="Recording locally on this phone. Voice following is paused.";popupTimer?.text="REC  00:00";handler.removeCallbacks(timerTick);handler.post(timerTick);status.text="Recording locally on this phone…"
        }catch(e:Exception){status.text="Could not start recording: "+(e.message?:"unknown error")}
    }
    private fun stopRecording(){
        try{recorder?.stop();recorder?.reset();recorder?.release();status.text="Recording saved locally.";renderLibrary()}
        catch(_:Exception){status.text="Recording was too short or failed."}
        recorder=null;handler.removeCallbacks(timerTick);updateRecordButton(false);popupRecordButton?.text="● Record";popupTimer?.text="REC  00:00";if(popupStatus!=null)popupStatus?.text="Recording saved locally. Tap Start following when ready to use voice following."
    }

    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED){if(requestCode==41)startFollowing();if(requestCode==42)startRecording()}
        else status.text="Microphone permission is needed for this feature."
    }
    override fun onDestroy(){stopFollowing();if(recorder!=null)stopRecording();player?.release();player=null;super.onDestroy()}
}
