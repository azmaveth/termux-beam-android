package com.example.beamapp;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * AccessibilityService for full UI control — reading screen content,
 * tapping, typing, swiping, navigation, and screenshots.
 * Runs a TCP server on port 9878 so BridgeServer can reach it
 * even across process boundaries (e.g. after APK update).
 */
public class ScreenService extends AccessibilityService {
    private static final String TAG = "ScreenService";
    private static final int DEFAULT_MAX_DEPTH = 10;
    private static final int PORT = 9878;

    private ServerSocket serverSocket;
    private volatile boolean running = false;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        startServer();
        Log.i(TAG, "AccessibilityService connected, server on port " + PORT);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // We don't need to process events — we query on demand
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "AccessibilityService interrupted");
    }

    @Override
    public void onDestroy() {
        stopServer();
        Log.i(TAG, "AccessibilityService destroyed");
        super.onDestroy();
    }

    /* ---- TCP Server ---- */

    private void startServer() {
        running = true;
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                Log.i(TAG, "Listening on port " + PORT);
                while (running) {
                    try {
                        Socket client = serverSocket.accept();
                        handleClient(client);
                    } catch (Exception e) {
                        if (running) Log.e(TAG, "Client error", e);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Server failed to start", e);
            }
        }, "ScreenService-Server").start();
    }

    private void stopServer() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception e) {}
    }

    private void handleClient(Socket client) {
        new Thread(() -> {
            try {
                client.setSoTimeout(30000);
                BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                PrintWriter out = new PrintWriter(client.getOutputStream(), true);
                String line;
                while ((line = in.readLine()) != null) {
                    String result = dispatch(line);
                    out.println(result);
                }
                client.close();
            } catch (Exception e) {
                Log.e(TAG, "Handle error", e);
            }
        }, "ScreenService-Client").start();
    }

    /**
     * Dispatch format: "method args..."
     * e.g. "readScreen 5" or "tapAt 100 200" or "globalAction back"
     */
    private String dispatch(String line) {
        if (line == null || line.isEmpty()) return "{\"error\":\"empty\"}";
        String method, args;
        int sp = line.indexOf(' ');
        if (sp > 0) {
            method = line.substring(0, sp);
            args = line.substring(sp + 1);
        } else {
            method = line;
            args = "";
        }
        try {
            switch (method) {
                case "readScreen":   return readScreen(args);
                case "findByText":   return findByText(args);
                case "clickByText":  return clickByText(args);
                case "clickById":    return clickById(args);
                case "tapAt":        return tapAt(args);
                case "typeText":     return typeText(args);
                case "swipe":        return swipe(args);
                case "globalAction": return globalAction(args);
                case "screenshot":   return screenshot(args);
                case "getInfo":      return getInfo();
                case "sendKey":      return sendKey(args);
                case "longClick":    return longClick(args);
                case "scroll":       return scroll(args);
                case "waitFor":      return waitFor(args);
                case "launch":       return launch(args);
                case "nodeInfo":     return nodeInfo(args);
                case "ping":         return "{\"pong\":true}";
                default:             return "{\"error\":\"unknown: " + esc(method) + "\"}";
            }
        } catch (Exception e) {
            Log.e(TAG, "Dispatch error: " + method, e);
            return "{\"error\":\"" + esc(e.getMessage()) + "\"}";
        }
    }

    /* ---- Helpers ---- */

    private static String esc(String s) {
        if (s == null) return null;
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /* ---- Read Screen (UI tree dump) ---- */

    private String readScreen(String args) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return "{\"error\":\"no active window\"}";
        }
        int maxDepth = DEFAULT_MAX_DEPTH;
        if (args != null && !args.isEmpty()) {
            try { maxDepth = Integer.parseInt(args.trim()); } catch (NumberFormatException e) {}
        }
        try {
            StringBuilder sb = new StringBuilder();
            nodeToJson(root, sb, 0, maxDepth);
            return sb.toString();
        } finally {
            root.recycle();
        }
    }

    private void nodeToJson(AccessibilityNodeInfo node, StringBuilder sb, int depth, int maxDepth) {
        if (node == null) {
            sb.append("null");
            return;
        }
        sb.append("{");

        // Class name (short)
        CharSequence cls = node.getClassName();
        if (cls != null) {
            String c = cls.toString();
            int dot = c.lastIndexOf('.');
            if (dot >= 0) c = c.substring(dot + 1);
            sb.append("\"cls\":\"").append(esc(c)).append("\"");
        }

        // Text
        CharSequence text = node.getText();
        if (text != null && text.length() > 0) {
            sb.append(",\"text\":\"").append(esc(text.toString())).append("\"");
        }

        // Content description
        CharSequence desc = node.getContentDescription();
        if (desc != null && desc.length() > 0) {
            sb.append(",\"desc\":\"").append(esc(desc.toString())).append("\"");
        }

        // View ID
        String viewId = node.getViewIdResourceName();
        if (viewId != null) {
            int slash = viewId.indexOf('/');
            if (slash >= 0) viewId = viewId.substring(slash + 1);
            sb.append(",\"id\":\"").append(esc(viewId)).append("\"");
        }

        // Bounds
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        sb.append(",\"bounds\":[").append(bounds.left).append(",").append(bounds.top)
          .append(",").append(bounds.width()).append(",").append(bounds.height()).append("]");

        // Key boolean properties (only include if true)
        if (node.isClickable()) sb.append(",\"click\":true");
        if (node.isEditable()) sb.append(",\"edit\":true");
        if (node.isFocused()) sb.append(",\"focus\":true");
        if (node.isScrollable()) sb.append(",\"scroll\":true");
        if (node.isChecked()) sb.append(",\"checked\":true");
        if (node.isSelected()) sb.append(",\"selected\":true");

        // Children
        int childCount = node.getChildCount();
        if (childCount > 0 && depth < maxDepth) {
            sb.append(",\"children\":[");
            boolean first = true;
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child == null) continue;
                if (!first) sb.append(",");
                first = false;
                nodeToJson(child, sb, depth + 1, maxDepth);
                child.recycle();
            }
            sb.append("]");
        } else if (childCount > 0) {
            sb.append(",\"childCount\":").append(childCount);
        }

        sb.append("}");
    }

    /* ---- Find nodes by text ---- */

    private String findByText(String text) {
        if (text == null || text.isEmpty()) {
            return "{\"error\":\"text required\"}";
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return "{\"error\":\"no active window\"}";
        }
        try {
            java.util.List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (AccessibilityNodeInfo n : nodes) {
                if (!first) sb.append(",");
                first = false;
                sb.append("{");
                CharSequence cls = n.getClassName();
                if (cls != null) {
                    String c = cls.toString();
                    int dot = c.lastIndexOf('.');
                    if (dot >= 0) c = c.substring(dot + 1);
                    sb.append("\"cls\":\"").append(esc(c)).append("\"");
                }
                CharSequence t = n.getText();
                if (t != null) sb.append(",\"text\":\"").append(esc(t.toString())).append("\"");
                CharSequence d = n.getContentDescription();
                if (d != null) sb.append(",\"desc\":\"").append(esc(d.toString())).append("\"");
                String vid = n.getViewIdResourceName();
                if (vid != null) {
                    int slash = vid.indexOf('/');
                    if (slash >= 0) vid = vid.substring(slash + 1);
                    sb.append(",\"id\":\"").append(esc(vid)).append("\"");
                }
                Rect bounds = new Rect();
                n.getBoundsInScreen(bounds);
                sb.append(",\"bounds\":[").append(bounds.left).append(",").append(bounds.top)
                  .append(",").append(bounds.width()).append(",").append(bounds.height()).append("]");
                if (n.isClickable()) sb.append(",\"click\":true");
                if (n.isEditable()) sb.append(",\"edit\":true");
                sb.append("}");
                n.recycle();
            }
            sb.append("]");
            return sb.toString();
        } finally {
            root.recycle();
        }
    }

    /* ---- Click by text ---- */

    private String clickByText(String text) {
        if (text == null || text.isEmpty()) {
            return "{\"error\":\"text required\"}";
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return "{\"error\":\"no active window\"}";
        }
        try {
            java.util.List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
            if (nodes.isEmpty()) {
                return "{\"error\":\"not found: " + esc(text) + "\"}";
            }
            AccessibilityNodeInfo target = nodes.get(0);
            boolean clicked = performClickOn(target);
            for (AccessibilityNodeInfo n : nodes) n.recycle();
            return clicked ? "{\"clicked\":true}" : "{\"error\":\"click failed\"}";
        } finally {
            root.recycle();
        }
    }

    /* ---- Click by view ID ---- */

    private String clickById(String id) {
        if (id == null || id.isEmpty()) {
            return "{\"error\":\"id required\"}";
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return "{\"error\":\"no active window\"}";
        }
        try {
            // Try with full resource name first, then with package prefix
            String fullId = id.contains(":") ? id : null;
            AccessibilityNodeInfo target = null;

            if (fullId != null) {
                java.util.List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(fullId);
                if (!nodes.isEmpty()) target = nodes.get(0);
            }
            if (target == null) {
                // Try common package prefixes
                CharSequence pkg = root.getPackageName();
                if (pkg != null) {
                    String rid = pkg + ":id/" + id;
                    java.util.List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(rid);
                    if (!nodes.isEmpty()) target = nodes.get(0);
                }
            }
            if (target == null) {
                return "{\"error\":\"not found: " + esc(id) + "\"}";
            }
            // Focus first, then click
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            boolean clicked = true;
            Rect bounds = new Rect();
            target.getBoundsInScreen(bounds);
            target.recycle();
            return "{\"clicked\":true,\"bounds\":[" + bounds.left + "," + bounds.top
                    + "," + bounds.width() + "," + bounds.height() + "]}";
        } finally {
            root.recycle();
        }
    }

    private boolean performClickOn(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        while (current != null) {
            if (current.isClickable()) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            AccessibilityNodeInfo parent = current.getParent();
            current = parent;
        }
        // Fallback: tap center of bounds
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        return tapAtSync(bounds.centerX(), bounds.centerY(), 100);
    }

    /* ---- Tap at coordinates ---- */

    private String tapAt(String args) {
        if (args == null || args.isEmpty()) {
            return "{\"error\":\"usage: <x> <y>\"}";
        }
        String[] parts = args.trim().split("\\s+");
        if (parts.length < 2) {
            return "{\"error\":\"usage: <x> <y>\"}";
        }
        try {
            float x = Float.parseFloat(parts[0]);
            float y = Float.parseFloat(parts[1]);
            boolean ok = tapAtSync(x, y, 100);
            return ok ? "{\"tapped\":true,\"x\":" + (int)x + ",\"y\":" + (int)y + "}"
                      : "{\"error\":\"gesture failed\"}";
        } catch (NumberFormatException e) {
            return "{\"error\":\"invalid coordinates\"}";
        }
    }

    private boolean tapAtSync(float x, float y, long durationMs) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, durationMs);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke).build();
        return dispatchGestureSync(gesture);
    }

    /* ---- Type text ---- */

    private String typeText(String text) {
        if (text == null) {
            return "{\"error\":\"text required\"}";
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return "{\"error\":\"no active window\"}";
        }
        try {
            AccessibilityNodeInfo target = findFocusedEditable(root);
            if (target == null) {
                return "{\"error\":\"no focused editable field\"}";
            }
            Bundle bundle = new Bundle();
            bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle);
            target.recycle();
            return ok ? "{\"typed\":true}" : "{\"error\":\"set text failed\"}";
        } finally {
            root.recycle();
        }
    }

    private AccessibilityNodeInfo findFocusedEditable(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused != null && focused.isEditable()) return focused;
        if (focused != null) focused.recycle();
        return findEditableRecursive(root);
    }

    private AccessibilityNodeInfo findEditableRecursive(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable() && node.isFocused()) {
            return AccessibilityNodeInfo.obtain(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo result = findEditableRecursive(child);
            child.recycle();
            if (result != null) return result;
        }
        return null;
    }

    /* ---- Swipe ---- */

    private String swipe(String args) {
        if (args == null || args.isEmpty()) {
            return "{\"error\":\"usage: <x1> <y1> <x2> <y2> [ms]\"}";
        }
        String[] parts = args.trim().split("\\s+");
        if (parts.length < 4) {
            return "{\"error\":\"usage: <x1> <y1> <x2> <y2> [ms]\"}";
        }
        try {
            float x1 = Float.parseFloat(parts[0]);
            float y1 = Float.parseFloat(parts[1]);
            float x2 = Float.parseFloat(parts[2]);
            float y2 = Float.parseFloat(parts[3]);
            long ms = parts.length > 4 ? Long.parseLong(parts[4]) : 300;

            Path path = new Path();
            path.moveTo(x1, y1);
            path.lineTo(x2, y2);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(path, 0, ms);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(stroke).build();
            boolean ok = dispatchGestureSync(gesture);
            return ok ? "{\"swiped\":true}" : "{\"error\":\"gesture failed\"}";
        } catch (NumberFormatException e) {
            return "{\"error\":\"invalid coordinates\"}";
        }
    }

    /* ---- Global actions ---- */

    private String globalAction(String action) {
        int actionId;
        switch (action != null ? action.toLowerCase().trim() : "") {
            case "back":          actionId = GLOBAL_ACTION_BACK; break;
            case "home":          actionId = GLOBAL_ACTION_HOME; break;
            case "recents":       actionId = GLOBAL_ACTION_RECENTS; break;
            case "notifications": actionId = GLOBAL_ACTION_NOTIFICATIONS; break;
            case "quick_settings": actionId = GLOBAL_ACTION_QUICK_SETTINGS; break;
            case "power_dialog":  actionId = GLOBAL_ACTION_POWER_DIALOG; break;
            case "lock_screen":
                if (Build.VERSION.SDK_INT >= 28) {
                    actionId = GLOBAL_ACTION_LOCK_SCREEN;
                } else {
                    return "{\"error\":\"lock_screen requires API 28+\"}";
                }
                break;
            case "take_screenshot":
                if (Build.VERSION.SDK_INT >= 28) {
                    actionId = GLOBAL_ACTION_TAKE_SCREENSHOT;
                } else {
                    return "{\"error\":\"take_screenshot requires API 28+\"}";
                }
                break;
            default:
                return "{\"error\":\"unknown action: " + esc(action) + "\"}";
        }
        boolean ok = performGlobalAction(actionId);
        return ok ? "{\"action\":\"" + esc(action) + "\",\"ok\":true}"
                  : "{\"error\":\"action failed\"}";
    }

    /* ---- Screenshot ---- */

    private String screenshot(String args) {
        if (Build.VERSION.SDK_INT < 30) {
            return "{\"error\":\"screenshot requires API 30+\"}";
        }
        String path = (args != null && !args.isEmpty()) ? args.trim() : "/sdcard/DCIM/screen.png";

        final CountDownLatch latch = new CountDownLatch(1);
        final String[] resultHolder = new String[1];

        takeScreenshot(Display.DEFAULT_DISPLAY,
                getMainExecutor(),
                new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult screenshotResult) {
                        try {
                            Bitmap bmp = Bitmap.wrapHardwareBuffer(
                                    screenshotResult.getHardwareBuffer(),
                                    screenshotResult.getColorSpace());
                            if (bmp == null) {
                                resultHolder[0] = "{\"error\":\"null bitmap\"}";
                                return;
                            }
                            Bitmap swBmp = bmp.copy(Bitmap.Config.ARGB_8888, false);
                            bmp.recycle();
                            screenshotResult.getHardwareBuffer().close();

                            File file = new File(path);
                            file.getParentFile().mkdirs();
                            FileOutputStream fos = new FileOutputStream(file);
                            swBmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                            fos.close();
                            swBmp.recycle();
                            resultHolder[0] = "{\"path\":\"" + esc(path) + "\",\"size\":" + file.length() + "}";
                        } catch (Exception e) {
                            resultHolder[0] = "{\"error\":\"" + esc(e.getMessage()) + "\"}";
                        } finally {
                            latch.countDown();
                        }
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        resultHolder[0] = "{\"error\":\"screenshot failed, code=" + errorCode + "\"}";
                        latch.countDown();
                    }
                });

        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            return "{\"error\":\"screenshot timeout\"}";
        }
        return resultHolder[0] != null ? resultHolder[0] : "{\"error\":\"screenshot timeout\"}";
    }

    /* ---- Info ---- */

    private String getInfo() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"connected\":true");

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            CharSequence pkg = root.getPackageName();
            if (pkg != null) {
                sb.append(",\"package\":\"").append(esc(pkg.toString())).append("\"");
            }
            root.recycle();
        } else {
            sb.append(",\"window\":null");
        }
        sb.append(",\"api\":").append(Build.VERSION.SDK_INT);
        sb.append(",\"gestures\":true");
        sb.append(",\"screenshot\":").append(Build.VERSION.SDK_INT >= 30);
        sb.append("}");
        return sb.toString();
    }

    /* ---- Send key ---- */

    private String sendKey(String args) {
        if (args == null || args.isEmpty()) {
            return "{\"error\":\"usage: <key> — keys: enter, backspace, delete, tab, escape, up, down, left, right, home, end\"}";
        }
        String key = args.trim().toLowerCase();
        AccessibilityNodeInfo root = getRootInActiveWindow();

        switch (key) {
            case "enter":
            case "return": {
                // Try IME enter on focused node (API 30+)
                if (root != null) {
                    AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                    if (focused != null) {
                        if (Build.VERSION.SDK_INT >= 30) {
                            boolean ok = focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
                            focused.recycle();
                            root.recycle();
                            if (ok) return "{\"key\":\"enter\",\"ok\":true}";
                        }
                        // Fallback: press enter via ACTION_CLICK on focused node
                        boolean ok = focused.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        focused.recycle();
                        root.recycle();
                        return ok ? "{\"key\":\"enter\",\"ok\":true}" : "{\"error\":\"enter failed\"}";
                    }
                    root.recycle();
                }
                return "{\"error\":\"no focused node\"}";
            }
            case "backspace":
            case "delete": {
                if (root != null) {
                    AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                    if (focused != null && focused.isEditable()) {
                        CharSequence text = focused.getText();
                        if (text != null && text.length() > 0) {
                            String newText;
                            if (key.equals("delete")) {
                                // Delete char after cursor (just remove last for simplicity)
                                newText = text.subSequence(0, text.length() - 1).toString();
                            } else {
                                newText = text.subSequence(0, text.length() - 1).toString();
                            }
                            Bundle b = new Bundle();
                            b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText);
                            boolean ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
                            focused.recycle();
                            root.recycle();
                            return ok ? "{\"key\":\"" + key + "\",\"ok\":true}" : "{\"error\":\"" + key + " failed\"}";
                        }
                        focused.recycle();
                    }
                    root.recycle();
                }
                return "{\"error\":\"no editable focused node\"}";
            }
            case "tab": {
                // Move focus to next element
                if (root != null) {
                    AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                    if (focused != null) {
                        boolean ok = focused.performAction(AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT)
                                || root.performAction(AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY);
                        focused.recycle();
                    }
                    // Fallback: traverse to next focusable
                    AccessibilityNodeInfo next = root.focusSearch(android.view.View.FOCUS_FORWARD);
                    if (next != null) {
                        boolean ok = next.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                        next.recycle();
                        root.recycle();
                        return ok ? "{\"key\":\"tab\",\"ok\":true}" : "{\"error\":\"tab failed\"}";
                    }
                    root.recycle();
                }
                return "{\"error\":\"no next focusable element\"}";
            }
            case "escape":
            case "esc": {
                if (root != null) root.recycle();
                boolean ok = performGlobalAction(GLOBAL_ACTION_BACK);
                return ok ? "{\"key\":\"escape\",\"ok\":true}" : "{\"error\":\"escape failed\"}";
            }
            case "up":
            case "down":
            case "left":
            case "right": {
                if (root != null) {
                    AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                    if (focused != null) {
                        Bundle b = new Bundle();
                        b.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
                                AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER);
                        int action = (key.equals("right") || key.equals("down"))
                                ? AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
                                : AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY;
                        // For up/down, use line granularity
                        if (key.equals("up") || key.equals("down")) {
                            b.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
                                    AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE);
                        }
                        boolean ok = focused.performAction(action, b);
                        focused.recycle();
                        root.recycle();
                        return ok ? "{\"key\":\"" + key + "\",\"ok\":true}" : "{\"error\":\"" + key + " failed\"}";
                    }
                    root.recycle();
                }
                return "{\"error\":\"no focused node\"}";
            }
            case "home":
            case "end": {
                if (root != null) {
                    AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                    if (focused != null) {
                        Bundle b = new Bundle();
                        b.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
                                AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE);
                        int action = key.equals("home")
                                ? AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY
                                : AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY;
                        // Move to line boundary
                        boolean ok = focused.performAction(action, b);
                        focused.recycle();
                        root.recycle();
                        return ok ? "{\"key\":\"" + key + "\",\"ok\":true}" : "{\"error\":\"" + key + " failed\"}";
                    }
                    root.recycle();
                }
                return "{\"error\":\"no focused node\"}";
            }
            default: {
                if (root != null) root.recycle();
                // For single printable chars, append to focused editable
                if (key.length() == 1 || args.trim().length() == 1) {
                    return appendText(args.trim());
                }
                return "{\"error\":\"unknown key: " + esc(key) + "\"}";
            }
        }
    }

    private String appendText(String ch) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "{\"error\":\"no active window\"}";
        try {
            AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused != null && focused.isEditable()) {
                CharSequence text = focused.getText();
                String newText = (text != null ? text.toString() : "") + ch;
                Bundle b = new Bundle();
                b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText);
                boolean ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
                focused.recycle();
                return ok ? "{\"key\":\"" + esc(ch) + "\",\"ok\":true}" : "{\"error\":\"append failed\"}";
            }
            if (focused != null) focused.recycle();
            return "{\"error\":\"no editable focused node\"}";
        } finally {
            root.recycle();
        }
    }

    /* ---- Long click ---- */

    private String longClick(String args) {
        if (args == null || args.isEmpty()) {
            return "{\"error\":\"usage: <text> or <x> <y>\"}";
        }
        String[] parts = args.trim().split("\\s+");
        // If 2 numbers, treat as coordinates
        if (parts.length >= 2) {
            try {
                float x = Float.parseFloat(parts[0]);
                float y = Float.parseFloat(parts[1]);
                // Long press = hold gesture for 600ms
                boolean ok = tapAtSync(x, y, 600);
                return ok ? "{\"long_clicked\":true,\"x\":" + (int)x + ",\"y\":" + (int)y + "}"
                          : "{\"error\":\"long click gesture failed\"}";
            } catch (NumberFormatException e) {
                // Not coordinates, treat as text
            }
        }
        // Find by text and long-click
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "{\"error\":\"no active window\"}";
        try {
            java.util.List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(args.trim());
            if (nodes.isEmpty()) {
                return "{\"error\":\"not found: " + esc(args.trim()) + "\"}";
            }
            AccessibilityNodeInfo target = nodes.get(0);
            // Try ACTION_LONG_CLICK walking up to find a long-clickable ancestor
            boolean clicked = performLongClickOn(target);
            for (AccessibilityNodeInfo n : nodes) n.recycle();
            return clicked ? "{\"long_clicked\":true}" : "{\"error\":\"long click failed\"}";
        } finally {
            root.recycle();
        }
    }

    private boolean performLongClickOn(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        while (current != null) {
            if (current.isLongClickable()) {
                return current.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);
            }
            AccessibilityNodeInfo parent = current.getParent();
            current = parent;
        }
        // Fallback: long-press gesture at center of bounds
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        return tapAtSync(bounds.centerX(), bounds.centerY(), 600);
    }

    /* ---- Scroll ---- */

    private String scroll(String args) {
        if (args == null || args.isEmpty()) {
            return "{\"error\":\"usage: <up|down|left|right|forward|backward> [view_id]\"}";
        }
        String[] parts = args.trim().split("\\s+", 2);
        String direction = parts[0].toLowerCase();
        String viewId = parts.length > 1 ? parts[1] : null;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "{\"error\":\"no active window\"}";

        try {
            AccessibilityNodeInfo scrollable = null;

            // If ID specified, find that specific scrollable
            if (viewId != null) {
                CharSequence pkg = root.getPackageName();
                if (pkg != null) {
                    String rid = pkg + ":id/" + viewId;
                    java.util.List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(rid);
                    if (!nodes.isEmpty()) scrollable = nodes.get(0);
                }
            }

            // Otherwise find the first scrollable container
            if (scrollable == null) {
                scrollable = findScrollable(root);
            }

            if (scrollable == null) {
                return "{\"error\":\"no scrollable container found\"}";
            }

            int action;
            switch (direction) {
                case "down":
                case "forward":
                    action = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
                    break;
                case "up":
                case "backward":
                    action = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
                    break;
                case "left":
                    action = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId();
                    break;
                case "right":
                    action = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId();
                    break;
                default:
                    scrollable.recycle();
                    return "{\"error\":\"direction must be up/down/left/right/forward/backward\"}";
            }

            boolean ok = scrollable.performAction(action);
            scrollable.recycle();
            return ok ? "{\"scrolled\":\"" + direction + "\"}" : "{\"error\":\"scroll failed\"}";
        } finally {
            root.recycle();
        }
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isScrollable()) return AccessibilityNodeInfo.obtain(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo result = findScrollable(child);
            child.recycle();
            if (result != null) return result;
        }
        return null;
    }

    /* ---- Wait for element ---- */

    private String waitFor(String args) {
        if (args == null || args.isEmpty()) {
            return "{\"error\":\"usage: <text> [timeout_secs]\"}";
        }
        // Parse: "Some text 10" or "Some text"
        String text;
        int timeoutSecs = 10;
        // Try to extract timeout from end
        String trimmed = args.trim();
        int lastSpace = trimmed.lastIndexOf(' ');
        if (lastSpace > 0) {
            try {
                timeoutSecs = Integer.parseInt(trimmed.substring(lastSpace + 1));
                text = trimmed.substring(0, lastSpace);
            } catch (NumberFormatException e) {
                text = trimmed;
            }
        } else {
            text = trimmed;
        }

        long deadline = System.currentTimeMillis() + (timeoutSecs * 1000L);
        int polls = 0;

        while (System.currentTimeMillis() < deadline) {
            polls++;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                java.util.List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
                if (!nodes.isEmpty()) {
                    // Found it — return info about the first match
                    AccessibilityNodeInfo n = nodes.get(0);
                    StringBuilder sb = new StringBuilder("{\"found\":true,\"polls\":");
                    sb.append(polls);
                    CharSequence cls = n.getClassName();
                    if (cls != null) {
                        String c = cls.toString();
                        int dot = c.lastIndexOf('.');
                        if (dot >= 0) c = c.substring(dot + 1);
                        sb.append(",\"cls\":\"").append(esc(c)).append("\"");
                    }
                    CharSequence t = n.getText();
                    if (t != null) sb.append(",\"text\":\"").append(esc(t.toString())).append("\"");
                    Rect bounds = new Rect();
                    n.getBoundsInScreen(bounds);
                    sb.append(",\"bounds\":[").append(bounds.left).append(",").append(bounds.top)
                      .append(",").append(bounds.width()).append(",").append(bounds.height()).append("]");
                    if (n.isClickable()) sb.append(",\"click\":true");
                    sb.append("}");
                    for (AccessibilityNodeInfo node : nodes) node.recycle();
                    root.recycle();
                    return sb.toString();
                }
                root.recycle();
            }
            try { Thread.sleep(500); } catch (InterruptedException e) { break; }
        }
        return "{\"found\":false,\"timeout\":" + timeoutSecs + ",\"polls\":" + polls + "}";
    }

    /* ---- Launch app ---- */

    private String launch(String args) {
        if (args == null || args.isEmpty()) {
            return "{\"error\":\"usage: <package_name>\"}";
        }
        String pkg = args.trim();
        try {
            PackageManager pm = getPackageManager();
            Intent intent = pm.getLaunchIntentForPackage(pkg);
            if (intent == null) {
                return "{\"error\":\"no launch intent for: " + esc(pkg) + "\"}";
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            return "{\"launched\":\"" + esc(pkg) + "\"}";
        } catch (Exception e) {
            return "{\"error\":\"" + esc(e.getMessage()) + "\"}";
        }
    }

    /* ---- Node info by ID ---- */

    private String nodeInfo(String args) {
        if (args == null || args.isEmpty()) {
            return "{\"error\":\"usage: <view_id>\"}";
        }
        String id = args.trim();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "{\"error\":\"no active window\"}";

        try {
            AccessibilityNodeInfo target = null;
            // Try with package prefix
            CharSequence pkg = root.getPackageName();
            if (pkg != null) {
                String rid = pkg + ":id/" + id;
                java.util.List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(rid);
                if (!nodes.isEmpty()) target = nodes.get(0);
            }
            // Try as fully qualified
            if (target == null && id.contains(":")) {
                java.util.List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
                if (!nodes.isEmpty()) target = nodes.get(0);
            }
            if (target == null) {
                return "{\"error\":\"not found: " + esc(id) + "\"}";
            }

            StringBuilder sb = new StringBuilder("{");
            // Class
            CharSequence cls = target.getClassName();
            if (cls != null) {
                String c = cls.toString();
                int dot = c.lastIndexOf('.');
                if (dot >= 0) c = c.substring(dot + 1);
                sb.append("\"cls\":\"").append(esc(c)).append("\"");
            }
            // Text
            CharSequence text = target.getText();
            if (text != null) sb.append(",\"text\":\"").append(esc(text.toString())).append("\"");
            // Content description
            CharSequence desc = target.getContentDescription();
            if (desc != null) sb.append(",\"desc\":\"").append(esc(desc.toString())).append("\"");
            // Full view ID
            String viewId = target.getViewIdResourceName();
            if (viewId != null) sb.append(",\"viewId\":\"").append(esc(viewId)).append("\"");
            // Bounds
            Rect bounds = new Rect();
            target.getBoundsInScreen(bounds);
            sb.append(",\"bounds\":[").append(bounds.left).append(",").append(bounds.top)
              .append(",").append(bounds.width()).append(",").append(bounds.height()).append("]");
            // All boolean properties
            sb.append(",\"clickable\":").append(target.isClickable());
            sb.append(",\"longClickable\":").append(target.isLongClickable());
            sb.append(",\"editable\":").append(target.isEditable());
            sb.append(",\"focusable\":").append(target.isFocusable());
            sb.append(",\"focused\":").append(target.isFocused());
            sb.append(",\"scrollable\":").append(target.isScrollable());
            sb.append(",\"checkable\":").append(target.isCheckable());
            sb.append(",\"checked\":").append(target.isChecked());
            sb.append(",\"selected\":").append(target.isSelected());
            sb.append(",\"enabled\":").append(target.isEnabled());
            sb.append(",\"visible\":").append(target.isVisibleToUser());
            sb.append(",\"password\":").append(target.isPassword());
            // Available actions
            sb.append(",\"actions\":[");
            java.util.List<AccessibilityNodeInfo.AccessibilityAction> actions = target.getActionList();
            boolean first = true;
            for (AccessibilityNodeInfo.AccessibilityAction a : actions) {
                if (!first) sb.append(",");
                first = false;
                CharSequence label = a.getLabel();
                sb.append("{\"id\":").append(a.getId());
                if (label != null) sb.append(",\"label\":\"").append(esc(label.toString())).append("\"");
                sb.append("}");
            }
            sb.append("]");
            // Child count
            sb.append(",\"childCount\":").append(target.getChildCount());

            sb.append("}");
            target.recycle();
            return sb.toString();
        } finally {
            root.recycle();
        }
    }

    /* ---- Gesture dispatch helper ---- */

    private boolean dispatchGestureSync(GestureDescription gesture) {
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] result = {false};

        boolean dispatched = dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                result[0] = true;
                latch.countDown();
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                result[0] = false;
                latch.countDown();
            }
        }, null);

        if (!dispatched) return false;

        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            return false;
        }
        return result[0];
    }
}
