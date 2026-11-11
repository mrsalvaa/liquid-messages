# Liquid Messages

**English** | [Español](README.es.md)

Installs on the phone as **Messages** (*Mensajes* in Spanish).

A free, tracker-free SMS and MMS app with the same liquid-glass interface as the rest of the suite
([Liquid Clock](https://github.com/mrsalvaa/liquid-clock), Calendar, Contacts, Phone, Gallery,
Recorder and Calc). Settings also has the Solid style.

**Privacy in three lines:** the app has no internet permission. Messages stay in the phone's own
message store and only leave it as the SMS or MMS you send. No ads, telemetry, crash reporting or
Google Play Services.

Licensed under GPL-3.0-or-later.

## Features

- **SMS and MMS**: photos, group conversations, subjects, delivery reports (optional), long
  messages with a part counter. MMS that failed to download stay with a "Download" button and are
  never lost.
- **Dual SIM without surprises**: the SIM is never switched silently. Each conversation keeps its
  SIM by default; you can also use a primary SIM, always ask, or let the system decide.
- **Undo**: text messages wait 5 seconds before sending (optional); deleting shows an "Undo"
  button. Deleted messages go to a 30-day trash.
- **Scheduled messages**, drafts, quick replies and replies from the notification.
- **Possible spam**: unknown numbers with scam-like links go to their own folder, without
  notifying. Decided on the phone, no lists from the internet; contacts and people you wrote to are
  never filtered.
- **Verification codes**: "Copy code" in the notification and in the message; optional cleanup of
  codes already read.
- **Private conversations**, hidden from the list, search and Starred; they open with your
  fingerprint or PIN. Optional app lock.
- **Starred messages**, message details, search inside a conversation and "go to date".
- **Reactions** sent from iPhone or Google Messages ("Liked “…”") show as an emoji on the original
  message.
- Pin, archive, mute, per-conversation sound, block numbers, swipe actions, multi-select.
- **Backup**: export and import in the "SMS Backup & Restore" XML format, through the system file
  picker.
- Bubble color, text size, compact list, notification preview (all, sender only, nothing), app icon.

## Permissions

| Permission | Why |
|---|---|
| `READ_SMS`, `SEND_SMS`, `RECEIVE_SMS`, `RECEIVE_MMS`, `RECEIVE_WAP_PUSH` | Being the SMS app. Android only lets the default SMS app write messages. |
| `READ_CONTACTS` | Names and photos in the list. Contacts are never modified. |
| `READ_PHONE_STATE` | Knowing which SIMs there are, to send through the right one. |
| `POST_NOTIFICATIONS` | New-message notifications. |
| `USE_BIOMETRIC` | App lock and private conversations (the phone's own fingerprint or PIN). |
| `RECEIVE_BOOT_COMPLETED` | Re-arming scheduled messages after a restart. |

No internet, location or phone number. `INTERNET`, `ACCESS_NETWORK_STATE`, location and
`READ_PHONE_NUMBERS` are explicitly removed from the merged manifest. The app never reads the IMEI,
IMSI, ICCID or your own number.

## Dependencies

| Library | License | Use |
|---|---|---|
| Jetpack Compose, AndroidX (core, activity, lifecycle) | Apache-2.0 | UI |
| kotlinx.serialization | Apache-2.0 | Settings, drafts and trash as JSON |
| [Backdrop](https://github.com/Kyant0/AndroidLiquidGlass) | Apache-2.0 | Glass effect |
| [libphonenumber](https://github.com/google/libphonenumber) | Apache-2.0 | Matching numbers with contacts |
| AOSP Messaging `mmslib` (copied, see `app/src/main/java/dev/liquidmessages/mms/pdu/NOTICE`) | Apache-2.0 | Reading and writing MMS |

## Download

See the [changelog](CHANGELOG.md) for what's new in each version.

Get the APK from [Releases](../../releases) and install it (Android 12 or later). Then open it and
make it the default SMS app.

### If the phone refuses to install it

Messages asks for SMS permissions, and Android blocks apps like that when they come from outside a
store. The APK is fine (check it below); to install it:

- **Play Protect**: Play Store → your picture → Play Protect → ⚙️ → turn off "Scan apps with Play
  Protect" while installing, then turn it back on.
- **Samsung Auto Blocker**: Settings → Security and privacy → Auto Blocker → turn it off while
  installing.
- **Or install it from a computer** with USB debugging on: `adb install liquid-messages-v1.0.2.apk`.

Check the file wasn't altered:

```bash
sha256sum -c liquid-messages-v1.0.2.apk.sha256
```

SHA-256 of `liquid-messages-v1.0.2.apk`:

```
8f2b99995529a59cc4fac53ff0e171e0b3cea9309afa0abe9b1b3104c982a61b
```

And that it's signed with the project key (certificate SHA-256 fingerprint):

```
2B:F0:DF:7E:60:20:6B:CF:8E:34:C0:02:72:99:AB:88:60:B8:91:F3:30:D4:83:26:B4:55:27:2E:3A:9C:52:24
```

```bash
apksigner verify --print-certs liquid-messages-v1.0.2.apk
```

## Build

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :core:test assembleDebug
```

To check real smoothness on the phone, use `assembleProfile` (optimized like the release, installs
as the test build): Compose debug builds are several times slower.

Debug and profile install as `dev.liquidmessages.debug`, "Messages (test)".
