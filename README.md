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

### ⚡ Automatic provider from a pasted `curl` command

Copy a sample request from your AI service's docs (the typical `curl https://... -H "Authorization: Bearer ..." -d '{"model": ...}'`) and paste it into **Settings → Custom AI Providers → Add provider automatically**. ClawLauncher detects:

- **Base URL** (path suffixes like `/chat/completions` or `/messages` are trimmed)
- **API key** (`Authorization: Bearer`, `x-api-key`, `x-goog-api-key`, `?key=` in URL)
- **API format** (OpenAI-completions, Anthropic-messages, Google Generative AI)
- **Model ID** (from the request body) and instantly makes it the active model

Official endpoints (`api.openai.com`, `api.anthropic.com`, `generativelanguage.googleapis.com`, `openrouter.ai`) are routed to the matching built-in provider automatically.

## Sign in with Google — Gemini without an API key

Select **AI Provider → Gemini (Google Account — no API key)** and press **Sign in with Google** in Settings. ClawLauncher performs the same OAuth sign-in flow as Google's Gemini CLI (public "installed app" OAuth client + PKCE, user-code flow):

1. The browser opens the official Google sign-in page.
2. Approve — Google shows a short authorization code.
3. Paste the code into the app. Done.

Tokens are stored both as Gemini-CLI ambient credentials (`~/.gemini/oauth_creds.json`) and as an OpenClaw OAuth auth profile (`google-gemini-cli:default`) inside OpenClaw's state store. The access token is refreshed automatically before every server start and every 20 minutes while the server runs — no manual maintenance needed. A **Test** button in Settings verifies the token against Google's quota endpoint.

## License
Private — All Rights Reserved © 2026
