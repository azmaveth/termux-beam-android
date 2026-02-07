package com.example.beamapp;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
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
