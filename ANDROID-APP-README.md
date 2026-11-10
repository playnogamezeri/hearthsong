# Hearthsong Android prototype

This prototype lives on the separate `android-app` branch. It does not change the published website on `main`.

## Privacy
- The Android manifest does not request INTERNET permission.
- Microphone permission is requested only when recording or lyric-following is started.
- Song text is stored in private app preferences.
- Recordings are stored in the app's private files directory.
- Voice following uses Android's on-device SpeechRecognizer only. If unavailable, it stops rather than falling back to online recognition.

## Experimental features
- Original lyric editor and local song list.
- Local microphone recording and saved take listing.
- On-device speech recognition attempt to match spoken/sung words to lyric lines.
- Experimental pitch estimate display.

The first prototype opens separate microphone capture paths for speech recognition and pitch analysis. Some Android devices may not allow both to receive audio simultaneously. Test this carefully; the next engineering step is to validate microphone concurrency and, if necessary, use a single shared audio pipeline.

## Build
The GitHub Actions workflow builds a debug APK from the `android-app` branch. In the repository's Actions tab, run **Build Hearthsong Android APK**, then download the `hearthsong-debug-apk` artifact from a successful run.

This is an experimental prototype, not a release-ready app.
