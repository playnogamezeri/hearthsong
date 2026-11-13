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
import android.media.AudioTrack
import android.media.AudioAttributes
import android.media.MediaRecorder
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.vosk.Model
import org.vosk.Recognizer
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.view.View
import android.animation.ObjectAnimator
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlin.math.ln
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONException

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
    private lateinit var homePage: LinearLayout
    private lateinit var homeDateView: TextView
    private var player: MediaPlayer? = null
    private var playingFile: String? = null
    private var playbackSeek: SeekBar? = null
    private var playbackTime: TextView? = null
    private var popupPlaybackSeek: SeekBar? = null
    private var popupPlaybackTime: TextView? = null
    private var popupPlaybackButton: Button? = null
    private var popupTakeSpinner: Spinner? = null
    private var popupRecordingLabel = "▶ Start Performance"
    @Volatile private var liveAutoTuneEnabled = false
    private var popupAutoTuneButton: Button? = null
    private var popupMonitorButton: Button? = null
    @Volatile private var liveMonitorEnabled = false
    @Volatile private var liveMonitorTrack: AudioTrack? = null
    @Volatile private var karaokePresetIndex = 0
    private var liveTuneSettings = LiveAutoTuneEngine.Settings()
    private val liveTuneEngine = LiveAutoTuneEngine()

    private var lyricScrollSpeed = 2
    private val lyricAutoScrollTick = object : Runnable {
        override fun run() {
            val scroll = popupLyricScroll ?: return
            if (!recordingActive) return
            val maxScroll = (scroll.getChildAt(0)?.height ?: 0) - scroll.height
            if (scroll.scrollY < maxScroll) {
                val pixels = (resources.displayMetrics.density * lyricScrollSpeed).toInt().coerceAtLeast(1)
                scroll.scrollBy(0, pixels)
                handler.postDelayed(this, 55)
            }
        }
    }
    private val playbackTick = object : Runnable {
        override fun run() {
            val p=player
            if(p!=null && playingFile!=null) {
                val position=if(p.isPlaying || !p.isPlaying) p.currentPosition else 0
                playbackSeek?.let { if(!it.isPressed) it.progress=position }
                playbackTime?.text=formatTime(position)+" / "+formatTime(p.duration)
                popupPlaybackSeek?.let { if(!it.isPressed) it.progress=position }
                popupPlaybackTime?.text=formatTime(position)+" / "+formatTime(p.duration)
                popupPlaybackButton?.text=if(p.isPlaying) "Ⅱ Pause recording" else "▶ Play recording"
                handler.postDelayed(this,500)
            }
        }
    }
    private var editingSongTitle: String? = null
    private val backupCreateRequest = 701
    private val backupRestoreRequest = 702
    private var popupLyricScroll: ScrollView? = null
    private var popupLyricText: TextView? = null
    private var popupStatus: TextView? = null
    private var popupPerformanceButton: Button? = null
    private var popupTimer: TextView? = null
    private var recordingStartedAt = 0L
    private val timerTick = object : Runnable {
        override fun run() {
            if (recordingActive) {
                val seconds = ((System.currentTimeMillis() - recordingStartedAt) / 1000).toInt()
                popupTimer?.text = String.format(java.util.Locale.US, "REC  %02d:%02d", seconds / 60, seconds % 60)
                handler.postDelayed(this, 500)
            }
        }
    }
    private var lyricLines: List<String> = emptyList()
    private var currentLineIndex = 0
    @Volatile private var recordingActive = false
    @Volatile private var performanceFinalizing = false
    @Volatile private var recognitionEnabled = true
    @Volatile private var modelLoading = false
    private var performanceAudio: AudioRecord? = null
    private var performanceThread: Thread? = null
    private var performanceRawFile: File? = null
    private var performanceFinalFile: File? = null
    @Volatile private var voskModel: Model? = null
    @Volatile private var pitchRunning = false
    private var audioRecord: AudioRecord? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val page = ScrollView(this).apply { isFillViewport=true; isVerticalScrollBarEnabled=true; clipToPadding=false; isNestedScrollingEnabled=true }
        val root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(16,18,16,96); setBackgroundColor(Color.WHITE) }
        page.addView(root)
        root.addView(TextView(this).apply { text="♫ Jack's RePO"; textSize=28f; setTypeface(null,Typeface.BOLD); setTextColor(teal); setPadding(2,4,2,2) })
        root.addView(TextView(this).apply { text="YOUR PRIVATE SONGWRITING & PERFORMANCE STUDIO"; textSize=10f; letterSpacing=.12f; setTextColor(Color.GRAY); setPadding(2,0,2,14) })
        homeDateView=TextView(this).apply{text=currentDateLabel();textSize=14f;setTextColor(teal);setTypeface(null,Typeface.BOLD);setPadding(2,10,2,2)}
        homePage=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        homePage.addView(homeDateView)
        homePage.addView(TextView(this).apply{text="What would you like to do today?";textSize=21f;setTypeface(null,Typeface.BOLD);setTextColor(Color.rgb(27,52,49));setPadding(2,18,2,8)})
        homePage.addView(TextView(this).apply{text="Choose a simple starting point. Your songs and recordings stay on this phone.";textSize=14f;setTextColor(Color.DKGRAY);setPadding(2,0,2,14)})
        fun addHomeChoice(title:String,description:String,action:()->Unit){
            val card=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(14,12,14,12);setBackgroundColor(Color.rgb(241,249,247))}
            card.addView(TextView(this).apply{text=title;textSize=18f;setTypeface(null,Typeface.BOLD);setTextColor(Color.rgb(25,75,69))})
            card.addView(TextView(this).apply{text=description;textSize=13f;setTextColor(Color.DKGRAY);setPadding(0,4,0,8)})
            card.addView(button("Open"){action()})
            val params=LinearLayout.LayoutParams(-1,-2);params.bottomMargin=(resources.displayMetrics.density*12).toInt()
            homePage.addView(card,params)
        }
        addHomeChoice("✍  Write Lyrics","Create a song, shape your words, and save your lyrics."){showPage(false)}
        addHomeChoice("🎤  Sing Lyrics","Open a clean lyric-following screen for singing practice. No guitar recording."){showLyrics(false)}
        addHomeChoice("🎸  Sing & Play Guitar","Follow your lyrics while recording your voice and guitar together."){showLyrics(true);startPerformance()}
        homePage.addView(button("My Songs & Recordings"){showPage(true)})
        editorPage=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;visibility=View.GONE}
        libraryPage=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;visibility=View.GONE}
        root.addView(homePage);root.addView(editorPage);root.addView(libraryPage)
        titleBox=EditText(this).apply { hint="Song title"; setSingleLine(true) }; editorPage.addView(titleBox)
        lyricsBox=EditText(this).apply { hint="Write your original lyrics here…"; minLines=5; gravity=android.view.Gravity.TOP; textSize=17f }
        editorPage.addView(lyricsBox,LinearLayout.LayoutParams(-1,440))
        lyricsBox.isVerticalScrollBarEnabled=true
        lyricsBox.setPadding(12,12,12,12)
        editorPage.addView(button("Save song"){ saveSong(); toast("Saved on this phone.") })
        editorPage.addView(button("Home"){homePage.visibility=View.VISIBLE;editorPage.visibility=View.GONE;libraryPage.visibility=View.GONE})
        editorPage.addView(button("New song"){editingSongTitle=null;titleBox.setText("");lyricsBox.setText("");titleBox.requestFocus();toast("Ready for a new song.")})
        editorPage.addView(button("Open lyric-following screen"){ showLyrics() })
        lyricScroll=ScrollView(this).apply { visibility=View.GONE; setBackgroundColor(Color.rgb(248,252,251)) }
        lyricView=TextView(this).apply { textSize=27f; setTextColor(Color.rgb(20,35,33)); gravity=android.view.Gravity.CENTER; setPadding(14,28,14,300); setLineSpacing(12f,1f) }
        lyricScroll.addView(lyricView)
        pitchView=TextView(this).apply { text="Pitch monitor: waiting"; textSize=12f; setTextColor(Color.GRAY) }; editorPage.addView(pitchView)
        status=TextView(this).apply { textSize=12f; setTextColor(teal); setPadding(0,6,0,6) }; editorPage.addView(status)
        val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        recordButton=button("Start Performance"){ if(modelLoading){toast("Preparing offline voice recognition…")} else if(performanceFinalizing){toast("Saving the previous performance…")} else if(recordingActive)stopPerformance() else { showLyrics(); startPerformance() } }
        row.addView(recordButton,LinearLayout.LayoutParams(-1,-2))
        updateRecordButton(false)
        editorPage.addView(row)
        editorPage.addView(button("Back to writing"){ lyricScroll.visibility=View.GONE; lyricsBox.visibility=View.VISIBLE })
        libraryPage.addView(TextView(this).apply { text="MY SONGS & TAKES"; textSize=18f; setTextColor(teal); setPadding(0,12,0,8) })
        libraryPage.addView(button("Home"){homePage.visibility=View.VISIBLE;editorPage.visibility=View.GONE;libraryPage.visibility=View.GONE})
        libraryPage.addView(button("Create backup file"){createBackupPicker()})
        libraryPage.addView(button("Restore from backup file"){restoreBackupPicker()})
        libraryPage.addView(TextView(this).apply{text="Backups include your saved lyrics and audio recordings. Keep the backup somewhere safe.";textSize=12f;setTextColor(Color.GRAY);setPadding(4,2,4,10)})
        library=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        // Keep one outer scroll container so long recording libraries remain reachable.
        libraryPage.addView(library,LinearLayout.LayoutParams(-1,-2))
        setContentView(page)
        renderLibrary()
        status.text="Offline lyric recognition uses a speech model stored on this phone. The first setup needs internet to download it."
    }

    private fun showPage(showLibrary:Boolean){
        if(!::editorPage.isInitialized)return
        homePage.visibility=View.GONE
        editorPage.visibility=if(showLibrary)View.GONE else View.VISIBLE
        libraryPage.visibility=if(showLibrary)View.VISIBLE else View.GONE
        if(showLibrary)renderLibrary()
    }

    private fun button(label:String, action:()->Unit)=Button(this).apply { text=label; isAllCaps=false; setTextColor(teal); setOnClickListener{action()} }
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_SHORT).show()

    private fun updateRecordButton(isRecording:Boolean) {
        if(!::recordButton.isInitialized)return
        recordButton.text=if(isRecording) "■ Stop Performance" else "▶ Start Performance"
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

    private fun renameSong(index:Int,oldTitle:String) {
        val songs=JSONArray(prefs.getString("songs","[]"))
        if(index !in 0 until songs.length())return
        val input=EditText(this).apply {
            setSingleLine(true)
            setText(oldTitle)
            setSelection(text.length)
            hint="New song title"
        }
        val dialog=AlertDialog.Builder(this)
            .setTitle("Rename saved song")
            .setMessage("This changes the song's title only. Your lyrics and recordings stay as they are.")
            .setView(input)
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Save name",null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newTitle=input.text.toString().trim()
                if(newTitle.isBlank()) {
                    input.error="Enter a song title"
                    return@setOnClickListener
                }
                for(i in 0 until songs.length()) {
                    if(i!=index && songs.getJSONObject(i).optString("title").equals(newTitle,true)) {
                        input.error="A song with that title already exists"
                        return@setOnClickListener
                    }
                }
                songs.getJSONObject(index).put("title",newTitle)
                prefs.edit().putString("songs",songs.toString()).apply()
                if(editingSongTitle==oldTitle)editingSongTitle=newTitle
                renderLibrary()
                dialog.dismiss()
                toast("Song renamed.")
            }
        }
        dialog.show()
    }

    private fun renderLibrary() {
        if(!::library.isInitialized)return
        library.removeAllViews()
        val songs=JSONArray(prefs.getString("songs","[]"))
        val songTitles=(0 until songs.length()).map{songs.getJSONObject(it).optString("title")}
        library.addView(TextView(this).apply{
            text="MY LYRICS";textSize=14f;setTypeface(null,Typeface.BOLD);setTextColor(teal);setPadding(2,12,2,2)
        })
        val songPicker=Spinner(this)
        val songOptions=if(songTitles.isEmpty()) listOf("No saved lyrics yet") else songTitles
        songPicker.adapter=android.widget.ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,songOptions)
        library.addView(songPicker,LinearLayout.LayoutParams(-1,-2))
        val songActions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        songActions.addView(button("Edit"){ 
            val index=songPicker.selectedItemPosition
            if(index in 0 until songs.length()){
                val song=songs.getJSONObject(index);val title=song.optString("title")
                titleBox.setText(title);lyricsBox.setText(song.optString("lyrics"));editingSongTitle=title
                showPage(false);toast("Edit your lyrics, then tap Save song.")
            }else toast("Save a song first.")
        },LinearLayout.LayoutParams(0,-2,1f))
        songActions.addView(button("Rename"){
            val index=songPicker.selectedItemPosition
            if(index in 0 until songs.length())renameSong(index,songs.getJSONObject(index).optString("title")) else toast("Save a song first.")
        },LinearLayout.LayoutParams(0,-2,1f))
        songActions.addView(button("Delete"){
            val index=songPicker.selectedItemPosition
            if(index in 0 until songs.length()){
                val title=songs.getJSONObject(index).optString("title")
                AlertDialog.Builder(this@MainActivity).setTitle("Delete song?").setMessage("Delete this song and its lyrics?").setNegativeButton("Cancel",null).setPositiveButton("Delete"){_,_->deleteSong(title)}.show()
            }else toast("Save a song first.")
        },LinearLayout.LayoutParams(0,-2,1f))
        library.addView(songActions)

        val takes=filesDir.listFiles()?.filter{it.name.startsWith("take_")&&it.extension in setOf("m4a","wav")}?.sortedByDescending{it.lastModified()}?: emptyList()
        library.addView(TextView(this).apply{text="RECORDED AUDIO · "+takes.size;textSize=14f;setTypeface(null,Typeface.BOLD);setTextColor(teal);setPadding(2,16,2,6)})
        val recordingBox=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(8,8,8,8)
            setBackgroundColor(Color.rgb(240,249,247))
        }
        if(player!=null && playingFile!=null) {
            val playerPanel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(8,6,8,10)}
            playerPanel.addView(TextView(this).apply{text="NOW PLAYING";textSize=11f;setTextColor(teal)})
            playerPanel.addView(TextView(this).apply{text=java.io.File(playingFile!!).name;textSize=14f;setTextColor(Color.DKGRAY)})
            val seek=SeekBar(this).apply{max=player?.duration?.coerceAtLeast(1)?:1;progress=player?.currentPosition?:0}
            playbackSeek=seek
            seek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onProgressChanged(sb:SeekBar?,progress:Int,fromUser:Boolean){if(fromUser)playbackTime?.text=formatTime(progress)+" / "+formatTime(player?.duration?:0)}
                override fun onStartTrackingTouch(sb:SeekBar?){}
                override fun onStopTrackingTouch(sb:SeekBar?){player?.seekTo(sb?.progress?:0)}
            })
            playerPanel.addView(seek)
            val time=TextView(this).apply{text="00:00 / "+formatTime(player?.duration?:0);textSize=12f;setTextColor(Color.GRAY)}
            playbackTime=time
            playerPanel.addView(time)
            val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
            actions.addView(button(if(player?.isPlaying==true)"Ⅱ Pause" else "▶ Resume"){if(player?.isPlaying==true)player?.pause() else player?.start();renderLibrary();handler.removeCallbacks(playbackTick);handler.post(playbackTick)},LinearLayout.LayoutParams(0,-2,1f))
            actions.addView(button("Stop"){player?.release();player=null;playingFile=null;playbackSeek=null;playbackTime=null;handler.removeCallbacks(playbackTick);renderLibrary()},LinearLayout.LayoutParams(0,-2,1f))
            playerPanel.addView(actions)
            recordingBox.addView(playerPanel)
            handler.removeCallbacks(playbackTick);handler.post(playbackTick)
        } else { playbackSeek=null;playbackTime=null;handler.removeCallbacks(playbackTick) }

        val recordingList=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        if(takes.isEmpty()) recordingList.addView(TextView(this).apply{text="No recordings yet.";textSize=13f;setTextColor(Color.GRAY);setPadding(6,12,6,12)})
        takes.forEachIndexed{index,file->
            val item=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(4,6,4,8)}
            item.addView(TextView(this).apply{text=(index+1).toString()+".  "+recordingDateLabel(file).removePrefix("Recorded: ");textSize=12f;setTextColor(Color.GRAY)})
            val takeRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
            takeRow.addView(button(if(playingFile==file.absolutePath && player?.isPlaying==true)"Ⅱ Play" else "▶ Play"){playTake(file)},LinearLayout.LayoutParams(0,-2,1f))
            takeRow.addView(button("Studio Enhance"){showGenreModifier(file)},LinearLayout.LayoutParams(0,-2,1f))
            takeRow.addView(button("Delete"){AlertDialog.Builder(this@MainActivity).setTitle("Delete recording?").setMessage("Permanently delete this saved take? This cannot be undone.").setNegativeButton("Cancel",null).setPositiveButton("Delete"){_,_->deleteTake(file)}.show()},LinearLayout.LayoutParams(0,-2,1f))
            item.addView(takeRow)
            item.addView(TextView(this).apply{text=file.name;textSize=10f;setTextColor(Color.DKGRAY);setPadding(4,0,4,0)})
            recordingList.addView(item)
            if(index< takes.lastIndex) recordingList.addView(View(this).apply{setBackgroundColor(Color.rgb(215,229,226));layoutParams=LinearLayout.LayoutParams(-1,1)})
        }
        val recordingScroll=ScrollView(this).apply{
            isVerticalScrollBarEnabled=true
            isFillViewport=true
            isNestedScrollingEnabled=true
            addView(recordingList,android.widget.FrameLayout.LayoutParams(-1,-2))
        }
        val scrollHeight=(260*resources.displayMetrics.density).toInt()
        recordingBox.addView(recordingScroll,LinearLayout.LayoutParams(-1,scrollHeight))
        library.addView(recordingBox,LinearLayout.LayoutParams(-1,-2))
    }

    // Modifier by Genre applies a reusable whole-mix preset plus user-adjustable processing controls.
    private data class GenreSound(val name:String,val tone:AutoTuneProcessor.ToneSettings)

    private fun genreSounds():List<GenreSound> = listOf(
        GenreSound("Pop",AutoTuneProcessor.ToneSettings(bassDb=1.0,trebleDb=1.5,reverb=5.0,compression=22.0,formantColor=18.0,presenceDb=2.0,deEss=0.18,saturation=0.025)),
        GenreSound("Hip-Hop",AutoTuneProcessor.ToneSettings(bassDb=3.0,trebleDb=1.0,reverb=3.0,compression=38.0,formantColor=10.0,presenceDb=2.5,deEss=0.12,saturation=0.08)),
        GenreSound("R&B",AutoTuneProcessor.ToneSettings(bassDb=1.8,trebleDb=1.8,reverb=12.0,compression=28.0,formantColor=28.0,presenceDb=1.8,deEss=0.25,saturation=0.025)),
        GenreSound("Rock",AutoTuneProcessor.ToneSettings(bassDb=1.5,trebleDb=2.0,reverb=4.0,compression=35.0,formantColor=12.0,presenceDb=2.8,deEss=0.12,saturation=0.09)),
        GenreSound("Country",AutoTuneProcessor.ToneSettings(bassDb=0.5,trebleDb=2.0,reverb=10.0,compression=22.0,formantColor=16.0,presenceDb=2.0,deEss=0.18,saturation=0.015)),
        GenreSound("EDM",AutoTuneProcessor.ToneSettings(bassDb=3.5,trebleDb=2.5,reverb=8.0,compression=42.0,formantColor=8.0,presenceDb=2.2,deEss=0.10,saturation=0.10)),
        GenreSound("Acoustic",AutoTuneProcessor.ToneSettings(bassDb=-0.5,trebleDb=1.0,reverb=8.0,compression=16.0,formantColor=12.0,presenceDb=1.4,deEss=0.15,saturation=0.0)),
        GenreSound("Emo",AutoTuneProcessor.ToneSettings(bassDb=0.8,trebleDb=1.5,reverb=15.0,compression=26.0,formantColor=24.0,presenceDb=2.2,deEss=0.25,saturation=0.035)),
        GenreSound("Jazz",AutoTuneProcessor.ToneSettings(bassDb=0.0,trebleDb=1.2,reverb=7.0,compression=14.0,formantColor=10.0,presenceDb=1.0,deEss=0.12,saturation=0.0)),
        GenreSound("Metal",AutoTuneProcessor.ToneSettings(bassDb=2.0,trebleDb=1.6,reverb=3.0,compression=48.0,formantColor=8.0,presenceDb=3.0,deEss=0.10,saturation=0.14)),
        GenreSound("Gospel",AutoTuneProcessor.ToneSettings(bassDb=0.8,trebleDb=1.8,reverb=20.0,compression=24.0,formantColor=22.0,presenceDb=2.0,deEss=0.22,saturation=0.015)),
        GenreSound("Indie",AutoTuneProcessor.ToneSettings(bassDb=0.4,trebleDb=1.0,reverb=11.0,compression=20.0,formantColor=16.0,presenceDb=1.6,deEss=0.16,saturation=0.045))
    )

    /**
     * One-tap automatic enhancement. No genre selection or manual audio controls:
     * the on-device engine analyzes the take and chooses a restrained whole-mix chain.
     */
    private fun showGenreModifier(source: File) {
        if(source.extension.lowercase()!="wav"){
            toast("Studio Enhance currently supports WAV recordings. Your original is unchanged.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("♫ Automatic Studio Enhance")
            .setMessage("I'll balance the recording automatically with gentle EQ, vocal presence, dynamic control, de-essing, light warmth, room ambience and safe output level. No sliders or technical choices. Your original recording stays untouched.")
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Enhance recording"){_,_->
                var output=File(filesDir,source.nameWithoutExtension+"_studio_enhanced.wav")
                var suffix=2
                while(output.exists()){
                    output=File(filesDir,source.nameWithoutExtension+"_studio_enhanced_"+suffix+".wav")
                    suffix++
                }
                toast("Analyzing and enhancing your recording…")
                thread(name="JacksREPOAutoStudioEnhance"){
                    try{
                        AutoTuneProcessor.autoEnhance(source,output)
                        runOnUiThread{
                            if(output.exists()){
                                toast("Studio-enhanced copy saved. Original preserved.")
                                renderLibrary()
                            }else toast("Studio Enhance could not save the new copy.")
                        }
                    }catch(e:Exception){
                        output.delete()
                        runOnUiThread{toast("Studio Enhance failed: "+(e.message?:"audio processing error"))}
                    }
                }
            }.show()
    }

    private fun showAutoTuneSettings(source: File) {
        if (source.extension.lowercase() != "wav") {
            toast("Auto-Tune currently works with WAV recordings. Make a new performance recording first.")
            return
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 12)
            setBackgroundColor(Color.WHITE)
        }
        panel.addView(TextView(this).apply {
            text = "♫ Auto-Tune Engine 3.0"
            textSize = 22f
            setTextColor(teal)
            setTypeface(null, Typeface.BOLD)
        })
        panel.addView(TextView(this).apply {
            text = "Choose your finish and emotion. Engine 3.0 analyzes the pitch, avoids uncertain notes, smooths correction, and builds a new version without changing your original recording."
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, 8, 0, 14)
        })
        panel.addView(TextView(this).apply {
            text = "CHOOSE YOUR SOUND"
            textSize = 12f
            setTextColor(teal)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 4, 0, 6)
        })
        val finishes = listOf("Natural & Expressive", "Polished Pop / Hip-Hop")
        val finishMode = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, finishes)
            setSelection(0)
        }
        panel.addView(finishMode)

        panel.addView(TextView(this).apply {
            text = "CHOOSE YOUR EMOTION"
            textSize = 12f
            setTextColor(teal)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 10, 0, 6)
        })
        val styles = listOf("Natural", "Cheerful", "Romantic", "Sad", "Dark / Depressive", "Sultry", "Angry", "Confident", "Dreamy", "Heartbroken", "Dramatic", "Robotic")
        val descriptions = listOf(
            "A balanced, natural sound with gentle pitch correction.",
            "A brighter, lively sound with extra sparkle.",
            "A warm, smooth sound with a soft, spacious finish.",
            "A softer, darker sound with restrained effects.",
            "A deep, subdued sound with a darker tone.",
            "A warm, smooth and intimate sound.",
            "A stronger, edgier sound with more presence.",
            "A clear, forward sound with firm dynamics.",
            "A soft, airy and spacious sound.",
            "A warm, emotional sound with a longer tail.",
            "A bold, expressive sound with stronger presence.",
            "A clean, synthetic sound with stronger pitch correction."
        )
        val style = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, styles)
            setSelection(0)
        }
        panel.addView(style)
        val description = TextView(this).apply {
            text = descriptions[0]
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, 8, 0, 12)
        }
        panel.addView(description)
        style.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                description.text = descriptions[position]
            }
        }
        panel.addView(TextView(this).apply {
            text = "That's all you need to choose. Key detection, pitch correction, EQ, reverb and other effects happen automatically. Your original recording is always kept safe."
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, 4, 0, 8)
        })

        val progress = TextView(this).apply {
            text = ""
            textSize = 13f
            setTextColor(teal)
            setPadding(0, 8, 0, 4)
            visibility = View.GONE
        }
        panel.addView(progress)

        val dialog = AlertDialog.Builder(this)
            .setView(panel)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create My Vocal Version", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selectedStyle = style.selectedItemPosition
                val styleName = styles[selectedStyle]
                val configs = arrayOf(
                    intArrayOf(50,50,12,20,10,45),
                    intArrayOf(55,68,18,28,8,45),
                    intArrayOf(60,56,30,22,20,38),
                    intArrayOf(42,42,24,20,18,35),
                    intArrayOf(38,35,12,35,35,42),
                    intArrayOf(58,54,32,18,12,32),
                    intArrayOf(48,65,8,48,42,50),
                    intArrayOf(55,62,10,42,30,48),
                    intArrayOf(54,58,48,16,12,32),
                    intArrayOf(42,45,38,25,25,38),
                    intArrayOf(48,60,28,38,38,48),
                    intArrayOf(50,58,8,35,45,100)
                )
                val v = configs[selectedStyle]
                val finish = if (finishMode.selectedItemPosition == 1) VocalPitchEngine.Finish.POLISHED else VocalPitchEngine.Finish.NATURAL
                val baseAmount = v[5] / 100.0
                val amount = if (finish == VocalPitchEngine.Finish.POLISHED && selectedStyle != 11) maxOf(baseAmount, 0.78) else baseAmount
                val tone = AutoTuneProcessor.ToneSettings(
                    bassDb = (v[0] - 50) * 0.12,
                    trebleDb = (v[1] - 50) * 0.12,
                    reverb = if (finish == VocalPitchEngine.Finish.POLISHED) v[2] * 0.88 else v[2].toDouble(),
                    compression = if (finish == VocalPitchEngine.Finish.POLISHED) (v[3] + 18).coerceAtMost(72).toDouble() else v[3].toDouble(),
                    formantColor = v[4].toDouble(),
                    presenceDb = (when(selectedStyle) { 1,6,7,10 -> 2.5; 2,5 -> 1.2; 3,4,9 -> -1.0; else -> 0.0 } + if (finish == VocalPitchEngine.Finish.POLISHED) 1.0 else 0.0),
                    deEss = (when(selectedStyle) { 1,6,10 -> 0.45; 2,5,7 -> 0.65; else -> 0.35 } + if (finish == VocalPitchEngine.Finish.POLISHED) 0.10 else 0.0).coerceAtMost(1.0),
                    saturation = (when(selectedStyle) { 4,6,11 -> 0.18; 7,10 -> 0.10; else -> 0.04 } + if (finish == VocalPitchEngine.Finish.POLISHED) 0.05 else 0.0).coerceAtMost(0.35)
                )

                style.isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                progress.visibility = View.VISIBLE
                progress.text = "Listening for the song's notes and preparing your vocal style…"

                thread(name = "JacksREPOAutoTune") {
                    try {
                        // Key/scale estimation is intentionally automatic and hidden from the user.
                        val estimate = AutoTuneProcessor.detectKey(source)
                        val effectiveScale = if (estimate.confidence >= 0.08) estimate.scale else 2
                        var output = File(filesDir, source.nameWithoutExtension + "_" + styleName.lowercase().replace(Regex("[^a-z0-9]+"), "_") + ".wav")
                        var suffix = 2
                        while (output.exists()) {
                            output = File(filesDir, source.nameWithoutExtension + "_" + styleName.lowercase().replace(Regex("[^a-z0-9]+"), "_") + "_" + suffix + ".wav")
                            suffix++
                        }
                        val corrected = AutoTuneProcessor.process(source, output, estimate.key, effectiveScale, amount, tone, 0, finish)
                        runOnUiThread {
                            if (!isFinishing) {
                                dialog.dismiss()
                                renderLibrary()
                                val finishLabel = if (finish == VocalPitchEngine.Finish.POLISHED) "Polished" else "Natural"
                                toast(if (corrected > 0) "$finishLabel $styleName version saved · pitch correction applied." else "$finishLabel $styleName version saved.")
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            if (!isFinishing) {
                                style.isEnabled = true
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                                progress.text = "Couldn't create the vocal version. Please try again."
                                toast("Audio processing failed: " + (e.message ?: "audio error"))
                            }
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun deleteTake(file:java.io.File){
        if(playingFile==file.absolutePath){
            try{player?.release()}catch(_:Exception){}
            player=null;playingFile=null;playbackSeek=null;playbackTime=null
            popupPlaybackSeek?.progress=0;popupPlaybackSeek?.isEnabled=false
            popupPlaybackTime?.text="00:00 / 00:00";popupPlaybackButton?.text="▶ Play recording"
            handler.removeCallbacks(playbackTick)
        }
        val deleted=try{file.delete()}catch(_:Exception){false}
        if(deleted){renderLibrary();toast("Recording deleted from this phone.")}
        else toast("Could not delete this recording.")
    }


    private fun createBackupPicker(){
        val intent=Intent(Intent.ACTION_CREATE_DOCUMENT).apply{
            addCategory(Intent.CATEGORY_OPENABLE)
            type="application/zip"
            putExtra(Intent.EXTRA_TITLE,"JacksRePO-backup-"+java.text.SimpleDateFormat("yyyyMMdd-HHmm",java.util.Locale.US).format(java.util.Date())+".zip")
        }
        startActivityForResult(intent,backupCreateRequest)
    }

    private fun restoreBackupPicker(){
        AlertDialog.Builder(this).setTitle("Restore Hearthsong backup?")
            .setMessage("This adds songs and recordings from a backup to this app. Existing songs and recordings will not be deleted.")
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Choose backup"){_,_->
                val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).apply{
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type="application/zip"
                    putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("application/zip","application/octet-stream"))
                }
                startActivityForResult(intent,backupRestoreRequest)
            }.show()
    }

    private fun writeBackup(uri:android.net.Uri){
        try{
            val songs=prefs.getString("songs","[]")?:"[]"
            val takes=filesDir.listFiles()?.filter{it.name.startsWith("take_")&&it.extension in setOf("m4a","wav")}?: emptyList()
            val metadata=JSONObject().put("app","Hearthsong").put("formatVersion",1).put("createdAt",System.currentTimeMillis()).put("songs",JSONArray(songs))
            val names=JSONArray()
            takes.forEach{names.put(it.name)}
            metadata.put("recordings",names)
            contentResolver.openOutputStream(uri)?.use{raw->
                ZipOutputStream(raw).use{zip->
                    zip.putNextEntry(ZipEntry("hearthsong-backup.json"))
                    zip.write(metadata.toString().toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    takes.forEach{file->
                        zip.putNextEntry(ZipEntry("recordings/"+file.name))
                        file.inputStream().use{input->input.copyTo(zip)}
                        zip.closeEntry()
                    }
                }
            }?:throw java.io.IOException("Could not open the selected backup location.")
            toast("Backup created: "+takes.size+" recordings included.")
        }catch(e:Exception){toast("Backup failed: "+(e.message?:"unknown error"))}
    }

    private fun readBackup(uri:android.net.Uri){
        val tempDir=java.io.File(cacheDir,"hearthsong_restore_"+System.currentTimeMillis())
        try{
            if(!tempDir.mkdirs())throw java.io.IOException("Could not prepare temporary restore space.")
            var metadata:JSONObject?=null
            contentResolver.openInputStream(uri)?.use{raw->
                ZipInputStream(raw).use{zip->
                    var entry=zip.nextEntry
                    while(entry!=null){
                        if(!entry.isDirectory){
                            val name=entry.name
                            if(name=="hearthsong-backup.json"){
                                val text=zip.readBytes().toString(Charsets.UTF_8)
                                metadata=JSONObject(text)
                            }else if(name.startsWith("recordings/") && name.removePrefix("recordings/").matches(Regex("take_[0-9]+(?:_tuned[0-9]*)?(?:\\.m4a|\\.wav)"))){
                                val out=java.io.File(tempDir,name.substringAfterLast('/'))
                                out.outputStream().use{zip.copyTo(it)}
                            }
                        }
                        zip.closeEntry()
                        entry=zip.nextEntry
                    }
                }
            }?:throw java.io.IOException("Could not read the selected file.")
            val data=metadata?:throw java.io.IOException("This is not a Hearthsong backup.")
            if(data.optString("app")!="Hearthsong" || data.optInt("formatVersion",-1)!=1)throw java.io.IOException("This Hearthsong backup version is not supported.")
            val incoming=data.optJSONArray("songs")?:JSONArray()
            val existing=JSONArray(prefs.getString("songs","[]"))
            val merged=JSONArray()
            val seen=mutableSetOf<String>()
            for(i in 0 until existing.length()){
                val item=existing.optJSONObject(i)?:continue
                merged.put(item);seen.add(item.optString("title"))
            }
            for(i in 0 until incoming.length()){
                val item=incoming.optJSONObject(i)?:continue
                val title=item.optString("title")
                if(title !in seen){merged.put(item);seen.add(title)}
            }
            var restored=0
            tempDir.listFiles()?.forEach{source->
                var target=java.io.File(filesDir,source.name)
                if(target.exists()){
                    val extension=source.extension
                    val base=source.name.removeSuffix("."+extension)
                    var suffix=1
                    do{target=java.io.File(filesDir,base+"_restored"+suffix+"."+extension);suffix++}while(target.exists())
                }
                source.copyTo(target,false);restored++
            }
            prefs.edit().putString("songs",merged.toString()).apply()
            renderLibrary()
            toast("Restore complete: "+restored+" recordings added. Existing content kept.")
        }catch(e:Exception){toast("Restore failed: "+(e.message?:"file could not be read"))}
        finally{tempDir.deleteRecursively()}
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(resultCode!=RESULT_OK)return
        val uri=data?.data?:return
        when(requestCode){
            backupCreateRequest->writeBackup(uri)
            backupRestoreRequest->readBackup(uri)
        }
    }

    private fun deleteSong(title:String){
        val old=JSONArray(prefs.getString("songs","[]"));val kept=JSONArray()
        for(i in 0 until old.length())if(old.getJSONObject(i).optString("title")!=title)kept.put(old.getJSONObject(i))
        prefs.edit().putString("songs",kept.toString()).apply();renderLibrary();toast("Song deleted.")
    }

    private fun currentDateLabel():String = java.text.SimpleDateFormat("EEEE, MMMM d, yyyy", java.util.Locale.getDefault()).format(java.util.Date())

    private fun recordingDateLabel(file:File):String {
        // Recording filenames carry the capture-start epoch; tuned copies retain that timestamp.
        val epoch=Regex("^take_(\\d+)").find(file.name)?.groupValues?.getOrNull(1)?.toLongOrNull()
        val date=epoch?.let{java.util.Date(it)}
        return if(date!=null) "Recorded: "+java.text.SimpleDateFormat("MMM d, yyyy · h:mm a", java.util.Locale.getDefault()).format(date)
        else "Recording date unavailable"
    }

    private fun formatTime(milliseconds:Int):String {
        val total=(milliseconds.coerceAtLeast(0)/1000)
        return String.format(java.util.Locale.US,"%02d:%02d",total/60,total%60)
    }

    private fun playTake(file:java.io.File){
        try{
            if(playingFile==file.absolutePath && player?.isPlaying==true){
                player?.pause();renderLibrary();return
            }
            player?.release();player=null
            player=MediaPlayer().apply{
                setDataSource(file.absolutePath)
                setOnCompletionListener{it.release();player=null;playingFile=null;playbackSeek=null;playbackTime=null;popupPlaybackSeek?.progress=0;popupPlaybackButton?.text="▶ Play recording";popupPlaybackTime?.text="00:00 / 00:00";handler.removeCallbacks(playbackTick);renderLibrary()}
                prepare();start()
            }
            playingFile=file.absolutePath
            popupPlaybackSeek?.max=player?.duration?.coerceAtLeast(1)?:1
            popupPlaybackSeek?.isEnabled=true
            popupPlaybackTime?.text="00:00 / "+formatTime(player?.duration?:0)
            popupPlaybackButton?.text="Ⅱ Pause recording"
            handler.removeCallbacks(playbackTick);handler.post(playbackTick)
            renderLibrary()
        }catch(e:Exception){player?.release();player=null;playingFile=null;toast("Could not play this recording.")}
    }

    private fun showLyrics(performanceMode:Boolean=true){
        popupRecordingLabel = if(performanceMode) "▶ Start Performance" else "▶ Start Recording"
        if(lyricsBox.text.isBlank()){toast("Write lyrics first.");return}
        lyricLines=lyricsBox.text.toString().lines()
        currentLineIndex=0
        val dialog=Dialog(this)
        val shell=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(250,252,251));setPadding(20,18,20,18)}
        val heading=TextView(this).apply{text=titleBox.text.toString().ifBlank{"Your song"};textSize=22f;setTypeface(null,Typeface.BOLD);setTextColor(Color.rgb(25,55,52));setPadding(4,10,4,14)}
        shell.addView(heading)
        lyricScrollSpeed=prefs.getInt("lyric_scroll_speed",2).coerceIn(1,8)
        val speedPanel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(4,2,4,8)}
        val speedLabel=TextView(this).apply{text="LYRIC SCROLL SPEED · "+when(lyricScrollSpeed){1->"VERY SLOW";2->"SLOW";3->"GENTLE";4->"MEDIUM";5->"BRISK";6->"FAST";7->"FASTER";else->"VERY FAST"};textSize=12f;setTextColor(teal)}
        speedPanel.addView(speedLabel)
        val speedControl=SeekBar(this).apply{max=7;progress=lyricScrollSpeed-1}
        speedControl.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(seekBar:SeekBar?,progress:Int,fromUser:Boolean){
                lyricScrollSpeed=progress+1
                val names=listOf("VERY SLOW","SLOW","GENTLE","MEDIUM","BRISK","FAST","FASTER","VERY FAST")
                speedLabel.text="LYRIC SCROLL SPEED · "+names[progress]
                if(fromUser)prefs.edit().putInt("lyric_scroll_speed",lyricScrollSpeed).apply()
            }
            override fun onStartTrackingTouch(seekBar:SeekBar?){}
            override fun onStopTrackingTouch(seekBar:SeekBar?){}
        })
        speedPanel.addView(speedControl)
        shell.addView(speedPanel)
        val scroll=ScrollView(this).apply{isFillViewport=true;setBackgroundColor(Color.rgb(250,252,251));clipToPadding=false}
        val display=TextView(this).apply{textSize=26f;gravity=android.view.Gravity.CENTER;setTextColor(Color.rgb(145,158,155));setPadding(20,190,20,250);setLineSpacing(20f,1.08f);text=lyricLines.joinToString("\n\n")}
        scroll.addView(display,android.widget.FrameLayout.LayoutParams(-1,-2))
        shell.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val liveStatus=TextView(this).apply{text=if(performanceMode)"Ready. Start Performance records your voice and guitar while following lyrics." else "Ready. Start Recording to capture your singing while following lyrics.";textSize=12f;setTextColor(teal);setPadding(4,8,4,4)}
        shell.addView(liveStatus)
        val liveTimer=TextView(this).apply{text="REC  00:00";textSize=16f;setTypeface(null,Typeface.BOLD);setTextColor(Color.rgb(190,45,50));gravity=android.view.Gravity.CENTER;setPadding(4,6,4,6)}
        shell.addView(liveTimer)
        val takes=filesDir.listFiles()?.filter{it.name.startsWith("take_")&&it.extension in setOf("m4a","wav")}?.sortedByDescending{it.lastModified()}?: emptyList()
        val playbackPanel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(10,8,10,8);setBackgroundColor(Color.rgb(239,248,246))}
        playbackPanel.addView(TextView(this).apply{text="YOUR RECORDINGS";textSize=11f;setTextColor(teal);setTypeface(null,Typeface.BOLD)})
        val takeSpinner=Spinner(this)
        val takeNames=if(takes.isEmpty()) listOf("No recordings yet") else takes.map{it.name}
        takeSpinner.adapter=android.widget.ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,takeNames)
        popupTakeSpinner=takeSpinner
        playbackPanel.addView(takeSpinner)
        val playbackSeek=SeekBar(this).apply{max=player?.duration?.coerceAtLeast(1)?:1;progress=player?.currentPosition?:0;isEnabled=player!=null}
        popupPlaybackSeek=playbackSeek
        playbackSeek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(sb:SeekBar?,progress:Int,fromUser:Boolean){if(fromUser)popupPlaybackTime?.text=formatTime(progress)+" / "+formatTime(player?.duration?:0)}
            override fun onStartTrackingTouch(sb:SeekBar?){}
            override fun onStopTrackingTouch(sb:SeekBar?){player?.seekTo(sb?.progress?:0)}
        })
        playbackPanel.addView(playbackSeek)
        val playbackTimeLabel=TextView(this).apply{text="00:00 / "+formatTime(player?.duration?:0);textSize=12f;setTextColor(Color.GRAY);gravity=android.view.Gravity.CENTER}
        popupPlaybackTime=playbackTimeLabel
        playbackPanel.addView(playbackTimeLabel)
        val playbackActions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        val playbackButton=button("▶ Play recording"){ 
            if(player?.isPlaying==true){player?.pause();popupPlaybackButton?.text="▶ Play recording"}
            else if(player!=null){player?.start();popupPlaybackButton?.text="Ⅱ Pause recording";handler.removeCallbacks(playbackTick);handler.post(playbackTick)}
            else if(takes.isNotEmpty()){playTake(takes.getOrNull(takeSpinner.selectedItemPosition)?:takes.first());popupPlaybackSeek?.isEnabled=true;handler.removeCallbacks(playbackTick);handler.post(playbackTick)}
        }
        popupPlaybackButton=playbackButton
        playbackActions.addView(playbackButton,LinearLayout.LayoutParams(0,-2,1f))
        playbackActions.addView(button("Stop"){player?.stop();player?.release();player=null;playingFile=null;handler.removeCallbacks(playbackTick);popupPlaybackSeek?.progress=0;popupPlaybackSeek?.isEnabled=false;popupPlaybackTime?.text="00:00 / 00:00";popupPlaybackButton?.text="▶ Play recording"},LinearLayout.LayoutParams(0,-2,1f))
        playbackPanel.addView(playbackActions)
        if(takes.isEmpty()) playbackPanel.alpha=0.85f
        val tuneControl=button(if(liveAutoTuneEnabled)"Auto-Tune ON" else "Auto-Tune OFF"){
            liveAutoTuneEnabled=!liveAutoTuneEnabled
            if(!liveAutoTuneEnabled)liveTuneEngine.reset()
            popupAutoTuneButton?.text=if(liveAutoTuneEnabled)"Auto-Tune ON" else "Auto-Tune OFF"
            popupStatus?.text=if(liveAutoTuneEnabled)"Recording with live pitch correction ON." else "Live pitch correction OFF. Recording continues."
            toast(if(liveAutoTuneEnabled)"Auto-Tune ON" else "Auto-Tune OFF")
        }
        popupAutoTuneButton=tuneControl
        val tuneControls=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        tuneControls.addView(tuneControl,LinearLayout.LayoutParams(0,-2,2f))
        tuneControls.addView(button("⚙ Tune settings"){showLiveAutoTuneSettings()},LinearLayout.LayoutParams(0,-2,1f))
        shell.addView(tuneControls,LinearLayout.LayoutParams(-1,-2))
        val karaokeControls=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        val monitorControl=button("🎧 Monitor OFF"){
            liveMonitorEnabled=!liveMonitorEnabled
            if(liveMonitorEnabled){if(recordingActive)startLiveMonitor();popupMonitorButton?.text="🎧 Monitor ON";popupStatus?.text="Live monitoring enabled. Use headphones to prevent feedback."}
            else{stopLiveMonitor();popupMonitorButton?.text="🎧 Monitor OFF";popupStatus?.text="Live monitoring off. Recording continues."}
        }
        popupMonitorButton=monitorControl
        karaokeControls.addView(monitorControl,LinearLayout.LayoutParams(0,-2,1f))
        karaokeControls.addView(button("🎤 Karaoke sound"){showKaraokeSettings()},LinearLayout.LayoutParams(0,-2,1f))
        shell.addView(karaokeControls,LinearLayout.LayoutParams(-1,-2))
        val controls=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        val performanceControl=button(popupRecordingLabel){if(modelLoading)toast("Preparing offline voice recognition…") else if(performanceFinalizing)toast("Saving the previous recording…") else if(recordingActive)stopPerformance() else startPerformance()}
        popupPerformanceButton=performanceControl
        controls.addView(performanceControl,LinearLayout.LayoutParams(0,-2,2f))
        controls.addView(button(if(performanceMode)"Close" else "Back to Home"){if(recordingActive)stopPerformance();dialog.dismiss()},LinearLayout.LayoutParams(0,-2,1f))
        shell.addView(controls)
        dialog.setContentView(shell)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(-1,-1)
        popupLyricScroll=scroll
        popupLyricText=display
        popupStatus=liveStatus
        popupTimer=liveTimer
        dialog.setOnDismissListener{handler.removeCallbacks(lyricAutoScrollTick);if(recordingActive)stopPerformance();popupLyricScroll=null;popupLyricText=null;popupStatus=null;popupTimer=null;popupPerformanceButton=null;popupPlaybackSeek=null;popupPlaybackTime=null;popupPlaybackButton=null;popupTakeSpinner=null;popupAutoTuneButton=null;popupMonitorButton=null;liveAutoTuneEnabled=false;liveMonitorEnabled=false;stopLiveMonitor();liveTuneEngine.reset()}
        renderActiveLyricLine(false)
    }

    private fun renderActiveLyricLine(animate:Boolean=true){
        val display=popupLyricText?:return
        val scroll=popupLyricScroll?:return
        display.text=lyricLines.joinToString("\n\n")
        scroll.post { scroll.scrollTo(0,0) }
    }
    private fun startPerformance(){

        if(recordingActive || performanceFinalizing || modelLoading)return
        if(lyricsBox.text.isBlank()){toast("Write or open a song before starting a performance.");return}
        if(popupLyricText==null)showLyrics()
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),43)
            return
        }
        stopPitchMonitor()
        popupStatus?.text="Preparing private, on-device lyric recognition. The first setup downloads a speech model once."
        status.text="Preparing on-device performance tools…"
        if(voskModel!=null){startPerformanceCapture();return}
        modelLoading=true
        startPerformanceCapture()
        thread(name="JacksRePOModelSetup"){
            try{
                val modelDir=File(filesDir,"vosk-model-small-en-us-0.15")
                if(!File(modelDir,"conf/model.conf").exists()){
                    val zipFile=File(cacheDir,"vosk-model-small-en-us-0.15.zip")
                    val connection=(URL("https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip").openConnection() as HttpURLConnection).apply{
                        connectTimeout=20000;readTimeout=60000;requestMethod="GET"
                    }
                    try{
                        connection.connect()
                        if(connection.responseCode !in 200..299)throw java.io.IOException("Model download returned HTTP "+connection.responseCode)
                        BufferedInputStream(connection.inputStream).use{input->FileOutputStream(zipFile).use{output->input.copyTo(output)}}
                    }finally{connection.disconnect()}
                    val unpackDir=File(cacheDir,"vosk-unpack-"+System.currentTimeMillis())
                    if(!unpackDir.mkdirs())throw java.io.IOException("Could not prepare model folder.")
                    try{
                        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use{zip->
                            var entry=zip.nextEntry
                            var total=0L
                            while(entry!=null){
                                val clean=entry.name.replace('\\','/')
                                val relative=clean.substringAfter('/',"")
                                if(relative.isNotBlank()){
                                    val out=File(unpackDir,relative)
                                    if(!out.canonicalPath.startsWith(unpackDir.canonicalPath+File.separator))throw java.io.IOException("Unsafe model archive entry.")
                                    if(entry.isDirectory)out.mkdirs() else{
                                        out.parentFile?.mkdirs()
                                        FileOutputStream(out).use{output->
                                            val buffer=ByteArray(8192);var count=inputRead(zip,buffer)
                                            while(count>0){total+=count;if(total>180L*1024L*1024L)throw java.io.IOException("Speech model archive is unexpectedly large.");output.write(buffer,0,count);count=inputRead(zip,buffer)}
                                        }
                                    }
                                }
                                zip.closeEntry();entry=zip.nextEntry
                            }
                        }
                        if(!File(unpackDir,"conf/model.conf").exists())throw java.io.IOException("The downloaded speech model was incomplete.")
                        if(modelDir.exists())modelDir.deleteRecursively()
                        if(!unpackDir.renameTo(modelDir)){
                            unpackDir.copyRecursively(modelDir,true);unpackDir.deleteRecursively()
                        }
                    }finally{unpackDir.deleteRecursively();zipFile.delete()}
                }
                val loaded=Model(modelDir.absolutePath)
                voskModel=loaded
                modelLoading=false
                runOnUiThread{
                    if(!isFinishing && popupLyricText!=null){
                        popupStatus?.text=if(recordingActive)"Offline model ready. Lyric matching will begin during this performance." else "Offline model ready for your next performance."
                    }
                }
            }catch(e:Exception){
                modelLoading=false
                recognitionEnabled=false
                runOnUiThread{
                    status.text="Offline lyric recognition could not be prepared: "+(e.message?:"unknown error")
                    if(!isFinishing && recordingActive){
                        popupStatus?.text="Recording continues. Offline lyric matching is unavailable right now."
                    }else updateRecordButton(false)
                }
            }
        }
    }

    private fun inputRead(input:java.io.InputStream,buffer:ByteArray):Int=input.read(buffer)

    private fun startPerformanceCapture(){
        popupPerformanceButton?.isEnabled=true
        if(recordingActive || performanceFinalizing)return
        val sampleRate=16000
        val minBuffer=AudioRecord.getMinBufferSize(sampleRate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
        if(minBuffer<=0){popupStatus?.text="Microphone is unavailable on this phone.";return}
        val bufferSize=maxOf(minBuffer*2,8192)
        val audio=try{AudioRecord(MediaRecorder.AudioSource.MIC,sampleRate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,bufferSize)}catch(e:Exception){
            popupStatus?.text="Could not open the microphone: "+(e.message?:"unknown error");return
        }
        if(audio.state!=AudioRecord.STATE_INITIALIZED){audio.release();popupStatus?.text="Microphone could not be initialized.";return}
        val stamp=System.currentTimeMillis()
        val raw=File(cacheDir,"take_$stamp.pcm")
        val final=File(filesDir,"take_$stamp.wav")
        performanceRawFile=raw;performanceFinalFile=final
        liveTuneSettings=loadLiveTuneSettings()
        liveTuneEngine.configure(liveTuneSettings)
        liveTuneEngine.reset()
        recordingActive=true;recognitionEnabled=true;lastRecognizedPhrase="";recordingStartedAt=stamp
        updateRecordButton(true)
        popupPerformanceButton?.isEnabled=true
        popupPerformanceButton?.text=if(popupRecordingLabel.contains("Recording"))"■ Stop Recording" else "■ Stop Performance"
        popupAutoTuneButton?.isEnabled=true
        popupTimer?.text="REC  00:00"
        popupStatus?.text=if(liveAutoTuneEnabled)"Recording locally with live pitch correction ON." else if(voskModel!=null)"Recording locally. Lyric matching runs on this same microphone audio." else if(modelLoading)"Recording locally. Preparing offline lyric matching in the background." else "Recording locally. Offline lyric matching is unavailable; audio recording will continue."
        status.text="Performance recording locally on this phone…"
        handler.removeCallbacks(timerTick);handler.post(timerTick)
        handler.removeCallbacks(lyricAutoScrollTick)
        popupLyricScroll?.scrollTo(0,0)
        handler.postDelayed(lyricAutoScrollTick,700)
        performanceAudio=audio
        if(liveMonitorEnabled)startLiveMonitor()
        performanceThread=thread(name="JacksRePOAudioCapture"){
            var recognizer:Recognizer?=null
            var recognitionFailed=false
            try{
                val initialModel=voskModel
                if(initialModel!=null){
                    recognizer=try{Recognizer(initialModel,16000.0f)}catch(_:Exception){recognitionFailed=true;recognitionEnabled=false;null}
                }
                audio.startRecording()
                FileOutputStream(raw).use{output->
                    val buffer=ByteArray(8192)
                    while(recordingActive){
                        val count=audio.read(buffer,0,buffer.size)
                        if(count>0){
                            val original=buffer.copyOf(count)
                            val recorded=if(liveAutoTuneEnabled)liveTuneEngine.process(original,16000) else original
                            output.write(recorded)
                            if(liveMonitorEnabled){val monitor=liveMonitorTrack;if(monitor!=null)try{val filtered=KaraokeLiveProcessor.process(recorded,karaokePresetIndex);monitor.write(filtered,0,filtered.size,AudioTrack.WRITE_BLOCKING)}catch(_:Exception){runOnUiThread{popupStatus?.text="Live monitor unavailable; recording continues."}}}
                            if(recognitionEnabled && recognizer==null){
                                val readyModel=voskModel
                                if(readyModel!=null){
                                    try{
                                        recognizer=Recognizer(readyModel,16000.0f)
                                        runOnUiThread{if(recordingActive)popupStatus?.text="Recording and following lyrics on this phone…"}
                                    }catch(_:Exception){
                                        recognitionFailed=true;recognitionEnabled=false
                                        runOnUiThread{popupStatus?.text="Still recording. Offline lyric matching could not start."}
                                    }
                                }
                            }
                            if(recognitionEnabled && recognizer!=null){
                                try{
                                    val result=if(recognizer.acceptWaveForm(original,count))recognizer.result else recognizer.partialResult
                                    val json=JSONObject(result)
                                    val words=json.optString("text").ifBlank{json.optString("partial")}
                                    if(words.isNotBlank())runOnUiThread{if(recordingActive)updateLyricsFromText(words)}
                                }catch(_:Exception){recognitionFailed=true;recognitionEnabled=false;try{recognizer.close()}catch(_:Exception){};recognizer=null
                                    runOnUiThread{popupStatus?.text="Still recording. Lyric matching paused after a recognition error."}
                                }
                            }
                        }else if(count<0 && recordingActive){recognitionEnabled=false;recognitionFailed=true;runOnUiThread{popupStatus?.text="Microphone input stopped. Saving the audio captured so far."};break}
                    }
                }
                if(recognizer==null)recognitionFailed=true
                if(!recognitionEnabled)recognitionFailed=true
                writeWav(raw,final,sampleRate)
                raw.delete()
                runOnUiThread{
                    performanceAudio=null;performanceThread=null;recordingActive=false;performanceFinalizing=false
                    handler.removeCallbacks(timerTick);updateRecordButton(false)
                    popupPerformanceButton?.isEnabled=true;popupPerformanceButton?.text=popupRecordingLabel;popupTimer?.text="REC  00:00";liveAutoTuneEnabled=false;popupAutoTuneButton?.isEnabled=true;popupAutoTuneButton?.text="Auto-Tune OFF"
                    popupStatus?.text=if(final.exists())"Performance saved on this phone. "+if(recognitionFailed)"Audio was saved; lyric matching was unavailable." else "Audio and lyric-following session saved." else "Recording could not be saved."
                    status.text=if(final.exists())"Performance saved locally: "+final.name else "Performance recording failed."
                    renderLibrary()
                }
            }catch(e:Exception){
                raw.delete()
                runOnUiThread{
                    performanceAudio=null;performanceThread=null;recordingActive=false;performanceFinalizing=false
                    handler.removeCallbacks(timerTick);updateRecordButton(false)
                    popupPerformanceButton?.isEnabled=true;popupPerformanceButton?.text=popupRecordingLabel
                    liveAutoTuneEnabled=false;popupAutoTuneButton?.isEnabled=true;popupAutoTuneButton?.text="Auto-Tune OFF"
                    popupStatus?.text="Recording stopped unexpectedly: "+(e.message?:"microphone error")
                    status.text="Performance recording failed."
                }
            }finally{
                try{recognizer?.close()}catch(_:Exception){}
                try{audio.stop()}catch(_:Exception){}
                audio.release()
            }
        }
    }

    // Each emotion selects a predefined musical key, scale and correction character.
    // These are creative starting points; they do not detect the backing track's key.
    private data class VoiceEmotionPreset(
        val name:String, val key:Int, val scale:Int, val strength:Int,
        val speed:Int, val finish:LiveAutoTuneEngine.Finish, val description:String
    )

    private val voiceEmotionPresets = listOf(
        VoiceEmotionPreset("😊 Happy",0,1,76,62,LiveAutoTuneEngine.Finish.POLISHED,"Bright C major · lively and clear"),
        VoiceEmotionPreset("💔 Sad",9,2,68,35,LiveAutoTuneEngine.Finish.NATURAL,"Tender A minor · gentle correction"),
        VoiceEmotionPreset("❤️ Romantic",5,1,66,38,LiveAutoTuneEngine.Finish.NATURAL,"Warm F major · smooth and intimate"),
        VoiceEmotionPreset("🔥 Confident",7,1,88,78,LiveAutoTuneEngine.Finish.POLISHED,"Strong G major · firm and focused"),
        VoiceEmotionPreset("😠 Angry",2,2,92,88,LiveAutoTuneEngine.Finish.POLISHED,"Intense D minor · tighter correction"),
        VoiceEmotionPreset("🌙 Dreamy",4,2,62,28,LiveAutoTuneEngine.Finish.NATURAL,"Soft E minor · relaxed and floating"),
        VoiceEmotionPreset("🌅 Hopeful",2,1,72,48,LiveAutoTuneEngine.Finish.NATURAL,"Open D major · gentle lift"),
        VoiceEmotionPreset("🖤 Dark",11,2,82,68,LiveAutoTuneEngine.Finish.POLISHED,"Moody B minor · controlled and focused")
    )

    private fun loadLiveTuneSettings():LiveAutoTuneEngine.Settings{
        val index=prefs.getInt("live_tune_emotion",0).coerceIn(0,voiceEmotionPresets.lastIndex)
        val preset=voiceEmotionPresets[index]
        return LiveAutoTuneEngine.Settings(
            key=preset.key, scale=preset.scale,
            strength=preset.strength/100.0, retuneSpeed=preset.speed/100.0,
            finish=preset.finish
        )
    }

    private fun startLiveMonitor(){
        if(liveMonitorTrack!=null)return
        try{
            val rate=16000
            val minimum=AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT)
            if(minimum<=0)throw IllegalStateException("Audio output unavailable")
            val track=AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum*2,4096)).setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build()
            if(track.state!=AudioTrack.STATE_INITIALIZED){track.release();throw IllegalStateException("Audio output could not initialize")}
            track.play();liveMonitorTrack=track;KaraokeLiveProcessor.reset()
        }catch(e:Exception){liveMonitorEnabled=false;popupMonitorButton?.text="🎧 Monitor OFF";popupStatus?.text="Live monitoring could not start. Recording remains available."}
    }
    private fun stopLiveMonitor(){val track=liveMonitorTrack;liveMonitorTrack=null;try{track?.pause();track?.flush();track?.stop()}catch(_:Exception){};try{track?.release()}catch(_:Exception){};KaraokeLiveProcessor.reset()}
    private fun showKaraokeSettings(){
        val names=listOf("Studio Vocal","Pop Karaoke","Ballad","Electronic / Robot")
        val details=listOf("Clean and warm, with gentle leveling and light room ambience.","Bright, forward vocals with a little extra space.","Soft tone, smooth leveling and a longer gentle reverb.","More synthetic presence and a stronger tuned character.")
        val selected=prefs.getInt("karaoke_preset",0).coerceIn(0,names.lastIndex)
        val picker=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,names);setSelection(selected)}
        val description=TextView(this).apply{text=details[selected];textSize=14f;setTextColor(teal);setPadding(2,12,2,8)}
        picker.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{override fun onNothingSelected(parent:AdapterView<*>?){};override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long){description.text=details[position]}}
        val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(22,8,22,4)}
        panel.addView(TextView(this).apply{text="Choose the sound you want while singing. Effects are automatic; no manual audio controls are needed.";textSize=14f;setTextColor(Color.DKGRAY)})
        panel.addView(picker);panel.addView(description)
        panel.addView(TextView(this).apply{text="Live effects are a lightweight phone-friendly monitor chain, not a professional studio plugin. Headphones are recommended. The finished recording can still receive its separate studio treatment.";textSize=12f;setTextColor(Color.GRAY);setPadding(0,12,0,4)})
        AlertDialog.Builder(this).setTitle("Karaoke Voice Filter").setView(panel).setNegativeButton("Cancel",null).setPositiveButton("Save sound"){_,_->karaokePresetIndex=picker.selectedItemPosition;prefs.edit().putInt("karaoke_preset",karaokePresetIndex).apply();KaraokeLiveProcessor.reset();toast(names[karaokePresetIndex]+" selected.")}.show()
    }
    private fun showLiveAutoTuneSettings(){
        val selected=prefs.getInt("live_tune_emotion",0).coerceIn(0,voiceEmotionPresets.lastIndex)
        val picker=Spinner(this).apply{
            adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,voiceEmotionPresets.map{it.name})
            setSelection(selected)
        }
        val description=TextView(this).apply{
            text=voiceEmotionPresets[selected].description
            textSize=14f;setTextColor(teal);setPadding(2,12,2,8)
        }
        picker.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
            override fun onNothingSelected(parent:AdapterView<*>?){}
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long){
                description.text=voiceEmotionPresets[position].description
            }
        }
        val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(22,8,22,4)}
        panel.addView(TextView(this).apply{
            text="Choose the feeling you want your vocal tuning to express. The app automatically selects its pitch target, scale and tuning character."
            textSize=14f;setTextColor(Color.DKGRAY);setPadding(0,0,0,8)
        })
        panel.addView(TextView(this).apply{text="VOICE EMOTION";textSize=12f;setTextColor(Color.GRAY);setPadding(0,10,0,3)})
        panel.addView(picker)
        panel.addView(description)
        panel.addView(TextView(this).apply{
            text="Important: each emotion uses a preset key and scale. If your guitar or instrumental is in a different key, the tuned voice may clash with it. The current recorder captures voice and guitar together and cannot identify the song key automatically."
            textSize=12f;setTextColor(Color.DKGRAY);setPadding(0,12,0,4)
        })
        AlertDialog.Builder(this).setTitle("Auto-Tune · Voice Emotion").setView(panel)
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Save emotion"){_,_->
                prefs.edit().putInt("live_tune_emotion",picker.selectedItemPosition).apply()
                liveTuneSettings=loadLiveTuneSettings()
                liveTuneEngine.configure(liveTuneSettings)
                liveTuneEngine.reset()
                toast("Voice emotion saved.")
            }.show()
    }

    private fun writeWav(raw:File,wav:File,sampleRate:Int){
        val dataLength=raw.length()
        FileOutputStream(wav).use{file->
            val out=BufferedOutputStream(file)
            val header=ByteArray(44)
            fun putInt(offset:Int,value:Int){for(i in 0..3)header[offset+i]=(value ushr (8*i)).toByte()}
            fun putShort(offset:Int,value:Int){header[offset]=(value and 255).toByte();header[offset+1]=((value ushr 8) and 255).toByte()}
            "RIFF".toByteArray(Charsets.US_ASCII).copyInto(header,0)
            putInt(4,(36+dataLength).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            "WAVE".toByteArray(Charsets.US_ASCII).copyInto(header,8)
            "fmt ".toByteArray(Charsets.US_ASCII).copyInto(header,12)
            putInt(16,16);putShort(20,1);putShort(22,1);putInt(24,sampleRate);putInt(28,sampleRate*2);putShort(32,2);putShort(34,16)
            "data".toByteArray(Charsets.US_ASCII).copyInto(header,36)
            putInt(40,dataLength.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            out.write(header)
            FileInputStream(raw).use{input->input.copyTo(out)}
            out.flush()
        }
    }

    private fun updateLyricsFromText(spoken:String){
        if(lyricLines.isEmpty())lyricLines=lyricsBox.text.toString().lines()
        if(lyricLines.isEmpty())return
        val normalized=spoken.lowercase().replace(Regex("[^\\p{L}\\p{N} ]")," ").trim()
        if(normalized.isBlank() || normalized==lastRecognizedPhrase)return
        lastRecognizedPhrase=normalized
        val tokens=normalized.split(Regex("\\s+")).filter{it.length>1}.distinct().takeLast(12)
        if(tokens.isEmpty())return
        val first=currentLineIndex.coerceAtLeast(0)
        val last=(currentLineIndex+3).coerceAtMost(lyricLines.lastIndex)
        var best=-1;var bestScore=0
        for(index in first..last){
            val words=lyricLines[index].lowercase().replace(Regex("[^\\p{L}\\p{N} ]")," ").split(Regex("\\s+")).filter{it.isNotBlank()}
            if(words.isEmpty())continue
            val score=tokens.count{token->words.any{word->word==token || (token.length>=4 && word.startsWith(token)) || (word.length>=4 && token.startsWith(word))}}
            val required=if(words.size<=3)1 else 2
            val adjusted=score*100-(index-currentLineIndex)*8
            if(score>=required && adjusted>bestScore){bestScore=adjusted;best=index}
        }
        // Lyric movement is now time-based auto-scroll; speech recognition no longer
        // controls highlighting or scroll position, so singing freely won't fight the screen.
    }

    private var lastRecognizedPhrase=""

    private fun stopPerformance(){
        if(!recordingActive)return
        recordingActive=false
        handler.removeCallbacks(lyricAutoScrollTick)
        performanceFinalizing=true
        recognitionEnabled=false
        try{performanceAudio?.stop()}catch(_:Exception){}
        popupStatus?.text="Finishing and saving your performance…"
        status.text="Finishing performance recording…"
        handler.removeCallbacks(timerTick)
        updateRecordButton(false)
        popupPerformanceButton?.text=popupRecordingLabel
        popupAutoTuneButton?.isEnabled=false
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

    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED){if(requestCode==43)startPerformance()}
        else status.text="Microphone permission is needed for this feature."
    }
    override fun onResume(){
        super.onResume()
        if(::homeDateView.isInitialized)homeDateView.text=currentDateLabel()
    }

    override fun onDestroy(){liveMonitorEnabled=false;stopLiveMonitor();if(recordingActive)stopPerformance();try{performanceThread?.join(1200)}catch(_:InterruptedException){};stopPitchMonitor();player?.release();player=null;try{voskModel?.close()}catch(_:Exception){};super.onDestroy()}
}
