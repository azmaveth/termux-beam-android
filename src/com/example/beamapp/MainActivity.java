package com.example.beamapp;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.app.AlertDialog;
import android.text.Html;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;

public class MainActivity extends Activity {
    private BeamService beamService;
    private boolean bound = false;

    private TextView statusText;
    private TextView logText;
    private ScrollView logScroll;
    private EditText messageInput;
    private Button startButton;
    private Button stopButton;
    private Button sendButton;
    private Button copyButton;
    private Button listenButton;
    private Button settingsButton;
    private Button commandsButton;
    private String runtimeLabel = "OTP 28";

    private volatile boolean isListening = false;
    private volatile Socket listenSocket;
    private int partialLogStart = -1;

    private StringBuilder logBuffer = new StringBuilder();

    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BeamService.LocalBinder binder = (BeamService.LocalBinder) service;
            beamService = binder.getService();
            bound = true;

            beamService.setOutputCallback(new BeamService.OutputCallback() {
                @Override
                public void onOutput(String text) {
                    appendLog(text);
                }

                @Override
                public void onStatusChanged(boolean running) {
                    updateStatus(running);
                }
            });

            updateStatus(beamService.isBeamRunning());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        requestPermissions();

        statusText = findViewById(R.id.status_text);
        logText = findViewById(R.id.log_text);
        logScroll = findViewById(R.id.log_scroll);
        messageInput = findViewById(R.id.message_input);
        startButton = findViewById(R.id.start_button);
        stopButton = findViewById(R.id.stop_button);
        sendButton = findViewById(R.id.send_button);
        copyButton = findViewById(R.id.copy_button);
        listenButton = findViewById(R.id.listen_button);
        settingsButton = findViewById(R.id.settings_button);
        commandsButton = findViewById(R.id.commands_button);
        listenButton.setEnabled(false);

        runtimeLabel = loadRuntimeLabel();

        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, ConfigActivity.class));
            }
        });

        commandsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showCommandsDialog();
            }
        });

        listenButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!isListening) {
                    startStreamListen();
                } else {
                    stopStreamListen();
                }
            }
        });

        copyButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("BEAM Log", logBuffer.toString()));
                Toast.makeText(MainActivity.this, "Log copied!", Toast.LENGTH_SHORT).show();
            }
        });

        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (bound) {
                    Intent serviceIntent = new Intent(MainActivity.this, BeamService.class);
                    startForegroundService(serviceIntent);
                    beamService.startBeam();
                }
            }
        });

        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (bound) {
                    beamService.stopBeam();
                }
            }
        });

        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String msg = messageInput.getText().toString().trim();
                if (msg.isEmpty()) return;
                messageInput.setText("");
                appendLog("> " + msg + "\n");

                /* Send via TCP to the BEAM echo server */
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Socket sock = new Socket("127.0.0.1", 9876);
                            OutputStream out = sock.getOutputStream();
                            BufferedReader in = new BufferedReader(
                                new InputStreamReader(sock.getInputStream()));

                            String tok = bridgeToken();
                            if (tok != null) {
                                out.write(("auth " + tok + "\n").getBytes());
                                out.flush();
                                in.read(new char[64]);
                            }

                            out.write(msg.getBytes());
                            out.flush();

                            /* Read response */
                            char[] buf = new char[4096];
                            int n = in.read(buf);
                            if (n > 0) {
                                final String resp = new String(buf, 0, n);
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        appendLog(resp + "\n");
                                    }
                                });
                            }
                            sock.close();
                        } catch (final Exception e) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    appendLog("TCP error: " + e.getMessage() + "\n");
                                }
                            });
                        }
                    }
                }).start();
            }
        });

        /* Start and bind to the service */
        Intent serviceIntent = new Intent(this, BeamService.class);
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE);
    }

    private void requestPermissions() {
        ArrayList<String> needed = new ArrayList<>();

        /* Location (runtime permission) */
        if (checkSelfPermission("android.permission.ACCESS_FINE_LOCATION")
                != PackageManager.PERMISSION_GRANTED) {
            needed.add("android.permission.ACCESS_FINE_LOCATION");
        }
        if (checkSelfPermission("android.permission.ACCESS_COARSE_LOCATION")
                != PackageManager.PERMISSION_GRANTED) {
            needed.add("android.permission.ACCESS_COARSE_LOCATION");
        }

        /* Microphone (runtime permission) */
        if (checkSelfPermission("android.permission.RECORD_AUDIO")
                != PackageManager.PERMISSION_GRANTED) {
            needed.add("android.permission.RECORD_AUDIO");
        }

        /* Notifications (runtime on Android 13+) */
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add("android.permission.POST_NOTIFICATIONS");
            }
        }

        /* Bluetooth (runtime on Android 12+) */
        if (checkSelfPermission("android.permission.BLUETOOTH_CONNECT")
                != PackageManager.PERMISSION_GRANTED) {
            needed.add("android.permission.BLUETOOTH_CONNECT");
        }
        if (checkSelfPermission("android.permission.BLUETOOTH_SCAN")
                != PackageManager.PERMISSION_GRANTED) {
            needed.add("android.permission.BLUETOOTH_SCAN");
        }

        /* Camera */
        if (checkSelfPermission("android.permission.CAMERA")
                != PackageManager.PERMISSION_GRANTED) {
            needed.add("android.permission.CAMERA");
        }

        /* Contacts, Call Log, SMS, Phone State */
        String[] extraPerms = {
            "android.permission.READ_CONTACTS",
            "android.permission.READ_CALL_LOG",
            "android.permission.READ_SMS",
            "android.permission.SEND_SMS",
            "android.permission.READ_PHONE_STATE"
        };
        for (String perm : extraPerms) {
            if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                needed.add(perm);
            }
        }

        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), 1);
        }
    }

    private void showCommandsDialog() {
        ScrollView scroll = new ScrollView(this);
        scroll.setPadding(32, 16, 32, 16);
        TextView tv = new TextView(this);
        tv.setText(Html.fromHtml(getString(R.string.commands_detail), Html.FROM_HTML_MODE_COMPACT));
        tv.setTextSize(13);
        tv.setTextColor(0xFF333333);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setLineSpacing(0, 1.2f);
        scroll.addView(tv);
        new AlertDialog.Builder(this)
            .setTitle("Available Commands")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show();
    }

    private String loadRuntimeLabel() {
        /* Read bundled erts_version asset written by prepare_assets.sh.
         *
         * Derive the OTP release from the ERTS major rather than hardcoding
         * it: ERTS 14 = OTP 26, 15 = 27, 16 = 28, 17 = 29, i.e. OTP is the
         * ERTS major + 12. This label previously read "OTP 28" unconditionally
         * and so reported an OTP 29 / ERTS 17.0.3 runtime as OTP 28, which is
         * exactly the sort of thing that sends someone hunting a toolchain
         * version mismatch that doesn't exist. */
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(getAssets().open("erlang/erts_version")))) {
            String v = br.readLine();
            if (v != null && !v.trim().isEmpty()) {
                String erts = v.trim();
                try {
                    int major = Integer.parseInt(erts.split("\\.")[0]);
                    return "OTP " + (major + 12) + " / ERTS " + erts;
                } catch (NumberFormatException ignored) {
                    return "ERTS " + erts;
                }
            }
        } catch (Exception e) { /* fall through to default */ }
        return "runtime version unknown";
    }

    private void updateStatus(boolean running) {
        if (running) {
            statusText.setText("Running (" + runtimeLabel + ")");
            statusText.setTextColor(0xFF4CAF50);
            startButton.setEnabled(false);
            stopButton.setEnabled(true);
            sendButton.setEnabled(true);
            listenButton.setEnabled(true);
        } else {
            statusText.setText("Stopped");
            statusText.setTextColor(0xFF999999);
            startButton.setEnabled(true);
            stopButton.setEnabled(false);
            sendButton.setEnabled(false);
            listenButton.setEnabled(false);
            if (isListening) stopStreamListen();
        }
    }

    private void startStreamListen() {
        isListening = true;
        partialLogStart = -1;
        listenButton.setText(R.string.stop_listen);

        if (isRemoteAsrMode()) {
            startRemoteListen();
        } else {
            startLocalStreamListen();
        }
    }

    /** Check .beam-config for asr_mode=remote */
    private boolean isRemoteAsrMode() {
        try {
            java.io.File f = BeamService.resolveConfigFile(this);
            if (!f.exists()) return false;
            BufferedReader br = new BufferedReader(new java.io.FileReader(f));
            String line;
            while ((line = br.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("asr_mode=")) {
                    br.close();
                    return trimmed.substring(9).trim().equalsIgnoreCase("remote");
                }
            }
            br.close();
        } catch (Exception e) { /* ignore */ }
        return false;
    }

    /** Remote ASR: record until Stop is pressed, then send to GPU for transcription */
    private void startRemoteListen() {
        appendLog("[Listening (remote GPU)...]\n");

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket sock = new Socket("127.0.0.1", 9877);
                    listenSocket = sock;
                    OutputStream os = sock.getOutputStream();
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(sock.getInputStream()));
                    authBridge(os, reader);

                    // Start recording indefinitely (until mic_stop)
                    String recReq = "{\"id\":100,\"cmd\":\"mic_record\",\"args\":\"stream\"}";
                    os.write((recReq + "\n").getBytes());
                    os.flush();
                    String recResponse = reader.readLine();

                    // Extract the WAV path from mic_record response
                    String wavPath = extractJsonString(recResponse, "path");

                    // Wait until user clicks Stop (isListening becomes false)
                    while (isListening) {
                        Thread.sleep(100);
                    }

                    // Stop recording
                    String stopReq = "{\"id\":101,\"cmd\":\"mic_stop\",\"args\":\"\"}";
                    os.write((stopReq + "\n").getBytes());
                    os.flush();
                    String stopResponse = reader.readLine();

                    // Use path from stop response if available, fall back to record response
                    String stopPath = extractJsonString(stopResponse, "path");
                    if (stopPath != null && !stopPath.isEmpty()) wavPath = stopPath;

                    if (wavPath == null || wavPath.isEmpty()) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                appendLog("[Error] No recording path found\n");
                            }
                        });
                        sock.close();
                        return;
                    }

                    final String path = wavPath;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            appendLog("[Transcribing...]\n");
                        }
                    });

                    // Send to remote ASR via BEAM: speech:remote_transcribe(Path)
                    String evalExpr = "speech:remote_transcribe(<<\"" + escEval(wavPath) + "\">>).";
                    String evalReq = "{\"id\":102,\"cmd\":\"beam_eval\",\"args\":\"" + escJson(evalExpr) + "\"}";
                    os.write((evalReq + "\n").getBytes());
                    os.flush();

                    String evalResponse = reader.readLine();

                    final String resultText;
                    if (evalResponse != null) {
                        int dataIdx = evalResponse.indexOf("\"data\":");
                        if (dataIdx >= 0) {
                            String data = evalResponse.substring(dataIdx + 7);
                            if (data.endsWith("}")) data = data.substring(0, data.length() - 1);
                            if (data.startsWith("\"") && data.endsWith("\""))
                                data = data.substring(1, data.length() - 1);
                            resultText = data.replace("\\n", "\n").replace("\\\"", "\"");
                        } else {
                            resultText = evalResponse;
                        }
                    } else {
                        resultText = "(no response)";
                    }

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            partialLogStart = -1;
                            appendLog("[Remote STT] " + resultText + "\n");
                        }
                    });

                    sock.close();
                } catch (final Exception e) {
                    final String msg = e.getMessage();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            appendLog("[Remote listen error] " + msg + "\n");
                        }
                    });
                } finally {
                    listenSocket = null;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            isListening = false;
                            partialLogStart = -1;
                            listenButton.setText(R.string.start_listen);
                        }
                    });
                }
            }
        }).start();
    }

    /** Read the bridge auth token (app-private file), or null. */
    private String bridgeToken() {
        try {
            if (new java.io.File(getFilesDir(), "bridge_auth_disabled").exists()) return null;
            java.io.File f = new java.io.File(getFilesDir(), "bridge_token");
            if (!f.exists()) return null;
            BufferedReader br = new BufferedReader(new java.io.FileReader(f));
            String tok = br.readLine();
            br.close();
            return tok != null ? tok.trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Authenticate a freshly opened bridge socket (consumes the reply). */
    private void authBridge(OutputStream os, BufferedReader reader) throws Exception {
        String tok = bridgeToken();
        if (tok == null) return;
        os.write(("{\"id\":0,\"cmd\":\"auth\",\"args\":\"" + escJson(tok) + "\"}\n").getBytes());
        os.flush();
        reader.readLine();
    }

    /** Extract a string value from a JSON response */
    private static String extractJsonString(String json, String key) {
        if (json == null) return null;
        String search = "\"" + key + "\":\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int start = idx + search.length();
        int end = json.indexOf("\"", start);
        if (end < 0) return null;
        return json.substring(start, end).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static String escEval(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String escJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Local ASR: stream_listen via sherpa-onnx JNI */
    private void startLocalStreamListen() {
        appendLog("[Listening (local)...]\n");

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket sock = new Socket("127.0.0.1", 9877);
                    listenSocket = sock;
                    OutputStream os = sock.getOutputStream();
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(sock.getInputStream()));
                    authBridge(os, reader);

                    // Send pre_listen first to start mic immediately, then stream_listen
                    String preReq = "{\"id\":98,\"cmd\":\"pre_listen\",\"args\":\"\"}";
                    os.write((preReq + "\n").getBytes());
                    os.flush();
                    // Read pre_listen response (quick)
                    reader.readLine();

                    String request = "{\"id\":99,\"cmd\":\"stream_listen\",\"args\":\"120\"}";
                    os.write((request + "\n").getBytes());
                    os.flush();

                    String line;
                    while ((line = reader.readLine()) != null) {
                        final String json = line;
                        if (line.contains("\"partial\":true")) {
                            int textStart = line.indexOf("\"text\":\"") + 8;
                            int textEnd = line.lastIndexOf("\"");
                            if (textStart > 7 && textEnd > textStart) {
                                final String text = line.substring(textStart, textEnd)
                                    .replace("\\n", "\n").replace("\\\"", "\"");
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (partialLogStart >= 0) {
                                            logBuffer.setLength(partialLogStart);
                                        } else {
                                            partialLogStart = logBuffer.length();
                                        }
                                        appendLog("[STT] " + text + "\n");
                                    }
                                });
                            }
                        } else {
                            /* Final response — extract data */
                            partialLogStart = -1;
                            final String finalText;
                            int dataIdx = line.indexOf("\"data\":");
                            if (dataIdx >= 0) {
                                finalText = line.substring(dataIdx + 7,
                                    line.length() - 1);
                            } else {
                                finalText = json;
                            }
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    appendLog("[Final] " + finalText + "\n");
                                }
                            });
                            break;
                        }
                    }

                    sock.close();
                } catch (final Exception e) {
                    final String msg = e.getMessage();
                    if (isListening) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                appendLog("[Listen error] " + msg + "\n");
                            }
                        });
                    }
                } finally {
                    listenSocket = null;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            isListening = false;
                            partialLogStart = -1;
                            listenButton.setText(R.string.start_listen);
                        }
                    });
                }
            }
        }).start();
    }

    private void stopStreamListen() {
        isListening = false;
        listenButton.setText(R.string.start_listen);
        partialLogStart = -1;
        if (!isRemoteAsrMode()) {
            // Local mode: ask the engine to stop. The in-flight stream_listen
            // then returns its final result promptly on the listen socket
            // (closing the socket used to leave the engine recording until
            // maxDuration with no way to reach it).
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try (Socket s = new Socket("127.0.0.1", 9877)) {
                        OutputStream os = s.getOutputStream();
                        BufferedReader br = new BufferedReader(
                            new InputStreamReader(s.getInputStream()));
                        authBridge(os, br);
                        os.write("{\"id\":103,\"cmd\":\"stop_listen\",\"args\":\"\"}\n".getBytes());
                        os.flush();
                        br.readLine();
                    } catch (Exception e) { /* ignore */ }
                }
            }, "stop-listen").start();
            appendLog("[Stopping...]\n");
        }
        // Remote mode: the recording thread detects isListening=false,
        // stops mic, and sends to GPU for transcription
    }

    private void appendLog(String text) {
        logBuffer.append(text);
        logText.setText(logBuffer.toString());
        logScroll.post(new Runnable() {
            @Override
            public void run() {
                logScroll.fullScroll(ScrollView.FOCUS_DOWN);
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (bound) {
            unbindService(connection);
            bound = false;
        }
        super.onDestroy();
    }
}
