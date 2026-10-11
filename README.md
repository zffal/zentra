# Zentra — Android Server Management Client

**Your infrastructure. One place.**

Zentra is a modern, native Android client for self-hosted Linux and Ubuntu servers. Designed with a dark graphite, minimalist aesthetic and an intuitive beginner-friendly UX, Zentra lets you monitor your server's health, track real-time telemetry, manage Docker containers, inspect logs, and dispatch Wake-on-LAN packets directly from your phone.

---

## Highlights

- **3-Second Health Verdict:** Instantly answers *"Is my server healthy?"* with simple human-readable status banners (`SERVER HEALTHY`, `NEEDS ATTENTION`, `OFFLINE`).
- **Real-Time Telemetry via WebSockets:** Live streaming updates for CPU load, RAM usage, storage consumption, temperatures, and network bandwidth without manual refreshing.
- **Simplified Services (Docker):** Manage containers with human-friendly service states (`Running`, `Stopped`), safe action confirmation dialogs, and a real-time terminal log viewer.
- **Smart Alert & Notification Engine:** Intelligent threshold monitoring with hysteresis (CPU temp warning $\ge 90^\circ\text{C}$ with recovery $\le 85^\circ\text{C}$, CPU usage warning $\ge 90\%$ with recovery $\le 80\%$), deduplication, foreground awareness, and instant deep-linking.
- **Wake-on-LAN (WOL) Integration:** Wake up sleeping or powered-off home lab servers with one tap using standard magic packets.
- **Floating Capsule Navigation:** Minimalist icon-only floating navigation bar with horizontal swipe gesture synchronization.
- **Strict Privacy & Security:** Client-authoritative JWT token storage via Android `EncryptedSharedPreferences` (AES-256-GCM). Zero telemetry leaks, zero third-party trackers, zero ads.
- **Flexible Networking:** Seamlessly connect over local home Wi-Fi (LAN) or encrypted mesh VPNs (Tailscale, WireGuard).

---

## UI / UX Design

Zentra follows modern iOS/macOS and utility-focused design principles (inspired by Linear, Raycast, and btop):

- **Clean Typography & Spacing:** Generous whitespace, refined contrast, and clean tabular numeric readouts.
- **Floating Capsule Bar:** An icon-only capsule navigation dock anchored cleanly above system gestures, supporting long-press tooltips.
- **Edge-to-Edge Experience:** Full window inset handling supporting gesture bars, cutouts, and status bars.
- **Zero Jargon Defaults:** Clear explanations accompany every metric card, helping beginners understand their server while power users can tap into deep technical breakdowns.

```text
┌──────────────────────────────────────────────┐
│  ● SERVER HEALTHY                            │
│  All systems running normally                │
├──────────────────────────────────────────────┤
│  [ CPU ]       [ RAM ]       [ STORAGE ]     │
│   14%           4.2 GB          120 GB       │
│  Very low      Plenty free     Plenty space  │
├──────────────────────────────────────────────┤
│  Services (Docker)                           │
│  ● Nextcloud        Active    [Restart]      │
│  ● Home Assistant   Active    [Logs]         │
│  ● Plex             Active    [Restart]      │
├──────────────────────────────────────────────┤
│               ( ☵  ⚙  ⊞  ⚡ )                 │
└──────────────────────────────────────────────┘
```

---

## Architecture

Zentra is a 100% native Android application built using Kotlin and modern Android architecture components:

```text
┌────────────────────────────────────────────────────────┐
│               Zentra Android Client                    │
│         Kotlin • MVVM • Material 3 • Coroutines        │
├──────────────────────────┬─────────────────────────────┤
│  Overview & Metrics      │  Docker & Service Controls  │
│  Live WebSocket Stream   │  Logs & Alerts History      │
└──────────────────────────┴─────────────────────────────┘
                           │
                           │ REST (HTTP/HTTPS) & WebSockets (WS/WSS)
                           │ e.g. http://192.168.1.100:8765 or Tailscale
                           v
┌────────────────────────────────────────────────────────┐
│               Server Backend Agent                     │
│            FastAPI • Python 3.10+ / Linux              │
│       psutil • Docker SDK API • SQLite • systemd       │
└────────────────────────────────────────────────────────┘
```

- **Networking:** OkHttp 4 + WebSockets with automatic exponential backoff reconnection.
- **JSON Serialization:** Google Gson.
- **Credential Storage:** Android Jetpack Security (`EncryptedSharedPreferences`).
- **Reactive UI:** Kotlin Coroutines (`Dispatchers.IO` / `Dispatchers.Main`) and ViewBinding.
- **Testing:** Comprehensive JUnit 4 test suites covering URL normalization, alert state machines, hysteresis, and telemetry deserialization.

---

## Android Permissions

Zentra requests only the minimum set of permissions necessary for server communication:

| Permission | Purpose |
|------------|---------|
| `android.permission.INTERNET` | Communicates with the server REST APIs and WebSockets. |
| `android.permission.ACCESS_NETWORK_STATE` | Detects connection transitions (Wi-Fi, Cellular, VPN). |
| `android.permission.ACCESS_WIFI_STATE` | Determines local Wi-Fi state for Wake-on-LAN broadcast routing. |
| `android.permission.POST_NOTIFICATIONS` | Delivers critical server notifications and threshold alerts (optional). |

*Note: Zentra never requests camera, microphone, GPS location, contacts, or external storage permissions.*

---

## Requirements

- **Android Device:** Android 8.0 (API level 26) or newer.
- **Server Backend:** A Linux host (e.g. Ubuntu 20.04/22.04/24.04, Debian, Raspberry Pi OS) running the backend monitoring service on port `8765`.

---

## Getting Started

### 1. Prerequisites
- [Android Studio Ladybug (or newer)](https://developer.android.com/studio)
- JDK 17 or higher
- Android SDK with Platform 35

### 2. Setup Build Environment
1. Clone this repository:
   ```bash
   git clone https://github.com/your-username/zentra-android.git
   cd zentra-android
   ```
2. Copy `local.properties.example` to `local.properties`:
   ```bash
   cp local.properties.example local.properties
   ```
3. Set your Android SDK path inside `local.properties`:
   ```properties
   sdk.dir=/Users/username/Library/Android/sdk
   # Or on Windows:
   # sdk.dir=C\:\\Users\\username\\AppData\\Local\\Android\\Sdk
   ```

### 3. Build & Run
- Open the project in Android Studio and click **Run**, or build using the Gradle wrapper:
  ```bash
  # On Linux / macOS
  ./gradlew assembleDebug

  # On Windows PowerShell
  .\gradlew.bat assembleDebug
  ```

---

## Server Setup

Zentra connects to the lightweight backend monitoring agent running on your server.

1. Ensure the backend service is installed and active on your server:
   ```bash
   systemctl status omnis.service
   ```
2. The agent listens on port `8765` by default:
   - **Local LAN:** `http://192.168.1.100:8765` *(example)*
   - **Tailscale VPN:** `http://100.x.x.x:8765` *(example)*
3. Verify server health:
   ```bash
   curl http://127.0.0.1:8765/api/health
   # Response: {"status":"healthy","service":"omnis","version":"1.0.0"}
   ```
4. In the Zentra Android app, enter your server address, create or log in to your account, and your dashboard will immediately connect.

---

## Contributing

Contributions, bug reports, and suggestions are welcome!

1. Fork the repository.
2. Create your feature branch (`git checkout -b feature/amazing-feature`).
3. Commit your changes (`git commit -m 'Add amazing feature'`).
4. Push to the branch (`git push origin feature/amazing-feature`).
5. Open a Pull Request.

---

## License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.
