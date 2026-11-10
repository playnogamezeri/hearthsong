# Jack's RePO Android app

The Android app lives on the separate `android-app` branch. It does not change the published website on `main`.

## Privacy and microphone behavior

- Song text is stored in private app preferences.
- Audio recordings are saved in the app's private files directory.
- Performance audio is captured once with Android `AudioRecord`. The same microphone audio buffers are written to the recording and passed to the offline Vosk recognizer.
- Lyric recognition runs on the phone. Voice recordings and lyric text are not uploaded for recognition.
- The first performance setup downloads the English Vosk speech model over HTTPS and stores it in the app's private files. This is a one-time model download; later lyric recognition runs locally.
- The app retains INTERNET permission for the one-time speech-model download and its optional YouTube player. YouTube playback streams from YouTube.
- Use headphones with YouTube backing tracks to reduce speaker audio leaking into the microphone.

## Performance mode

- One **Start Performance** control starts microphone recording and lyric following together.
- If lyric recognition errors, lyric matching pauses but the audio capture loop continues.
- Takes are saved as local WAV files. Older M4A takes remain supported in the library and backup/restore.
- Lyric following is experimental. Singing, guitar, room noise, and vocal style can affect recognition accuracy. It may miss or skip lines.
- The app does not capture internal YouTube audio; it records sound entering the microphone.

## Build and testing

The GitHub Actions workflow builds a signed release APK when the repository signing secrets are available. A successful build confirms compilation and packaging, not real-device behavior. Test the APK on the target Android phone before relying on it for performances or deleting any backup.
