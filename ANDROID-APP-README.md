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
