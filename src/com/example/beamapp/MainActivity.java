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

        /* Notifications (runtime on Android 13+) */
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add("android.permission.POST_NOTIFICATIONS");
            }
        }

        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), 1);
        }
    }

    private void updateStatus(boolean running) {
        if (running) {
            statusText.setText("Running (OTP 28 / ERTS 16.2)");
            statusText.setTextColor(0xFF4CAF50);
            startButton.setEnabled(false);
            stopButton.setEnabled(true);
            sendButton.setEnabled(true);
        } else {
            statusText.setText("Stopped");
            statusText.setTextColor(0xFF999999);
            startButton.setEnabled(true);
            stopButton.setEnabled(false);
            sendButton.setEnabled(false);
        }
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
