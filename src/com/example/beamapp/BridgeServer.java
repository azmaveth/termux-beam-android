package com.example.beamapp;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;
import android.widget.Toast;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TCP bridge server that lets BEAM processes call Android APIs.
 * Protocol: JSON lines over TCP on port 9877.
 *
 * Request:  {"id":1,"cmd":"device_info","args":[]}
 * Response: {"id":1,"ok":true,"data":{...}}
 * Error:    {"id":1,"ok":false,"error":"message"}
 */
public class BridgeServer {
    private static final String TAG = "BridgeServer";
    public static final int PORT = 9877;

    private final Context context;
    private ServerSocket serverSocket;
    private volatile boolean running = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /* Sensor state */
    private SensorManager sensorManager;
    private final ConcurrentHashMap<Integer, float[]> sensorData = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, SensorEventListener> sensorListeners = new ConcurrentHashMap<>();

    /* Audio recording state */
    private AudioRecord audioRecord;
    private final AtomicBoolean recording = new AtomicBoolean(false);
    private String lastRecordingPath;
    private static final int SAMPLE_RATE = 16000;
    private String recordingDir;

    /* Bluetooth */
    private BluetoothAdapter btAdapter;
    private static final UUID CCCD_UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    /* Speech engine (JNI-based STT/TTS/VAD) */
    private SpeechEngine speechEngine;

    /* Buddie earbuds BLE service */
    private BuddieService buddieService;

    /* Phone API (camera, media, files, etc.) */
    private PhoneAPI phoneAPI;

    public BridgeServer(Context context) {
        this.context = context;
        this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.recordingDir = new File(context.getFilesDir(), "recordings").getAbsolutePath();
        new File(recordingDir).mkdirs();
        BluetoothManager btManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (btManager != null) this.btAdapter = btManager.getAdapter();
        /* Initialize JNI-based speech engine on background thread */
        speechEngine = new SpeechEngine(context);
        new Thread(new Runnable() {
            @Override
            public void run() {
                speechEngine.init();
            }
        }, "speech-init").start();
        buddieService = new BuddieService(context, btAdapter, speechEngine);
        phoneAPI = new PhoneAPI(context);
    }

    public void start() {
        running = true;
        prewarmSensors();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    serverSocket = new ServerSocket(PORT);
                    Log.i(TAG, "Bridge server listening on port " + PORT);
                    while (running) {
                        Socket client = serverSocket.accept();
                        Log.i(TAG, "Bridge client connected");
                        handleClient(client);
                    }
                } catch (Exception e) {
                    if (running) Log.e(TAG, "Bridge server error", e);
                }
            }
        }, "bridge-server").start();
    }

    private void prewarmSensors() {
        int[] types = {
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_LIGHT
        };
        for (final int type : types) {
            Sensor sensor = sensorManager.getDefaultSensor(type);
            if (sensor == null) continue;
            SensorEventListener listener = new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent event) {
                    sensorData.put(type, event.values.clone());
                }
                @Override
                public void onAccuracyChanged(Sensor s, int accuracy) {}
            };
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI, mainHandler);
            sensorListeners.put(type, listener);
        }
        Log.i(TAG, "Pre-warmed " + sensorListeners.size() + " sensors");
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception e) { /* ignore */ }
        /* Clean up sensor listeners */
        for (SensorEventListener l : sensorListeners.values()) {
            sensorManager.unregisterListener(l);
        }
        sensorListeners.clear();
        /* Clean up Buddie BLE service */
        if (buddieService != null) {
            buddieService.release();
        }
        /* Clean up phone API */
        if (phoneAPI != null) {
            phoneAPI.release();
        }
        /* Clean up speech engine */
        if (speechEngine != null) {
            speechEngine.release();
        }
    }

    private void handleClient(final Socket client) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader in = new BufferedReader(
                        new InputStreamReader(client.getInputStream()));
                    PrintWriter out = new PrintWriter(client.getOutputStream(), true);

                    String line;
                    while ((line = in.readLine()) != null) {
                        String response = dispatch(line.trim(), out);
                        if (response != null) {
                            out.println(response);
                        }
                    }
                    client.close();
                    Log.i(TAG, "Bridge client disconnected");
                } catch (Exception e) {
                    Log.e(TAG, "Client handler error", e);
                }
            }
        }, "bridge-client").start();
    }

    /* ---- Command dispatch ---- */

    private String dispatch(String request, PrintWriter out) {
        String id = "0";
        try {
            /* Minimal JSON parsing — avoid external deps */
            id = jsonGet(request, "id");
            String cmd = jsonGet(request, "cmd");
            String args = jsonGet(request, "args");

            String result;
            switch (cmd) {
                case "device_info":    result = cmdDeviceInfo(); break;
                case "battery":        result = cmdBattery(); break;
                case "vibrate":        result = cmdVibrate(args); break;
                case "toast":          result = cmdToast(args); break;
                case "clipboard_get":  result = cmdClipboardGet(); break;
                case "clipboard_set":  result = cmdClipboardSet(args); break;
                case "sensors_list":   result = cmdSensorsList(); break;
                case "sensor_start":   result = cmdSensorStart(args); break;
                case "sensor_read":    result = cmdSensorRead(args); break;
                case "sensor_stop":    result = cmdSensorStop(args); break;
                case "wifi_info":      result = cmdWifiInfo(); break;
                case "network_info":   result = cmdNetworkInfo(); break;
                case "location":       result = cmdLocation(); break;
                case "packages":       result = cmdPackages(); break;
                case "system_prop":    result = cmdSystemProp(args); break;
                case "memory_info":    result = cmdMemoryInfo(); break;
                case "screen_brightness": result = cmdScreenBrightness(); break;
                case "notify":         result = cmdNotify(args); break;
                case "mic_record":     result = cmdMicRecord(args); break;
                case "mic_stop":       result = cmdMicStop(); break;
                case "tts":            result = cmdTts(args); break;
                case "tts_voices":     result = cmdTtsVoices(args); break;
                case "stt":            result = cmdStt(args); break;
                case "listen":         result = cmdListen(args); break;
                case "pre_listen":     speechEngine.startPreRecord(); result = "\"ok\""; break;
                case "stream_listen":  result = cmdStreamListen(args, id, out); break;
                case "speech_status":  result = speechEngine.getStatus(); break;
                case "transcribe_offline": result = cmdTranscribeOffline(args); break;
                case "diarize":        result = cmdDiarize(args); break;
                case "services":       result = cmdServices(); break;
                case "features":       result = cmdFeatures(); break;
                case "shell":          result = cmdShell(args); break;
                case "bt_status":      result = cmdBtStatus(); break;
                case "bt_bonded":      result = cmdBtBonded(); break;
                case "bt_connected":   result = cmdBtConnected(); break;
                case "bt_scan":        result = cmdBtScan(args); break;
                case "bt_scan_ble":    result = cmdBtScanBle(args); break;
                case "bt_gatt":        result = cmdBtGatt(args); break;
                case "bt_gatt_read":   result = cmdBtGattRead(args); break;
                case "bt_gatt_write":  result = cmdBtGattWrite(args); break;
                case "bt_gatt_notify": result = cmdBtGattNotify(args); break;
                case "bt_rssi":        result = cmdBtRssi(args); break;
                case "buddie_connect":    result = cmdBuddieConnect(args); break;
                case "buddie_disconnect": result = cmdBuddieDisconnect(); break;
                case "buddie_listen":     result = cmdBuddieListen(args, id, out); break;
                case "buddie_status":     result = cmdBuddieStatus(); break;
                case "logcat":         result = cmdLogcat(args); break;
                case "dumpsys":        result = cmdDumpsys(args); break;
                case "pm_path":        result = cmdPmPath(args); break;
                case "pm_info":        result = cmdPmInfo(args); break;
                case "content_query":  result = cmdContentQuery(args); break;
                case "intent_send":    result = cmdIntentSend(args); break;
                case "help":           result = PhoneAPI.help(); break;
                case "camera_info":    result = phoneAPI.cameraInfo(); break;
                case "camera_photo":   result = phoneAPI.cameraPhoto(args); break;
                case "torch":          result = phoneAPI.torch(args); break;
                case "volume_get":     result = phoneAPI.volumeGet(); break;
                case "volume_set":     result = phoneAPI.volumeSet(args); break;
                case "audio_info":     result = phoneAPI.audioInfo(); break;
                case "brightness_set": result = phoneAPI.brightnessSet(args); break;
                case "contacts":       result = phoneAPI.contacts(args); break;
                case "call_log":       result = phoneAPI.callLog(args); break;
                case "sms_inbox":      result = phoneAPI.smsInbox(args); break;
                case "sms_send":       result = phoneAPI.smsSend(args); break;
                case "telephony_info": result = phoneAPI.telephonyInfo(); break;
                case "media_play":     result = phoneAPI.mediaPlay(args); break;
                case "media_stop":     result = phoneAPI.mediaStop(); break;
                case "media_status":   result = phoneAPI.mediaStatus(); break;
                case "wake_lock":      result = phoneAPI.wakeLockAcquire(args); break;
                case "wake_unlock":    result = phoneAPI.wakeLockRelease(); break;
                case "open_url":       result = phoneAPI.openUrl(args); break;
                case "wifi_scan":      result = phoneAPI.wifiScan(); break;
                case "file_read":      result = phoneAPI.fileRead(args); break;
                case "file_write":     result = phoneAPI.fileWrite(args); break;
                case "file_list":      result = phoneAPI.fileList(args); break;
                case "screen_state":   result = phoneAPI.screenState(); break;
                case "process_info":   result = phoneAPI.processInfo(); break;
                case "screen_read":    result = cmdScreen("readScreen", args); break;
                case "screen_find":    result = cmdScreen("findByText", args); break;
                case "screen_click":   result = cmdScreen("clickByText", args); break;
                case "screen_click_id": result = cmdScreen("clickById", args); break;
                case "screen_tap":     result = cmdScreen("tapAt", args); break;
                case "screen_type":    result = cmdScreen("typeText", args); break;
                case "screen_swipe":   result = cmdScreen("swipe", args); break;
                case "screen_back":    result = cmdScreen("globalAction", "back"); break;
                case "screen_home":    result = cmdScreen("globalAction", "home"); break;
                case "screen_recents": result = cmdScreen("globalAction", "recents"); break;
                case "screen_notifications": result = cmdScreen("globalAction", "notifications"); break;
                case "screen_screenshot": result = cmdScreen("screenshot", args); break;
                case "screen_info":    result = cmdScreen("getInfo", args); break;
                case "screen_key":     result = cmdScreen("sendKey", args); break;
                case "screen_long_click": result = cmdScreen("longClick", args); break;
                case "screen_scroll":  result = cmdScreen("scroll", args); break;
                case "screen_wait":    result = cmdScreen("waitFor", args); break;
                case "screen_launch":  result = cmdScreen("launch", args); break;
                case "screen_node":    result = cmdScreen("nodeInfo", args); break;
                case "ping":           result = "\"pong\""; break;
                default:
                    return "{\"id\":" + id + ",\"ok\":false,\"error\":\"unknown command: " + cmd + "\"}";
            }
            return "{\"id\":" + id + ",\"ok\":true,\"data\":" + result + "}";
        } catch (Exception e) {
            Log.e(TAG, "Dispatch error: " + request, e);
            return "{\"id\":" + id + ",\"ok\":false,\"error\":\"" + escJson(e.getMessage()) + "\"}";
        }
    }

    /* ---- Screen/Accessibility commands ---- */

    private String cmdScreen(String method, String args) {
        // ScreenService runs its own TCP server on port 9878.
        // This works across process boundaries (e.g. after APK update).
        String request = (args != null && !args.isEmpty()) ? method + " " + args : method;
        try (Socket sock = new Socket("127.0.0.1", 9878)) {
            sock.setSoTimeout(15000);
            PrintWriter pw = new PrintWriter(sock.getOutputStream(), true);
            BufferedReader br = new BufferedReader(new InputStreamReader(sock.getInputStream()));
            pw.println(request);
            String response = br.readLine();
            return response != null ? response : "{\"error\":\"no response from screen service\"}";
        } catch (java.net.ConnectException e) {
            return "{\"error\":\"accessibility service not enabled — enable in Settings > Accessibility > BeamApp Screen Control\"}";
        } catch (Exception e) {
            return "{\"error\":\"screen service error: " + escJson(e.getMessage()) + "\"}";
        }
    }

    /* ---- Android API commands ---- */

    private String cmdDeviceInfo() {
        return "{\"model\":\"" + escJson(Build.MODEL) + "\""
            + ",\"manufacturer\":\"" + escJson(Build.MANUFACTURER) + "\""
            + ",\"brand\":\"" + escJson(Build.BRAND) + "\""
            + ",\"device\":\"" + escJson(Build.DEVICE) + "\""
            + ",\"product\":\"" + escJson(Build.PRODUCT) + "\""
            + ",\"android_version\":\"" + Build.VERSION.RELEASE + "\""
            + ",\"sdk_int\":" + Build.VERSION.SDK_INT
            + ",\"board\":\"" + escJson(Build.BOARD) + "\""
            + ",\"hardware\":\"" + escJson(Build.HARDWARE) + "\""
            + ",\"display\":\"" + escJson(Build.DISPLAY) + "\""
            + ",\"fingerprint\":\"" + escJson(Build.FINGERPRINT) + "\""
            + "}";
    }

    private String cmdBattery() {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent battery = context.registerReceiver(null, ifilter);
        if (battery == null) return "{\"error\":\"no battery info\"}";
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int temp = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
        int voltage = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL;
        int pct = (level * 100) / scale;
        return "{\"level\":" + pct
            + ",\"charging\":" + charging
            + ",\"temperature\":" + (temp / 10.0)
            + ",\"voltage\":" + (voltage / 1000.0)
            + ",\"status\":" + status + "}";
    }

    @SuppressWarnings("deprecation")
    private String cmdVibrate(String args) {
        int ms = parseInt(args, 200);
        Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null) return "\"no vibrator\"";
        try {
            /* Use AudioAttributes with USAGE_ALARM — routes through audio/haptic pipeline
               which works on ROMs where the standard vibrator HAL is absent */
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE),
                new android.media.AudioAttributes.Builder()
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                    .build());
            return "\"ok\"";
        } catch (Exception e) {
            Log.w(TAG, "Vibrate failed", e);
            return "\"error: " + escJson(e.getMessage()) + "\"";
        }
    }

    private String cmdToast(String args) {
        final String msg = unquote(args);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
            }
        });
        return "\"ok\"";
    }

    private String cmdClipboardGet() {
        final String[] result = {""};
        /* Clipboard must be accessed on main thread */
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                ClipboardManager cm = (ClipboardManager)
                    context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm.hasPrimaryClip()) {
                    ClipData.Item item = cm.getPrimaryClip().getItemAt(0);
                    CharSequence text = item.getText();
                    if (text != null) {
                        synchronized (result) {
                            result[0] = text.toString();
                            result.notify();
                        }
                    }
                }
                synchronized (result) { result.notify(); }
            }
        });
        synchronized (result) {
            try { result.wait(2000); } catch (InterruptedException e) { /* */ }
        }
        return "\"" + escJson(result[0]) + "\"";
    }

    private String cmdClipboardSet(String args) {
        final String text = unquote(args);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                ClipboardManager cm = (ClipboardManager)
                    context.getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("beam", text));
            }
        });
        return "\"ok\"";
    }

    private String cmdSensorsList() {
        List<Sensor> sensors = sensorManager.getSensorList(Sensor.TYPE_ALL);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < sensors.size(); i++) {
            Sensor s = sensors.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"type\":").append(s.getType())
              .append(",\"name\":\"").append(escJson(s.getName())).append("\"")
              .append(",\"vendor\":\"").append(escJson(s.getVendor())).append("\"")
              .append(",\"resolution\":").append(s.getResolution())
              .append(",\"max_range\":").append(s.getMaximumRange())
              .append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    private String cmdSensorStart(String args) {
        final int type = parseInt(args, Sensor.TYPE_ACCELEROMETER);
        Sensor sensor = sensorManager.getDefaultSensor(type);
        if (sensor == null) return "\"sensor not found\"";

        if (sensorListeners.containsKey(type)) return "\"already started\"";

        SensorEventListener listener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                sensorData.put(type, event.values.clone());
            }
            @Override
            public void onAccuracyChanged(Sensor s, int accuracy) {}
        };
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI, mainHandler);
        sensorListeners.put(type, listener);
        return "\"ok\"";
    }

    private String cmdSensorRead(String args) {
        int type = parseInt(args, Sensor.TYPE_ACCELEROMETER);
        float[] values = sensorData.get(type);
        if (values == null) return "{\"error\":\"no data, call sensor_start first\"}";
        StringBuilder sb = new StringBuilder("{\"values\":[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(values[i]);
        }
        sb.append("]}");
        return sb.toString();
    }

    private String cmdSensorStop(String args) {
        int type = parseInt(args, Sensor.TYPE_ACCELEROMETER);
        SensorEventListener l = sensorListeners.remove(type);
        if (l != null) {
            sensorManager.unregisterListener(l);
            sensorData.remove(type);
            return "\"ok\"";
        }
        return "\"not started\"";
    }

    @SuppressWarnings("deprecation")
    private String cmdWifiInfo() {
        WifiManager wm = (WifiManager) context.getApplicationContext()
            .getSystemService(Context.WIFI_SERVICE);
        if (wm == null) return "{\"error\":\"no wifi manager\"}";
        WifiInfo wi = wm.getConnectionInfo();
        if (wi == null) return "{\"error\":\"no wifi info\"}";
        return "{\"ssid\":\"" + escJson(wi.getSSID()) + "\""
            + ",\"bssid\":\"" + escJson(String.valueOf(wi.getBSSID())) + "\""
            + ",\"rssi\":" + wi.getRssi()
            + ",\"link_speed\":" + wi.getLinkSpeed()
            + ",\"frequency\":" + wi.getFrequency()
            + ",\"ip\":" + wi.getIpAddress()
            + "}";
    }

    @SuppressWarnings("deprecation")
    private String cmdNetworkInfo() {
        ConnectivityManager cm = (ConnectivityManager)
            context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm.getActiveNetworkInfo();
        if (ni == null) return "{\"connected\":false}";
        return "{\"connected\":" + ni.isConnected()
            + ",\"type\":\"" + escJson(ni.getTypeName()) + "\""
            + ",\"subtype\":\"" + escJson(ni.getSubtypeName()) + "\""
            + ",\"extra\":\"" + escJson(String.valueOf(ni.getExtraInfo())) + "\""
            + "}";
    }

    @SuppressWarnings("MissingPermission")
    private String cmdLocation() {
        try {
            LocationManager lm = (LocationManager)
                context.getSystemService(Context.LOCATION_SERVICE);
            Location loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (loc == null) loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (loc == null) return "{\"error\":\"no location available\"}";
            return "{\"latitude\":" + loc.getLatitude()
                + ",\"longitude\":" + loc.getLongitude()
                + ",\"altitude\":" + loc.getAltitude()
                + ",\"accuracy\":" + loc.getAccuracy()
                + ",\"speed\":" + loc.getSpeed()
                + ",\"bearing\":" + loc.getBearing()
                + ",\"time\":" + loc.getTime()
                + ",\"provider\":\"" + escJson(loc.getProvider()) + "\""
                + "}";
        } catch (SecurityException e) {
            return "{\"error\":\"location permission denied\"}";
        }
    }

    private String cmdPackages() {
        PackageManager pm = context.getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(0);
        StringBuilder sb = new StringBuilder("[");
        int count = 0;
        for (ApplicationInfo app : apps) {
            if (count > 0) sb.append(",");
            String label = String.valueOf(pm.getApplicationLabel(app));
            sb.append("{\"package\":\"").append(escJson(app.packageName)).append("\"")
              .append(",\"label\":\"").append(escJson(label)).append("\"")
              .append(",\"system\":").append((app.flags & ApplicationInfo.FLAG_SYSTEM) != 0)
              .append("}");
            count++;
        }
        sb.append("]");
        return sb.toString();
    }

    private String cmdSystemProp(String args) {
        String prop = unquote(args);
        try {
            @SuppressWarnings("rawtypes")
            Class sp = Class.forName("android.os.SystemProperties");
            String val = (String) sp.getMethod("get", String.class).invoke(null, prop);
            return "\"" + escJson(val != null ? val : "") + "\"";
        } catch (Exception e) {
            return "\"" + escJson(e.getMessage()) + "\"";
        }
    }

    private String cmdMemoryInfo() {
        Runtime rt = Runtime.getRuntime();
        long totalMem = rt.totalMemory();
        long freeMem = rt.freeMemory();
        long maxMem = rt.maxMemory();
        return "{\"total\":" + totalMem
            + ",\"free\":" + freeMem
            + ",\"max\":" + maxMem
            + ",\"used\":" + (totalMem - freeMem) + "}";
    }

    private String cmdScreenBrightness() {
        try {
            int brightness = android.provider.Settings.System.getInt(
                context.getContentResolver(),
                android.provider.Settings.System.SCREEN_BRIGHTNESS);
            return String.valueOf(brightness);
        } catch (Exception e) {
            return "{\"error\":\"" + escJson(e.getMessage()) + "\"}";
        }
    }

    private String cmdNotify(String args) {
        /* args: "title|body" */
        String text = unquote(args);
        String[] parts = text.split("\\|", 2);
        String title = parts[0];
        String body = parts.length > 1 ? parts[1] : "";

        android.app.NotificationManager nm =
            (android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        android.app.Notification notif = new android.app.Notification.Builder(context, "beam_service_channel")
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build();
        nm.notify((int) System.currentTimeMillis(), notif);
        return "\"ok\"";
    }

    /* ---- Introspection ---- */

    private String cmdServices() {
        try {
            @SuppressWarnings("rawtypes")
            Class smClass = Class.forName("android.os.ServiceManager");
            String[] services = (String[]) smClass.getMethod("listServices").invoke(null);
            if (services == null) return "[]";
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < services.length; i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(escJson(services[i])).append("\"");
            }
            sb.append("]");
            return sb.toString();
        } catch (Exception e) {
            return "{\"error\":\"" + escJson(e.getMessage()) + "\"}";
        }
    }

    private String cmdFeatures() {
        PackageManager pm = context.getPackageManager();
        android.content.pm.FeatureInfo[] features = pm.getSystemAvailableFeatures();
        if (features == null) return "[]";
        StringBuilder sb = new StringBuilder("[");
        int count = 0;
        for (android.content.pm.FeatureInfo fi : features) {
            if (fi.name == null) continue;
            if (count > 0) sb.append(",");
            sb.append("{\"name\":\"").append(escJson(fi.name)).append("\"")
              .append(",\"version\":").append(fi.version).append("}");
            count++;
        }
        sb.append("]");
        return sb.toString();
    }

    private String cmdShell(String args) {
        String cmd = unquote(args);
        if (cmd.isEmpty()) return "{\"error\":\"usage: shell <command>\"}";
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", cmd});
            java.io.InputStream is = p.getInputStream();
            java.io.InputStream es = p.getErrorStream();
            byte[] buf = new byte[8192];
            StringBuilder out = new StringBuilder();
            int n;
            while ((n = is.read(buf)) > 0) out.append(new String(buf, 0, n));
            StringBuilder err = new StringBuilder();
            while ((n = es.read(buf)) > 0) err.append(new String(buf, 0, n));
            int exitCode = p.waitFor();
            return "{\"exit\":" + exitCode
                + ",\"stdout\":\"" + escJson(out.toString()) + "\""
                + ",\"stderr\":\"" + escJson(err.toString()) + "\"}";
        } catch (Exception e) {
            return "{\"error\":\"" + escJson(e.getMessage()) + "\"}";
        }
    }

    /* ---- Audio / Speech ---- */

    /**
     * Start recording from microphone. Saves 16-bit mono 16kHz WAV.
     * Args: duration in seconds (default 5), or "stream" for manual stop.
     */
    @SuppressWarnings("MissingPermission")
    private String cmdMicRecord(String args) {
        if (recording.get()) return "\"already recording\"";
        int durationSec = parseInt(args, 5);

        int bufSize = Math.max(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
            SAMPLE_RATE * 2  /* 1 second buffer */
        );

        try {
            audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            );
        } catch (Exception e) {
            return "\"mic init failed: " + escJson(e.getMessage()) + "\"";
        }

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release();
            audioRecord = null;
            return "\"mic not available\"";
        }

        new File(recordingDir).mkdirs();
        lastRecordingPath = recordingDir + "/rec_" + System.currentTimeMillis() + ".wav";
        recording.set(true);

        final int dur = durationSec;
        final String path = lastRecordingPath;
        final int bSize = bufSize;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    FileOutputStream fos = new FileOutputStream(path);
                    /* Write placeholder WAV header — we'll fix it after recording */
                    byte[] header = new byte[44];
                    fos.write(header);

                    audioRecord.startRecording();
                    byte[] buf = new byte[bSize];
                    long totalBytes = 0;
                    long maxBytes = (long) dur * SAMPLE_RATE * 2;

                    while (recording.get() && totalBytes < maxBytes) {
                        int read = audioRecord.read(buf, 0, buf.length);
                        if (read > 0) {
                            fos.write(buf, 0, read);
                            totalBytes += read;
                        }
                    }

                    audioRecord.stop();
                    fos.flush();
                    fos.close();

                    /* Fix WAV header */
                    writeWavHeader(path, totalBytes);

                    Log.i(TAG, "Recording saved: " + path + " (" + totalBytes + " bytes)");
                } catch (Exception e) {
                    Log.e(TAG, "Recording error", e);
                } finally {
                    recording.set(false);
                    if (audioRecord != null) {
                        audioRecord.release();
                        audioRecord = null;
                    }
                }
            }
        }, "mic-record").start();

        return "{\"status\":\"recording\",\"path\":\"" + escJson(path) + "\",\"duration\":" + dur + "}";
    }

    private String cmdMicStop() {
        if (!recording.get()) return "\"not recording\"";
        recording.set(false);
        /* Wait a moment for the recording thread to finish */
        try { Thread.sleep(200); } catch (InterruptedException e) { /* */ }
        return "{\"status\":\"stopped\",\"path\":\"" + escJson(lastRecordingPath) + "\"}";
    }

    /** Write a proper WAV header to an existing file */
    private static void writeWavHeader(String path, long dataSize) throws Exception {
        RandomAccessFile raf = new RandomAccessFile(path, "rw");
        int channels = 1;
        int bitsPerSample = 16;
        long byteRate = SAMPLE_RATE * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;

        raf.seek(0);
        raf.writeBytes("RIFF");
        raf.writeInt(Integer.reverseBytes((int) (36 + dataSize)));
        raf.writeBytes("WAVE");
        raf.writeBytes("fmt ");
        raf.writeInt(Integer.reverseBytes(16));          /* chunk size */
        raf.writeShort(Short.reverseBytes((short) 1));   /* PCM format */
        raf.writeShort(Short.reverseBytes((short) channels));
        raf.writeInt(Integer.reverseBytes(SAMPLE_RATE));
        raf.writeInt(Integer.reverseBytes((int) byteRate));
        raf.writeShort(Short.reverseBytes((short) blockAlign));
        raf.writeShort(Short.reverseBytes((short) bitsPerSample));
        raf.writeBytes("data");
        raf.writeInt(Integer.reverseBytes((int) dataSize));
        raf.close();
    }

    /**
     * Text-to-speech via sherpa-onnx JNI (KittenTTS).
     */
    private String cmdTts(String args) {
        String text = unquote(args);
        if (text.isEmpty()) return "\"usage: tts <text> or tts <sid> <speed> <text>\"";
        if (!speechEngine.isTtsReady()) {
            /* Wait briefly for engine init */
            for (int i = 0; i < 50 && !speechEngine.isTtsReady(); i++) {
                try { Thread.sleep(100); } catch (InterruptedException e) { break; }
            }
            if (!speechEngine.isTtsReady()) return "\"tts engine not ready\"";
        }
        // Check for "sid speed text" format (e.g. "2 1.2 Hello world")
        int sid = 0;
        float speed = 1.0f;
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("^(\\d+)\\s+(\\d+\\.?\\d*)\\s+(.+)$", java.util.regex.Pattern.DOTALL)
            .matcher(text);
        if (m.matches()) {
            sid = Integer.parseInt(m.group(1));
            speed = Float.parseFloat(m.group(2));
            text = m.group(3);
        }
        return speechEngine.speak(text, sid, speed);
    }

    private String cmdTtsVoices(String args) {
        int n = speechEngine.getTtsNumSpeakers();
        return "{\"num_speakers\":" + n + "}";
    }

    /**
     * Speech-to-text via sherpa-onnx JNI.
     * Args: path to WAV file (default: last recording).
     */
    private String cmdStt(String args) {
        String wavPath = unquote(args);
        if (wavPath.isEmpty() && lastRecordingPath != null) {
            wavPath = lastRecordingPath;
        }
        if (wavPath.isEmpty()) return "\"no audio file specified\"";
        File wavFile = new File(wavPath);
        if (!wavFile.exists()) return "\"file not found: " + escJson(wavPath) + "\"";
        if (!speechEngine.isSttReady()) return "\"stt engine not ready\"";
        return speechEngine.transcribeFile(wavPath);
    }

    /**
     * Combined listen command: record for N seconds, then run STT via JNI.
     * Args: duration in seconds (default 5).
     */
    private String cmdListen(String args) {
        int durationSec = parseInt(args, 5);

        /* Record */
        String recResult = cmdMicRecord(String.valueOf(durationSec));
        if (!recResult.contains("\"recording\"")) return recResult;

        /* Wait for recording to finish */
        long waitMs = (durationSec + 1) * 1000L;
        long start = System.currentTimeMillis();
        while (recording.get() && (System.currentTimeMillis() - start) < waitMs) {
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }
        try { Thread.sleep(300); } catch (InterruptedException e) { /* */ }

        /* Run STT on the recording via JNI */
        return cmdStt("\"" + lastRecordingPath + "\"");
    }

    /**
     * Streaming listen: record from mic with real-time STT and VAD.
     * Sends partial results as JSON lines, returns final result.
     * Args: max duration in seconds (default 10).
     */
    private String cmdStreamListen(String args, final String msgId, final PrintWriter writer) {
        int maxDuration = parseInt(args, 10);
        if (!speechEngine.isSttReady()) return "\"stt engine not ready\"";

        SpeechEngine.PartialCallback callback = null;
        if (writer != null) {
            callback = new SpeechEngine.PartialCallback() {
                @Override
                public void onPartial(String text) {
                    String display = speechEngine.addPunctuation(text);
                    String partial = "{\"id\":" + msgId + ",\"partial\":true,\"text\":\""
                        + escJson(display) + "\"}";
                    synchronized (writer) {
                        writer.println(partial);
                        writer.flush();
                    }
                }
            };
        }

        String result = speechEngine.streamListen(maxDuration, callback);
        /* Return null — we send final response directly for streaming commands */
        if (writer != null) {
            String finalResponse = "{\"id\":" + msgId + ",\"ok\":true,\"data\":" + result + "}";
            synchronized (writer) {
                writer.println(finalResponse);
                writer.flush();
            }
            return null;
        }
        return result;
    }

    /**
     * Offline transcription using SenseVoice: returns text with punctuation, casing, emotion, language.
     */
    private String cmdTranscribeOffline(String args) {
        String wavPath = unquote(args);
        if (wavPath.isEmpty() && lastRecordingPath != null) {
            wavPath = lastRecordingPath;
        }
        if (wavPath.isEmpty()) return "\"no audio file specified\"";
        File wavFile = new File(wavPath);
        if (!wavFile.exists()) return "\"file not found: " + escJson(wavPath) + "\"";
        if (!speechEngine.isOfflineSttReady()) return "\"offline STT not ready\"";
        return speechEngine.transcribeOffline(wavPath);
    }

    /**
     * Speaker diarization + transcription: identify speakers and transcribe each segment.
     */
    private String cmdDiarize(String args) {
        String wavPath = unquote(args);
        if (wavPath.isEmpty() && lastRecordingPath != null) {
            wavPath = lastRecordingPath;
        }
        if (wavPath.isEmpty()) return "\"no audio file specified\"";
        File wavFile = new File(wavPath);
        if (!wavFile.exists()) return "\"file not found: " + escJson(wavPath) + "\"";
        if (!speechEngine.isDiarizationReady()) return "\"diarization not ready\"";
        return speechEngine.diarize(wavPath);
    }

    /* ---- Buddie earbuds commands ---- */

    private String cmdBuddieConnect(String args) {
        String address = unquote(args).trim();
        if (address.isEmpty()) return "{\"error\":\"usage: buddie_connect <ble_address>\"}";
        return buddieService.connect(address);
    }

    private String cmdBuddieDisconnect() {
        return buddieService.disconnect();
    }

    private String cmdBuddieListen(String args, final String msgId, final PrintWriter writer) {
        int maxDuration = parseInt(args, 30);

        SpeechEngine.PartialCallback callback = null;
        if (writer != null) {
            callback = new SpeechEngine.PartialCallback() {
                @Override
                public void onPartial(String text) {
                    String display = speechEngine.addPunctuation(text);
                    String partial = "{\"id\":" + msgId + ",\"partial\":true,\"text\":\""
                        + escJson(display) + "\"}";
                    synchronized (writer) {
                        writer.println(partial);
                        writer.flush();
                    }
                }
            };
        }

        String result = buddieService.listen(maxDuration, callback);
        if (writer != null) {
            String finalResponse = "{\"id\":" + msgId + ",\"ok\":true,\"data\":" + result + "}";
            synchronized (writer) {
                writer.println(finalResponse);
                writer.flush();
            }
            return null;
        }
        return result;
    }

    private String cmdBuddieStatus() {
        return buddieService.getStatus();
    }

    /* ---- System investigation commands ---- */

    /**
     * Capture system logs. Args: "tag_filter|max_lines" e.g. "BluetoothGatt|100" or just "100".
     */
    private String cmdLogcat(String args) {
        String text = unquote(args);
        String tagFilter = null;
        int maxLines = 200;

        if (!text.isEmpty()) {
            if (text.contains("|")) {
                String[] parts = text.split("\\|", 2);
                tagFilter = parts[0].trim();
                try { maxLines = Integer.parseInt(parts[1].trim()); } catch (Exception e) { /* keep default */ }
            } else {
                try {
                    maxLines = Integer.parseInt(text.trim());
                } catch (Exception e) {
                    tagFilter = text.trim();
                }
            }
        }
        if (maxLines > 5000) maxLines = 5000;

        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("logcat");
            cmd.add("-d");
            cmd.add("-v");
            cmd.add("threadtime");
            if (tagFilter != null && !tagFilter.isEmpty()) {
                cmd.add("-s");
                cmd.add(tagFilter + ":*");
            }

            Process p = Runtime.getRuntime().exec(cmd.toArray(new String[0]));
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
            p.waitFor();

            /* Keep only the last maxLines */
            int start = Math.max(0, lines.size() - maxLines);
            StringBuilder sb = new StringBuilder("{\"lines\":[");
            int count = 0;
            for (int i = start; i < lines.size(); i++) {
                if (count > 0) sb.append(",");
                sb.append("\"").append(escJson(lines.get(i))).append("\"");
                count++;
            }
            sb.append("],\"count\":").append(count).append("}");
            return sb.toString();
        } catch (Exception e) {
            return "{\"error\":\"" + escJson(e.getMessage()) + "\"}";
        }
    }

    /**
     * Query system service dumps. Args: service name e.g. "bluetooth_manager".
     */
    private String cmdDumpsys(String args) {
        String service = unquote(args);
        if (service.isEmpty()) return "{\"error\":\"usage: dumpsys <service>\"}";

        try {
            Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", "dumpsys " + service});
            java.io.InputStream is = p.getInputStream();
            java.io.InputStream es = p.getErrorStream();
            byte[] buf = new byte[8192];
            StringBuilder out = new StringBuilder();
            int n;
            int maxBytes = 65536;
            while ((n = is.read(buf)) > 0 && out.length() < maxBytes) {
                out.append(new String(buf, 0, n));
            }
            boolean truncated = out.length() >= maxBytes;
            if (truncated) {
                /* Drain remaining input to avoid broken pipe */
                while (is.read(buf) > 0) { /* discard */ }
            }
            StringBuilder err = new StringBuilder();
            while ((n = es.read(buf)) > 0) err.append(new String(buf, 0, n));
            p.waitFor();

            if (err.length() > 0 && out.length() == 0) {
                return "{\"error\":\"" + escJson(err.toString().trim()) + "\"}";
            }
            return "{\"output\":\"" + escJson(out.toString()) + "\",\"truncated\":" + truncated + "}";
        } catch (Exception e) {
            return "{\"error\":\"" + escJson(e.getMessage()) + "\"}";
        }
    }

    /**
     * Get APK path for a package. Args: package name.
     */
    private String cmdPmPath(String args) {
        String pkg = unquote(args);
        if (pkg.isEmpty()) return "{\"error\":\"usage: pm_path <package>\"}";

        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return "{\"package\":\"" + escJson(pkg) + "\""
                + ",\"path\":\"" + escJson(ai.sourceDir) + "\""
                + ",\"data_dir\":\"" + escJson(ai.dataDir) + "\""
                + ",\"native_lib_dir\":\"" + escJson(ai.nativeLibraryDir != null ? ai.nativeLibraryDir : "") + "\""
                + "}";
        } catch (PackageManager.NameNotFoundException e) {
            return "{\"error\":\"package not found: " + escJson(pkg) + "\"}";
        }
    }

    /**
     * Detailed package info. Args: package name.
     */
    private String cmdPmInfo(String args) {
        String pkg = unquote(args);
        if (pkg.isEmpty()) return "{\"error\":\"usage: pm_info <package>\"}";

        try {
            PackageManager pm = context.getPackageManager();
            int flags = PackageManager.GET_PERMISSIONS | PackageManager.GET_SERVICES
                      | PackageManager.GET_RECEIVERS | PackageManager.GET_ACTIVITIES;
            PackageInfo pi = pm.getPackageInfo(pkg, flags);

            StringBuilder sb = new StringBuilder("{");
            sb.append("\"package\":\"").append(escJson(pkg)).append("\"");
            sb.append(",\"version\":\"").append(escJson(pi.versionName != null ? pi.versionName : "")).append("\"");
            sb.append(",\"version_code\":").append(pi.getLongVersionCode());
            sb.append(",\"target_sdk\":").append(pi.applicationInfo.targetSdkVersion);
            sb.append(",\"min_sdk\":").append(pi.applicationInfo.minSdkVersion);

            /* Permissions */
            sb.append(",\"permissions\":[");
            if (pi.requestedPermissions != null) {
                for (int i = 0; i < pi.requestedPermissions.length; i++) {
                    if (i > 0) sb.append(",");
                    sb.append("\"").append(escJson(pi.requestedPermissions[i])).append("\"");
                }
            }
            sb.append("]");

            /* Services */
            sb.append(",\"services\":[");
            if (pi.services != null) {
                for (int i = 0; i < pi.services.length; i++) {
                    if (i > 0) sb.append(",");
                    ServiceInfo si = pi.services[i];
                    sb.append("{\"name\":\"").append(escJson(si.name)).append("\"");
                    sb.append(",\"exported\":").append(si.exported);
                    sb.append("}");
                }
            }
            sb.append("]");

            /* Receivers */
            sb.append(",\"receivers\":[");
            if (pi.receivers != null) {
                for (int i = 0; i < pi.receivers.length; i++) {
                    if (i > 0) sb.append(",");
                    ActivityInfo ri = pi.receivers[i];
                    sb.append("{\"name\":\"").append(escJson(ri.name)).append("\"");
                    sb.append(",\"exported\":").append(ri.exported);
                    sb.append("}");
                }
            }
            sb.append("]");

            /* Activities */
            sb.append(",\"activities\":[");
            if (pi.activities != null) {
                for (int i = 0; i < pi.activities.length; i++) {
                    if (i > 0) sb.append(",");
                    ActivityInfo ai = pi.activities[i];
                    sb.append("{\"name\":\"").append(escJson(ai.name)).append("\"");
                    sb.append(",\"exported\":").append(ai.exported);
                    sb.append("}");
                }
            }
            sb.append("]");

            sb.append("}");
            return sb.toString();
        } catch (PackageManager.NameNotFoundException e) {
            return "{\"error\":\"package not found: " + escJson(pkg) + "\"}";
        }
    }

    /**
     * Query content providers. Args: "content://uri" or "content://uri|projection|selection".
     */
    private String cmdContentQuery(String args) {
        String text = unquote(args);
        if (text.isEmpty()) return "{\"error\":\"usage: content_query <uri> or <uri|projection|selection>\"}";

        String uriStr;
        String[] projection = null;
        String selection = null;

        if (text.contains("|")) {
            String[] parts = text.split("\\|", 3);
            uriStr = parts[0].trim();
            if (parts.length > 1 && !parts[1].trim().isEmpty()) {
                projection = parts[1].trim().split(",");
            }
            if (parts.length > 2 && !parts[2].trim().isEmpty()) {
                selection = parts[2].trim();
            }
        } else {
            uriStr = text.trim();
        }

        try {
            Uri uri = Uri.parse(uriStr);
            ContentResolver cr = context.getContentResolver();
            Cursor cursor = cr.query(uri, projection, selection, null, null);
            if (cursor == null) return "{\"error\":\"query returned null cursor\"}";

            try {
                String[] columns = cursor.getColumnNames();
                StringBuilder sb = new StringBuilder("{\"columns\":[");
                for (int i = 0; i < columns.length; i++) {
                    if (i > 0) sb.append(",");
                    sb.append("\"").append(escJson(columns[i])).append("\"");
                }
                sb.append("],\"rows\":[");

                int rowCount = 0;
                int maxRows = 500;
                while (cursor.moveToNext() && rowCount < maxRows) {
                    if (rowCount > 0) sb.append(",");
                    sb.append("[");
                    for (int c = 0; c < columns.length; c++) {
                        if (c > 0) sb.append(",");
                        int type = cursor.getType(c);
                        switch (type) {
                            case Cursor.FIELD_TYPE_NULL:
                                sb.append("null");
                                break;
                            case Cursor.FIELD_TYPE_INTEGER:
                                sb.append(cursor.getLong(c));
                                break;
                            case Cursor.FIELD_TYPE_FLOAT:
                                sb.append(cursor.getDouble(c));
                                break;
                            case Cursor.FIELD_TYPE_BLOB:
                                sb.append("\"<blob ").append(cursor.getBlob(c).length).append(" bytes>\"");
                                break;
                            default:
                                sb.append("\"").append(escJson(cursor.getString(c))).append("\"");
                                break;
                        }
                    }
                    sb.append("]");
                    rowCount++;
                }
                sb.append("],\"count\":").append(rowCount).append("}");
                return sb.toString();
            } finally {
                cursor.close();
            }
        } catch (SecurityException e) {
            return "{\"error\":\"permission denied: " + escJson(e.getMessage()) + "\"}";
        } catch (Exception e) {
            return "{\"error\":\"" + escJson(e.getMessage()) + "\"}";
        }
    }

    /**
     * Send intents. Args: "type|action|extra_key=extra_val&key2=val2".
     * Types: broadcast, activity, service.
     */
    private String cmdIntentSend(String args) {
        String text = unquote(args);
        if (text.isEmpty()) return "{\"error\":\"usage: intent_send <type|action|extras>\"}";

        String[] parts = text.split("\\|", 3);
        if (parts.length < 2) return "{\"error\":\"usage: intent_send <type|action|extras>\"}";

        String type = parts[0].trim().toLowerCase(Locale.US);
        String action = parts[1].trim();
        String extras = parts.length > 2 ? parts[2].trim() : "";

        Intent intent = new Intent(action);

        /* Parse extras: key=val&key2=val2 */
        if (!extras.isEmpty()) {
            String[] pairs = extras.split("&");
            for (String pair : pairs) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    String key = pair.substring(0, eq).trim();
                    String val = pair.substring(eq + 1).trim();
                    intent.putExtra(key, val);
                }
            }
        }

        try {
            switch (type) {
                case "broadcast":
                    context.sendBroadcast(intent);
                    return "{\"sent\":true,\"type\":\"broadcast\",\"action\":\"" + escJson(action) + "\"}";
                case "activity":
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                    return "{\"sent\":true,\"type\":\"activity\",\"action\":\"" + escJson(action) + "\"}";
                case "service":
                    context.startService(intent);
                    return "{\"sent\":true,\"type\":\"service\",\"action\":\"" + escJson(action) + "\"}";
                default:
                    return "{\"error\":\"unknown intent type: " + escJson(type) + ". Use broadcast, activity, or service\"}";
            }
        } catch (Exception e) {
            return "{\"error\":\"" + escJson(e.getMessage()) + "\"}";
        }
    }

    /* ---- Bluetooth ---- */

    private boolean hasBtPermission(String perm) {
        return context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private String cmdBtStatus() {
        if (btAdapter == null) return "{\"error\":\"no bluetooth adapter\"}";
        boolean enabled = btAdapter.isEnabled();
        int state = btAdapter.getState();
        String stateStr;
        switch (state) {
            case BluetoothAdapter.STATE_OFF: stateStr = "off"; break;
            case BluetoothAdapter.STATE_ON: stateStr = "on"; break;
            case BluetoothAdapter.STATE_TURNING_ON: stateStr = "turning_on"; break;
            case BluetoothAdapter.STATE_TURNING_OFF: stateStr = "turning_off"; break;
            default: stateStr = "unknown"; break;
        }
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"enabled\":").append(enabled);
        sb.append(",\"state\":\"").append(stateStr).append("\"");
        if (hasBtPermission("android.permission.BLUETOOTH_CONNECT")) {
            sb.append(",\"name\":\"").append(escJson(btAdapter.getName())).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    @SuppressWarnings("MissingPermission")
    private String cmdBtBonded() {
        if (btAdapter == null) return "{\"error\":\"no bluetooth adapter\"}";
        if (!hasBtPermission("android.permission.BLUETOOTH_CONNECT"))
            return "{\"error\":\"BLUETOOTH_CONNECT permission not granted\"}";
        java.util.Set<BluetoothDevice> bonded = btAdapter.getBondedDevices();
        if (bonded == null) return "[]";
        StringBuilder sb = new StringBuilder("[");
        int count = 0;
        for (BluetoothDevice dev : bonded) {
            if (count > 0) sb.append(",");
            sb.append("{\"name\":\"").append(escJson(dev.getName())).append("\"");
            sb.append(",\"address\":\"").append(escJson(dev.getAddress())).append("\"");
            int type = dev.getType();
            String typeStr;
            switch (type) {
                case BluetoothDevice.DEVICE_TYPE_CLASSIC: typeStr = "classic"; break;
                case BluetoothDevice.DEVICE_TYPE_LE: typeStr = "le"; break;
                case BluetoothDevice.DEVICE_TYPE_DUAL: typeStr = "dual"; break;
                default: typeStr = "unknown"; break;
            }
            sb.append(",\"type\":\"").append(typeStr).append("\"");
            BluetoothClass btClass = dev.getBluetoothClass();
            if (btClass != null) {
                sb.append(",\"major_class\":").append(btClass.getMajorDeviceClass());
                sb.append(",\"device_class\":").append(btClass.getDeviceClass());
            }
            sb.append("}");
            count++;
        }
        sb.append("]");
        return sb.toString();
    }

    @SuppressWarnings("MissingPermission")
    private String cmdBtConnected() {
        if (btAdapter == null) return "{\"error\":\"no bluetooth adapter\"}";
        if (!hasBtPermission("android.permission.BLUETOOTH_CONNECT"))
            return "{\"error\":\"BLUETOOTH_CONNECT permission not granted\"}";

        final java.util.List<String> entries = java.util.Collections.synchronizedList(
            new java.util.ArrayList<String>());
        int[] profiles = { BluetoothProfile.A2DP, BluetoothProfile.HEADSET,
                           4 /* HID_HOST */, 7 /* GATT */, 21 /* HEARING_AID */,
                           22 /* LE_AUDIO */ };
        final String[] profileNames = { "a2dp", "headset", "hid_host", "gatt",
                                        "hearing_aid", "le_audio" };
        final CountDownLatch latch = new CountDownLatch(profiles.length);

        for (int i = 0; i < profiles.length; i++) {
            final int idx = i;
            final int profile = profiles[i];
            boolean ok = btAdapter.getProfileProxy(context,
                new BluetoothProfile.ServiceListener() {
                    @Override
                    public void onServiceConnected(int p, BluetoothProfile proxy) {
                        try {
                            List<BluetoothDevice> devs = proxy.getConnectedDevices();
                            for (BluetoothDevice dev : devs) {
                                StringBuilder sb = new StringBuilder("{");
                                sb.append("\"name\":\"").append(escJson(dev.getName())).append("\"");
                                sb.append(",\"address\":\"").append(escJson(dev.getAddress())).append("\"");
                                sb.append(",\"profile\":\"").append(profileNames[idx]).append("\"");
                                sb.append("}");
                                entries.add(sb.toString());
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "BT profile query error", e);
                        } finally {
                            btAdapter.closeProfileProxy(profile, proxy);
                            latch.countDown();
                        }
                    }
                    @Override
                    public void onServiceDisconnected(int p) {}
                }, profile);
            if (!ok) latch.countDown();
        }

        try { latch.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { /* */ }

        /* Also check bonded devices via BluetoothManager for GATT/BLE connections
           that profile proxies may miss */
        java.util.Set<String> seenAddrs = new java.util.HashSet<>();
        for (String e : entries) {
            /* Extract address from JSON entry */
            int ai = e.indexOf("\"address\":\"");
            if (ai >= 0) {
                int as = ai + 11;
                int ae = e.indexOf("\"", as);
                if (ae > as) seenAddrs.add(e.substring(as, ae));
            }
        }
        BluetoothManager btm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (btm != null) {
            java.util.Set<BluetoothDevice> bonded = btAdapter.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice dev : bonded) {
                    if (seenAddrs.contains(dev.getAddress())) continue;
                    boolean connected = false;
                    /* Try each BLE-relevant profile */
                    int[] bleProfiles = { 7 /* GATT */, 21 /* HEARING_AID */, 22 /* LE_AUDIO */ };
                    for (int bp : bleProfiles) {
                        try {
                            if (btm.getConnectionState(dev, bp) == BluetoothProfile.STATE_CONNECTED) {
                                connected = true;
                                break;
                            }
                        } catch (Exception ex) { /* profile not supported */ }
                    }
                    if (connected) {
                        StringBuilder e2 = new StringBuilder("{");
                        e2.append("\"name\":\"").append(escJson(dev.getName())).append("\"");
                        e2.append(",\"address\":\"").append(escJson(dev.getAddress())).append("\"");
                        e2.append(",\"profile\":\"ble\"");
                        e2.append("}");
                        entries.add(e2.toString());
                    }
                }
            }
        }

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(entries.get(i));
        }
        sb.append("]");
        return sb.toString();
    }

    @SuppressWarnings("MissingPermission")
    private String cmdBtScan(String args) {
        if (btAdapter == null) return "{\"error\":\"no bluetooth adapter\"}";
        if (!hasBtPermission("android.permission.BLUETOOTH_SCAN"))
            return "{\"error\":\"BLUETOOTH_SCAN permission not granted\"}";
        if (!hasBtPermission("android.permission.BLUETOOTH_CONNECT"))
            return "{\"error\":\"BLUETOOTH_CONNECT permission not granted\"}";
        if (!btAdapter.isEnabled()) return "{\"error\":\"bluetooth is disabled\"}";

        int seconds = parseInt(args, 5);
        if (seconds > 30) seconds = 30;

        final java.util.Map<String, String> found = new java.util.LinkedHashMap<>();
        final CountDownLatch latch = new CountDownLatch(1);

        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();
                if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                    BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (dev == null) return;
                    short rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
                    String addr = dev.getAddress();
                    if (found.containsKey(addr)) return;
                    StringBuilder sb = new StringBuilder("{");
                    String name = dev.getName();
                    sb.append("\"name\":\"").append(escJson(name != null ? name : "")).append("\"");
                    sb.append(",\"address\":\"").append(escJson(addr)).append("\"");
                    sb.append(",\"rssi\":").append(rssi);
                    int type = dev.getType();
                    String typeStr;
                    switch (type) {
                        case BluetoothDevice.DEVICE_TYPE_CLASSIC: typeStr = "classic"; break;
                        case BluetoothDevice.DEVICE_TYPE_LE: typeStr = "le"; break;
                        case BluetoothDevice.DEVICE_TYPE_DUAL: typeStr = "dual"; break;
                        default: typeStr = "unknown"; break;
                    }
                    sb.append(",\"type\":\"").append(typeStr).append("\"");
                    BluetoothClass btClass = dev.getBluetoothClass();
                    if (btClass != null) {
                        sb.append(",\"major_class\":").append(btClass.getMajorDeviceClass());
                        sb.append(",\"device_class\":").append(btClass.getDeviceClass());
                    }
                    sb.append("}");
                    found.put(addr, sb.toString());
                } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                    latch.countDown();
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        context.registerReceiver(receiver, filter);

        btAdapter.startDiscovery();
        try { latch.await(seconds, TimeUnit.SECONDS); } catch (InterruptedException e) { /* */ }
        btAdapter.cancelDiscovery();
        context.unregisterReceiver(receiver);

        StringBuilder sb = new StringBuilder("[");
        int count = 0;
        for (String entry : found.values()) {
            if (count > 0) sb.append(",");
            sb.append(entry);
            count++;
        }
        sb.append("]");
        return sb.toString();
    }

    @SuppressWarnings("MissingPermission")
    private String cmdBtScanBle(String args) {
        if (btAdapter == null) return "{\"error\":\"no bluetooth adapter\"}";
        if (!hasBtPermission("android.permission.BLUETOOTH_SCAN"))
            return "{\"error\":\"BLUETOOTH_SCAN permission not granted\"}";
        if (!btAdapter.isEnabled()) return "{\"error\":\"bluetooth is disabled\"}";

        BluetoothLeScanner scanner = btAdapter.getBluetoothLeScanner();
        if (scanner == null) return "{\"error\":\"BLE scanner not available\"}";

        int seconds = parseInt(args, 5);
        if (seconds > 30) seconds = 30;

        final java.util.Map<String, ScanResult> found = new java.util.concurrent.ConcurrentHashMap<>();

        ScanCallback callback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                String addr = result.getDevice().getAddress();
                ScanResult existing = found.get(addr);
                if (existing == null || result.getRssi() > existing.getRssi()) {
                    found.put(addr, result);
                }
            }
            @Override
            public void onScanFailed(int errorCode) {
                Log.w(TAG, "BLE scan failed: " + errorCode);
            }
        };

        scanner.startScan(callback);
        try { Thread.sleep(seconds * 1000L); } catch (InterruptedException e) { /* */ }
        scanner.stopScan(callback);

        boolean canConnect = hasBtPermission("android.permission.BLUETOOTH_CONNECT");
        StringBuilder sb = new StringBuilder("[");
        int count = 0;
        for (ScanResult result : found.values()) {
            if (count > 0) sb.append(",");
            BluetoothDevice dev = result.getDevice();
            sb.append("{");
            String name = canConnect ? dev.getName() : null;
            sb.append("\"name\":\"").append(escJson(name != null ? name : "")).append("\"");
            sb.append(",\"address\":\"").append(escJson(dev.getAddress())).append("\"");
            sb.append(",\"rssi\":").append(result.getRssi());
            sb.append(",\"connectable\":").append(result.isConnectable());
            android.bluetooth.le.ScanRecord record = result.getScanRecord();
            if (record != null) {
                List<android.os.ParcelUuid> uuids = record.getServiceUuids();
                if (uuids != null && !uuids.isEmpty()) {
                    sb.append(",\"uuids\":[");
                    for (int i = 0; i < uuids.size(); i++) {
                        if (i > 0) sb.append(",");
                        sb.append("\"").append(uuids.get(i).toString()).append("\"");
                    }
                    sb.append("]");
                }
                int txPower = record.getTxPowerLevel();
                if (txPower != Integer.MIN_VALUE) {
                    sb.append(",\"tx_power\":").append(txPower);
                }
            }
            sb.append("}");
            count++;
        }
        sb.append("]");
        return sb.toString();
    }

    /* ---- GATT operations ---- */

    private static String propsToString(int props) {
        StringBuilder sb = new StringBuilder();
        if ((props & BluetoothGattCharacteristic.PROPERTY_READ) != 0) sb.append("read,");
        if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) sb.append("write,");
        if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) sb.append("write_no_resp,");
        if ((props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) sb.append("notify,");
        if ((props & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) sb.append("indicate,");
        if ((props & BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE) != 0) sb.append("signed_write,");
        if ((props & BluetoothGattCharacteristic.PROPERTY_BROADCAST) != 0) sb.append("broadcast,");
        if ((props & BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS) != 0) sb.append("extended,");
        if (sb.length() > 0) sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        hex = hex.replaceAll("\\s+", "");
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    private static String bytesToUtf8(byte[] bytes) {
        if (bytes == null) return "";
        try {
            return new String(bytes, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    @SuppressWarnings("MissingPermission")
    private String cmdBtGatt(String args) throws InterruptedException {
        if (btAdapter == null) return "{\"error\":\"no bluetooth adapter\"}";
        if (!hasBtPermission("android.permission.BLUETOOTH_CONNECT"))
            return "{\"error\":\"BLUETOOTH_CONNECT permission not granted\"}";

        String address = unquote(args).trim().toUpperCase(Locale.US);
        if (address.isEmpty()) return "{\"error\":\"usage: bt_gatt <address>\"}";

        final BluetoothDevice device = btAdapter.getRemoteDevice(address);
        final CountDownLatch connectLatch = new CountDownLatch(1);
        final CountDownLatch discoverLatch = new CountDownLatch(1);
        final boolean[] success = {false};

        BluetoothGattCallback callback = new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    connectLatch.countDown();
                    gatt.discoverServices();
                } else {
                    connectLatch.countDown();
                    discoverLatch.countDown();
                }
            }
            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                success[0] = (status == BluetoothGatt.GATT_SUCCESS);
                discoverLatch.countDown();
            }
        };

        BluetoothGatt gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
        try {
            if (!connectLatch.await(10, TimeUnit.SECONDS)) {
                gatt.disconnect();
                gatt.close();
                return "{\"error\":\"connection timeout\"}";
            }
            if (!discoverLatch.await(10, TimeUnit.SECONDS)) {
                gatt.disconnect();
                gatt.close();
                return "{\"error\":\"service discovery timeout\"}";
            }
            if (!success[0]) {
                gatt.disconnect();
                gatt.close();
                return "{\"error\":\"service discovery failed\"}";
            }

            List<BluetoothGattService> services = gatt.getServices();
            StringBuilder sb = new StringBuilder("[");
            int sCount = 0;
            for (BluetoothGattService svc : services) {
                if (sCount > 0) sb.append(",");
                sb.append("{\"uuid\":\"").append(svc.getUuid().toString()).append("\"");
                sb.append(",\"characteristics\":[");
                List<BluetoothGattCharacteristic> chars = svc.getCharacteristics();
                int cCount = 0;
                for (BluetoothGattCharacteristic ch : chars) {
                    if (cCount > 0) sb.append(",");
                    sb.append("{\"uuid\":\"").append(ch.getUuid().toString()).append("\"");
                    sb.append(",\"properties\":\"").append(propsToString(ch.getProperties())).append("\"");
                    sb.append(",\"descriptors\":[");
                    List<BluetoothGattDescriptor> descs = ch.getDescriptors();
                    int dCount = 0;
                    for (BluetoothGattDescriptor desc : descs) {
                        if (dCount > 0) sb.append(",");
                        sb.append("\"").append(desc.getUuid().toString()).append("\"");
                        dCount++;
                    }
                    sb.append("]}");
                    cCount++;
                }
                sb.append("]}");
                sCount++;
            }
            sb.append("]");
            return sb.toString();
        } finally {
            gatt.disconnect();
            gatt.close();
        }
    }

    @SuppressWarnings("MissingPermission")
    private String cmdBtGattRead(String args) throws InterruptedException {
        if (btAdapter == null) return "{\"error\":\"no bluetooth adapter\"}";
        if (!hasBtPermission("android.permission.BLUETOOTH_CONNECT"))
            return "{\"error\":\"BLUETOOTH_CONNECT permission not granted\"}";

        String[] parts = unquote(args).trim().split("\\s+", 2);
        if (parts.length < 2) return "{\"error\":\"usage: bt_gatt_read <address> <char_uuid>\"}";
        String address = parts[0].toUpperCase(Locale.US);
        String charUuid = parts[1].toLowerCase(Locale.US);

        final BluetoothDevice device = btAdapter.getRemoteDevice(address);
        final CountDownLatch connectLatch = new CountDownLatch(1);
        final CountDownLatch discoverLatch = new CountDownLatch(1);
        final CountDownLatch readLatch = new CountDownLatch(1);
        final byte[][] readValue = {null};
        final int[] readStatus = {-1};

        BluetoothGattCallback callback = new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    connectLatch.countDown();
                    gatt.discoverServices();
                } else {
                    connectLatch.countDown();
                    discoverLatch.countDown();
                    readLatch.countDown();
                }
            }
            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                discoverLatch.countDown();
            }
            @Override
            public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic ch, byte[] value, int status) {
                readValue[0] = value;
                readStatus[0] = status;
                readLatch.countDown();
            }
        };

        BluetoothGatt gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
        try {
            if (!connectLatch.await(10, TimeUnit.SECONDS)) return "{\"error\":\"connection timeout\"}";
            if (!discoverLatch.await(10, TimeUnit.SECONDS)) return "{\"error\":\"discovery timeout\"}";

            BluetoothGattCharacteristic target = findCharacteristic(gatt, charUuid);
            if (target == null) return "{\"error\":\"characteristic not found: " + escJson(charUuid) + "\"}";

            gatt.readCharacteristic(target);
            if (!readLatch.await(10, TimeUnit.SECONDS)) return "{\"error\":\"read timeout\"}";
            if (readStatus[0] != BluetoothGatt.GATT_SUCCESS) return "{\"error\":\"read failed, status=" + readStatus[0] + "\"}";

            byte[] val = readValue[0];
            return "{\"hex\":\"" + bytesToHex(val) + "\""
                + ",\"utf8\":\"" + escJson(bytesToUtf8(val)) + "\""
                + ",\"length\":" + (val != null ? val.length : 0) + "}";
        } finally {
            gatt.disconnect();
            gatt.close();
        }
    }

    @SuppressWarnings("MissingPermission")
    private String cmdBtGattWrite(String args) throws InterruptedException {
        if (btAdapter == null) return "{\"error\":\"no bluetooth adapter\"}";
        if (!hasBtPermission("android.permission.BLUETOOTH_CONNECT"))
            return "{\"error\":\"BLUETOOTH_CONNECT permission not granted\"}";

        String[] parts = unquote(args).trim().split("\\s+", 3);
        if (parts.length < 3) return "{\"error\":\"usage: bt_gatt_write <address> <char_uuid> <hex>\"}";
        String address = parts[0].toUpperCase(Locale.US);
        String charUuid = parts[1].toLowerCase(Locale.US);
        byte[] data;
        try {
            data = hexToBytes(parts[2]);
        } catch (Exception e) {
            return "{\"error\":\"invalid hex data\"}";
        }

        final BluetoothDevice device = btAdapter.getRemoteDevice(address);
        final CountDownLatch connectLatch = new CountDownLatch(1);
        final CountDownLatch discoverLatch = new CountDownLatch(1);
        final CountDownLatch writeLatch = new CountDownLatch(1);
        final int[] writeStatus = {-1};

        BluetoothGattCallback callback = new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    connectLatch.countDown();
                    gatt.discoverServices();
                } else {
                    connectLatch.countDown();
                    discoverLatch.countDown();
                    writeLatch.countDown();
                }
            }
            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                discoverLatch.countDown();
            }
            @Override
            public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic ch, int status) {
                writeStatus[0] = status;
                writeLatch.countDown();
            }
        };

        BluetoothGatt gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
        try {
            if (!connectLatch.await(10, TimeUnit.SECONDS)) return "{\"error\":\"connection timeout\"}";
            if (!discoverLatch.await(10, TimeUnit.SECONDS)) return "{\"error\":\"discovery timeout\"}";

            BluetoothGattCharacteristic target = findCharacteristic(gatt, charUuid);
            if (target == null) return "{\"error\":\"characteristic not found: " + escJson(charUuid) + "\"}";

            int writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
            if ((target.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0
                && (target.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
            }
            gatt.writeCharacteristic(target, data, writeType);
            if (!writeLatch.await(10, TimeUnit.SECONDS)) return "{\"error\":\"write timeout\"}";
            if (writeStatus[0] != BluetoothGatt.GATT_SUCCESS) return "{\"error\":\"write failed, status=" + writeStatus[0] + "\"}";

            return "{\"status\":\"ok\",\"bytes_written\":" + data.length + "}";
        } finally {
            gatt.disconnect();
            gatt.close();
        }
    }

    @SuppressWarnings("MissingPermission")
    private String cmdBtGattNotify(String args) throws InterruptedException {
        if (btAdapter == null) return "{\"error\":\"no bluetooth adapter\"}";
        if (!hasBtPermission("android.permission.BLUETOOTH_CONNECT"))
            return "{\"error\":\"BLUETOOTH_CONNECT permission not granted\"}";

        String[] parts = unquote(args).trim().split("\\s+", 3);
        if (parts.length < 2) return "{\"error\":\"usage: bt_gatt_notify <address> <char_uuid> [seconds]\"}";
        String address = parts[0].toUpperCase(Locale.US);
        String charUuid = parts[1].toLowerCase(Locale.US);
        int seconds = 5;
        if (parts.length >= 3) {
            try { seconds = Integer.parseInt(parts[2]); } catch (Exception e) { /* default */ }
        }
        if (seconds > 60) seconds = 60;

        final BluetoothDevice device = btAdapter.getRemoteDevice(address);
        final CountDownLatch connectLatch = new CountDownLatch(1);
        final CountDownLatch discoverLatch = new CountDownLatch(1);
        final CountDownLatch descriptorLatch = new CountDownLatch(1);
        final List<String> values = java.util.Collections.synchronizedList(new ArrayList<String>());
        final boolean[] connected = {false};

        BluetoothGattCallback callback = new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    connected[0] = true;
                    connectLatch.countDown();
                    gatt.discoverServices();
                } else {
                    connectLatch.countDown();
                    discoverLatch.countDown();
                    descriptorLatch.countDown();
                }
            }
            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                discoverLatch.countDown();
            }
            @Override
            public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor desc, int status) {
                descriptorLatch.countDown();
            }
            @Override
            public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic ch, byte[] value) {
                String hex = bytesToHex(value);
                String utf8 = bytesToUtf8(value);
                long ts = System.currentTimeMillis();
                values.add("{\"time\":" + ts + ",\"hex\":\"" + hex + "\",\"utf8\":\"" + escJson(utf8) + "\"}");
            }
        };

        BluetoothGatt gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
        try {
            if (!connectLatch.await(10, TimeUnit.SECONDS)) return "{\"error\":\"connection timeout\"}";
            if (!connected[0]) return "{\"error\":\"connection failed\"}";
            if (!discoverLatch.await(10, TimeUnit.SECONDS)) return "{\"error\":\"discovery timeout\"}";

            BluetoothGattCharacteristic target = findCharacteristic(gatt, charUuid);
            if (target == null) return "{\"error\":\"characteristic not found: " + escJson(charUuid) + "\"}";

            int props = target.getProperties();
            boolean canNotify = (props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
            boolean canIndicate = (props & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
            if (!canNotify && !canIndicate) return "{\"error\":\"characteristic does not support notify/indicate\"}";

            gatt.setCharacteristicNotification(target, true);
            BluetoothGattDescriptor cccd = target.getDescriptor(CCCD_UUID);
            if (cccd != null) {
                byte[] enableValue = canNotify
                    ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
                gatt.writeDescriptor(cccd, enableValue);
                descriptorLatch.await(5, TimeUnit.SECONDS);
            }

            Thread.sleep(seconds * 1000L);

            /* Disable notifications */
            gatt.setCharacteristicNotification(target, false);
            if (cccd != null) {
                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
                Thread.sleep(200);
            }

            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(values.get(i));
            }
            sb.append("]");
            return sb.toString();
        } finally {
            gatt.disconnect();
            gatt.close();
        }
    }

    @SuppressWarnings("MissingPermission")
    private String cmdBtRssi(String args) throws InterruptedException {
        if (btAdapter == null) return "{\"error\":\"no bluetooth adapter\"}";
        if (!hasBtPermission("android.permission.BLUETOOTH_CONNECT"))
            return "{\"error\":\"BLUETOOTH_CONNECT permission not granted\"}";

        String address = unquote(args).trim().toUpperCase(Locale.US);
        if (address.isEmpty()) return "{\"error\":\"usage: bt_rssi <address>\"}";

        final BluetoothDevice device = btAdapter.getRemoteDevice(address);
        final CountDownLatch connectLatch = new CountDownLatch(1);
        final CountDownLatch rssiLatch = new CountDownLatch(1);
        final int[] rssiResult = {0};
        final int[] rssiStatus = {-1};

        BluetoothGattCallback callback = new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    connectLatch.countDown();
                    gatt.readRemoteRssi();
                } else {
                    connectLatch.countDown();
                    rssiLatch.countDown();
                }
            }
            @Override
            public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
                rssiResult[0] = rssi;
                rssiStatus[0] = status;
                rssiLatch.countDown();
            }
        };

        BluetoothGatt gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
        try {
            if (!connectLatch.await(5, TimeUnit.SECONDS)) return "{\"error\":\"connection timeout\"}";
            if (!rssiLatch.await(5, TimeUnit.SECONDS)) return "{\"error\":\"rssi read timeout\"}";
            if (rssiStatus[0] != BluetoothGatt.GATT_SUCCESS) return "{\"error\":\"rssi read failed, status=" + rssiStatus[0] + "\"}";

            return "{\"rssi\":" + rssiResult[0] + ",\"address\":\"" + escJson(address) + "\"}";
        } finally {
            gatt.disconnect();
            gatt.close();
        }
    }

    /** Find a characteristic by UUID across all discovered services */
    @SuppressWarnings("MissingPermission")
    private BluetoothGattCharacteristic findCharacteristic(BluetoothGatt gatt, String uuid) {
        UUID target = uuid.contains("-") ? UUID.fromString(uuid)
            : UUID.fromString("0000" + uuid + "-0000-1000-8000-00805f9b34fb");
        for (BluetoothGattService svc : gatt.getServices()) {
            for (BluetoothGattCharacteristic ch : svc.getCharacteristics()) {
                if (ch.getUuid().equals(target)) return ch;
            }
        }
        return null;
    }

    /* ---- Helpers ---- */

    private static String escJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') sb.append("\\\\");
            else if (c == '"') sb.append("\\\"");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.toString();
    }

    private static String unquote(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\""))
            s = s.substring(1, s.length() - 1);
        return s.replace("\\\"", "\"").replace("\\n", "\n");
    }

    private static int parseInt(String s, int def) {
        try {
            s = unquote(s.trim());
            return Integer.parseInt(s);
        } catch (Exception e) { return def; }
    }

    /** Minimal JSON field extractor — no external deps needed */
    static String jsonGet(String json, String key) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) return "";
        int start = idx + search.length();
        /* Skip whitespace */
        while (start < json.length() && json.charAt(start) == ' ') start++;
        if (start >= json.length()) return "";

        char c = json.charAt(start);
        if (c == '"') {
            /* String value */
            int end = json.indexOf('"', start + 1);
            while (end > 0 && json.charAt(end - 1) == '\\') {
                end = json.indexOf('"', end + 1);
            }
            return json.substring(start + 1, end);
        } else if (c == '[') {
            /* Array — find matching bracket */
            int depth = 1;
            int end = start + 1;
            while (end < json.length() && depth > 0) {
                if (json.charAt(end) == '[') depth++;
                else if (json.charAt(end) == ']') depth--;
                end++;
            }
            return json.substring(start, end);
        } else {
            /* Number/boolean — read until comma or } */
            int end = start;
            while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
            return json.substring(start, end).trim();
        }
    }
}
