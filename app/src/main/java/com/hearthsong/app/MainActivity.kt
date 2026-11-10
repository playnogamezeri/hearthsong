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
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
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
    private lateinit var youtubeLinkBox: EditText
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
    private var playbackSeek: SeekBar? = null
    private var playbackTime: TextView? = null
    private var popupPlaybackSeek: SeekBar? = null
    private var popupPlaybackTime: TextView? = null
    private var popupPlaybackButton: Button? = null
    private var popupTakeSpinner: Spinner? = null
    private var youtubeWebView: WebView? = null
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
    @Volatile private var recognitionEnabled = true
    private var performanceAudio: AudioRecord? = null
    private var performanceThread: Thread? = null
    private var performanceRawFile: File? = null
    private var performanceFinalFile: File? = null
    private var voskModel: Model? = null
    @Volatile private var pitchRunning = false
    private var audioRecord: AudioRecord? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val page = ScrollView(this).apply { isFillViewport=true; isVerticalScrollBarEnabled=true }
        val root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(16,18,16,20); setBackgroundColor(Color.WHITE) }
        page.addView(root)
        root.addView(TextView(this).apply { text="♫ Jack's RePO"; textSize=26f; setTextColor(teal) })
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
        youtubeLinkBox=EditText(this).apply{hint="Optional YouTube backing-track link";setSingleLine(true);inputType=android.text.InputType.TYPE_TEXT_VARIATION_URI}
        editorPage.addView(youtubeLinkBox)
        editorPage.addView(button("Play YouTube in app"){openYouTubeLink()})
        editorPage.addView(TextView(this).apply{text="Tip: use headphones so the YouTube music does not leak into your microphone recording.";textSize=12f;setTextColor(Color.GRAY);setPadding(2,2,2,8)})
        editorPage.addView(button("Save song"){ saveSong(); toast("Saved on this phone.") })
        editorPage.addView(button("New song"){editingSongTitle=null;titleBox.setText("");lyricsBox.setText("");youtubeLinkBox.setText("");titleBox.requestFocus();toast("Ready for a new song.")})
        editorPage.addView(button("Open lyric-following screen"){ showLyrics() })
        lyricScroll=ScrollView(this).apply { visibility=View.GONE; setBackgroundColor(Color.rgb(248,252,251)) }
        lyricView=TextView(this).apply { textSize=27f; setTextColor(Color.rgb(20,35,33)); gravity=android.view.Gravity.CENTER; setPadding(14,28,14,300); setLineSpacing(12f,1f) }
        lyricScroll.addView(lyricView)
        pitchView=TextView(this).apply { text="Pitch monitor: waiting"; textSize=12f; setTextColor(Color.GRAY) }; editorPage.addView(pitchView)
        status=TextView(this).apply { textSize=12f; setTextColor(teal); setPadding(0,6,0,6) }; editorPage.addView(status)
        val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        recordButton=button("Start Performance"){ showLyrics(); startPerformance() }
        row.addView(recordButton,LinearLayout.LayoutParams(-1,-2))
        updateRecordButton(false)
        editorPage.addView(row)
        editorPage.addView(button("Back to writing"){ lyricScroll.visibility=View.GONE; lyricsBox.visibility=View.VISIBLE })
        libraryPage.addView(TextView(this).apply { text="MY SONGS & TAKES"; textSize=18f; setTextColor(teal); setPadding(0,12,0,8) })
        libraryPage.addView(button("Create backup file"){createBackupPicker()})
        libraryPage.addView(button("Restore from backup file"){restoreBackupPicker()})
        libraryPage.addView(TextView(this).apply{text="Backups include your saved lyrics, YouTube links and audio recordings. Keep the backup somewhere safe.";textSize=12f;setTextColor(Color.GRAY);setPadding(4,2,4,10)})
        library=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        val listScroll=ScrollView(this).apply { isNestedScrollingEnabled=true; isVerticalScrollBarEnabled=true }
        listScroll.addView(library); libraryPage.addView(listScroll,LinearLayout.LayoutParams(-1,-2))
        setContentView(page)
        renderLibrary()
        status.text="Offline lyric recognition uses a speech model stored on this phone. The first setup needs internet to download it."
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
        recordButton.text=if(isRecording) "■ Stop Performance" else "▶ Start Performance"
        recordButton.setTextColor(if(isRecording) Color.WHITE else teal)
        recordButton.backgroundTintList=android.content.res.ColorStateList.valueOf(if(isRecording) Color.rgb(220,45,55) else Color.rgb(235,248,246))
        recordButton.isActivated=isRecording
    }

    private fun youtubeVideoId(raw:String):String? {
        return try {
            val uri=android.net.Uri.parse(if(raw.startsWith("https://")||raw.startsWith("http://"))raw else "https://"+raw)
            val host=uri.host?.lowercase()?.removePrefix("www.")
            if(host=="youtu.be") uri.lastPathSegment?.takeIf{it.matches(Regex("[A-Za-z0-9_-]{11}"))}
            else if(host in setOf("youtube.com","m.youtube.com","music.youtube.com")) {
                val id=uri.getQueryParameter("v") ?: uri.pathSegments.let { parts ->
                    val marker=parts.indexOfFirst{it in setOf("embed","shorts","live","v")}
                    if(marker>=0) parts.getOrNull(marker+1) else null
                }
                id?.takeIf{it.matches(Regex("[A-Za-z0-9_-]{11}"))}
            } else null
        } catch(_:Exception) { null }
    }

    private fun createYouTubeWebView(raw:String):WebView? {
        val id=youtubeVideoId(raw) ?: return null
        val web=WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled=true
            settings.domStorageEnabled=true
            settings.mediaPlaybackRequiresUserGesture=true
            settings.loadsImagesAutomatically=true
            settings.useWideViewPort=true
            settings.loadWithOverviewMode=true
            settings.mixedContentMode=android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient=WebViewClient()
            webChromeClient=WebChromeClient()
        }
        val html="""
            <!doctype html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
              <meta name="referrer" content="strict-origin-when-cross-origin">
            </head>
            <body style="margin:0;background:#000;overflow:hidden">
              <iframe
                width="100%" height="100%"
                src="https://www.youtube.com/embed/$id?playsinline=1&autoplay=0&rel=0&enablejsapi=1&origin=https%3A%2F%2Fplaynogamezeri.github.io"
                title="YouTube video player"
                frameborder="0"
                allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share"
                referrerpolicy="strict-origin-when-cross-origin"
                allowfullscreen></iframe>
            </body></html>
        """.trimIndent()
        web.loadDataWithBaseURL("https://playnogamezeri.github.io/",html,"text/html","UTF-8",null)
        youtubeWebView=web
        return web
    }

    private fun destroyYouTubeWebView() {
        try { youtubeWebView?.stopLoading(); youtubeWebView?.loadUrl("about:blank"); youtubeWebView?.destroy() } catch(_:Exception) {}
        youtubeWebView=null
    }

    private fun openYouTubeLink(){
        if(!::youtubeLinkBox.isInitialized){toast("Enter a YouTube link in the song editor first.");return}
        val raw=youtubeLinkBox.text.toString().trim()
        if(raw.isBlank()){toast("Paste a YouTube video link in the song editor first.");return}
        if(youtubeVideoId(raw)==null){toast("Please enter a valid YouTube video link.");return}
        val dialog=Dialog(this)
        val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.WHITE);setPadding(12,12,12,12)}
        panel.addView(TextView(this).apply{text="YouTube · Jack's RePO";textSize=18f;setTextColor(teal);setPadding(4,6,4,10)})
        val web=createYouTubeWebView(raw) ?: return
        panel.addView(web,LinearLayout.LayoutParams(-1,dp(230)))
        panel.addView(button("Close player"){dialog.dismiss()})
        dialog.setContentView(panel)
        dialog.show()
        dialog.window?.setLayout(-1,-2)
        dialog.setOnDismissListener{destroyYouTubeWebView()}
    }

    private fun dp(value:Int):Int=(value*resources.displayMetrics.density).toInt()

    private fun saveSong() {
        val title=titleBox.text.toString().trim().ifBlank{"Untitled song"}
        val all=JSONArray(prefs.getString("songs","[]")); val item=JSONObject().put("title",title).put("lyrics",lyricsBox.text.toString()).put("youtubeUrl",if(::youtubeLinkBox.isInitialized)youtubeLinkBox.text.toString() else "")
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
            row.addView(button("Edit: "+songTitle){titleBox.setText(songTitle);lyricsBox.setText(song.optString("lyrics"));youtubeLinkBox.setText(song.optString("youtubeUrl"));editingSongTitle=songTitle;showPage(false);toast("Edit your lyrics, then tap Save song.")},LinearLayout.LayoutParams(0,-2,2f))
            row.addView(button("Delete"){AlertDialog.Builder(this@MainActivity).setTitle("Delete song?").setMessage("Delete this song and its lyrics?").setNegativeButton("Cancel",null).setPositiveButton("Delete"){_,_->deleteSong(songTitle)}.show()},LinearLayout.LayoutParams(0,-2,1f))
            library.addView(row)
        }
        val takes=filesDir.listFiles()?.filter{it.name.startsWith("take_")&&it.extension in setOf("m4a","wav")}?: emptyList()
        library.addView(TextView(this).apply{text="Saved recordings: "+takes.size; textSize=12f; setTextColor(Color.GRAY);setPadding(0,12,0,4)})
        if(player!=null && playingFile!=null) {
            val playerPanel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(12,8,12,12);setBackgroundColor(Color.rgb(240,249,247))}
            playerPanel.addView(TextView(this).apply{text="NOW PLAYING";textSize=11f;setTextColor(teal)})
            playerPanel.addView(TextView(this).apply{text=java.io.File(playingFile!!).name;textSize=15f;setTextColor(Color.DKGRAY)})
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
            library.addView(playerPanel)
            handler.removeCallbacks(playbackTick);handler.post(playbackTick)
        } else { playbackSeek=null;playbackTime=null;handler.removeCallbacks(playbackTick) }
        takes.sortedByDescending{it.lastModified()}.forEach{file->
            val takeRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
            takeRow.addView(button(if(playingFile==file.absolutePath && player?.isPlaying==true) "Ⅱ  "+file.name else "▶  "+file.name){playTake(file)},LinearLayout.LayoutParams(0,-2,3f))
            takeRow.addView(button("Delete"){AlertDialog.Builder(this@MainActivity).setTitle("Delete recording?").setMessage("Permanently delete this saved take? This cannot be undone.").setNegativeButton("Cancel",null).setPositiveButton("Delete"){_,_->deleteTake(file)}.show()},LinearLayout.LayoutParams(0,-2,1f))
            library.addView(takeRow)
        }
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
                            }else if(name.startsWith("recordings/") && name.removePrefix("recordings/").matches(Regex("take_[0-9]+(?:\\.m4a|\\.wav)"))){
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

    private fun showLyrics(){
        if(lyricsBox.text.isBlank()){toast("Write lyrics first.");return}
        lyricLines=lyricsBox.text.toString().lines()
        currentLineIndex=0
        val dialog=Dialog(this)
        val shell=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(250,252,251));setPadding(20,18,20,18)}
        val heading=TextView(this).apply{text=titleBox.text.toString().ifBlank{"Your song"};textSize=22f;setTypeface(null,Typeface.BOLD);setTextColor(Color.rgb(25,55,52));setPadding(4,10,4,14)}
        shell.addView(heading)
        val backingUrl=youtubeLinkBox.text.toString().trim()
        if(backingUrl.isNotBlank()) {
            val video=createYouTubeWebView(backingUrl)
            if(video!=null) shell.addView(video,LinearLayout.LayoutParams(-1,dp(210)))
            else shell.addView(TextView(this).apply{text="The saved YouTube link is not a supported video URL.";textSize=12f;setTextColor(Color.GRAY)})
        }
        val scroll=ScrollView(this).apply{isFillViewport=true;setBackgroundColor(Color.rgb(250,252,251));clipToPadding=false}
        val display=TextView(this).apply{textSize=26f;gravity=android.view.Gravity.CENTER;setTextColor(Color.rgb(145,158,155));setPadding(20,190,20,250);setLineSpacing(20f,1.08f);text=lyricLines.joinToString("\n\n")}
        scroll.addView(display,android.widget.FrameLayout.LayoutParams(-1,-2))
        shell.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val liveStatus=TextView(this).apply{text="Ready. Start Performance records your voice while following lyrics on this phone.";textSize=12f;setTextColor(teal);setPadding(4,8,4,4)}
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
        shell.addView(playbackPanel)
        if(takes.isEmpty()) playbackPanel.alpha=0.85f
        val controls=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        val performanceControl=button("▶ Start Performance"){if(recordingActive)stopPerformance() else startPerformance()}
        popupPerformanceButton=performanceControl
        controls.addView(performanceControl,LinearLayout.LayoutParams(0,-2,2f))
        controls.addView(button("Close"){if(recordingActive)stopPerformance();dialog.dismiss()},LinearLayout.LayoutParams(0,-2,1f))
        shell.addView(controls)
        dialog.setContentView(shell)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(-1,-1)
        popupLyricScroll=scroll
        popupLyricText=display
        popupStatus=liveStatus
        popupTimer=liveTimer
        dialog.setOnDismissListener{if(recordingActive)stopPerformance();destroyYouTubeWebView();popupLyricScroll=null;popupLyricText=null;popupStatus=null;popupTimer=null;popupPerformanceButton=null;popupPlaybackSeek=null;popupPlaybackTime=null;popupPlaybackButton=null;popupTakeSpinner=null}
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
    private fun startPerformance(){
        if(recordingActive)return
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
                                val clean=entry.name.replace('\\\\','/')
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
                runOnUiThread{if(!isFinishing)startPerformanceCapture()}
            }catch(e:Exception){
                runOnUiThread{
                    popupStatus?.text="Recording could not start because the offline speech model was unavailable. Check internet once, then try again."
                    status.text="Could not prepare offline lyric recognition: "+(e.message?:"unknown error")
                    updateRecordButton(false)
                }
            }
        }
    }

    private fun inputRead(input:java.io.InputStream,buffer:ByteArray):Int=input.read(buffer)

    private fun startPerformanceCapture(){
        if(recordingActive)return
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
        recordingActive=true;recognitionEnabled=true;recordingStartedAt=stamp
        updateRecordButton(true)
        popupPerformanceButton?.text="■ Stop Performance"
        popupTimer?.text="REC  00:00"
        popupStatus?.text="Recording locally. Lyric matching runs on this same microphone audio."
        status.text="Performance recording locally on this phone…"
        handler.removeCallbacks(timerTick);handler.post(timerTick)
        performanceAudio=audio
        performanceThread=thread(name="JacksRePOAudioCapture"){
            var recognizer:Recognizer?=null
            var recognitionFailed=false
            try{
                recognizer=try{Recognizer(voskModel,16000.0f)}catch(_:Exception){recognitionFailed=true;null}
                audio.startRecording()
                FileOutputStream(raw).use{output->
                    val buffer=ByteArray(4096)
                    while(recordingActive){
                        val count=audio.read(buffer,0,buffer.size)
                        if(count>0){
                            output.write(buffer,0,count)
                            if(recognitionEnabled && recognizer!=null){
                                try{
                                    val result=if(recognizer.acceptWaveForm(buffer,count))recognizer.result else recognizer.partialResult
                                    val json=JSONObject(result)
                                    val words=json.optString("text").ifBlank{json.optString("partial")}
                                    if(words.isNotBlank())runOnUiThread{if(recordingActive)updateLyricsFromText(words)}
                                }catch(_:Exception){recognitionFailed=true;recognitionEnabled=false;try{recognizer.close()}catch(_:Exception){};recognizer=null
                                    runOnUiThread{popupStatus?.text="Still recording. Lyric matching paused after a recognition error."}
                                }
                            }
                        }else if(count<0 && recordingActive){recognitionEnabled=false;recognitionFailed=true}
                    }
                }
                writeWav(raw,final,sampleRate)
                raw.delete()
                runOnUiThread{
                    performanceAudio=null;performanceThread=null;recordingActive=false
                    handler.removeCallbacks(timerTick);updateRecordButton(false)
                    popupPerformanceButton?.text="▶ Start Performance";popupTimer?.text="REC  00:00"
                    popupStatus?.text=if(final.exists())"Performance saved on this phone. "+if(recognitionFailed)"Audio was saved; lyric matching was unavailable." else "Audio and lyric-following session saved." else "Recording could not be saved."
                    status.text=if(final.exists())"Performance saved locally: "+final.name else "Performance recording failed."
                    renderLibrary()
                }
            }catch(e:Exception){
                raw.delete()
                runOnUiThread{
                    performanceAudio=null;performanceThread=null;recordingActive=false
                    handler.removeCallbacks(timerTick);updateRecordButton(false)
                    popupPerformanceButton?.text="▶ Start Performance"
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
        if(best>=currentLineIndex && bestScore>0){
            currentLineIndex=best
            runOnUiThread{renderActiveLyricLine(true);popupStatus?.text="Recording · following lyric line ${best+1}"}
        }
    }

    private var lastRecognizedPhrase=""

    private fun stopPerformance(){
        if(!recordingActive)return
        recordingActive=false
        recognitionEnabled=false
        try{performanceAudio?.stop()}catch(_:Exception){}
        popupStatus?.text="Finishing and saving your performance…"
        status.text="Finishing performance recording…"
        handler.removeCallbacks(timerTick)
        updateRecordButton(false)
        popupPerformanceButton?.text="▶ Start Performance"
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
    override fun onDestroy(){if(recordingActive)stopPerformance();try{performanceThread?.join(1200)}catch(_:InterruptedException){};stopPitchMonitor();player?.release();player=null;try{voskModel?.close()}catch(_:Exception){};super.onDestroy()}
}
