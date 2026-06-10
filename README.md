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

## 🔒 Permissions & Security

- **Internet & Wi-Fi Permissions**: Needed to bind the web server port (`8765`) and discover the device's local IP address.
- **All Files Access (`MANAGE_EXTERNAL_STORAGE`)**: Required on Android 11+ to share/read files outside the app's private sandbox (specifically in `Downloads/LANBeam`).
- **Sandbox Fallback**: If permissions are denied, the app automatically falls back to internal sandbox directories, ensuring it functions safely without requesting risky permissions.

---

<p align="center">
  Developed with ❤️ for secure local file sharing.
</p>
