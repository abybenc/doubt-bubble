# Doubt Bubble

A floating **?** bubble for Android. Reading something in any app and a doubt comes up?
Tap the bubble. It grabs your screen, opens a small chat panel on top, and Gemini answers
your question about what you were reading.

## Install on your phone

1. Open this repo's **Releases** page on your phone and download `DoubtBubble.apk`
   (a new build appears automatically every time code is pushed to `main`).
2. Open the file and allow "Install unknown apps" for your browser when Android asks.
3. Open **Doubt Bubble**, tap **Get a free key**, create a Gemini API key in Google AI Studio,
   paste it, tap **Save**.
4. Tap **Turn on** and allow:
   - **Display over other apps** (so the bubble can float)
   - **Screen sharing** (pick **Entire screen** if asked)

## Using it

- The bubble is **hidden by default**. When a doubt comes up, pull down your notifications
  and tap **Doubt Bubble** (or add the **Ask a doubt** Quick Settings tile for one tap).
- Tap **?**, type your doubt, or tap a quick chip like *Explain this simply*.
- Follow-up questions keep the same screenshot as context.
- Done? **Drag the bubble down onto the ✕** to throw it away (Messenger style).
- Drag it anywhere else and it snaps to the nearest edge.
- **Turn off** from the notification to stop everything.

## How it works

| Piece | File |
|---|---|
| Setup screen (key, permissions) | `MainActivity.kt` |
| Floating bubble, throw-away target, screen capture | `BubbleService.kt` |
| Notification / tile shortcut that summons the bubble | `ShowBubbleActivity.kt`, `BubbleTileService.kt` |
| Chat panel that slides up over any app | `ChatActivity.kt` |
| Gemini REST call (no extra libraries) | `GeminiClient.kt` |

Model defaults to `gemini-flash-latest`; you can change it in the app if Google renames models.

## Notes

- Apps that block screenshots (banking, some streaming apps) will show a black capture.
- `app/doubtbubble.keystore` is a personal signing key committed on purpose so each new build
  installs over the old one. Fine for personal sideloading; don't use it for Play Store.
- Your API key stays on the phone (app-private storage) and is sent only to Google's Gemini API.
