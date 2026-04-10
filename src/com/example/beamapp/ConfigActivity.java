package com.example.beamapp;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Edits /sdcard/.beam-config — the file read by BeamService.readConfig() on BEAM startup.
 * Preserves keys that this screen doesn't know about (forward-compatible).
 */
public class ConfigActivity extends Activity {
    private static final String CONFIG_PATH = "/sdcard/.beam-config";

    private Switch distributedSwitch;
    private EditText nodeNameField;
    private EditText clusterNodesField;
    private EditText cookieField;
    private Button cookieShowButton;
    private Button cookieGenerateButton;
    private Button saveButton;
    private Button cancelButton;

    /** Keep any keys we don't explicitly edit so they survive a save. */
    private LinkedHashMap<String, String> originalConfig = new LinkedHashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_config);

        distributedSwitch    = findViewById(R.id.config_distributed_switch);
        nodeNameField        = findViewById(R.id.config_node_name);
        clusterNodesField    = findViewById(R.id.config_cluster_nodes);
        cookieField          = findViewById(R.id.config_cookie);
        cookieShowButton     = findViewById(R.id.config_cookie_show);
        cookieGenerateButton = findViewById(R.id.config_cookie_generate);
        saveButton           = findViewById(R.id.config_save);
        cancelButton         = findViewById(R.id.config_cancel);

        loadConfig();

        cookieShowButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean hidden = (cookieField.getInputType() & InputType.TYPE_MASK_VARIATION)
                                  == InputType.TYPE_TEXT_VARIATION_PASSWORD;
                if (hidden) {
                    cookieField.setInputType(InputType.TYPE_CLASS_TEXT
                                             | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                    cookieShowButton.setText(R.string.config_hide);
                } else {
                    cookieField.setInputType(InputType.TYPE_CLASS_TEXT
                                             | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                    cookieShowButton.setText(R.string.config_show);
                }
                cookieField.setTypeface(android.graphics.Typeface.MONOSPACE);
                cookieField.setSelection(cookieField.getText().length());
            }
        });

        cookieGenerateButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cookieField.setText(generateCookie());
                Toast.makeText(ConfigActivity.this,
                    R.string.config_cookie_generated, Toast.LENGTH_SHORT).show();
            }
        });

        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (saveConfig()) {
                    Toast.makeText(ConfigActivity.this,
                        R.string.config_saved, Toast.LENGTH_LONG).show();
                    finish();
                }
            }
        });

        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    private void loadConfig() {
        /* Defaults match BeamService.readConfig() */
        originalConfig.put("distributed", "true");
        originalConfig.put("node_name", "beamapp@10.42.43.2");
        originalConfig.put("cookie", "");
        originalConfig.put("cluster_nodes", "");

        File f = new File(CONFIG_PATH);
        if (f.exists()) {
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                    int eq = trimmed.indexOf('=');
                    if (eq > 0) {
                        originalConfig.put(
                            trimmed.substring(0, eq).trim(),
                            trimmed.substring(eq + 1).trim());
                    }
                }
            } catch (IOException e) {
                Toast.makeText(this, "Could not read config: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            }
        }

        distributedSwitch.setChecked("true".equalsIgnoreCase(
            originalConfig.get("distributed")));
        nodeNameField.setText(valueOrEmpty("node_name"));
        clusterNodesField.setText(valueOrEmpty("cluster_nodes"));
        cookieField.setText(valueOrEmpty("cookie"));
    }

    private String valueOrEmpty(String key) {
        String v = originalConfig.get(key);
        return v == null ? "" : v;
    }

    private boolean saveConfig() {
        String nodeName     = nodeNameField.getText().toString().trim();
        String clusterNodes = clusterNodesField.getText().toString().trim()
                              .replaceAll("\\s*\\n\\s*", ",")
                              .replaceAll(",,+", ",")
                              .replaceAll("^,|,$", "");
        String cookie       = cookieField.getText().toString().trim();
        boolean distributed = distributedSwitch.isChecked();

        if (distributed) {
            if (nodeName.isEmpty() || !nodeName.contains("@")) {
                Toast.makeText(this, R.string.config_err_node_name,
                    Toast.LENGTH_LONG).show();
                return false;
            }
            if (cookie.isEmpty()) {
                Toast.makeText(this, R.string.config_err_cookie_empty,
                    Toast.LENGTH_LONG).show();
                return false;
            }
        }

        /* Merge edited values into originalConfig so unknown keys survive. */
        originalConfig.put("distributed", distributed ? "true" : "false");
        originalConfig.put("node_name", nodeName);
        originalConfig.put("cluster_nodes", clusterNodes);
        originalConfig.put("cookie", cookie);

        StringBuilder out = new StringBuilder();
        out.append("# BeamApp Distributed Erlang Configuration\n");
        out.append("# Edit via the in-app Settings screen, or this file directly.\n\n");
        out.append("# Set to false to run as standalone (non-distributed) node\n");
        out.append("distributed=").append(originalConfig.get("distributed")).append("\n\n");
        out.append("# Node name (long name format: name@ip)\n");
        out.append("node_name=").append(originalConfig.get("node_name")).append("\n\n");
        out.append("# Erlang distribution cookie (must match on all nodes)\n");
        out.append("cookie=").append(originalConfig.get("cookie")).append("\n\n");
        out.append("# Comma-separated list of nodes to connect to on startup\n");
        out.append("cluster_nodes=").append(originalConfig.get("cluster_nodes")).append("\n");

        /* Append any foreign keys we loaded but don't edit */
        boolean wroteHeader = false;
        for (Map.Entry<String, String> e : originalConfig.entrySet()) {
            String k = e.getKey();
            if (k.equals("distributed") || k.equals("node_name")
                || k.equals("cookie") || k.equals("cluster_nodes")) continue;
            if (!wroteHeader) {
                out.append("\n# Additional settings\n");
                wroteHeader = true;
            }
            out.append(k).append("=").append(e.getValue()).append("\n");
        }

        try (FileOutputStream fos = new FileOutputStream(CONFIG_PATH)) {
            fos.write(out.toString().getBytes());
            return true;
        } catch (IOException e) {
            Toast.makeText(this, "Save failed: " + e.getMessage(),
                Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private static String generateCookie() {
        /* 16 bytes = 32 hex chars — matches what the cluster setup script uses. */
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
