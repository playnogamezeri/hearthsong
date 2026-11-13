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


## Recording key match and transposition (0.5.8)

- **Match My Recording** estimates a key and major/minor mode from the recorded mono mix, then uses that estimate as the pitch-correction target.
- **Change to a Different Key** estimates the source tonic, lets the user choose a destination tonic, and transposes the complete mix while retaining the estimated major/minor mode.
- Key detection is experimental and analyzes the combined voice-and-guitar signal. Chords, accompaniment, noise, and strong harmonics can produce a wrong estimate; the app displays a confidence indicator and the result should be checked by listening.
- Transposition resamples to shift pitch, then uses waveform-similarity overlap-add (WSOLA) time stretching to restore the original sample count and preserve the recording duration and tempo. This lightweight mono algorithm is experimental; dense guitar chords, strong transients, and larger shifts may create audible artifacts.
- Both modes create a new WAV copy and leave the original recording unchanged. All analysis and processing run on-device.


## Emotion Vocal Engine 2.0 (0.6.0)

- Replaces the first-pass tone stage with a local offline DSP chain: low-frequency cleanup, tonal EQ, vocal-presence shaping, dynamic sibilance control, level compression with smoothed gain, gentle saturation, and a four-comb feedback reverb.
- The 12 emotion presets automatically select their processing values. There are no manual effect sliders.
- Processing is still applied to the complete mono voice-and-guitar mix. This is not source separation: guitar and other sounds can be affected by EQ, compression, saturation, and reverb. The app does not claim isolated vocal processing.
- The de-esser is a lightweight high-frequency detector and dynamic attenuation stage, not a dedicated multiband vocal processor. The reverb is algorithmic, not convolution or a modeled studio room. Pitch correction remains the existing experimental frame-based algorithm.
- All processing is offline and on-device. The original WAV take remains untouched; a new processed copy is created.
- “Emotion Vocal Engine 2.0” describes the DSP upgrade, not a claim of perfect emotional expression, professional mastering, or guaranteed results. Listening tests on the target phone and varied voice/guitar recordings are still required.


## Simplified Auto-Tune experience (0.6.1)

- The Auto-Tune screen now asks only for an emotional vocal style.
- Key and scale analysis runs automatically in the background; users no longer need to understand or select musical keys/scales or manually press Analyze.
- If the key/scale estimate has low confidence, pitch correction uses chromatic mode rather than forcing a potentially incorrect major/minor scale.
- The app creates a new processed WAV copy and keeps the original take unchanged.


## Auto-Tune Engine 3.0 (0.7.0)

- The Auto-Tune screen offers two simple sound directions: **Natural & Expressive** and **Polished Pop / Hip-Hop**, alongside the existing emotion choices.
- Natural mode uses a conservative correction range and stronger vibrato preservation. Polished mode uses firmer correction and a denser vocal-style finish. The Robotic emotion remains intentionally strong in either mode.
- A new offline pitch stage uses YIN-style periodicity analysis, confidence gating, a short median pitch trajectory, smoothed correction movement, and windowed overlap-add processing. Uncertain/unvoiced frames are treated cautiously rather than being forced to a guessed note.
- The existing emotional DSP stage remains responsible for tone shaping. Polished mode adjusts the preset's compression, presence, de-essing, saturation, and reverb balance automatically.
- This is an experimental mono-mix engine, not a claim of professional-grade Auto-Tune or a substitute for a studio vocal chain. The microphone recording contains voice and guitar together; there is no vocal/instrument source separation, so guitar notes may still be affected and chords can confuse pitch analysis.
- The original WAV is never overwritten. A new processed WAV is created locally on the phone. A successful build confirms compilation and packaging only; real listening tests on the target phone and with varied singing styles are still required.


## Live recording Auto-Tune and genre equalizer (0.8.0)

- The Sing Lyrics and Sing & Play Guitar screens include one **Auto-Tune ON/OFF** toggle. It can be switched during an active recording without stopping the microphone capture.
- When enabled, the recorder applies a lightweight YIN-based, chromatic, natural pitch-correction pass to incoming microphone audio in short PCM blocks. Speech recognition continues to use the uncorrected microphone input.
- Live correction is experimental and runs on the complete mono microphone mix. It is not vocal isolation: guitar notes, chords, and background sounds can confuse the pitch tracker. Short-block processing can create artifacts and needs real-device listening tests.
- The recordings library includes a **Equalizer** action for WAV takes, with Pop, Hip-Hop, R&B, Rock, Country, EDM, Acoustic, and Emo presets.
- Applying a genre preset creates a new processed WAV copy and preserves the original. Equalization is local/offline and affects the whole mono mix.
- A successful Android build verifies compilation and packaging only; it does not establish real-time audio quality or performance on the Galaxy A17.


## Live Auto-Tune engine refresh (0.8.1)

- Adds a dedicated stateful live processing wrapper that carries a rolling 2,048-sample pre-roll between microphone blocks instead of restarting pitch analysis at every AudioRecord read.
- Adds a live **Tune settings** panel with song key, Chromatic/Major/Natural minor scale, Natural/Polished/Robotic correction profiles, correction strength, and retune speed. Settings are stored locally on the phone.
- Uses the same on-device YIN pitch tracker with a selectable hard-correction profile. The Vosk recognizer continues to receive the original uncorrected microphone samples.
- The Auto-Tune switch remains independent of the recording control. Turning it off does not stop recording; turning it back on resumes processing with the current settings.
- This remains experimental real-time mono-mix pitch processing, not a commercial studio-grade phase-vocoder or source-separated vocal processor. The microphone captures voice, guitar, and room sound together. A rolling analysis window reduces block-boundary resets but does not eliminate pitch-tracking errors, latency, or artifacts. Test short takes with headphones and keep original recordings.
- Version 0.8.1 uses Android versionCode 19. A successful CI build confirms compilation and packaging only; actual audio quality and device stability must be evaluated on the Galaxy A17.


## Automatic Studio Enhance (0.8.3)

- Keeps the app focused on recording, lyric-following and simple playback rather than manual audio editing.
- Replaces the manual genre/sliders workflow with one **Studio Enhance** action on each supported WAV recording. One tap analyzes the take and automatically applies a restrained whole-mix chain: low-frequency cleanup, adaptive tonal balance, vocal presence, compression, de-essing, subtle saturation, room ambience and conservative loudness balancing with peak headroom.
- No genre selection, EQ sliders or effect controls are required. The enhancement makes conservative adjustments based on measured recording level, crest factor and brightness proxy; it does not pretend to identify every instrument or perform source separation.
- The processed audio is saved as a separate `_studio_enhanced.wav` copy. The original recording is never overwritten.
- Live Auto-Tune remains a separate ON/OFF control during recording. The app continues to capture voice and guitar together as a mono mix, so all whole-mix effects can affect both.
- This is an automatic starter enhancement chain, not a guarantee of studio mastering. Listening tests on real recordings and the Galaxy A17 remain necessary.
- Version 0.8.3 uses Android versionCode 21.
