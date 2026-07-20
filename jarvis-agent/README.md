# 🤖 Jarvis Agent — Your Personal Android AI Assistant

A native Kotlin Android app that acts as a personal AI agent, capable of controlling your phone through natural language commands using the Accessibility Service.

## Features

- **Accessibility-Based Automation** — Reads the screen UI tree and dispatches gestures (tap, swipe, type, scroll)
- **Multi-Provider LLM Support** — OpenRouter, NVIDIA NIM, DeepSeek, OpenAI, or any OpenAI-compatible API
- **Voice Control** — Native Android STT + TTS for hands-free interaction
- **Floating Overlay** — Chat bubble that floats over any app
- **Telegram Remote Control** — Send commands to your phone via Telegram bot
- **VLM Screenshot Analysis** — Uses vision models for visual UI understanding
- **Skill Memory** — Learns and replays successful task sequences
- **Shizuku Integration** — Elevated ADB commands for actions AccessibilityService can't handle
- **Safety Gate** — Blocks destructive actions (payments, deletions) by default
- **Recovery Engine** — Auto-recovers from action failures

## Architecture

```
User Command → LLM Route (Tier 1) → Action Handler → Execute
                                         ↓
                              Multi-step? → Agent Loop (Tier 2):
                                  1. Dump screen (AccessibilityService)
                                  2. Call LLM for next action
                                  3. Execute (tap/type/scroll/back)
                                  4. Repeat until "done"
```

## Two-Tier Action System

**Tier 1 (Route):** LLM decides high-level action — open_app, make_call, send_sms, execute_task, etc.

**Tier 2 (Agent Loop):** For complex tasks, enters a loop of atomic screen actions — click_text, type_text, scroll, press_back, done

## Setup

1. Install the APK
2. Enable **Accessibility Service** in Settings → Accessibility → Jarvis Agent
3. Configure your preferred AI provider (OpenRouter, NIM, DeepSeek, or OpenAI)
4. Start chatting!

## Build from Source

```bash
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

## Requirements

- Android 8.0+ (API 26)
- Accessibility Service enabled
- An OpenAI-compatible LLM API key

## License

MIT
