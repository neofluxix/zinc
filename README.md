# Zinc (Android)

Live two-way voice translation using Google's Gemini Live API.
A small Kotlin shell hosts the web app in `app/src/main/assets/index.html` inside a WebView.

## Build it
**Android Studio (easiest):** File > Open > this folder. Wait for Gradle sync, then
Build > Build APK(s). The APK appears at `app/build/outputs/apk/debug/app-debug.apk`.
Or press Run with a phone plugged in (USB debugging on).

**Command line:** `./gradlew assembleDebug` (needs JDK 17 and the Android SDK).

**No installs:** push this folder to GitHub; the Actions tab builds the APK for you.

## Change the basics
- App name:      app/src/main/res/values/strings.xml  (app_name)
- Package id:    app/build.gradle.kts  (applicationId; also namespace + the Kotlin package if you rename it)
- Version:       app/build.gradle.kts  (versionCode / versionName)
- Icon:          app/src/main/res/drawable/ic_launcher_foreground.xml and values/colors.xml
- The app UI:    app/src/main/assets/index.html

## First run
Open the app, allow the microphone, paste your Gemini API key, pick your language, and tap Start conversation.
The key is stored only on the phone. Use a key you can revoke.
