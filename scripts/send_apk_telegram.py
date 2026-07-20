#!/usr/bin/env python3
"""
Send the Jarvis APK via Telegram Bot API.
Usage: python3 send_apk_telegram.py <BOT_TOKEN> <CHAT_ID>
"""
import sys
import os
import requests

APK_PATH = "/home/z/my-project/jarvis-agent/app/build/outputs/apk/debug/app-debug.apk"

def send_apk(bot_token, chat_id):
    if not os.path.exists(APK_PATH):
        print(f"ERROR: APK not found at {APK_PATH}")
        sys.exit(1)

    file_size = os.path.getsize(APK_PATH)
    print(f"APK size: {file_size / (1024*1024):.1f} MB")

    # Telegram allows up to 50MB for bot uploads
    if file_size > 50 * 1024 * 1024:
        print("ERROR: APK exceeds Telegram's 50MB limit")
        sys.exit(1)

    url = f"https://api.telegram.org/bot{bot_token}/sendDocument"

    with open(APK_PATH, "rb") as f:
        files = {"document": ("jarvis-agent-debug.apk", f, "application/vnd.android.package-archive")}
        data = {
            "chat_id": chat_id,
            "caption": "Jarvis Agent v1.0.0 (Debug)\n\n- Status bar fix\n- Stop button on notification\n- Task History with star/save (5 limit, 24h expiry)"
        }
        response = requests.post(url, files=files, data=data, timeout=120)

    if response.status_code == 200:
        result = response.json()
        if result.get("ok"):
            print("APK sent successfully via Telegram!")
        else:
            print(f"Telegram API error: {result}")
    else:
        print(f"HTTP error {response.status_code}: {response.text}")

if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("Usage: python3 send_apk_telegram.py <BOT_TOKEN> <CHAT_ID>")
        print("\nTo find your Chat ID, send a message to your bot then visit:")
        print(f"  https://api.telegram.org/bot<YOUR_TOKEN>/getUpdates")
        sys.exit(1)

    send_apk(sys.argv[1], sys.argv[2])
