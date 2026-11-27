# SA-TV – Android IPTV starter source

This is a rebranded Android source starter for SA-TV. It includes a dark Compose UI, M3U parsing and stream recovery starter code, a 6-hour best-effort WorkManager refresh, and the user's master playlist as an app asset.

Important: This is not a finished production app and no APK is included. The Not Working button is still a placeholder; playlist loading into the channel UI, playback validation, persistent recovery/history, secure admin-only publishing, and Android TV home-screen channel integration remain to be implemented. Periodic WorkManager jobs are best effort and may be deferred by Android.

Master playlist: `app/src/main/assets/master.m3u`
Remote URL: `https://raw.githubusercontent.com/sovandey/bengal-iptv-data/main/bengali_merged.m3u`

Build with AndroidIDE or Android Studio, JDK 17 and a compatible Android SDK/Gradle setup. Only use streams you are authorized to access.
