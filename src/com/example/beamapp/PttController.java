package com.example.beamapp;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.util.Log;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.InputDevice;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Push-to-talk from a physical keyboard (e.g. Twiddler): a configured key
 * taps the microphone button of a supported app's voice UI.
 * Settings live in SharedPreferences "ptt" and are read on every event.
 */
public final class PttController {
    private static final String TAG = "PttController";
    public static final String PREFS = "ptt";
    public static final String K_ENABLED = "enabled";
    public static final String K_MODE = "mode";
    public static final String K_KEY = "keycode";
    public static final String MODE_TOGGLE = "toggle";
    public static final String MODE_HOLD = "hold";
    public static final int DEFAULT_KEY = KeyEvent.KEYCODE_F12;

    /** Selectable trigger keys (label -> keycode). */
    public static final int[] KEY_CODES = {
        KeyEvent.KEYCODE_F12, KeyEvent.KEYCODE_SCROLL_LOCK, KeyEvent.KEYCODE_F11,
        KeyEvent.KEYCODE_F10, KeyEvent.KEYCODE_BREAK
    };
    public static final String[] KEY_LABELS = { "F12", "Scroll Lock", "F11", "F10", "Pause" };

    /** Per-app matching rules for the mic button. Add apps here. */
    static final class Rule {
        final String[] words; final String[] idParts;
        Rule(String[] words, String[] idParts) { this.words = words; this.idParts = idParts; }
    }
    static final Map<String, Rule> TARGETS = new LinkedHashMap<>();
    static {
        TARGETS.put("ai.x.grok.bot", new Rule(
            new String[] { "microphone", "mic", "unmute", "mute" },
            new String[] { "mic" }));
    }

    /* ---- state ---- */
    private static volatile boolean talking = false;
    private static volatile long lastTriggerTime = 0;
    private static volatile String lastTargetPackage = null;
    private static volatile String lastError = null;
    private static volatile Context appContext = null;

    private PttController() {}

    static void init(Context c) { appContext = c.getApplicationContext(); }

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isPhysicalKeyboard(KeyEvent e) {
        if (e.getDeviceId() == KeyCharacterMap.VIRTUAL_KEYBOARD) return false;
        InputDevice d = e.getDevice();
        if (d == null || d.isVirtual()) return false;
        return (e.getSource() & InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD;
    }

    /** Called from ScreenService.onKeyEvent. Returns true to swallow. */
    static boolean onKeyEvent(AccessibilityService svc, KeyEvent e) {
        SharedPreferences p = prefs(svc);
        if (!p.getBoolean(K_ENABLED, true)) return false;
        if (e.getKeyCode() != p.getInt(K_KEY, DEFAULT_KEY)) return false;
        if (!isPhysicalKeyboard(e)) return false;
        if (e.getRepeatCount() > 0) return true;
        String mode = p.getString(K_MODE, MODE_TOGGLE);
        int action = e.getAction();
        if (action == KeyEvent.ACTION_DOWN) {
            if (tapMic(svc)) talking = !talking;
        } else if (action == KeyEvent.ACTION_UP && MODE_HOLD.equals(mode)) {
            if (talking && tapMic(svc)) talking = false;
        }
        return true;
    }

    private static boolean tapMic(AccessibilityService svc) {
        lastTriggerTime = System.currentTimeMillis();
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null) { lastError = "no active window"; return false; }
        CharSequence pkgCs = root.getPackageName();
        String pkg = pkgCs == null ? null : pkgCs.toString();
        lastTargetPackage = pkg;
        Rule rule = pkg == null ? null : TARGETS.get(pkg);
        if (rule == null) { lastError = "foreground app not supported: " + pkg; return false; }
        AccessibilityNodeInfo mic = findMic(root, rule);
        if (mic == null) { lastError = "mic button not found in " + pkg; return false; }
        AccessibilityNodeInfo clickable = mic;
        while (clickable != null && !clickable.isClickable()) clickable = clickable.getParent();
        if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            lastError = null; return true;
        }
        Rect b = new Rect();
        mic.getBoundsInScreen(b);
        if (b.isEmpty()) { lastError = "mic click failed, empty bounds"; return false; }
        Path path = new Path();
        path.moveTo(b.exactCenterX(), b.exactCenterY());
        GestureDescription g = new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path, 0, 50)).build();
        if (svc.dispatchGesture(g, null, null)) { lastError = null; return true; }
        lastError = "mic click and gesture fallback failed";
        return false;
    }

    private static AccessibilityNodeInfo findMic(AccessibilityNodeInfo root, Rule rule) {
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        AccessibilityNodeInfo best = null; int bestScore = 0;
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.poll();
            int s = score(n, rule);
            if (s > bestScore && n.isVisibleToUser()) { best = n; bestScore = s; }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return best;
    }

    private static int score(AccessibilityNodeInfo n, Rule rule) {
        String desc = lower(n.getContentDescription());
        String text = lower(n.getText());
        String id = lower(n.getViewIdResourceName());
        int s = 0;
        for (String w : rule.words) {
            if (matchesWord(desc, w)) s = Math.max(s, 3);
            if (matchesWord(text, w)) s = Math.max(s, 2);
        }
        for (String part : rule.idParts) if (id.contains(part)) s = Math.max(s, 2);
        if (s > 0 && (n.isClickable() || desc.length() > 0)) s++;
        return s;
    }

    private static boolean matchesWord(String hay, String w) {
        if (hay.isEmpty()) return false;
        return java.util.regex.Pattern.compile("\\b" + w + "\\b").matcher(hay).find();
    }

    private static String lower(CharSequence cs) { return cs == null ? "" : cs.toString().toLowerCase(); }

    static String keyLabel(int code) {
        for (int i = 0; i < KEY_CODES.length; i++) if (KEY_CODES[i] == code) return KEY_LABELS[i];
        return KeyEvent.keyCodeToString(code);
    }

    private static String q(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static String statusJson(Context c) {
        SharedPreferences p = prefs(c);
        int key = p.getInt(K_KEY, DEFAULT_KEY);
        return "{\"enabled\":" + p.getBoolean(K_ENABLED, true)
            + ",\"mode\":" + q(p.getString(K_MODE, MODE_TOGGLE))
            + ",\"keycode\":" + key
            + ",\"key\":" + q(keyLabel(key))
            + ",\"talking\":" + talking
            + ",\"lastTriggerTime\":" + lastTriggerTime
            + ",\"lastTargetPackage\":" + q(lastTargetPackage)
            + ",\"lastError\":" + q(lastError)
            + ",\"serviceConnected\":" + ScreenService.isConnected()
            + ",\"targets\":" + q(String.join(",", TARGETS.keySet()))
            + "}";
    }

    /** ptt_set enabled|mode|key value */
    static String set(Context c, String args) {
        String[] a = args == null ? new String[0] : args.trim().split("\\s+", 2);
        if (a.length < 2) return "{\"error\":\"usage: ptt_set <enabled|mode|key> <value>\"}";
        SharedPreferences.Editor ed = prefs(c).edit();
        String v = a[1].trim();
        switch (a[0]) {
            case "enabled":
                ed.putBoolean(K_ENABLED, v.equals("true") || v.equals("1") || v.equals("on")); break;
            case "mode":
                if (!v.equals(MODE_TOGGLE) && !v.equals(MODE_HOLD))
                    return "{\"error\":\"mode must be toggle or hold\"}";
                ed.putString(K_MODE, v); talking = false; break;
            case "key":
                int code;
                try { code = Integer.parseInt(v); }
                catch (NumberFormatException ex) {
                    code = KeyEvent.keyCodeFromString(v.startsWith("KEYCODE_") ? v : "KEYCODE_" + v.toUpperCase());
                }
                if (code <= 0) return "{\"error\":\"unknown key: " + v.replace("\"", "") + "\"}";
                ed.putInt(K_KEY, code); break;
            default:
                return "{\"error\":\"unknown setting: " + a[0].replace("\"", "") + "\"}";
        }
        ed.apply();
        return statusJson(c);
    }

    static void resetTalking() { talking = false; }
}
