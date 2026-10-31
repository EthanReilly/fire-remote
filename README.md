# Fire Remote

An Android phone app that controls an Amazon Fire TV / Fire Stick over Wi-Fi — no physical remote needed.

## Features

- **Pairs with a 4-digit code** shown on the TV. It uses the Fire TV's built-in remote service (the same one Amazon's own app uses), so no developer settings or on-screen prompts are needed.
- **Instant response.** Presses are sent over one persistent connection.
- **Swipe touchpad** (swipe to move, tap to select, hold for options) or a classic **D-pad**.
- Back, Home, Menu, Play/Pause, Rewind, Fast forward, Sleep/Wake.
- One-tap **YouTube** and **Stremio** buttons, plus an Apps pop-up (Prime Video, Netflix, Disney+, BBC iPlayer, Spotify, and every installed app).
- **Find on Wi-Fi** locates the Fire TV automatically and wakes it if it's asleep.
- Fits on one screen, with no scrolling.
- **Optional volume & typing link** (ADB): enables volume control and live typing into search boxes. You accept the TV's prompt using the app's own touchpad.

## Install

Download [`FireRemote.apk`](FireRemote.apk) on your Android phone (Android 5.0+) and open it. Allow installs from unknown sources if asked, then tap **Find on Wi-Fi** and enter the code shown on your TV.

### Volume

The code connection can't change volume; Amazon's own remote app has the same limit. To use volume:

1. On the TV (using the app's touchpad), open **Settings → My Fire TV → Developer options** and turn **ADB debugging** on. If Developer options is missing, go to **My Fire TV → About** and press OK on the device name 7 times.
2. In the app, open **gear → Volume & typing link → Connect**.
3. On the TV, tick **Always allow** and select **OK**.

Volume reaches the TV through HDMI-CEC (**Settings → Display & Sounds → HDMI CEC Device Control**).

## How it works

| Feature | Protocol |
|---|---|
| Wake | `POST http://TV:8009/apps/FireTVRemote` (DIAL) |
| Pairing | `POST https://TV:8080/v1/FireTV/pin/display`, then `/pin/verify` returns a client token |
| Buttons | `POST https://TV:8080/v1/FireTV?action=dpad_up` (and similar), with no body |
| Media | `POST https://TV:8080/v1/media?action=play` or `scan` |
| Apps | `POST https://TV:8080/v1/FireTV/app/<package>` |
| Volume & typing | ADB over TCP port 5555 with RSA auth, sending `input keyevent` and `input text` |

The source lives in `src/com/fireremote/app/`:

- `LightningClient.java`: the code-pairing remote protocol (a minimal HTTPS/1.1 keep-alive client).
- `AdbClient.java` and `AdbKeys.java`: a minimal ADB client.
- `MainActivity.java`, `TouchpadView.java` and `DpadView.java`: the user interface.

## Build

You can build it with no Android Studio or Gradle, using Debian/Ubuntu packages:

```bash
sudo apt-get install aapt android-sdk-platform-23 apksigner zipalign dalvik-exchange openjdk-17-jdk python3-pil
./build.sh
```

`build.sh` creates a signing key (`release.keystore`) the first time it runs. Keep that file, and don't commit it: updates must be signed with the same key to install over an existing copy.

## Credits

The Fire TV remote protocol details come from community reverse-engineering, including the [Home Assistant community thread](https://community.home-assistant.io/t/rest-commands-for-fire-tv/901314) and [pyfiretvremote](https://pypi.org/project/pyfiretvremote/).
