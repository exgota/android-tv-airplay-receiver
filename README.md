# Android TV AirPlay Receiver

AirPlay receiver for old Android TV hardware: screen mirroring (H.264), audio (AAC-ELD, AAC-LC, ALAC, PCM), and photos. Pure Java, no native code, no root. About 270 KB.

Uses legacy AirPlay pairing, FairPlay SAP v1 and the AirPlay 1 timing protocol, the same approach as UxPlay. FairPlay DRM content (Netflix, Apple TV+) cannot work.

## Tested

| Device | OS | Result |
|---|---|---|
| Fire TV 2nd gen (AFTS, MT8173) | Fire OS 5, Android 5.1 | 1080p30, ~6 ms decode latency |
| Chromecast with Google TV 4K (sabrina) | Android 14 | 1080p30, 4K30 from a Mac |

Senders: current iPhone and Mac (October 2026), screen mirroring and AirPlay audio.

## Build and install

Needs JDK 17 and Android SDK 34.

```sh
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
# Android 10+: allow the service to open the video screen from the background
adb shell appops set io.github.airplayreceiver SYSTEM_ALERT_WINDOW allow
```

Open the app once. It runs as a foreground service, starts on boot, and advertises over mDNS on port 7000.

## Hardware notes

- **MediaTek decoders hold about 7 frames (~90 ms) by default.** This Fire OS build's ACodec reads the vendor format keys `vdec-lowlatency` and `vdec-no-reorder`. Setting them cut decode latency to ~6 ms. Newer devices use `KEY_LOW_LATENCY` (Android 11+).
- **Android 5.1 has no ALAC decoder.** `AlacDecoder.java` is a port of Apple's reference decoder. Its output matches `afconvert` bit for bit.
- **AAC-ELD needs `csd-0` = `F8 E8 50 00`**, and the `OMX.google.aac.decoder` software decoder.
- **iOS restarts the audio stream on every pause.** Treat each SETUP as a new audio stream.

## Layout

- `protocol/`: platform-independent AirPlay. Covers RTSP/HTTP control, pairing, FairPlay, stream decryption, mDNS responder, binary plist, ALAC.
- `android/`: service, MediaCodec video and audio renderers, UI.

## Credits and license

GPL-3.0. FairPlay code derives from [java-airplay-lib](https://github.com/serezhka/java-airplay-lib) (MIT, notice in `protocol/fairplay/`), a Java port of playfair. Protocol details follow [UxPlay](https://github.com/FDH2/UxPlay).
