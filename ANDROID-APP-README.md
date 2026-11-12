# Jack's RePO Android app

The Android app lives on the separate `android-app` branch. It does not change the published website on `main`.

## Privacy and microphone behavior

- Song text is stored in private app preferences.
- Audio recordings are saved in the app's private files directory.
- Performance audio is captured once with Android `AudioRecord`. The same microphone audio buffers are written to the recording and passed to the offline Vosk recognizer.
- Lyric recognition runs on the phone. Voice recordings and lyric text are not uploaded for recognition.
- The first performance setup downloads the English Vosk speech model over HTTPS and stores it in the app's private files. This is a one-time model download; later lyric recognition runs locally.

## Performance mode

- One **Start Performance** control starts microphone recording and lyric following together.
- If lyric recognition errors, lyric matching pauses but the audio capture loop continues.
- Takes are saved as local WAV files. Older M4A takes remain supported in the library and backup/restore.
- Lyric following is experimental. Singing, guitar, room noise, and vocal style can affect recognition accuracy. It may miss or skip lines.

## Auto-Tune (experimental)

- Saved mono 16-bit WAV takes have an **Auto-Tune** action in the recording library.
- Preloaded styles: Natural (45%), Strong (78%), and Robotic / T-Pain (100%). Correction strength remains adjustable from 0–100%.
- Key defaults to C and scale defaults to Major; Chromatic is also available.
- Processing runs locally on the phone. The original take is never overwritten; a separate tuned WAV copy is saved.
- This first implementation uses lightweight frame-based pitch estimation and correction. It is experimental and is not equivalent to a commercial studio-grade pitch-correction engine.
- Because the microphone records voice and guitar together, the algorithm processes the complete mix. Guitar notes may be altered, and chords, backing tracks, room sound, or noisy recordings can confuse pitch detection. It may create artifacts or miss notes.
- Auto-Tune currently accepts the app's mono 16-bit WAV takes. Older M4A recordings are playable but are not yet eligible for processing.

## Build and testing

The GitHub Actions workflow builds a signed release APK when the repository signing secrets are available. A successful build confirms compilation and packaging, not real-device behavior. Test the APK on the target Android phone before relying on it for performances or deleting any backup.

## YouTube player removal (0.5.1)

The embedded YouTube player and backing-track link controls have been removed from the Android app. Song lyrics and audio recording/playback remain available. Existing saved song entries with an old YouTube URL are still readable; the app no longer displays or uses those links.

## Lyrics auto-scroll (0.5.3)

When Start Performance is pressed, the lyric screen begins a gentle, steady downward scroll automatically. It no longer depends on speech recognition to highlight or move between lines, so the singer does not need to match the words to the microphone recognition. Scrolling stops when the performance stops or the lyric page closes. The scroll speed is a fixed gentle pace and is not yet synchronized to the song's tempo.


## Saved song titles and Auto-Tune guidance (0.5.5)

- Each saved song has a **Rename** action in the library. Renaming changes only the song title; lyrics and recordings are left untouched. Duplicate titles are prevented.
- Auto-Tune labels explain **Key** as the song's home note and **Scale** as the family of notes that fit the song.
- Major often sounds brighter, Minor often sounds moodier, and Chromatic allows all 12 notes. These are simple starting explanations, not a substitute for identifying the actual key and scale.
- Auto-Tune remains experimental and processes the complete voice-and-guitar recording.


## Emotional tone processing (0.5.6)

- Emotional-style presets include Natural, Cheerful, Romantic, Sad, Dark / Depressive, Sultry, Angry, Confident, Dreamy, Heartbroken, Dramatic, and Robotic.
- Presets combine adjustable pitch correction with local whole-mix bass/treble EQ, simple delay-based reverb, dynamic-range compression, and formant-style resonant coloration.
- The processing is applied to the entire mono recording, including guitar. It does not isolate vocals.
- The formant-style control uses two resonant EQ peaks to color the tone. It is **not** a true formant shifter and cannot independently reshape vocal anatomy or guarantee an emotional performance.
- Reverb is a lightweight multi-tap delay, not convolution reverb. Compression and EQ are simple first-pass DSP, not studio-grade mastering.
- The original recording is kept unchanged; each processed result is saved as a new WAV copy. Processing is on-device.
- These effects are experimental and need listening tests on the target phone and different voice/guitar recordings. A successful GitHub build only verifies compilation and packaging.


## Automatic emotional presets (0.5.7)

- Emotional presets use fixed, predefined settings for pitch correction, bass/treble EQ, reverb, compression, and formant-style resonant coloration. Users select an emotion; they do not need to adjust audio-effect sliders.
- Available styles: Natural, Cheerful, Romantic, Sad, Dark / Depressive, Sultry, Angry, Confident, Dreamy, Heartbroken, Dramatic, and Robotic.
- Key and scale remain selectable because pitch correction needs the song's musical key and note family. These are separate from the emotional tone controls.
- Each generated copy is named for its emotional style. The original take remains unchanged.
