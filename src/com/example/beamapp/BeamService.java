package com.example.beamapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.res.AssetManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Map;

public class BeamService extends Service {
    private static final String TAG = "BeamService";
    private static final String CHANNEL_ID = "beam_service_channel";
    private static final int NOTIFICATION_ID = 1;

    private final IBinder binder = new LocalBinder();
    private Handler handler;
    private OutputCallback outputCallback;
    private Process beamProcess;
    private OutputStream beamStdin;
    private volatile boolean isBeamRunning = false;
    private BridgeServer bridgeServer;

    public interface OutputCallback {
        void onOutput(String text);
        void onStatusChanged(boolean running);
    }

    public class LocalBinder extends Binder {
        BeamService getService() {
            return BeamService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification());
        if (intent != null && intent.getBooleanExtra("autostart", false)) {
            Log.i(TAG, "Autostart requested — launching BEAM VM");
            startBeam();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public void setOutputCallback(OutputCallback callback) {
        this.outputCallback = callback;
    }

    public void startBeam() {
        if (isBeamRunning) return;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    appendOutput("Preparing BEAM runtime...\n");

                    /* Extract OTP assets (always re-extract to pick up changes) */
                    File erlRoot = new File(getFilesDir(), "erlang");
                    appendOutput("Extracting OTP files...\n");
                    extractAssets("erlang", erlRoot);
                    appendOutput("Extraction complete.\n");

                    /* Start the Android bridge server */
                    if (bridgeServer != null) bridgeServer.stop();
                    bridgeServer = new BridgeServer(BeamService.this);
                    bridgeServer.start();
                    appendOutput("Bridge server started on port " + BridgeServer.PORT + "\n");

                    /* Set up symlinks for shared libraries */
                    String nativeLibDir = getApplicationInfo().nativeLibraryDir;
                    File libDir = new File(getFilesDir(), "lib");
                    libDir.mkdirs();
                    createSymlink(nativeLibDir + "/libncursesw_compat.so",
                                  libDir + "/libncursesw.so.6");
                    createSymlink(nativeLibDir + "/libz_compat.so",
                                  libDir + "/libz.so.1");
                    createSymlink(nativeLibDir + "/libc++_shared.so",
                                  libDir + "/libc++_shared.so");
                    /* OpenSSL libraries for crypto NIF */
                    createSymlink(nativeLibDir + "/libcrypto3.so",
                                  libDir + "/libcrypto.so.3");
                    createSymlink(nativeLibDir + "/libssl3.so",
                                  libDir + "/libssl.so.3");

                    /* Set up ERTS bin dir with proper names */
                    File ertsBin = new File(getFilesDir(), "erts/bin");
                    ertsBin.mkdirs();
                    createSymlink(nativeLibDir + "/libbeam_vm.so",
                                  ertsBin + "/beam.smp");
                    createSymlink(nativeLibDir + "/liberl_child_setup.so",
                                  ertsBin + "/erl_child_setup");
                    createSymlink(nativeLibDir + "/libinet_gethost.so",
                                  ertsBin + "/inet_gethost");

                    String beamPath = nativeLibDir + "/libbeam_vm.so";
                    String rootDir = erlRoot.getAbsolutePath();
                    String binDir = ertsBin.getAbsolutePath();

                    /* Erlang boot: start android bridge, then command server */
                    String evalCode =
                        "io:format(\"~n=== BEAM VM Started ===~n\"), " +
                        "io:format(\"OTP Release: ~s~n\", [erlang:system_info(otp_release)]), " +
                        "io:format(\"ERTS Version: ~s~n\", [erlang:system_info(version)]), " +
                        "io:format(\"Schedulers: ~p~n\", [erlang:system_info(schedulers)]), " +

                        /* Start crypto and SSL */
                        "io:format(\"~nStarting crypto... \"), " +
                        "case application:ensure_all_started(ssl) of " +
                        "  {ok, Started} -> " +
                        "    io:format(\"ok (~p apps)~n\", [length(Started)]), " +
                        "    [{_,_,CryptoVer}|_] = crypto:info_lib(), " +
                        "    io:format(\"Crypto: ~s~n\", [CryptoVer]), " +
                        "    io:format(\"SSL/TLS ready.~n\"); " +
                        "  {error, Reason} -> " +
                        "    io:format(\"FAILED: ~p~n\", [Reason]) " +
                        "end, " +

                        /* Start android bridge */
                        "timer:sleep(500), " +
                        "io:format(\"~nConnecting to Android bridge...\"), " +
                        "{ok, _} = android:start_link(), " +
                        "io:format(\" connected!~n\"), " +

                        /* Query device info */
                        "{ok, DevInfo} = android:device_info(), " +
                        "io:format(\"Device: ~s~n\", [DevInfo]), " +
                        "{ok, Batt} = android:battery(), " +
                        "io:format(\"Battery: ~s~n\", [Batt]), " +

                        "io:format(\"~n--- Android Bridge Ready ---~n\"), " +
                        "io:format(\"Commands: device battery vibrate toast ping,~n\"), " +
                        "io:format(\"  sensors accel gyro light wifi network memory,~n\"), " +
                        "io:format(\"  notify <t> <b>, clipboard, copy <text>,~n\"), " +
                        "io:format(\"  packages, brightness, procs, prop <name>,~n\"), " +
                        "io:format(\"  services, features, shell <cmd>,~n\"), " +
                        "io:format(\"  say <text>, listen [secs], stream_listen [secs],~n\"), " +
                        "io:format(\"  record [secs], stt [path], transcribe [path],~n\"), " +
                        "io:format(\"  diarize [path], speech_status,~n\"), " +
                        "io:format(\"  bt_status, bt_bonded, bt_connected,~n\"), " +
                        "io:format(\"  bt_scan [secs], bt_scan_ble [secs],~n\"), " +
                        "io:format(\"  bt_gatt <addr>, bt_gatt_read <addr> <uuid>,~n\"), " +
                        "io:format(\"  bt_gatt_write <addr> <uuid> <hex>,~n\"), " +
                        "io:format(\"  bt_gatt_notify <addr> <uuid> [secs],~n\"), " +
                        "io:format(\"  bt_rssi <addr>~n~n\"), " +

                        /* Start command TCP server on 9876 for interactive use */
                        "CmdPort = 9876, " +
                        "{ok, LSock} = gen_tcp:listen(CmdPort, " +
                        "  [binary, {active, false}, {reuseaddr, true}, {packet, 0}]), " +
                        "io:format(\"Command server on port ~p. Send commands!~n~n\", [CmdPort]), " +

                        "HandleCmd = fun HandleC(S) -> " +
                        "  case gen_tcp:recv(S, 0, 60000) of " +
                        "    {ok, Data} -> " +
                        "      Cmd = string:trim(Data), " +
                        "      io:format(\"> ~s~n\", [Cmd]), " +
                        "      Result = try " +
                        "        case Cmd of " +
                        "          <<\"device\">> -> android:device_info(); " +
                        "          <<\"battery\">> -> android:battery(); " +
                        "          <<\"memory\">> -> android:memory_info(); " +
                        "          <<\"wifi\">> -> android:wifi_info(); " +
                        "          <<\"network\">> -> android:network_info(); " +
                        "          <<\"location\">> -> android:location(); " +
                        "          <<\"sensors\">> -> android:sensors(); " +
                        "          <<\"brightness\">> -> android:screen_brightness(); " +
                        "          <<\"packages\">> -> android:packages(); " +
                        "          <<\"ping\">> -> android:ping(); " +
                        "          <<\"vibrate\">> -> android:vibrate(200); " +
                        "          <<\"accel\">> -> " +
                        "            android:sensor_start(accelerometer), " +
                        "            timer:sleep(200), " +
                        "            android:sensor_read(accelerometer); " +
                        "          <<\"gyro\">> -> " +
                        "            android:sensor_start(gyroscope), " +
                        "            timer:sleep(200), " +
                        "            android:sensor_read(gyroscope); " +
                        "          <<\"light\">> -> " +
                        "            android:sensor_start(light), " +
                        "            timer:sleep(200), " +
                        "            android:sensor_read(light); " +
                        "          <<\"procs\">> -> " +
                        "            {ok, list_to_binary(io_lib:format(\"~p\", " +
                        "              [erlang:system_info(process_count)]))}; " +
                        "          <<\"toast \", Msg/binary>> -> android:toast(Msg); " +
                        "          <<\"notify \", Rest/binary>> -> " +
                        "            case binary:split(Rest, <<\" \">>) of " +
                        "              [T, B] -> android:notify(T, B); " +
                        "              [T] -> android:notify(T, <<>>) " +
                        "            end; " +
                        "          <<\"clipboard\">> -> android:clipboard_get(); " +
                        "          <<\"copy \", Text/binary>> -> android:clipboard_set(Text); " +
                        "          <<\"prop \", Prop/binary>> -> android:system_prop(Prop); " +
                        "          <<\"services\">> -> android:call(<<\"services\">>); " +
                        "          <<\"features\">> -> android:call(<<\"features\">>); " +
                        "          <<\"shell \", ShCmd/binary>> -> android:call(<<\"shell\">>, ShCmd); " +
                        "          <<\"say \", Txt/binary>> -> android:call(<<\"tts\">>, Txt, 30000); " +
                        "          <<\"listen\">> -> android:call(<<\"listen\">>, <<\"5\">>, 15000); " +
                        "          <<\"listen \", Dur/binary>> -> " +
                        "            Secs = binary_to_integer(string:trim(Dur)), " +
                        "            android:call(<<\"listen\">>, Dur, (Secs + 5) * 1000); " +
                        "          <<\"stream_listen\">> -> android:call(<<\"stream_listen\">>, <<\"10\">>, 20000); " +
                        "          <<\"stream_listen \", Dur/binary>> -> " +
                        "            Secs2 = binary_to_integer(string:trim(Dur)), " +
                        "            android:call(<<\"stream_listen\">>, Dur, (Secs2 + 5) * 1000); " +
                        "          <<\"speech_status\">> -> android:call(<<\"speech_status\">>); " +
                        "          <<\"record\">> -> android:call(<<\"mic_record\">>, <<\"5\">>); " +
                        "          <<\"record \", Dur/binary>> -> android:call(<<\"mic_record\">>, Dur); " +
                        "          <<\"stt\">> -> android:call(<<\"stt\">>, <<>>, 30000); " +
                        "          <<\"stt \", Path/binary>> -> android:call(<<\"stt\">>, Path, 30000); " +
                        "          <<\"transcribe\">> -> android:call(<<\"transcribe_offline\">>, <<>>, 60000); " +
                        "          <<\"transcribe \", Path/binary>> -> android:call(<<\"transcribe_offline\">>, Path, 60000); " +
                        "          <<\"diarize\">> -> android:call(<<\"diarize\">>, <<>>, 120000); " +
                        "          <<\"diarize \", Path/binary>> -> android:call(<<\"diarize\">>, Path, 120000); " +
                        "          <<\"bt_status\">> -> android:call(<<\"bt_status\">>); " +
                        "          <<\"bt_bonded\">> -> android:call(<<\"bt_bonded\">>); " +
                        "          <<\"bt_connected\">> -> android:call(<<\"bt_connected\">>, <<>>, 10000); " +
                        "          <<\"bt_scan\">> -> android:call(<<\"bt_scan\">>, <<\"5\">>, 15000); " +
                        "          <<\"bt_scan \", Dur3/binary>> -> " +
                        "            Secs3 = binary_to_integer(string:trim(Dur3)), " +
                        "            android:call(<<\"bt_scan\">>, Dur3, (Secs3 + 5) * 1000); " +
                        "          <<\"bt_scan_ble\">> -> android:call(<<\"bt_scan_ble\">>, <<\"5\">>, 15000); " +
                        "          <<\"bt_scan_ble \", Dur4/binary>> -> " +
                        "            Secs4 = binary_to_integer(string:trim(Dur4)), " +
                        "            android:call(<<\"bt_scan_ble\">>, Dur4, (Secs4 + 5) * 1000); " +
                        "          <<\"bt_gatt \", Addr/binary>> -> " +
                        "            android:call(<<\"bt_gatt\">>, Addr, 15000); " +
                        "          <<\"bt_gatt_read \", RArgs/binary>> -> " +
                        "            android:call(<<\"bt_gatt_read\">>, RArgs, 15000); " +
                        "          <<\"bt_gatt_write \", WArgs/binary>> -> " +
                        "            android:call(<<\"bt_gatt_write\">>, WArgs, 15000); " +
                        "          <<\"bt_gatt_notify \", NArgs/binary>> -> " +
                        "            case binary:split(string:trim(NArgs), <<\" \">>, [global]) of " +
                        "              [NA, NU, NS] -> " +
                        "                NSecs = binary_to_integer(string:trim(NS)), " +
                        "                android:call(<<\"bt_gatt_notify\">>, NArgs, (NSecs + 10) * 1000); " +
                        "              _ -> android:call(<<\"bt_gatt_notify\">>, NArgs, 20000) " +
                        "            end; " +
                        "          <<\"bt_rssi \", RAddr/binary>> -> " +
                        "            android:call(<<\"bt_rssi\">>, RAddr, 10000); " +
                        "          _ -> {ok, <<\"Unknown: \", Cmd/binary, " +
                        "            \". Try: device battery memory wifi sensors accel \" " +
                        "            \"gyro light vibrate toast <msg> notify <t> <b> \" " +
                        "            \"clipboard copy <text> packages brightness ping \" " +
                        "            \"services features shell <cmd> prop <name> \" " +
                        "            \"say <text> listen [secs] stream_listen [secs] \" " +
                        "            \"record [secs] stt [path] transcribe [path] \" " +
                        "            \"diarize [path] speech_status \" " +
                        "            \"bt_status bt_bonded bt_connected \" " +
                        "            \"bt_scan [secs] bt_scan_ble [secs] \" " +
                        "            \"bt_gatt <addr> bt_gatt_read <addr> <uuid> \" " +
                        "            \"bt_gatt_write <addr> <uuid> <hex> \" " +
                        "            \"bt_gatt_notify <addr> <uuid> [secs] bt_rssi <addr>\">>} " +
                        "        end " +
                        "      catch E:R -> {error, list_to_binary(io_lib:format(\"~p:~p\", [E,R]))} " +
                        "      end, " +
                        "      Resp = case Result of " +
                        "        {ok, V} -> <<V/binary, \"\\n\">>; " +
                        "        {error, Err} -> <<\"ERROR: \", Err/binary, \"\\n\">> " +
                        "      end, " +
                        "      io:format(\"  ~s\", [Resp]), " +
                        "      gen_tcp:send(S, Resp), " +
                        "      HandleC(S); " +
                        "    {error, timeout} -> HandleC(S); " +
                        "    {error, closed} -> " +
                        "      io:format(\"Client disconnected~n\"), ok " +
                        "  end " +
                        "end, " +
                        "AcceptLoop = fun Loop() -> " +
                        "  case gen_tcp:accept(LSock) of " +
                        "    {ok, Sock} -> " +
                        "      io:format(\"Client connected~n\"), " +
                        "      spawn(fun() -> HandleCmd(Sock) end), " +
                        "      Loop(); " +
                        "    {error, _} -> ok " +
                        "  end " +
                        "end, " +
                        "AcceptLoop().";

                    appendOutput("Launching beam.smp...\n");
                    appendOutput("  Root: " + rootDir + "\n");
                    appendOutput("  Bin:  " + binDir + "\n\n");

                    /* Code paths for OTP apps */
                    String libBase = erlRoot.getAbsolutePath() + "/lib";
                    String androidEbin = libBase + "/android/ebin";
                    String cryptoEbin = libBase + "/crypto-5.8/ebin";
                    String asn1Ebin = libBase + "/asn1-5.4.2/ebin";
                    String publicKeyEbin = libBase + "/public_key-1.20.1/ebin";
                    String sslEbin = libBase + "/ssl-11.5.1/ebin";

                    ProcessBuilder pb = new ProcessBuilder(
                        beamPath,
                        "--", "-root", rootDir,
                        "-bindir", binDir,
                        "-boot", "start_clean",
                        "-noshell",
                        "-pa", androidEbin,
                        "-pa", cryptoEbin,
                        "-pa", asn1Ebin,
                        "-pa", publicKeyEbin,
                        "-pa", sslEbin,
                        "-eval", evalCode
                    );

                    Map<String, String> env = pb.environment();
                    env.put("HOME", getFilesDir().getAbsolutePath());
                    env.put("TERM", "dumb");
                    env.put("EMU", "beam");
                    env.put("ROOTDIR", rootDir);
                    env.put("BINDIR", binDir);
                    env.put("PROGNAME", "erl");
                    /* LD_LIBRARY_PATH: our symlink dir + native lib dir + system */
                    env.put("LD_LIBRARY_PATH",
                        libDir + ":" + nativeLibDir + ":/system/lib64");

                    pb.redirectErrorStream(true);
                    beamProcess = pb.start();
                    beamStdin = beamProcess.getOutputStream();
                    isBeamRunning = true;
                    notifyStatus(true);

                    /* Read stdout in this thread */
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(beamProcess.getInputStream()));
                    char[] buf = new char[4096];
                    int n;
                    while ((n = reader.read(buf)) != -1) {
                        final String text = new String(buf, 0, n);
                        appendOutput(text);
                    }

                    /* Process exited */
                    int exitCode = beamProcess.waitFor();
                    isBeamRunning = false;
                    appendOutput("\nBEAM exited (code: " + exitCode + ")\n");
                    notifyStatus(false);

                } catch (Exception e) {
                    isBeamRunning = false;
                    appendOutput("\nError: " + e.getMessage() + "\n");
                    Log.e(TAG, "BEAM launch failed", e);
                    notifyStatus(false);
                }
            }
        }).start();
    }

    public void stopBeam() {
        if (bridgeServer != null) {
            bridgeServer.stop();
            bridgeServer = null;
        }
        if (beamProcess != null) {
            beamProcess.destroy();
            isBeamRunning = false;
            notifyStatus(false);
            appendOutput("BEAM stopped by user.\n");
        }
    }

    public boolean isBeamRunning() {
        return isBeamRunning;
    }

    private void createSymlink(String target, String link) {
        try {
            File linkFile = new File(link);
            if (linkFile.exists()) linkFile.delete();
            Runtime.getRuntime().exec(new String[]{
                "ln", "-sf", target, link
            }).waitFor();
        } catch (Exception e) {
            Log.w(TAG, "Symlink failed: " + target + " -> " + link, e);
        }
    }

    private void extractAssets(String assetPath, File destDir) throws IOException {
        AssetManager am = getAssets();
        String[] children = am.list(assetPath);
        if (children == null || children.length == 0) {
            /* It's a file, copy it */
            destDir.getParentFile().mkdirs();
            InputStream in = am.open(assetPath);
            FileOutputStream out = new FileOutputStream(destDir);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close();
            in.close();
        } else {
            /* It's a directory, recurse */
            destDir.mkdirs();
            for (String child : children) {
                extractAssets(assetPath + "/" + child,
                              new File(destDir, child));
            }
        }
    }

    private void appendOutput(final String text) {
        if (outputCallback != null) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    outputCallback.onOutput(text);
                }
            });
        }
    }

    private void notifyStatus(final boolean running) {
        if (outputCallback != null) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    outputCallback.onStatusChanged(running);
                }
            });
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.channel_desc));
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pi)
            .setOngoing(true)
            .build();
    }

    @Override
    public void onDestroy() {
        stopBeam();
        super.onDestroy();
    }
}
