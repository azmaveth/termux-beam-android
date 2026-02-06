package com.example.beamapp;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

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

    public BridgeServer(Context context) {
        this.context = context;
        this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
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
                        String response = dispatch(line.trim());
                        out.println(response);
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

    private String dispatch(String request) {
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
                case "services":       result = cmdServices(); break;
                case "features":       result = cmdFeatures(); break;
                case "shell":          result = cmdShell(args); break;
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

    /* ---- Helpers ---- */

    private static String escJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
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
