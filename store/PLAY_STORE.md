# Google Play submission kit

Everything the Play Console asks for, ready to paste. Build artefacts are produced by
`./gradlew bundleRelease` (upload `app/build/outputs/bundle/release/app-release.aab`).

## App identity
- Package: `io.github.m1d0r1x.lanbeam` (permanent once uploaded)
- Version: 2.3 (versionCode 7). Bump `versionCode` for every upload.
- Signing: enrol in **Play App Signing**; the AAB is signed with the *upload key*
  `~/.android/lanbeam-upload.jks` (credentials in the git-ignored `keystore.properties`).
  Back both up somewhere safe; Google support can reset a lost upload key, but it takes days.

## Store listing
- **App name:** LAN Beam: Wi-Fi File Transfer
- **Short description (80):** Send files between your phone and any device on your Wi-Fi. No cables, no cloud.
- **Full description:**

  LAN Beam turns your phone into a fast, private file-sharing point on your own Wi-Fi.

  Open the link or scan the QR code on a laptop, tablet or another phone. It works in any browser, so there's nothing to install on the other side. Download what you share, or send files to your phone from the browser.

  • Fast: files go straight across your local network, never through the internet
  • Big files: resumable transfers that pick up where they left off if Wi-Fi drops
  • Works everywhere: Windows, Mac, Linux, iPhone, iPad, Chromebook and Android, all through a browser
  • Share from any app: use Android's share sheet to add photos, videos, APKs and documents
  • Received files land in Download/LANBeam and show up in Files and Gallery
  • Works on home Wi-Fi or on your phone's own hotspot, even with no internet
  • Private: no account, no ads, no tracking, no servers

- **Category:** Tools
- **Tags:** file transfer, Wi-Fi, share
- **Icon:** `store/icon-512.png` · **Feature graphic:** `store/feature-graphic-1024x500.png`
- **Screenshots:** at least 2 phone screenshots (take on the device: connect card, transfers, Received tab)
- **Privacy policy URL:** https://github.com/M1D0R1x/LAN_Beam_Android/blob/master/PRIVACY.md

## Data safety form
- Does your app collect or share user data? **No**
- (Files are transferred only between the user's own devices on their local network at the user's
  request; nothing is sent to the developer or third parties, so nothing counts as "collected".)
- Encrypted in transit? **No** (local network HTTP). Answer honestly; this is allowed.
- Account deletion: not applicable (no accounts).

## Foreground service declaration (Policy → App content → Foreground service permissions)
- Type: **dataSync**
- Use case: *"Keeps the local file-transfer server running while the user transfers files between their
  own devices over Wi-Fi. Started only by the user (switch or share sheet), shows a persistent
  notification with a Stop action, and stops when the user turns sharing off."*
- Demo video: record 20-30 s showing start → transfer → notification → Stop.

## Storage and media permissions (why there are none to declare)
- Files to share come from the **Android photo picker** (photos/videos) and the **system file picker**
  (everything else), plus the share sheet. These grant access to the picked items only and need no
  permission. Play's Photo and Video Permissions policy allows `READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO`
  only when a picker cannot serve the core feature, so LAN Beam does not request them, and therefore
  never shows the "Allow all / Limited / Don't allow" media dialog.
- Received files are created in `Download/LANBeam` via direct paths (allowed without permission on
  Android 11+). Android 10 and older use `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion=29`).
- No `MANAGE_EXTERNAL_STORAGE`, so no All-files-access declaration.

## Other App content answers
- Ads: No · Target audience: 18+ (or 13+) · Content rating questionnaire: Utility, no user-generated content shared publicly.
- News app / COVID / Government: No.
- Sensitive permissions: none restricted (no All-files access, no SMS/Call log, no location).

## Pre-launch checklist
- [ ] Install `app-release.apk` on a real phone and walk through: welcome → permissions → share a file in → download it on a laptop → send a file from the laptop → open it in Received.
- [ ] Internal testing track first; Play shows a pre-launch report (crashes, accessibility).
- [ ] New personal developer accounts must run a **closed test with 12+ testers for 14 days** before production access.
