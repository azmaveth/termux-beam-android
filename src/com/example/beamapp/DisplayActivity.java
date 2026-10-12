package com.example.beamapp;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.Locale;

/**
 * Lightweight full-screen viewer driven by the bridge "display" command.
 * Renders images, video, PDF, and HTML/markdown/text from a local path.
 * (http(s) URLs are downloaded to cache by BridgeServer before launch.)
 * Dismiss with back, the X button, or the bridge "display_close" command.
 */
public class DisplayActivity extends Activity {
    private static final String TAG = "DisplayActivity";
    public static final String EXTRA_PATH = "path";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_TYPE = "type";
    public static final String EXTRA_LOOP = "loop";

    /* Set while a viewer is resumed; lets the bridge confirm and close it. */
    private static volatile WeakReference<DisplayActivity> current = new WeakReference<>(null);
    private static volatile long lastShownAt = 0;

    private PdfRenderer pdfRenderer;
    private ParcelFileDescriptor pdfFd;

    public static boolean isShowing() {
        return current.get() != null;
    }

    public static long lastShownAt() {
        return lastShownAt;
    }

    /** Close the current viewer, if any. Returns true if one was open. */
    public static boolean closeCurrent() {
        final DisplayActivity a = current.get();
        if (a == null) return false;
        a.runOnUiThread(new Runnable() {
            @Override public void run() { a.finish(); }
        });
        return true;
    }

    /** Classify a file by extension (falls back to "text"). */
    public static String detectType(String path) {
        String p = path.toLowerCase(Locale.US);
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        if (p.endsWith(".png") || p.endsWith(".jpg") || p.endsWith(".jpeg")
                || p.endsWith(".gif") || p.endsWith(".webp") || p.endsWith(".bmp")
                || p.endsWith(".heic")) return "image";
        if (p.endsWith(".mp4") || p.endsWith(".webm") || p.endsWith(".m4v")
                || p.endsWith(".mkv") || p.endsWith(".3gp") || p.endsWith(".mov")) return "video";
        if (p.endsWith(".pdf")) return "pdf";
        if (p.endsWith(".html") || p.endsWith(".htm") || p.endsWith(".svg")) return "html";
        if (p.endsWith(".md") || p.endsWith(".markdown")) return "markdown";
        return "text";
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        render(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        render(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        current = new WeakReference<>(this);
        lastShownAt = System.currentTimeMillis();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (current.get() == this) current = new WeakReference<>(null);
    }

    @Override
    protected void onDestroy() {
        closePdf();
        if (current.get() == this) current = new WeakReference<>(null);
        super.onDestroy();
    }

    private void closePdf() {
        try { if (pdfRenderer != null) pdfRenderer.close(); } catch (Exception ignored) {}
        try { if (pdfFd != null) pdfFd.close(); } catch (Exception ignored) {}
        pdfRenderer = null;
        pdfFd = null;
    }

    private void render(Intent intent) {
        closePdf();
        String path = intent.getStringExtra(EXTRA_PATH);
        String title = intent.getStringExtra(EXTRA_TITLE);
        String type = intent.getStringExtra(EXTRA_TYPE);
        boolean loop = intent.getBooleanExtra(EXTRA_LOOP, false);
        if (path == null) path = "";
        if (type == null || type.isEmpty()) type = detectType(path);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        View content;
        try {
            File f = new File(path);
            if (!f.canRead()) throw new Exception("cannot read " + path);
            switch (type) {
                case "image":    content = imageView(f); break;
                case "video":    content = videoView(f, loop); break;
                case "pdf":      content = pdfView(f); break;
                case "html":
                case "markdown":
                case "text":     content = webView(f, type); break;
                default:         content = webView(f, "text"); break;
            }
        } catch (Throwable e) {
            Log.e(TAG, "render failed: " + path, e);
            TextView tv = new TextView(this);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(16);
            tv.setPadding(40, 160, 40, 40);
            tv.setText("Could not display:\n" + path + "\n\n" + e);
            content = tv;
        }
        FrameLayout.LayoutParams contentLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        /* Documents scroll from the top: keep them clear of the title bar. */
        boolean isDoc = !"image".equals(type) && !"video".equals(type);
        if (isDoc && title != null && !title.isEmpty()) contentLp.topMargin = 160;
        root.addView(content, contentLp);

        if (title != null && !title.isEmpty()) {
            TextView t = new TextView(this);
            t.setText(title);
            t.setTextColor(Color.WHITE);
            t.setTextSize(16);
            t.setBackgroundColor(0x99000000);
            t.setPadding(36, 90, 160, 24);
            t.setSingleLine(true);
            root.addView(t, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        }

        TextView close = new TextView(this);
        close.setText("\u2715");
        close.setTextColor(Color.WHITE);
        close.setTextSize(24);
        close.setGravity(Gravity.CENTER);
        close.setBackgroundColor(0x88000000);
        close.setContentDescription("Close");
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(130, 130, Gravity.TOP | Gravity.END);
        clp.topMargin = 80;
        clp.rightMargin = 20;
        root.addView(close, clp);

        setContentView(root);
    }

    private View imageView(File f) throws Exception {
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setBackgroundColor(Color.BLACK);
        if (Build.VERSION.SDK_INT >= 28) {
            Drawable d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(f),
                new ImageDecoder.OnHeaderDecodedListener() {
                    @Override
                    public void onHeaderDecoded(ImageDecoder decoder, ImageDecoder.ImageInfo info,
                                                ImageDecoder.Source src) {
                        int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                        int max = 4096;
                        if (w > max || h > max) {
                            float s = Math.min((float) max / w, (float) max / h);
                            decoder.setTargetSize(Math.max(1, (int) (w * s)), Math.max(1, (int) (h * s)));
                        }
                    }
                });
            iv.setImageDrawable(d);
            if (d instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) d).start();
        } else {
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b == null) throw new Exception("could not decode image");
            iv.setImageBitmap(b);
        }
        return iv;
    }

    private View videoView(File f, final boolean loop) {
        FrameLayout box = new FrameLayout(this);
        box.setBackgroundColor(Color.BLACK);
        final VideoView vv = new VideoView(this);
        vv.setVideoURI(Uri.fromFile(f));
        vv.setOnPreparedListener(new android.media.MediaPlayer.OnPreparedListener() {
            @Override public void onPrepared(android.media.MediaPlayer mp) {
                mp.setLooping(loop);
                vv.start();
            }
        });
        vv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (vv.isPlaying()) vv.pause(); else vv.start();
            }
        });
        box.addView(vv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        return box;
    }

    private View pdfView(File f) throws Exception {
        pdfFd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        pdfRenderer = new PdfRenderer(pdfFd);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Color.DKGRAY);
        int pages = Math.min(pdfRenderer.getPageCount(), 50);
        for (int i = 0; i < pages; i++) {
            PdfRenderer.Page page = pdfRenderer.openPage(i);
            int w = screenW;
            int h = Math.max(1, (int) ((long) page.getHeight() * w / Math.max(1, page.getWidth())));
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.eraseColor(Color.WHITE);
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            page.close();
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(bmp);
            iv.setAdjustViewBounds(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = 8;
            col.addView(iv, lp);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        return sv;
    }

    private View webView(File f, String type) throws Exception {
        WebView wv = new WebView(this);
        wv.setBackgroundColor(Color.WHITE);
        wv.getSettings().setJavaScriptEnabled(false);
        wv.getSettings().setAllowFileAccess(true);
        wv.getSettings().setBuiltInZoomControls(true);
        wv.getSettings().setDisplayZoomControls(false);
        wv.getSettings().setLoadWithOverviewMode(true);
        wv.getSettings().setUseWideViewPort(true);
        if ("html".equals(type)) {
            wv.loadUrl(Uri.fromFile(f).toString());
        } else {
            String text = readText(f);
            String html = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<style>body{font:15px/1.45 sans-serif;margin:0;padding:96px 14px 14px;background:#111;color:#eee}"
                + "pre{white-space:pre-wrap;word-wrap:break-word;font:13px/1.4 monospace}</style><pre>"
                + android.text.TextUtils.htmlEncode(text) + "</pre>";
            wv.loadDataWithBaseURL(Uri.fromFile(f.getParentFile()).toString() + "/", html,
                    "text/html", "utf-8", null);
        }
        return wv;
    }

    private static String readText(File f) throws Exception {
        long len = Math.min(f.length(), 2 * 1024 * 1024);
        byte[] buf = new byte[(int) len];
        try (InputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            return new String(buf, 0, off, "UTF-8");
        }
    }
}
