# 🦀 ClawLauncher - OpenClaw for Android

Run OpenClaw server directly on your Android device.

## Features

### Control Panel
- **Start/Stop/Restart** OpenClaw server
- **Status monitoring** with live indicators
- **Auto-bootstrap** Node.js + OpenClaw on first run

### Toolkit
- **Skill Store** — Find and install new tools
- **Skills Manager** — Export and restore skills
- **Backup Restore Points** — Create snapshots, roll back anytime
- **Config Snapshots** — Hot-swap AI models, ports, endpoints

### Architecture
```
Android App (Kotlin + Compose)
    ↓
Process Manager (manages Node.js process)
    ↓
Bootstrap (downloads Node.js ARM64 + OpenClaw)
    ↓
Node.js → OpenClaw Gateway (localhost:3000)
```

## Build

```bash
./gradlew assembleDebug
```

## Requirements
- Android 8.0+ (API 26)
- ARM64 device (most modern Android phones)
- ~100MB storage for Node.js + OpenClaw

## Tech Stack
- Kotlin + Jetpack Compose
- Material3 Dark Theme
- OkHttp for downloads
- Process management via ProcessBuilder

## License
Private — All Rights Reserved © 2026
