package com.example.beamapp;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureRequest;
import android.graphics.ImageFormat;
import android.media.AudioManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaPlayer;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.telephony.CellInfo;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.graphics.SurfaceTexture;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Phone hardware/OS capabilities exposed as simple JSON-returning methods.
 * Each method can be called from BridgeServer dispatch.
 */
public class PhoneAPI {
    private static final String TAG = "PhoneAPI";

    private final Context context;
    private MediaPlayer mediaPlayer;
    private PowerManager.WakeLock wakeLock;

    public PhoneAPI(Context context) {
        this.context = context;
    }

    public void release() {
        if (mediaPlayer != null) {
            try { mediaPlayer.release(); } catch (Exception e) {}
            mediaPlayer = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception e) {}
        }
    }

    /* ---- Helpers ---- */
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /* ---- Camera ---- */

    @SuppressWarnings("MissingPermission")
    public String cameraInfo() {
        try {
            CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            String[] ids = cm.getCameraIdList();
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < ids.length; i++) {
                if (i > 0) sb.append(",");
                CameraCharacteristics cc = cm.getCameraCharacteristics(ids[i]);
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                String facingStr = facing == CameraCharacteristics.LENS_FACING_FRONT ? "front"
                    : facing == CameraCharacteristics.LENS_FACING_BACK ? "back" : "external";
                Size[] sizes = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    .getOutputSizes(ImageFormat.JPEG);
                String maxSize = sizes != null && sizes.length > 0 ?
                    sizes[0].getWidth() + "x" + sizes[0].getHeight() : "unknown";
                sb.append("{\"id\":\"").append(ids[i])
                  .append("\",\"facing\":\"").append(facingStr)
                  .append("\",\"max_resolution\":\"").append(maxSize).append("\"}");
            }
            sb.append("]");
            return sb.toString();
        } catch (Exception e) {
            return "{\"error\":\"" + esc(e.getMessage()) + "\"}";
        }
    }

    @SuppressWarnings("MissingPermission")
    public String cameraPhoto(String args) {
        // args: "camera_id output_path" e.g. "0 /sdcard/photo.jpg"
        String[] parts = args.trim().split("\\s+", 2);
        String cameraId = parts.length > 0 ? parts[0] : "0";
        String outputPath = parts.length > 1 ? parts[1] : "/sdcard/photo.jpg";

        try {
            CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics cc = cm.getCameraCharacteristics(cameraId);
            Size[] sizes = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                .getOutputSizes(ImageFormat.JPEG);
            // Use a moderate resolution for speed
            Size size = sizes[Math.min(2, sizes.length - 1)];

            HandlerThread ht = new HandlerThread("camera");
            ht.start();
            Handler handler = new Handler(ht.getLooper());

            // JPEG ImageReader for the actual capture
            ImageReader reader = ImageReader.newInstance(size.getWidth(), size.getHeight(),
                ImageFormat.JPEG, 2);

            // Dummy preview surface — some camera HALs require a preview target
            SurfaceTexture dummyTexture = new SurfaceTexture(0);
            dummyTexture.setDefaultBufferSize(640, 480);
            Surface previewSurface = new Surface(dummyTexture);

            CountDownLatch photoLatch = new CountDownLatch(1);
            final String[] error = {null};

            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader r) {
                    Image image = null;
                    try {
                        image = r.acquireLatestImage();
                        if (image == null) return;
                        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                        byte[] bytes = new byte[buffer.remaining()];
                        buffer.get(bytes);
                        FileOutputStream fos = new FileOutputStream(outputPath);
                        fos.write(bytes);
                        fos.close();
                    } catch (Exception e) {
                        error[0] = e.getMessage();
                    } finally {
                        if (image != null) image.close();
                        photoLatch.countDown();
                    }
                }
            }, handler);

            Semaphore cameraLock = new Semaphore(0);
            final CameraDevice[] cameraRef = {null};

            cm.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    cameraRef[0] = camera;
                    cameraLock.release();
                }
                @Override
                public void onDisconnected(CameraDevice camera) {
                    cameraLock.release();
                }
                @Override
                public void onError(CameraDevice camera, int err) {
                    error[0] = "Camera error: " + err;
                    cameraLock.release();
                }
            }, handler);

            if (!cameraLock.tryAcquire(5, TimeUnit.SECONDS)) {
                previewSurface.release(); dummyTexture.release();
                ht.quitSafely();
                return "{\"error\":\"camera open timeout\"}";
            }
            if (error[0] != null) {
                previewSurface.release(); dummyTexture.release();
                ht.quitSafely();
                return "{\"error\":\"" + esc(error[0]) + "\"}";
            }

            CameraDevice camera = cameraRef[0];
            Surface jpegSurface = reader.getSurface();

            // Session with both preview + JPEG surfaces
            CountDownLatch sessionLatch = new CountDownLatch(1);
            camera.createCaptureSession(Arrays.asList(previewSurface, jpegSurface),
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession session) {
                        try {
                            // Run preview on dummy surface to warm up 3A
                            CaptureRequest.Builder preview = camera.createCaptureRequest(
                                CameraDevice.TEMPLATE_PREVIEW);
                            preview.addTarget(previewSurface);
                            preview.set(CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                            preview.set(CaptureRequest.CONTROL_AE_MODE,
                                CaptureRequest.CONTROL_AE_MODE_ON);
                            session.setRepeatingRequest(preview.build(), null, handler);

                            // After 1.5s warmup, fire still capture to JPEG surface
                            handler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        CaptureRequest.Builder capture = camera.createCaptureRequest(
                                            CameraDevice.TEMPLATE_STILL_CAPTURE);
                                        capture.addTarget(jpegSurface);
                                        capture.set(CaptureRequest.JPEG_QUALITY, (byte) 90);
                                        capture.set(CaptureRequest.CONTROL_AF_MODE,
                                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                        capture.set(CaptureRequest.CONTROL_AE_MODE,
                                            CaptureRequest.CONTROL_AE_MODE_ON);
                                        session.stopRepeating();
                                        session.capture(capture.build(), null, handler);
                                    } catch (Exception e) {
                                        error[0] = e.getMessage();
                                        photoLatch.countDown();
                                    }
                                }
                            }, 1500);
                        } catch (Exception e) {
                            error[0] = e.getMessage();
                            photoLatch.countDown();
                        }
                        sessionLatch.countDown();
                    }
                    @Override
                    public void onConfigureFailed(CameraCaptureSession session) {
                        error[0] = "session configure failed";
                        sessionLatch.countDown();
                        photoLatch.countDown();
                    }
                }, handler);

            sessionLatch.await(5, TimeUnit.SECONDS);
            photoLatch.await(10, TimeUnit.SECONDS);

            camera.close();
            reader.close();
            previewSurface.release();
            dummyTexture.release();
            ht.quitSafely();

            if (error[0] != null) {
                return "{\"error\":\"" + esc(error[0]) + "\"}";
            }

            File f = new File(outputPath);
            if (!f.exists() || f.length() == 0) {
                return "{\"error\":\"photo file not created\"}";
            }
            return "{\"path\":\"" + esc(outputPath) + "\",\"size\":" + f.length()
                + ",\"width\":" + size.getWidth() + ",\"height\":" + size.getHeight() + "}";
        } catch (Exception e) {
            Log.e(TAG, "Camera photo failed", e);
            return "{\"error\":\"" + esc(e.getMessage()) + "\"}";
        }
    }

    /* ---- Torch / Flashlight ---- */

    public String torch(String args) {
        boolean on = "on".equalsIgnoreCase(args.trim()) || "1".equals(args.trim()) || "true".equalsIgnoreCase(args.trim());
        try {
            CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            // Find back camera for torch
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics cc = cm.getCameraCharacteristics(id);
                Boolean hasFlash = cc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (hasFlash != null && hasFlash) {
                    cm.setTorchMode(id, on);
                    return "{\"torch\":" + on + ",\"camera_id\":\"" + id + "\"}";
                }
            }
            return "{\"error\":\"no flash available\"}";
        } catch (Exception e) {
            return "{\"error\":\"" + esc(e.getMessage()) + "\"}";
        }
    }

    /* ---- Volume ---- */

    public String volumeGet() {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            StringBuilder sb = new StringBuilder("[");
            int[][] streams = {
                {AudioManager.STREAM_MUSIC, 0},
                {AudioManager.STREAM_RING, 0},
                {AudioManager.STREAM_NOTIFICATION, 0},
                {AudioManager.STREAM_ALARM, 0},
                {AudioManager.STREAM_VOICE_CALL, 0},
                {AudioManager.STREAM_SYSTEM, 0}
            };
            String[] names = {"music", "ring", "notification", "alarm", "call", "system"};
            for (int i = 0; i < streams.length; i++) {
                if (i > 0) sb.append(",");
                int current = am.getStreamVolume(streams[i][0]);
                int max = am.getStreamMaxVolume(streams[i][0]);
                sb.append("{\"stream\":\"").append(names[i])
                  .append("\",\"volume\":").append(current)
                  .append(",\"max\":").append(max).append("}");
            }
            sb.append("]");
            return sb.toString();
        } catch (Exception e) {
            return "{\"error\":\"" + esc(e.getMessage()) + "\"}";
        }
    }

    public String volumeSet(String args) {
        // args: "stream level" e.g. "music 10"
        try {
            String[] parts = args.trim().split("\\s+", 2);
            String stream = parts[0].toLowerCase();
            int level = Integer.parseInt(parts[1]);
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            int streamType;
            switch (stream) {
                case "music":        streamType = AudioManager.STREAM_MUSIC; break;
                case "ring":         streamType = AudioManager.STREAM_RING; break;
                case "notification": streamType = AudioManager.STREAM_NOTIFICATION; break;
                case "alarm":        streamType = AudioManager.STREAM_ALARM; break;
                case "call":         streamType = AudioManager.STREAM_VOICE_CALL; break;
                case "system":       streamType = AudioManager.STREAM_SYSTEM; break;
                default: return "{\"error\":\"unknown stream: " + esc(stream) + "\"}";
            }
            am.setStreamVolume(streamType, level, 0);
            return "{\"stream\":\"" + stream + "\",\"volume\":" + level
                + ",\"max\":" + am.getStreamMaxVolume(streamType) + "}";
        } catch (Exception e) {
            return "{\"error\":\"" + esc(e.getMessage()) + "\"}";
        }
    }

    /* ---- Audio Info ---- */

    public String audioInfo() {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            return "{\"mode\":\"" + audioModeStr(am.getMode()) + "\""
                + ",\"ringer_mode\":\"" + ringerModeStr(am.getRingerMode()) + "\""
                + ",\"music_active\":" + am.isMusicActive()
                + ",\"speaker_on\":" + am.isSpeakerphoneOn()
                + ",\"bluetooth_sco\":" + am.isBluetoothScoOn()
                + ",\"wired_headset\":" + am.isWiredHeadsetOn()
                + "}";
        } catch (Exception e) {
            return "{\"error\":\"" + esc(e.getMessage()) + "\"}";
        }
    }

    private String audioModeStr(int mode) {
        switch (mode) {
            case AudioManager.MODE_NORMAL: return "normal";
            case AudioManager.MODE_RINGTONE: return "ringtone";
            case AudioManager.MODE_IN_CALL: return "in_call";
            case AudioManager.MODE_IN_COMMUNICATION: return "in_communication";
            default: return "unknown_" + mode;
        }
    }

    private String ringerModeStr(int mode) {
        switch (mode) {
            case AudioManager.RINGER_MODE_SILENT: return "silent";
            case AudioManager.RINGER_MODE_VIBRATE: return "vibrate";
            case AudioManager.RINGER_MODE_NORMAL: return "normal";
            default: return "unknown_" + mode;
        }
    }

    /* ---- Brightness ---- */

    public String brightnessSet(String args) {
        try {
            int level = Integer.parseInt(args.trim());
            level = Math.max(0, Math.min(255, level));
            Settings.System.putInt(context.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS, level);
            return "{\"brightness\":" + level + "}";
        } catch (Exception e) {
            return "{\"error\":\"" + esc(e.getMessage()) + "\"}";
        }
    }

    /* ---- Contacts ---- */

    @SuppressWarnings("MissingPermission")
    public String contacts(String args) {
        int limit = 50;
        try { limit = Integer.parseInt(args.trim()); } catch (Exception e) {}
        try {
            ContentResolver cr = context.getContentResolver();
            Cursor c = cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                }, null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC");

            StringBuilder sb = new StringBuilder("[");
            int count = 0;
            if (c != null) {
                while (c.moveToNext() && count < limit) {
                    if (count > 0) sb.append(",");
                    String name = c.getString(0);
                    String number = c.getString(1);
                    sb.append("{\"name\":\"").append(esc(name))
                      .append("\",\"number\":\"").append(esc(number)).append("\"}");
                    count++;
                }
                c.close();
            }
            sb.append("]");
            return sb.toString();
        } catch (Exception e) {
            return "{\"error\":\"" + esc(e.getMessage()) + "\"}";
        }
    }

    /* ---- Call Log ---- */

    @SuppressWarnings("MissingPermission")
    public String callLog(String args) {
        int limit = 20;
        try { limit = Integer.parseInt(args.trim()); } catch (Exception e) {}
        try {
            ContentResolver cr = context.getContentResolver();
            Cursor c = cr.query(CallLog.Calls.CONTENT_URI,
                new String[]{
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION
                }, null, null, CallLog.Calls.DATE + " DESC");

            StringBuilder sb = new StringBuilder("[");
            int count = 0;
            if (c != null) {
                while (c.moveToNext() && count < limit) {
                    if (count > 0) sb.append(",");
                    String number = c.getString(0);
                    String name = c.getString(1);
                    int type = c.getInt(2);
                    long date = c.getLong(3);
                    int duration = c.getInt(4);
                    String typeStr = type == CallLog.Calls.INCOMING_TYPE ? "incoming"
                        : type == CallLog.Calls.OUTGOING_TYPE ? "outgoing"
                        : type == CallLog.Calls.MISSED_TYPE ? "missed" : "other";
                    sb.append("{\"number\":\"").append(esc(number))
                      .append("\",\"name\":\"").append(esc(name != null ? name : ""))
                      .append("\",\"type\":\"").append(typeStr)
                      .append("\",\"date\":").append(date)
                      .append(",\"duration\":").append(duration).append("}");
                    count++;
                }
                c.close();
            }
            sb.append("]");
            return sb.toString();
        } catch (Exception e) {
            return "{\"error\":\"" + esc(e.getMessage()) + "\"}";
        }
    }

    /* ---- SMS ---- */

    @SuppressWarnings("MissingPermission")
    public String smsInbox(String args) {
        int limit = 20;
        try { limit = Integer.parseInt(args.trim()); } catch (Exception e) {}
        try {
            ContentResolver cr = context.getContentResolver();
            Cursor c = cr.query(Uri.parse("content://sms/inbox"),
                new String[]{"address", "body", "date", "read"},
                null, null, "date DESC");

            StringBuilder sb = new StringBuilder("[");
            int count = 0;
            if (c != null) {
                while (c.moveToNext() && count < limit) {
                    if (count > 0) sb.append(",");
                    String addr = c.getString(0);
                    String body = c.getString(1);
                    long date = c.getLong(2);
                    int read = c.getInt(3);
                    sb.append("{\"from\":\"").append(esc(addr))
                      .append("\",\"body\":\"").append(esc(body))
                      .append("\",\"date\":").append(date)
                      .append(",\"read\":").append(read == 1).append("}");
                    count++;
                }
                c.close();
            }
            sb.append("]");
            return sb.toString();
        } catch (Exception e) {
            return "{\"error\":\"" + esc(e.getMessage()) + "\"}";
        }
    }

    @SuppressWarnings("MissingPermission")
    public String smsSend(String args) {
        // args: "number message"
        String[] parts = args.trim().split("\\s+", 2);
        if (parts.length < 2) return "{\"error\":\"usage: sms_send <number> <message>\"}";
        String number = parts[0];
        String message = parts[1];
        try {
            android.telephony.SmsManager sms = android.telephony.SmsManager.getDefault();
            sms.sendTextMessage(number, null, message, null, null);
            return "{\"sent\":true,\"to\":\"" + esc(number)
                + "\",\"length\":" + message.length() + "}";
        } catch (Exception e) {
            return "{\"error\":\"" + esc(e.getMessage()) + "\"}";
        }
    }

    /* ---- Telephony ---- */

    @SuppressWarnings("MissingPermission")
    public String telephonyInfo() {
        try {
            TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            String operator = tm.getNetworkOperatorName();
            String simOp = tm.getSimOperatorName();
            int phoneType = tm.getPhoneType();
            String phoneTypeStr = phoneType == TelephonyManager.PHONE_TYPE_GSM ? "GSM"
                : phoneType == TelephonyManager.PHONE_TYPE_CDMA ? "CDMA"
                : phoneType == TelephonyManager.PHONE_TYPE_SIP ? "SIP" : "none";
            int networkType = tm.getDataNetworkType();
            String networkStr = networkTypeStr(networkType);
            int simState = tm.getSimState();
            String simStr = simState == TelephonyManager.SIM_STATE_READY ? "ready"
                : simState == TelephonyManager.SIM_STATE_ABSENT ? "absent" : "other";

            StringBuilder sb = new StringBuilder("{");
            sb.append("\"operator\":\"").append(esc(operator)).append("\"");
            sb.append(",\"sim_operator\":\"").append(esc(simOp)).append("\"");
            sb.append(",\"phone_type\":\"").append(phoneTypeStr).append("\"");
            sb.append(",\"network_type\":\"").append(networkStr).append("\"");
            sb.append(",\"sim_state\":\"").append(simStr).append("\"");

            // Cell info
            try {
                List<CellInfo> cells = tm.getAllCellInfo();
                if (cells != null) {
                    sb.append(",\"cell_count\":").append(cells.size());
                }
            } catch (Exception e) {
                sb.append(",\"cell_count\":-1");
            }

            sb.append("}");
            return sb.toString();
        } catch (Exception e) {
            return "{\"error\":\"" + esc(e.getMessage()) + "\"}";
        }
    }

    private String networkTypeStr(int type) {
        switch (type) {
            case TelephonyManager.NETWORK_TYPE_LTE: return "LTE";
            case TelephonyManager.NETWORK_TYPE_NR: return "5G";
            case TelephonyManager.NETWORK_TYPE_HSDPA: return "HSDPA";
            case TelephonyManager.NETWORK_TYPE_HSUPA: return "HSUPA";
            case TelephonyManager.NETWORK_TYPE_HSPA: return "HSPA";
            case TelephonyManager.NETWORK_TYPE_UMTS: return "UMTS";
            case TelephonyManager.NETWORK_TYPE_EDGE: return "EDGE";
            case TelephonyManager.NETWORK_TYPE_GPRS: return "GPRS";
            default: return "type_" + type;
        }
    }

    /* ---- Media Player ---- */

    public String mediaPlay(String args) {
        String path = args.trim();
        if (path.isEmpty()) return "{\"error\":\"usage: media_play <path_or_url>\"}";
        try {
            if (mediaPlayer != null) {
                mediaPlayer.release();
            }
            mediaPlayer = new MediaPlayer();
            if (path.startsWith("http://") || path.startsWith("https://")) {
                mediaPlayer.setDataSource(path);
            } else {
                mediaPlayer.setDataSource(path);
            }
            mediaPlayer.prepare();
            mediaPlayer.start();
            return "{\"playing\":true,\"path\":\"" + esc(path)
                + "\",\"duration\":" + (mediaPlayer.getDuration() / 1000.0) + "}";
        } catch (Exception e) {
            return "{\"error\":\"" + esc(e.getMessage()) + "\"}";
        }
    }

    public String mediaStop() {
        if (mediaPlayer == null) return "{\"error\":\"no media playing\"}";
        try {
            int pos = mediaPlayer.getCurrentPosition();
            mediaPlayer.stop();
            mediaPlayer.release();
            mediaPlayer = null;
            return "{\"stopped\":true,\"position\":" + (pos / 1000.0) + "}";
        } catch (Exception e) {
            return "{\"error\":\"" + esc(e.getMessage()) + "\"}";
        }
    }

    public String mediaStatus() {
        if (mediaPlayer == null) return "{\"playing\":false}";
        try {
            return "{\"playing\":" + mediaPlayer.isPlaying()
                + ",\"position\":" + (mediaPlayer.getCurrentPosition() / 1000.0)
                + ",\"duration\":" + (mediaPlayer.getDuration() / 1000.0) + "}";
        } catch (Exception e) {
            return "{\"error\":\"" + esc(e.getMessage()) + "\"}";
        }
    }

    /* ---- Wake Lock ---- */

    public String wakeLockAcquire(String args) {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                return "{\"status\":\"already_held\"}";
            }
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            int timeout = 0;
            try { timeout = Integer.parseInt(args.trim()); } catch (Exception e) {}
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "beamapp:wakelock");
            if (timeout > 0) {
                wakeLock.acquire(timeout * 1000L);
            } else {
                wakeLock.acquire();
            }
            return "{\"status\":\"acquired\"" + (timeout > 0 ? ",\"timeout\":" + timeout : "") + "}";
        } catch (Exception e) {
            return "{\"error\":\"" + esc(e.getMessage()) + "\"}";
        }
    }

    public String wakeLockRelease() {
        if (wakeLock == null || !wakeLock.isHeld()) {
            return "{\"status\":\"not_held\"}";
        }
        try {
            wakeLock.release();
            return "{\"status\":\"released\"}";
        } catch (Exception e) {
            return "{\"error\":\"" + esc(e.getMessage()) + "\"}";
        }
    }

    /* ---- Open URL / Intent ---- */

    public String openUrl(String args) {
        String url = args.trim();
        if (url.isEmpty()) return "{\"error\":\"usage: open_url <url>\"}";
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return "{\"opened\":\"" + esc(url) + "\"}";
        } catch (Exception e) {
            return "{\"error\":\"" + esc(e.getMessage()) + "\"}";
        }
    }

    /* ---- WiFi Scan ---- */

    @SuppressWarnings("MissingPermission")
    public String wifiScan() {
        try {
            WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            wm.startScan();
            List<ScanResult> results = wm.getScanResults();
            StringBuilder sb = new StringBuilder("[");
            int count = 0;
            for (ScanResult r : results) {
                if (count > 0) sb.append(",");
                sb.append("{\"ssid\":\"").append(esc(r.SSID))
                  .append("\",\"bssid\":\"").append(esc(r.BSSID))
                  .append("\",\"level\":").append(r.level)
                  .append(",\"frequency\":").append(r.frequency)
                  .append(",\"capabilities\":\"").append(esc(r.capabilities)).append("\"}");
                count++;
                if (count >= 50) break;
            }
            sb.append("]");
            return sb.toString();
        } catch (Exception e) {
            return "{\"error\":\"" + esc(e.getMessage()) + "\"}";
        }
    }

    /* ---- File Operations ---- */

    public String fileRead(String args) {
        String path = args.trim();
        if (path.isEmpty()) return "{\"error\":\"usage: file_read <path>\"}";
        try {
            File f = new File(path);
            if (!f.exists()) return "{\"error\":\"file not found\"}";
            if (f.length() > 1024 * 1024) return "{\"error\":\"file too large (>1MB)\"}";
            byte[] data = new byte[(int) f.length()];
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            fis.read(data);
            fis.close();
            return "{\"path\":\"" + esc(path) + "\",\"size\":" + f.length()
                + ",\"content\":\"" + esc(new String(data)) + "\"}";
        } catch (Exception e) {
            return "{\"error\":\"" + esc(e.getMessage()) + "\"}";
        }
    }

    public String fileWrite(String args) {
        // args: "path content"
        int sep = args.indexOf(' ');
        if (sep < 0) return "{\"error\":\"usage: file_write <path> <content>\"}";
        String path = args.substring(0, sep).trim();
        String content = args.substring(sep + 1);
        try {
            FileOutputStream fos = new FileOutputStream(path);
            fos.write(content.getBytes());
            fos.close();
            return "{\"path\":\"" + esc(path) + "\",\"size\":" + content.length() + "}";
        } catch (Exception e) {
            return "{\"error\":\"" + esc(e.getMessage()) + "\"}";
        }
    }

    public String fileList(String args) {
        String path = args.trim();
        if (path.isEmpty()) path = "/sdcard";
        try {
            File dir = new File(path);
            if (!dir.isDirectory()) return "{\"error\":\"not a directory\"}";
            File[] files = dir.listFiles();
            if (files == null) return "{\"error\":\"cannot list directory\"}";
            StringBuilder sb = new StringBuilder("[");
            Arrays.sort(files);
            for (int i = 0; i < files.length && i < 200; i++) {
                if (i > 0) sb.append(",");
                File f = files[i];
                sb.append("{\"name\":\"").append(esc(f.getName()))
                  .append("\",\"type\":\"").append(f.isDirectory() ? "dir" : "file")
                  .append("\",\"size\":").append(f.length())
                  .append(",\"modified\":").append(f.lastModified()).append("}");
            }
            sb.append("]");
            return sb.toString();
        } catch (Exception e) {
            return "{\"error\":\"" + esc(e.getMessage()) + "\"}";
        }
    }

    /* ---- Screen State ---- */

    public String screenState() {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            boolean interactive = pm.isInteractive();
            int brightness = Settings.System.getInt(context.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS, -1);
            return "{\"screen_on\":" + interactive + ",\"brightness\":" + brightness + "}";
        } catch (Exception e) {
            return "{\"error\":\"" + esc(e.getMessage()) + "\"}";
        }
    }

    /* ---- Process Info ---- */

    public String processInfo() {
        try {
            Runtime rt = Runtime.getRuntime();
            long maxMem = rt.maxMemory();
            long totalMem = rt.totalMemory();
            long freeMem = rt.freeMemory();
            int procs = rt.availableProcessors();
            long uptime = android.os.SystemClock.elapsedRealtime();
            return "{\"max_memory\":" + maxMem
                + ",\"total_memory\":" + totalMem
                + ",\"free_memory\":" + freeMem
                + ",\"used_memory\":" + (totalMem - freeMem)
                + ",\"processors\":" + procs
                + ",\"uptime_ms\":" + uptime + "}";
        } catch (Exception e) {
            return "{\"error\":\"" + esc(e.getMessage()) + "\"}";
        }
    }

    /* ---- Help: list all available commands ---- */

    public static String help() {
        return "[" +
            // System
            "{\"cmd\":\"help\",\"desc\":\"List all commands\"}," +
            "{\"cmd\":\"ping\",\"desc\":\"Health check (returns pong)\"}," +
            "{\"cmd\":\"device_info\",\"desc\":\"Device model, OS, SDK info\"}," +
            "{\"cmd\":\"battery\",\"desc\":\"Battery level, charging status\"}," +
            "{\"cmd\":\"memory_info\",\"desc\":\"System memory usage\"}," +
            "{\"cmd\":\"process_info\",\"desc\":\"JVM memory and CPU info\"}," +
            "{\"cmd\":\"screen_state\",\"desc\":\"Screen on/off, brightness\"}," +
            "{\"cmd\":\"screen_brightness\",\"desc\":\"Get screen brightness\"}," +
            "{\"cmd\":\"brightness_set\",\"args\":\"<0-255>\",\"desc\":\"Set screen brightness\"}," +
            "{\"cmd\":\"system_prop\",\"args\":\"<prop>\",\"desc\":\"Get Android system property\"}," +
            "{\"cmd\":\"packages\",\"desc\":\"List installed packages\"}," +
            "{\"cmd\":\"services\",\"desc\":\"List running services\"}," +
            "{\"cmd\":\"features\",\"desc\":\"List device features\"}," +
            // Network
            "{\"cmd\":\"wifi_info\",\"desc\":\"WiFi connection info\"}," +
            "{\"cmd\":\"wifi_scan\",\"desc\":\"Scan nearby WiFi networks\"}," +
            "{\"cmd\":\"network_info\",\"desc\":\"Network connectivity info\"}," +
            "{\"cmd\":\"telephony_info\",\"desc\":\"Carrier, SIM, network type\"}," +
            // Location & Sensors
            "{\"cmd\":\"location\",\"desc\":\"GPS coordinates\"}," +
            "{\"cmd\":\"sensors_list\",\"desc\":\"List available sensors\"}," +
            "{\"cmd\":\"sensor_start\",\"args\":\"<type>\",\"desc\":\"Start sensor readings\"}," +
            "{\"cmd\":\"sensor_read\",\"args\":\"<type>\",\"desc\":\"Read sensor data\"}," +
            "{\"cmd\":\"sensor_stop\",\"args\":\"<type>\",\"desc\":\"Stop sensor readings\"}," +
            // Camera
            "{\"cmd\":\"camera_info\",\"desc\":\"List cameras with capabilities\"}," +
            "{\"cmd\":\"camera_photo\",\"args\":\"<cam_id> <path>\",\"desc\":\"Take a photo\"}," +
            "{\"cmd\":\"torch\",\"args\":\"<on|off>\",\"desc\":\"Toggle flashlight\"}," +
            // Audio & Speech
            "{\"cmd\":\"tts\",\"args\":\"<text> or <sid> <speed> <text>\",\"desc\":\"Text-to-speech (8 voices)\"}," +
            "{\"cmd\":\"tts_voices\",\"desc\":\"Number of TTS voices\"}," +
            "{\"cmd\":\"stt\",\"args\":\"<wav_path>\",\"desc\":\"Transcribe WAV file\"}," +
            "{\"cmd\":\"listen\",\"args\":\"<seconds>\",\"desc\":\"Listen via phone mic (STT)\"}," +
            "{\"cmd\":\"stream_listen\",\"args\":\"<seconds>\",\"desc\":\"Streaming STT with partials\"}," +
            "{\"cmd\":\"pre_listen\",\"desc\":\"Start pre-recording for faster listen\"}," +
            "{\"cmd\":\"transcribe_offline\",\"args\":\"<wav_path>\",\"desc\":\"Offline STT (SenseVoice)\"}," +
            "{\"cmd\":\"diarize\",\"args\":\"<wav_path>\",\"desc\":\"Speaker diarization + transcription\"}," +
            "{\"cmd\":\"speech_status\",\"desc\":\"STT/TTS/VAD engine status\"}," +
            "{\"cmd\":\"mic_record\",\"args\":\"<seconds>\",\"desc\":\"Record mic to WAV file\"}," +
            "{\"cmd\":\"mic_stop\",\"desc\":\"Stop mic recording\"}," +
            "{\"cmd\":\"audio_info\",\"desc\":\"Audio mode, routing info\"}," +
            "{\"cmd\":\"volume_get\",\"desc\":\"Get all volume levels\"}," +
            "{\"cmd\":\"volume_set\",\"args\":\"<stream> <level>\",\"desc\":\"Set volume (music/ring/alarm/call/notification/system)\"}," +
            "{\"cmd\":\"media_play\",\"args\":\"<path_or_url>\",\"desc\":\"Play audio file or URL\"}," +
            "{\"cmd\":\"media_stop\",\"desc\":\"Stop media playback\"}," +
            "{\"cmd\":\"media_status\",\"desc\":\"Media player status\"}," +
            // BLE Earbuds
            "{\"cmd\":\"buddie_connect\",\"args\":\"<ble_addr>\",\"desc\":\"Connect Buddie earbuds\"}," +
            "{\"cmd\":\"buddie_disconnect\",\"desc\":\"Disconnect earbuds\"}," +
            "{\"cmd\":\"buddie_listen\",\"args\":\"<seconds>\",\"desc\":\"STT via earbuds with VAD\"}," +
            "{\"cmd\":\"buddie_status\",\"desc\":\"Earbud connection status\"}," +
            // Bluetooth
            "{\"cmd\":\"bt_status\",\"desc\":\"Bluetooth adapter status\"}," +
            "{\"cmd\":\"bt_bonded\",\"desc\":\"List paired BT devices\"}," +
            "{\"cmd\":\"bt_connected\",\"desc\":\"List connected BT profiles\"}," +
            "{\"cmd\":\"bt_scan\",\"args\":\"<seconds>\",\"desc\":\"Classic BT scan\"}," +
            "{\"cmd\":\"bt_scan_ble\",\"args\":\"<seconds>\",\"desc\":\"BLE scan\"}," +
            "{\"cmd\":\"bt_gatt\",\"args\":\"<addr>\",\"desc\":\"Discover GATT services\"}," +
            "{\"cmd\":\"bt_gatt_read\",\"args\":\"<addr> <uuid>\",\"desc\":\"Read GATT characteristic\"}," +
            "{\"cmd\":\"bt_gatt_write\",\"args\":\"<addr> <uuid> <hex>\",\"desc\":\"Write GATT characteristic\"}," +
            "{\"cmd\":\"bt_gatt_notify\",\"args\":\"<addr> <uuid> <seconds>\",\"desc\":\"Subscribe GATT notifications\"}," +
            "{\"cmd\":\"bt_rssi\",\"args\":\"<addr>\",\"desc\":\"Read BLE RSSI\"}," +
            // Communication
            "{\"cmd\":\"sms_inbox\",\"args\":\"[limit]\",\"desc\":\"Read SMS inbox\"}," +
            "{\"cmd\":\"sms_send\",\"args\":\"<number> <message>\",\"desc\":\"Send SMS\"}," +
            "{\"cmd\":\"contacts\",\"args\":\"[limit]\",\"desc\":\"List contacts\"}," +
            "{\"cmd\":\"call_log\",\"args\":\"[limit]\",\"desc\":\"Recent call history\"}," +
            "{\"cmd\":\"notify\",\"args\":\"<title> <text>\",\"desc\":\"Show notification\"}," +
            "{\"cmd\":\"toast\",\"args\":\"<text>\",\"desc\":\"Show toast message\"}," +
            // Clipboard
            "{\"cmd\":\"clipboard_get\",\"desc\":\"Get clipboard text\"}," +
            "{\"cmd\":\"clipboard_set\",\"args\":\"<text>\",\"desc\":\"Set clipboard text\"}," +
            // Files
            "{\"cmd\":\"file_read\",\"args\":\"<path>\",\"desc\":\"Read file contents\"}," +
            "{\"cmd\":\"file_write\",\"args\":\"<path> <content>\",\"desc\":\"Write file\"}," +
            "{\"cmd\":\"file_list\",\"args\":\"[dir]\",\"desc\":\"List directory contents\"}," +
            // System control
            "{\"cmd\":\"vibrate\",\"args\":\"<ms>\",\"desc\":\"Vibrate device\"}," +
            "{\"cmd\":\"wake_lock\",\"args\":\"[seconds]\",\"desc\":\"Acquire wake lock\"}," +
            "{\"cmd\":\"wake_unlock\",\"desc\":\"Release wake lock\"}," +
            "{\"cmd\":\"open_url\",\"args\":\"<url>\",\"desc\":\"Open URL in browser\"}," +
            "{\"cmd\":\"shell\",\"args\":\"<command>\",\"desc\":\"Execute shell command\"}," +
            // Debugging
            "{\"cmd\":\"logcat\",\"args\":\"[lines]\",\"desc\":\"Android log output\"}," +
            "{\"cmd\":\"dumpsys\",\"args\":\"<service>\",\"desc\":\"Dump system service info\"}," +
            "{\"cmd\":\"pm_path\",\"args\":\"<pkg>\",\"desc\":\"APK path for package\"}," +
            "{\"cmd\":\"pm_info\",\"args\":\"<pkg>\",\"desc\":\"Package info\"}," +
            "{\"cmd\":\"content_query\",\"args\":\"<uri>\",\"desc\":\"Query content provider\"}," +
            "{\"cmd\":\"intent_send\",\"args\":\"<json>\",\"desc\":\"Send Android intent\"}," +
            // Screen control (AccessibilityService)
            "{\"cmd\":\"screen_info\",\"desc\":\"Accessibility service status\"}," +
            "{\"cmd\":\"screen_read\",\"args\":\"[max_depth]\",\"desc\":\"Dump UI tree as JSON\"}," +
            "{\"cmd\":\"screen_find\",\"args\":\"<text>\",\"desc\":\"Find UI nodes by text\"}," +
            "{\"cmd\":\"screen_click\",\"args\":\"<text>\",\"desc\":\"Click UI element by text\"}," +
            "{\"cmd\":\"screen_tap\",\"args\":\"<x> <y>\",\"desc\":\"Tap screen coordinates\"}," +
            "{\"cmd\":\"screen_type\",\"args\":\"<text>\",\"desc\":\"Type text into focused field\"}," +
            "{\"cmd\":\"screen_swipe\",\"args\":\"<x1> <y1> <x2> <y2> [ms]\",\"desc\":\"Swipe gesture\"}," +
            "{\"cmd\":\"screen_back\",\"desc\":\"Press Back button\"}," +
            "{\"cmd\":\"screen_home\",\"desc\":\"Press Home button\"}," +
            "{\"cmd\":\"screen_recents\",\"desc\":\"Open recent apps\"}," +
            "{\"cmd\":\"screen_notifications\",\"desc\":\"Open notification shade\"}," +
            "{\"cmd\":\"screen_screenshot\",\"args\":\"[path]\",\"desc\":\"Take screenshot (API 30+)\"}" +
            "]";
    }
}
