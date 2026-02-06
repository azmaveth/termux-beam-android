# termux-beam-android

Run the Erlang/OTP BEAM virtual machine as an Android foreground service with a bridge to Android system APIs. Built entirely from Termux.

The BEAM VM runs as a child process inside the app, with a TCP-based bridge that lets Erlang code call Android APIs -- sensors, vibration, toasts, notifications, clipboard, WiFi, and more.

## Architecture

```
┌─────────────────────────────────────────────┐
│                 Android App                  │
│                                              │
│  ┌──────────────┐     ┌──────────────────┐  │
│  │ MainActivity │     │   BeamService    │  │
│  │   (UI/Log)   │     │ (Foreground Svc) │  │
│  └──────┬───────┘     └──────┬───────────┘  │
│         │                    │               │
│         │ TCP:9876    ┌──────┴──────┐        │
│         │ (commands)  │ BridgeServer│        │
│         │             │  TCP:9877   │        │
│         │             └──────┬──────┘        │
│         │                    │ JSON protocol │
│         ▼                    ▼               │
│  ┌─────────────────────────────────────┐    │
│  │         BEAM VM (beam.smp)          │    │
│  │  OTP 28 / ERTS 16.2 / 8 schedulers │    │
│  │                                      │    │
│  │  ┌──────────┐  ┌─────────────────┐  │    │
│  │  │android.erl│  │ Command Server  │  │    │
│  │  │(gen_server)│  │   TCP:9876     │  │    │
│  │  └──────────┘  └─────────────────┘  │    │
│  └─────────────────────────────────────┘    │
└─────────────────────────────────────────────┘
```

**BEAM VM** runs as a native aarch64 process (beam.smp), bundled directly in the APK.

**BridgeServer** (Java) exposes Android APIs over a TCP JSON protocol on port 9877.

**android.erl** (Erlang gen_server) connects to the bridge and provides an idiomatic Erlang API.

**Command Server** (Erlang) listens on port 9876 for interactive text commands from the UI.

## Prerequisites

- Android device (aarch64) with [Termux](https://f-droid.org/en/packages/com.termux/) installed
- Java (JDK) installed: `pkg install openjdk-21`

## Quick Start

```bash
git clone https://github.com/azmaveth/termux-beam-android.git
cd termux-beam-android

# Install build tools + Erlang/OTP
bash setup.sh

# Bundle BEAM runtime into APK assets
bash prepare_assets.sh

# Build the APK
bash build.sh

# Grant storage access (needed once)
termux-setup-storage

# Install on device
cp build/apk/beamapp.apk ~/storage/shared/ && termux-open ~/storage/shared/beamapp.apk
```

## Available Commands

Send these from the app's input field once the BEAM VM is running:

| Command | Description |
|---------|-------------|
| `device` | Device model, manufacturer, Android version, SDK level |
| `battery` | Battery level, charging status, temperature, voltage |
| `memory` | JVM memory usage (total, free, max, used) |
| `wifi` | WiFi SSID, BSSID, RSSI, link speed, frequency |
| `network` | Network connection type and status |
| `sensors` | List all available hardware sensors |
| `accel` | Read accelerometer (starts sensor, waits 200ms, reads) |
| `gyro` | Read gyroscope |
| `light` | Read ambient light sensor |
| `brightness` | Screen brightness level |
| `vibrate` | Vibrate for 200ms |
| `toast <msg>` | Show an Android toast message |
| `notify <title> <body>` | Post a notification |
| `clipboard` | Read clipboard contents |
| `copy <text>` | Copy text to clipboard |
| `packages` | List installed apps |
| `ping` | Bridge health check |
| `procs` | BEAM process count |
| `prop <name>` | Read Android system property |

## Erlang API

The `android.erl` module provides a full Erlang API for the bridge:

```erlang
%% Start the bridge
{ok, _} = android:start_link().

%% Query device info
{ok, Info} = android:device_info().

%% Sensors
android:sensor_start(accelerometer).
{ok, Data} = android:sensor_read(accelerometer).
android:sensor_stop(accelerometer).

%% Feedback
android:vibrate(500).
android:toast(<<"Hello from Erlang!">>).
android:notify(<<"Title">>, <<"Body">>).

%% Clipboard
android:clipboard_set(<<"copied from BEAM">>).
{ok, Text} = android:clipboard_get().

%% System info
{ok, Batt} = android:battery().
{ok, Wifi} = android:wifi_info().
{ok, Mem} = android:memory_info().
{ok, Pkgs} = android:packages().
```

## Bridge Protocol

The bridge uses newline-delimited JSON over TCP:

**Request:**
```json
{"id":1,"cmd":"device_info","args":""}
```

**Response:**
```json
{"id":1,"ok":true,"data":{"model":"Pixel","android_version":"16",...}}
```

**Error:**
```json
{"id":1,"ok":false,"error":"sensor not found"}
```

## Project Structure

```
.
├── AndroidManifest.xml
├── setup.sh                          # Install dependencies
├── prepare_assets.sh                 # Bundle BEAM runtime
├── build.sh                          # Build APK
├── erlang_src/
│   └── android.erl                   # Erlang bridge module (gen_server)
├── jni/
│   └── beam_launcher.c              # Reference JNI launcher (unused)
├── res/
│   ├── layout/activity_main.xml
│   └── values/strings.xml
└── src/com/example/beamapp/
    ├── MainActivity.java             # UI: log viewer + command input
    ├── BeamService.java              # Foreground service managing BEAM lifecycle
    └── BridgeServer.java             # TCP bridge: Android APIs for BEAM
```

## How It Works

1. **BeamService** starts as an Android foreground service
2. OTP runtime files are extracted from APK assets to app-private storage
3. Symlinks are created for shared libraries with proper sonames
4. **BridgeServer** starts listening on TCP port 9877
5. **beam.smp** is launched via `ProcessBuilder` with `LD_LIBRARY_PATH` set
6. The BEAM boots OTP, starts `android:start_link()` which connects to the bridge
7. A command server starts on port 9876 for interactive use from the UI
8. Commands typed in the UI are sent over TCP to the BEAM, which dispatches them through the bridge to Android APIs

## Tested On

- BraX3, Android 16 (API 36), aarch64, 8 cores, 8GB RAM
- OTP 28, ERTS 16.2
- Termux with JDK 21

## License

MIT
