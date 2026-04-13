package com.example.beamapp;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages the custom dictionary for the remote ASR service.
 * Words are stored locally in /sdcard/.beam-dictionary and sent
 * to the GPU node's hotword boosting system via BEAM RPC.
 */
public class DictionaryActivity extends Activity {
    private static final String DICT_FILE = "/sdcard/.beam-dictionary";

    private EditText wordsField;
    private TextView statusText;
    private Button saveButton;
    private Button cancelButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dictionary);

        wordsField = findViewById(R.id.dict_words);
        statusText = findViewById(R.id.dict_status);
        saveButton = findViewById(R.id.dict_save);
        cancelButton = findViewById(R.id.dict_cancel);

        loadDictionary();

        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveDictionary();
            }
        });

        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    private void loadDictionary() {
        File f = new File(DICT_FILE);
        if (f.exists()) {
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(trimmed);
                    }
                }
                wordsField.setText(sb.toString());
            } catch (Exception e) {
                Toast.makeText(this, "Could not load dictionary: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            }
        }
    }

    private void saveDictionary() {
        String text = wordsField.getText().toString().trim();
        String[] lines = text.split("\\n");
        List<String> words = new ArrayList<>();
        for (String line : lines) {
            String w = line.trim();
            if (!w.isEmpty()) words.add(w);
        }

        // Save to local file
        try (FileOutputStream fos = new FileOutputStream(DICT_FILE)) {
            StringBuilder sb = new StringBuilder();
            sb.append("# BeamApp Custom Dictionary\n");
            sb.append("# One word or phrase per line\n\n");
            for (String w : words) {
                sb.append(w).append("\n");
            }
            fos.write(sb.toString().getBytes());
        } catch (Exception e) {
            Toast.makeText(this, "Save failed: " + e.getMessage(),
                Toast.LENGTH_LONG).show();
            return;
        }

        // Send to remote ASR service via BEAM
        if (!words.isEmpty()) {
            applyHotwords(words);
        } else {
            clearHotwords();
        }
    }

    private void applyHotwords(final List<String> words) {
        statusText.setText("Sending to ASR service...");
        statusText.setVisibility(View.VISIBLE);

        new Thread(new Runnable() {
            @Override
            public void run() {
                // Build Erlang expression: speech:set_hotwords([<<"word1">>, <<"word2">>]).
                StringBuilder erlList = new StringBuilder("[");
                for (int i = 0; i < words.size(); i++) {
                    if (i > 0) erlList.append(", ");
                    erlList.append("<<\"").append(escErl(words.get(i))).append("\">>");
                }
                erlList.append("]");
                String expr = "speech:set_hotwords(" + erlList + ").";

                final String result = evalBeam(expr);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (result != null && (result.contains("ok") || result.contains("hotwords"))) {
                            statusText.setText("Dictionary applied (" + words.size() + " words)");
                            statusText.setTextColor(0xFF4CAF50); // green
                            Toast.makeText(DictionaryActivity.this,
                                "Dictionary saved and applied", Toast.LENGTH_SHORT).show();
                        } else {
                            statusText.setText("Saved locally. Remote apply failed: " +
                                (result != null ? result : "no response"));
                            statusText.setTextColor(0xFFFF9800); // orange
                        }
                        statusText.setVisibility(View.VISIBLE);
                    }
                });
            }
        }).start();
    }

    private void clearHotwords() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String result = evalBeam("speech:clear_hotwords().");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        statusText.setText("Dictionary cleared");
                        statusText.setTextColor(0xFF4CAF50);
                        statusText.setVisibility(View.VISIBLE);
                        Toast.makeText(DictionaryActivity.this,
                            "Dictionary cleared", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    /**
     * Evaluate an Erlang expression on the local BEAM node via the command port.
     */
    private String evalBeam(String expression) {
        try (Socket sock = new Socket("127.0.0.1", 9876)) {
            sock.setSoTimeout(15000);
            OutputStream os = sock.getOutputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(sock.getInputStream()));
            os.write(("eval " + expression).getBytes());
            os.flush();
            char[] buf = new char[65536];
            int n = br.read(buf);
            if (n > 0) {
                return new String(buf, 0, n).trim();
            }
            return null;
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    private static String escErl(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
