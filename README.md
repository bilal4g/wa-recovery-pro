# WA Recovery Pro 🛡️

**WhatsApp Message Recovery & Media Manager** — Android App

Recover deleted WhatsApp messages, read voice notes, capture view-once media, and backup all your WhatsApp files.

## Features

| Feature | Description |
|---|---|
| 🔄 **Deleted Message Recovery** | Captures all WhatsApp messages before they can be deleted |
| 🎤 **Advanced Voice Suite** | Multi-speed (1x, 1.25x, 1.5x, 2x), pitch effects & AI transcription |
| 📲 **Voice Sharing Hub** | System Share Sheet, WhatsApp Direct Share, audio file export & ringtones |
| 👁️ **View-Once Capture** | Saves one-time view photos and videos before they self-destruct |
| 📁 **Media Recovery** | Auto-backs up photos, videos, documents, stickers, GIFs |
| 🛡️ **Interactive Onboarding** | First-launch setup wizard that deep-links to system settings |
| 🚀 **In-App Auto-Updater** | Live OTA hot-patching (updates instantly without re-downloading APK) |
| 🔍 **Full-Text Search** | Search across all messages, contacts, and media |
| 📊 **Dashboard** | Real-time statistics and service status |
| 🔄 **Auto-Start** | Restarts automatically after device reboot |
| 📤 **Export Data** | Export messages and media as backup files |

## How It Works

This app uses **official Android APIs** to capture WhatsApp notifications:

1. **NotificationListenerService** — Captures all incoming WhatsApp notifications and stores them locally. When a sender deletes a message, the app detects the notification removal and marks it as "deleted but recovered."

2. **FileObserver** — Monitors WhatsApp's media directories on your device and backs up files to the app's private storage before they can be deleted.

3. **View-Once Capture** — Extracts thumbnail previews from notification data for view-once messages before they self-destruct.

> ⚠️ **Important:** This app only works on your own device for notifications you receive. It does not hack, decrypt, or reverse-engineer WhatsApp.

## Tech Stack

- **Frontend:** HTML5, CSS3, Vanilla JavaScript
- **Build Tool:** Vite 5
- **Native Runtime:** Capacitor 6
- **Native Code:** Java (Android)
- **Database:** SQLite (native) + IndexedDB (web)
- **Target:** Android 8.0+ (API 26+)

## Project Structure

```
whatsapp app/
├── index.html                 # Main HTML shell
├── src/
│   ├── css/app.css            # Premium dark design system
│   └── js/
│       ├── app.js             # Main application controller
│       ├── database.js        # IndexedDB database layer
│       ├── ui-components.js   # Reusable UI components
│       └── media-manager.js   # Media gallery & lightbox
├── android-src/               # Native Android source files
│   ├── AndroidManifest-additions.xml
│   └── java/com/warecovery/pro/
│       ├── NotificationListener.java   # Core message capture
│       ├── MediaScanner.java           # Media file monitoring
│       ├── VoiceExtractor.java         # Voice note extraction
│       ├── ViewOnceCapture.java        # View-once handler
│       ├── DatabaseHelper.java         # SQLite database
│       ├── RecoveryBridge.java         # Capacitor plugin bridge
│       └── BootReceiver.java           # Auto-start on boot
├── scripts/
│   ├── setup-android-sdk.ps1  # Android SDK installer
│   └── build-apk.ps1         # APK build automation
├── capacitor.config.ts        # Capacitor configuration
├── vite.config.js             # Vite build configuration
└── package.json               # Node.js dependencies
```

## Building the APK

### Prerequisites
1. **Node.js 18+** and npm
2. **JDK 17** (Microsoft OpenJDK recommended)
3. **Android SDK** (platform 34, build-tools 34.0.0)

### Quick Build

```bash
# 1. Install dependencies
npm install

# 2. Setup Android SDK (first time only)
powershell -ExecutionPolicy Bypass -File scripts/setup-android-sdk.ps1

# 3. Build the APK
powershell -ExecutionPolicy Bypass -File scripts/build-apk.ps1
```

The APK will be output as `WA-Recovery-Pro.apk` in the project root.

### Manual Build

```bash
# Build web assets
npm run build

# Add Android platform (first time only)
npx cap add android

# Sync web assets to Android
npx cap sync android

# Build debug APK
cd android
./gradlew assembleDebug
```

## Installation on Android

1. Transfer the APK to your Android device
2. Enable "Install from Unknown Sources" in Settings
3. Install the APK
4. Grant **Notification Access** in Settings → Notification Access
5. Grant **Storage Permissions** when prompted
6. The app will start capturing messages immediately

## Legal Disclaimer

This application is for **personal use only**. It uses public Android APIs (NotificationListenerService, FileObserver) and does not modify, hack, or reverse-engineer WhatsApp. Users are responsible for compliance with local privacy laws and WhatsApp's Terms of Service.

## License

MIT License — For personal, educational use only.
