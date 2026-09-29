<p align="center">
  <img src="https://img.shields.io/badge/Kotlin-1.9+-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin">
  <img src="https://img.shields.io/badge/Android-SDK%2024%20--%2036-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android">
  <img src="https://img.shields.io/badge/Jetpack%20Compose-UI-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white" alt="Compose">
</p>

<h1 align="center">📡 LAN Beam (Android App)</h1>
<p align="center">
  <strong>Blazing fast file transfer over your local Wi-Fi, hosted directly on your Android phone</strong><br>
  No USB cables. No cloud services. No registration. Just your home Wi-Fi network.
</p>

---

## What is LAN Beam Android?

This app turns your Android device into a local Web/File server. Other devices (PCs, iPhones, Macs, Laptops, or other Androids) on the same Wi-Fi network can connect to it via their browser to browse, download files, or upload files directly to your phone.

1. **Start Server:** Launch the app and toggle the **Live** switch.
2. **Scan/Connect:** Let the other device scan the QR code displayed on your screen or navigate to the connection URL (e.g. `http://192.168.1.5:8765`).
3. **Transfer:** Fast local Wi-Fi speeds (~10–100 MB/s depending on your router).

---

## ✨ Features

| Feature | Description |
|---------|-------------|
| 📲 **Host Server on Phone** | Uses an embedded `NanoHTTPD` server running locally on port `8765`. |
| 🛡️ **Hardened Security** | Path traversal protection prevents unauthorized filesystem access. |
| 📂 **Add & Share Files** | Share files using the native system file picker. |
| 📤 **Upload Files** | Other devices can send files (photos, videos, docs) back to your phone via the web interface. |
| 🏷️ **Custom Hostname** | Set a custom name for your device so connecting clients know who they are connected to. |
| 📱 **Local WebView** | View and manage the shared files directly from the app using the built-in web portal. |
| 📤 **Share APK** | Share the LAN Beam installer APK itself with nearby devices directly from the app header. |
| 📳 **No-Internet Operation** | Works completely offline; files never leave your local Wi-Fi network. |

---

## 🏗️ Technical Architecture

- **HTTP Server**: `NanoHTTPD` (lightweight Java web server running inside an Android Service/Activity).
- **Frontend Assets**: Pre-compiled single-file web portal (`frontend.html`) served from Android assets.
- **UI Framework**: Modern **Jetpack Compose** (Material 3) with full edge-to-edge styling.
- **QR Code Engine**: `com.google.zxing:core` to generate scan-to-connect QR codes on the fly.
- **File System**:
  - *Shared Directory*: Files you pick to share (`Download/LANBeam/Shared` or App Internal sandbox if storage permission is not granted).
  - *Uploads Directory*: Files sent to the phone (`Download/LANBeam/Uploads` or App Internal sandbox).

---

## 🛠️ How to Compile and Build the APK

### Prerequisites
- **Java Development Kit (JDK)**: Version 17
- **Android SDK**: Build tools and platform for API 36 (Android 16 / Android V)

### Compiling via CLI
Use the included Gradle wrapper to clean and build the app:

```bash
# Set execute permission if needed
chmod +x gradlew

# Build the debug APK
./gradlew clean assembleDebug
```

The compiled APK will be located at:
`app/build/outputs/apk/debug/app-debug.apk`

For convenience, we copy the compiled output to the **`apks/`** directory as **`apks/LAN-Beam-v1.1.apk`** (where you can find all versions of the compiled APKs).

---

## 🔧 v2.2 transfer engine (what changed and why)

| Symptom | Root cause (v2.1) | Fix |
|---|---|---|
| Large downloads stall at ~90-99 % (.mov on iPhone, resumed downloads) | Range (206) responses advertised the **full** file size in `Content-Length`, so any resumed/ranged request waited for bytes that never came | Length set once, from the range; `If-Range`/`ETag`, suffix ranges, 416; HEAD no longer sends a body |
| Uploads slow; >2 GB uploads silently fail | NanoHTTPD multipart parsing: 512-byte reads, file written 3 times, `mmap` of the whole body (fails > 2 GB, HTTP 500 shown as success) | Resumable raw chunk uploads (`/api/upload/chunk`, 8 MB), written once into place, auto-retry/resume after drops |
| Transfers slow down with the screen off | No wake lock / Wi-Fi lock; 5 s socket timeout | Partial wake lock + Wi-Fi high-perf/low-latency locks while bytes move; 30 s timeout |
| Works on hotspot, fails on the same router Wi-Fi | URL/QR used the first IPv4 of any interface: often mobile data (`rmnet`), 464XLAT (`192.0.0.4`) or VPN (`tun`) | Addresses ranked from the actual Wi-Fi client / hotspot interface; all shown as chips. Router **AP/client isolation** still blocks device-to-device traffic: explained in-app |
| App closes after sharing a file into it | Share-sheet copy ran on the main thread (ANR on big files); foreground-service promise not kept on repeat starts; Android 15 6-hour dataSync timeout | Copy runs in the service on a worker thread with the URI grant; `startForeground()` on every start; `onTimeout()` handled |

API additions: `GET/POST/DELETE /api/upload/chunk?id=&name=&offset=&total=`, `PUT /api/upload/raw?name=` (e.g. `curl -T file`), `GET /api/ping`. The old multipart `/api/upload` still works.

### Verifying

```bash
./gradlew testDebugUnitTest                                   # server, ranges, resume, legacy bug repros
./gradlew testDebugUnitTest --tests '*TransferBenchmarkTest*' -Dlanbeam.bench=512   # v2.1 vs v2.2 on loopback
scripts/lanbeam-bench.sh http://PHONE_IP:8765 512             # real numbers on your Wi-Fi
```

---

## 🔒 Permissions & Security

- **Internet & Wi-Fi Permissions**: Needed to bind the web server port (`8765`) and discover the device's local IP address.
- **All Files Access (`MANAGE_EXTERNAL_STORAGE`)**: Required on Android 11+ to share/read files outside the app's private sandbox (specifically in `Downloads/LANBeam`).
- **Sandbox Fallback**: If permissions are denied, the app automatically falls back to internal sandbox directories, ensuring it functions safely without requesting risky permissions.

---

<p align="center">
  Developed with ❤️ for secure local file sharing.
</p>
