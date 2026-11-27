# Diagonal

An Android home screen (launcher) where every app is a big coloured diagonal stripe.

- **Stripes.** About five stripes fit on the screen. Each one takes its colour from the app icon. Simple two-colour icons (DeepSeek, YouTube…) become a white glyph on the brand colour, and you can pick another colour for them.
- **Opens in the middle.** You start on your 4 home apps with the **tablet** in the centre: a clock with weather and date, a full music player while something plays (controls, progress, time), and new notifications as a short line in the app's colour. Its corner shows the battery level and, in Ukraine, an air-raid alert dot for your region (red = missiles / mass strike, yellow = drones; data: [alerts.in.ua](https://alerts.in.ua)). Long-press the tablet to choose the 4 home apps.
- **Swipe across the stripes:**
  - **Up:** apps from A to Z (your home apps are listed here too).
  - **Down:** apps by time used.
- **Swipe along the stripes:**
  - **Towards the bottom-left:** a black quick-settings triangle opens in the top-right corner. Swipe again for the big one with more tiles and your notifications.
  - **Towards the top-right:** the widget window grows out of the bottom-left corner. It peeks first, then a second swipe opens it fully.
- **Widgets.** Player, chats (recent conversations from your messengers), Claude, weather, calendar, battery, next alarm and a note.
  - The cards are stacked like stairs so they fit the triangle.
  - Long-press a card to move it or remove it. The card in the corner is the one you see in the small window.
  - Use “+ Add widget” to bring a card back.
- **Long-press an app** to put it on one of the 4 home places, change its colour, open app info or uninstall it.
- **Search** (top) shows results as rounded bars rising from the search box. Tap a result to jump to the app and see it blink.
- **⌂** takes you back to the start. **↩** opens the system “Home app” setting so you can switch back to your old launcher.
- **Languages:** English and Ukrainian, following the phone's language.

## Install

1. Open **[Releases](../../releases/latest)** on the phone and download `diagonal-launcher.apk`.
2. Allow your browser or file manager to “install unknown apps”, then install the APK.
3. Press Home, choose **Diagonal**, then **Always**. You can also set it in Settings → Apps → Default apps → Home app.

New builds install over the old one. The APK is signed with a fixed test key that is kept in this repository (`app/signing.jks`). That makes updates easy, but it also means anyone could sign an APK with the same key, so only install builds from this repository.

## Permissions

Everything is optional. Diagonal still works without any of them; you only lose the feature listed next to it.

| Permission | Asked when | Used for |
|---|---|---|
| Usage access | first start | sorting apps “by time used”; without it, Diagonal counts launches from this home screen |
| Notification access | second start | music on the centre stripe and in the player, new-message line, notifications in the big triangle, the play/pause buttons |
| Approximate location | third start | local weather; without it the weather is for Kyiv |
| Calendar | when you tap the Calendar widget | upcoming events |
| Do Not Disturb access | when you tap that tile | switching DND on/off |
| Modify system settings | when you tap Auto-rotate | switching auto-rotate |
| Set wallpaper | only if you switch on “Home screen on the lock screen” | putting a picture of your stripes on the lock screen |
| Do Not Disturb access | first start (Android 10+) | Diagonal's own home-screen rule that hides other apps' pop-ups while you are on the home screen and shows Diagonal's banner instead (calls, alarms, important conversations still pop up) |
| Internet | always | weather from [Open-Meteo](https://open-meteo.com) and, in Ukraine, air-raid alerts from [alerts.in.ua](https://alerts.in.ua) (no account, no key) |

Diagonal has no analytics and no servers. The only network requests are the weather and (in Ukraine) the public air-raid alert report. The alert dot is a convenience, not a replacement for the official “Повітряна тривога” app or sirens.

## Known problems and fixes

**Google Play Protect warns or blocks the install** (“unsafe app”, “app built for an older version”, “unknown developer”).
- This happens because the APK comes from outside Google Play and is signed by an unregistered developer.
- When the warning appears, tap *More details → Install anyway*. If Play Protect offers to scan the app first, you can let it.
- If the install is blocked completely, you can turn Play Protect off for the install (Play Store → profile → Play Protect → ⚙ → *Scan apps with Play Protect*) and turn it back on afterwards.
- Google is rolling out *developer verification* for sideloaded apps on certified Android devices. It started on 30 September 2026 in Brazil, Indonesia, Singapore and Thailand, and Google says it will expand worldwide in 2027. Unregistered apps can then still be installed with `adb install diagonal-launcher.apk` or through Android's “advanced” install flow.

**“Restricted setting” when you try to allow notification access** (Android 13 and newer).
- Android blocks sensitive permissions for sideloaded apps until you unlock them.
- Open Settings → Apps → Diagonal → ⋮ (top right) → **Allow restricted settings**, confirm, then grant notification access again.

**Wi-Fi / Bluetooth / Airplane tiles open a system panel instead of switching right away.**
Android doesn't let third-party apps toggle these directly. The torch, Do Not Disturb and auto-rotate do switch directly.

**The notification shade needs two swipes.**
The status bar is hidden on the home screen: the first swipe shows the bar, the second opens the shade. You can turn this off with the **Diagonal** tile in the big quick-settings triangle (*Hide the status bar*).

**Chats widget is empty.**
- Diagonal must be your default home app to read app shortcuts.
- The chats only appear after a messenger has published its recent conversations. Open Telegram, WhatsApp or another messenger once, then come back.

**Xiaomi / HyperOS / MIUI.**
These phones may limit gestures and the recents screen with third-party launchers, and may stop background permissions. If notification features stop working, enable *Autostart* for Diagonal and set its battery saver to *No restrictions*.

**Lock screen picture didn't change.**
Some phones (Samsung, Xiaomi) use their own lock-screen wallpaper managers and ignore the system lock-screen wallpaper.

## Build it yourself

GitHub Actions builds every push to `main` and publishes `diagonal-launcher.apk` as a release (`.github/workflows/build.yml`). To build locally you need JDK 17 and the Android SDK:

```
gradle assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

The code is plain Java with no libraries (minSdk 26, targetSdk 35). The main classes:

- `DiagonalView`: the stripes, gestures and centre stripe.
- `Panels`: quick settings and widgets.
- `QuickPanel` and `WidgetPanel`: the two triangles.
- `InfoHub`: clock, weather, music and notifications.
- `ColorExtractor`: icon colours and glyphs.

## Claude Code agents

`.claude/agents/` has four roles for working on the code: `designer`, `coder`, `tester` and `optimizer`.
