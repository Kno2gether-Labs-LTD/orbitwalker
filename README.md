<p align="center">
  <img src="docs/images/banner.svg" alt="OrbitWalker: your whole Android desk, pinned in space" width="100%">
</p>

<p align="center">
  <a href="LICENSE"><img alt="License: Apache-2.0" src="https://img.shields.io/badge/license-Apache--2.0-6f8cff?style=flat-square"></a>
  <img alt="Android 13+" src="https://img.shields.io/badge/Android-13%2B-7df5d6?style=flat-square">
  <img alt="Glasses: VITURE Pro 2" src="https://img.shields.io/badge/glasses-VITURE%20Pro%202-a98bff?style=flat-square">
  <img alt="Status: early preview" src="https://img.shields.io/badge/status-early%20preview-ffb35c?style=flat-square">
  <img alt="No tracking, no accounts" src="https://img.shields.io/badge/tracking-none-3fe39a?style=flat-square">
</p>

<p align="center">
  <b>Every Android app on its own floating screen. Head-tracked, keyboard and mouse ready, no DeX, no cloud, no account.</b>
</p>

---

OrbitWalker turns your phone and a pair of **VITURE Pro 2** XR glasses into a **multi-monitor desk that stays put in space**. Open up to ten apps side by side, look around to move between them, and drive them with a Bluetooth keyboard and mouse. It's a travel workstation that fits in a pocket.

VITURE's glasses stream their motion sensors, but on Android their own app only pins its built-in screens, and Samsung DeX can't be pinned at all. OrbitWalker does the head tracking on the phone and gives **any app** its own pinned screen.

> **Part of the Orbit family**: open-source, local-first apps that keep your data on your own devices. One promise across all of them: *your hardware, your data, no vendor cloud in the middle.* The story of every Orbit app, today's and tomorrow's, lives at **[orbitapps.space](https://orbitapps.space)**.

## In the glasses

| | |
|---|---|
| ![Arrange screens with the mouse: drag the title bar](docs/images/title-bar.png) | ![Clock, battery and recent apps under the centre screen](docs/images/dock-widgets.png) |
| **Arrange with your mouse.** Drag a screen's title bar to move it; resize, push back, reset or close it from the bar. | **Dock under the centre screen.** Clock, date, phone battery and your recent apps, one click away. |
| ![The Orbit sky: a planet for each Orbit app](docs/images/sky-health.png) | ![A ringed planet in the Orbit sky](docs/images/sky-server.png) |
| **The Orbit sky.** A calm solar system circles you. Planets drift under 0.2° a second, or stay completely still. | **The family, as planets.** Each planet is an upcoming Orbit app. |

*Captured from the glasses' output on the Android emulator test rig.*

## Features

**A real multi-screen desk**
- Up to **10 app screens**: side by side on a gentle curve, then a second row above.
- **Head-tracked pinning** in every direction, with drift calibration, motion prediction and one-tap **Recenter**. Pinned or Follow mode.
- **You control the layout**: distance, size, gap and height, plus per-screen move, push/pull and resize, all saved.
- **Pixel-perfect sizing** and Sharp/Ultra screen resolutions for crisp text.

**Keyboard and mouse first**
- **Bluetooth keyboard and mouse** drive the glasses directly: click, drag, scroll and right-click Back, with typing in every app, passwords included.
- A **status card** shows where each device is going (glasses or phone) and why keys aren't arriving, if they aren't.
- **A pointer you can't lose**: it stays bright away from screens, an arrow at the edge of the view points to it, **Ctrl+Alt+C** brings it to where you're looking and **Ctrl+Alt+R** recenters everything.
- **Ctrl+Esc** hands the keyboard and mouse back to the phone.
- An **in-glasses keyboard** and a full-screen **phone trackpad** when you're away from your desk kit.

**Orbit, your voice agent** (new in 0.18)
- Say **"Hey Orbit"** (detected offline on the phone) or click the **mic in the dock** (Ctrl+Alt+O), then ask: "open YouTube", "put Chrome on the left and make it bigger", "start recording", "how did I sleep?".
- It stays in the conversation: keep talking without the wake word. After 30 seconds of quiet (or "that's all") it steps back.
- A little **Orbit** lives on your dock: it wanders and naps, pops up when you call it, and listens, thinks and talks with you.
- Answers out loud in the voice you pick: OpenAI, ElevenLabs, Inworld or AssemblyAI voices through your server, or the phone's own voice.
- Runs on **your own [Orbit Server](https://github.com/Kno2gether-Labs-LTD/orbit-server)**, with the AI model and voices you choose and your own keys. Pair by scanning the server's OrbitWalker QR code.
- It knows which apps are open and where; it doesn't see what's on your screens.

**Open apps from the glasses**
- The dock's **All apps** opens your app library as a screen in the glasses: search and open any app without touching the phone.

**Orbit Browser, built in**
- A full Chromium browser (Android's System WebView) on its own screen: tabs, search, downloads, file upload, full-screen video and a Desktop-site switch.
- **Drag a tab down out of the strip** and that page gets a screen of its own, still playing as it moves.
- Click a planet in the Orbit sky to read about that Orbit app.

**Record your desk, and you in it**
- **Glasses view**: exactly what you see, 1080p with your voice.
- **Mixed reality**: your phone on a tripod films you, and OrbitWalker draws every open screen where it floats around you, live, from the camera's point of view. Person detection (on the phone) puts you in front of the screens behind you. Any angle: behind, over a shoulder, beside or in front of you.
- **Video-ready framing**: a framed hero screen that fills the shot, a bigger-screens mode, or real positions, plus a **clean screen** file of just the focused screen. Sharp camera settings and steady exposure while recording.
- Both are saved at the same time as separate files in Movies/OrbitWalker, ready to cut together. **Ctrl+Alt+S** records; **Ctrl+Alt+M** opens a camera monitor in the glasses to line up the overlay.
- Your typing, notifications and Orbit's captions never appear in recordings.

**Made for long sessions**
- **Eye care**: a reminder every 20 minutes (adjustable) to look far away, and a real-break nudge after 90 minutes. Never during a film.
- **Comfort by design**: the sky moves slower than clouds, there's a **Still sky** switch, and nothing in the scene swims with your head.
- **Notifications** as cards in the glasses (optionally without their text).

**Watch and play**
- **Cinema mode** for Netflix, Prime Video, Disney+ and other protected video, with play, pause and seek from the phone.
- **3D desk**: true side-by-side stereo, plus 3D video support per screen.
- **Mirror mode** shows your phone screen pinned in space, with no setup at all.

**Private by design**
- No accounts, no analytics, no ads, no Orbit cloud. Head tracking, window management and input handling all run on the phone.
- OrbitWalker goes online only for things you turn on: the Orbit agent talks to **your own Orbit Server** (pinned certificate or your own HTTPS), "Hey Orbit" downloads its offline listening model once (checksum-verified), and the browser loads the pages you open.
- "Hey Orbit" listening never leaves the phone; only what you say after it goes to your server.

## How OrbitWalker gets its powers

Putting other apps on virtual screens needs Android's debugging-level ("shell") permissions. Tools like Shizuku provide them; **OrbitWalker has its own built-in helper**, so it's the only app you need:

1. Turn on **Developer options** and connect to Wi-Fi.
2. In OrbitWalker: **Set up → Pair**, then type the 6-digit code from *Wireless debugging → Pair device with pairing code* into OrbitWalker's notification.
3. OrbitWalker starts its helper. It keeps running until the phone restarts.

On **Samsung** phones, the app first takes you to **Auto Blocker** so you can switch it off for a while; you can switch it back on once the helper is running. After a phone restart you only tap **Start**: no new pairing.

The helper accepts calls only from OrbitWalker itself, and after an update it only follows the same signed app. No root is needed, and Samsung Knox isn't tripped.

## Requirements

| | |
|---|---|
| **Glasses** | VITURE Pro 2 (USB `35CA:1300/1301`). Other VITURE models are untested. |
| **Phone** | Android 13 or newer with USB-C video out (DisplayPort Alt Mode). Tested on a Samsung Galaxy Z Fold. |
| **Samsung** | Turn off DeX, so the glasses act as a plain external display. |
| **VITURE SpaceWalker** | Not needed for the APK. Only needed if you build from source (see below). |

## Getting OrbitWalker

<p align="center">
  <a href="https://github.com/Kno2gether-Labs-LTD/orbitwalker/releases/latest/download/OrbitWalker.apk"><img alt="Download OrbitWalker APK" src="https://img.shields.io/badge/Download-OrbitWalker.apk-7df5d6?style=for-the-badge&logo=android&logoColor=white"></a>
</p>

**[Download the latest APK](https://github.com/Kno2gether-Labs-LTD/orbitwalker/releases/latest/download/OrbitWalker.apk)** (Android 13+, 64-bit ARM), or see all versions under [Releases](https://github.com/Kno2gether-Labs-LTD/orbitwalker/releases).

1. Open the downloaded file on your phone and allow your browser or Files app to install it.
2. Open OrbitWalker and follow **Set up**: it walks you through Developer options, pairing and (on Samsung) Auto Blocker.
3. Plug in your glasses.

Every release is signed with the same key, so updates install over the previous version. The SHA-256 checksum of each APK is listed on its release page.

### Build it yourself

The source here doesn't include VITURE's libraries. To build from source, take them from your own copy of SpaceWalker:

```bash
# Requirements: JDK 17+, Android SDK 35
# 1. Get SpaceWalker's APK from your own phone
adb shell pm path com.viture.spacewalker      # then: adb pull <path> SpaceWalker.apk
# 2. Extract VITURE's libraries into app/src/main/jniLibs (gitignored, never committed)
scripts/extract-viture-libs.sh SpaceWalker.apk
# 3. Build and install
./gradlew testDebugUnitTest assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Release builds read their signing key from `~/.gradle/gradle.properties` (`ORBIT_RELEASE_*`); without it they fall back to the debug key.

## How it works

```
VITURE Pro 2 ──USB──▶ VITURE native libs ──raw IMU──▶ orientation filter (bias calibration, prediction)
                                                           │ head pose
OrbitWalker helper (shell uid) ─ virtual display per app ─▶ OpenGL ES scene on the glasses
        ▲  taps, drags, keys, scroll                          (screens placed by the layout model)
        └──── Bluetooth keyboard/mouse or phone trackpad ─▶ pointer ray ─▶ the right pixel on the right screen
```

| Folder | What's inside |
|---|---|
| `glasses/` | USB and VITURE SDK bring-up, IMU parsing |
| `tracking/` | Orientation filter, recenter, prediction |
| `layout/` | Screen arrangement, pointer ray and hit testing, dock and title bar (pure, unit-tested) |
| `render/` | The glasses scene: screens, sky, dock, cards, stereo |
| `helper/` | Wireless-debugging pairing and the built-in shell helper |
| `shell/` | The helper's service: virtual displays, input injection, raw Bluetooth input |
| `input/` | Keyboard and mouse mapping, status |
| `ui/` | The Jetpack Compose phone app |

`scripts/emulator/` holds the test rig used during development: an Android emulator with a simulated glasses display and a virtual Bluetooth keyboard and mouse.

## Roadmap

**OrbitWalker**
- Loading VITURE's libraries from your installed SpaceWalker, so the APK doesn't need to bundle them.
- 2D → 3D conversion of ordinary video with an on-device depth model.
- Saved layouts per task, and more glasses as the community asks for them.

**The Orbit family**: apps for the cheap smart devices in our lives, without the vendor cloud.

| App | The idea |
|---|---|
| **[Orbit Health](https://github.com/Kno2gether-Labs-LTD/orbit-health)** (out now) | Your smart ring's data on your phone, not a vendor's server. |
| **Orbit Secure** | Cheap cameras and doorbells, recorded to your own space. |
| **Orbit Home** | Smart plugs and bulbs controlled locally, no vendor account. |
| **[Orbit Server](https://github.com/Kno2gether-Labs-LTD/orbit-server)** (out now) | One box you own, instead of a subscription per vendor: your data, your AI agent, your voices. |
| **Orbit Agents** | Your own AI on your own hardware, under rules you set. |

Their home is **[orbitapps.space](https://orbitapps.space)**: the story behind each app, downloads, and [guides on how to use them](https://orbitapps.space/docs/).

## Built by Kno2gether Labs

OrbitWalker and the Orbit family are designed and built by **[Kno2gether Labs LTD](https://kno2gether.com)**, the team behind **[Knotie](https://knotie-ai.pro)**.

We build apps like this one, websites, and **custom AI solutions** for businesses: from private, on-device AI to agents that work with your own data. If you'd like something built, get in touch at [kno2gether.com](https://kno2gether.com) or see what Knotie can do at [knotie-ai.pro](https://knotie-ai.pro).

## Contributing

Thank you for the interest! **Contributions aren't open yet**: the project is still being set up and maintainer time is limited, so pull requests can't be reviewed for now. They will open soon. In the meantime, feedback is very welcome: open an issue with your glasses, phone and what you saw (Settings → **Share logs** helps a lot). See [CONTRIBUTING.md](CONTRIBUTING.md).

To report a security problem privately, see [SECURITY.md](SECURITY.md).

Video guides: [youtube.com/@Kno2gether](https://www.youtube.com/@Kno2gether)

## License

[Apache-2.0](LICENSE) for OrbitWalker's own code. Third-party components are listed in [NOTICE](NOTICE).

VITURE's native libraries are not part of this source repository and remain VITURE's property; the APKs under Releases bundle them so the app can talk to the glasses. OrbitWalker is an independent project, not affiliated with or endorsed by VITURE or Samsung. *VITURE*, *SpaceWalker*, *Samsung*, *Galaxy* and *DeX* are trademarks of their owners.
