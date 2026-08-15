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

## OpenClaw Updates

Open the **Setup** screen after OpenClaw is installed and press **Check** in the OpenClaw updates card. ClawLauncher checks the official npm registry, compares the installed package version with the latest published version, and shows the result. Updating remains an explicit user action through **Update OpenClaw**; the app does not silently replace the installed package.

## Custom AI Providers

Open **Settings → Custom AI Providers** to add an OpenAI-compatible service. You can choose a popular preset or enter a custom provider ID, display name, Base URL, API key, API format, and comma-separated model IDs. The settings are persisted under OpenClaw's `models.providers` configuration and the selected model is used as the primary `provider/model` reference.

The provider form supports local services such as Ollama and LM Studio as well as hosted services such as Groq, DeepSeek, Together AI, Fireworks AI, Mistral, xAI, and Perplexity. For local services, the API key can be left empty.

## License
Private — All Rights Reserved © 2026
