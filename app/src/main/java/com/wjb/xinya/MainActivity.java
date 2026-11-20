package com.wjb.xinya;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Intent;
import android.net.Uri;
import android.webkit.DownloadListener;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Size;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.net.Uri;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayOutputStream;
import android.util.Base64;
import android.provider.CalendarContract;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private WebView web;
    private TextToSpeech tts;
    private boolean ttsReady = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);      // key 和备份都靠它
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        web.setWebViewClient(new WebViewClient());

        // 网页里触发下载（比如「导出记忆包」）时交给系统下载器
        web.setDownloadListener((url, userAgent, contentDisposition, mimetype, len) -> {
            try {
                if (url.startsWith("data:")) {
                    android.widget.Toast.makeText(this, "data: 链接不支持下载", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                if (dm != null) dm.enqueue(r);
            } catch (Exception ignored) {}
        });

        // 录音：WebView 里 getUserMedia 要显式放行
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }

            // <input type=file>：不接这个，点「＋」不会有任何反应
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (fileCb != null) { fileCb.onReceiveValue(null); }
                fileCb = cb;
                try {
                    // 相册
                    Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                    pick.addCategory(Intent.CATEGORY_OPENABLE);
                    pick.setType("image/*");

                    // 相机（拍照）：输出到缓存目录，用 FileProvider 授权
                    cameraUri = null;
                    Intent shot = null;
                    try {
                        java.io.File dir = new java.io.File(getCacheDir(), "shots");
                        if (!dir.exists()) dir.mkdirs();
                        java.io.File f = new java.io.File(dir, "shot_" + System.currentTimeMillis() + ".jpg");
                        cameraUri = androidx.core.content.FileProvider.getUriForFile(
                                MainActivity.this, getPackageName() + ".fileprovider", f);
                        shot = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
                        shot.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, cameraUri);
                        shot.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Exception ignored) { cameraUri = null; }

                    Intent chooser = Intent.createChooser(pick, "选一张图");
                    if (shot != null) {
                        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{shot});
                    }
                    startActivityForResult(chooser, REQ_PICK);
                    return true;
                } catch (Exception e) {
                    fileCb = null;
                    return false;
                }
            }
        });

        web.addJavascriptInterface(new Bridge(), "PaoNative");

        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) return;
            int r = tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                r = tts.setLanguage(Locale.CHINA);
            }
            ttsReady = r != TextToSpeech.LANG_MISSING_DATA;
        });

        setContentView(web);
        loadApp();
        handleShare(getIntent());

        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2);
        }
        // 之前开过提醒的话，每次启动重排一次（杀进程后闹钟可能被清）
        if (getSharedPreferences(AlarmReceiver.PREFS, MODE_PRIVATE).getBoolean(AlarmReceiver.KEY_ON, false)) {
            AlarmReceiver.schedule(this, false);
        }
    }

    // file:// 不是「安全上下文」，getUserMedia / speechSynthesis 都会被浏览器拦。
    // 所以把网页读出来，用一个 https 的假源加载——对 WebView 来说它就成了安全上下文。
    private void loadApp() {
        try (java.io.InputStream in = getAssets().open("index.html")) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            String html = bo.toString("UTF-8");
            web.loadDataWithBaseURL("https://pao.local/", html, "text/html", "UTF-8", null);
        } catch (Exception e) {
            web.loadUrl("file:///android_asset/index.html");
        }
    }

    // ── 原生录音：16k 单声道 16bit，直接封 WAV ──
    private android.media.AudioRecord recorder;
    private Thread recThread;
    private ByteArrayOutputStream recBuf;
    private volatile boolean recording = false;

    private boolean ensureMic() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            return false;
        }
        return true;
    }

    private static byte[] wavHeader(int dataLen, int sr) {
        byte[] h = new byte[44];
        long totalDataLen = dataLen + 36;
        long byteRate = sr * 2L;
        h[0]='R'; h[1]='I'; h[2]='F'; h[3]='F';
        put32(h, 4, totalDataLen);
        h[8]='W'; h[9]='A'; h[10]='V'; h[11]='E';
        h[12]='f'; h[13]='m'; h[14]='t'; h[15]=' ';
        put32(h, 16, 16);
        h[20]=1; h[21]=0;                 // PCM
        h[22]=1; h[23]=0;                 // mono
        put32(h, 24, sr);
        put32(h, 28, (int) byteRate);
        h[32]=2; h[33]=0;                 // block align
        h[34]=16; h[35]=0;                // bits
        h[36]='d'; h[37]='a'; h[38]='t'; h[39]='a';
        put32(h, 40, dataLen);
        return h;
    }

    private static void put32(byte[] a, int off, long v) {
        a[off] = (byte) (v & 0xff);
        a[off+1] = (byte) ((v >> 8) & 0xff);
        a[off+2] = (byte) ((v >> 16) & 0xff);
        a[off+3] = (byte) ((v >> 24) & 0xff);
    }

    // ── 读日历（只读，默认关，开关在网页里）──
    private boolean ensureCalendar() {
        if (Build.VERSION.SDK_INT >= 23) {
            java.util.List<String> want = new java.util.ArrayList<>();
            if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED)
                want.add(Manifest.permission.READ_CALENDAR);
            if (checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED)
                want.add(Manifest.permission.WRITE_CALENDAR);
            if (!want.isEmpty()) {
                requestPermissions(want.toArray(new String[0]), 3);
                return false;
            }
        }
        return true;
    }

    /** 找一个能写入的日历（优先主日历） */
    private long writableCalendarId() {
        String[] proj = {
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.IS_PRIMARY,
        };
        try (android.database.Cursor c = getContentResolver().query(
                CalendarContract.Calendars.CONTENT_URI, proj,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL + " >= ?",
                new String[]{String.valueOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR)},
                CalendarContract.Calendars.IS_PRIMARY + " DESC")) {
            if (c != null && c.moveToFirst()) return c.getLong(0);
        } catch (Exception ignored) { }
        return -1;
    }

    /** 最近照片：返回 [{id, thumb(dataURL)}] */
    @JavascriptInterface
    public String recentImages(int limit) {
        org.json.JSONArray arr = new org.json.JSONArray();
        String[] proj = { android.provider.MediaStore.Images.Media._ID };
        try (android.database.Cursor c = getContentResolver().query(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                proj, null, null,
                android.provider.MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            if (c == null) return arr.toString();
            int n = 0;
            while (c.moveToNext() && n < Math.max(1, limit)) {
                long id = c.getLong(0);
                String thumb = thumbDataUrl(id, 240);
                if (thumb == null) continue;
                try {
                    org.json.JSONObject o = new org.json.JSONObject();
                    o.put("id", id);
                    o.put("thumb", thumb);
                    arr.put(o);
                    n++;
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return arr.toString();
    }

    private android.net.Uri imageUri(long id) {
        return android.content.ContentUris.withAppendedId(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
    }

    private String thumbDataUrl(long id, int side) {
        try {
            Bitmap bmp = null;
            if (Build.VERSION.SDK_INT >= 29) {
                bmp = getContentResolver().loadThumbnail(imageUri(id), new Size(side, side), null);
            } else {
                bmp = android.provider.MediaStore.Images.Thumbnails.getThumbnail(
                        getContentResolver(), id,
                        android.provider.MediaStore.Images.Thumbnails.MINI_KIND, null);
            }
            if (bmp == null) return null;
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 70, bo);
            return "data:image/jpeg;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    /** 取原图（按 maxSide 缩放），返回 dataURL */
    @JavascriptInterface
    public String loadImage(long id, int maxSide) {
        try {
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inJustDecodeBounds = true;
            java.io.InputStream in1 = getContentResolver().openInputStream(imageUri(id));
            BitmapFactory.decodeStream(in1, null, opt);
            if (in1 != null) in1.close();
            int scale = 1;
            int big = Math.max(opt.outWidth, opt.outHeight);
            while (big / scale > maxSide * 2) scale *= 2;

            BitmapFactory.Options opt2 = new BitmapFactory.Options();
            opt2.inSampleSize = scale;
            java.io.InputStream in2 = getContentResolver().openInputStream(imageUri(id));
            Bitmap bmp = BitmapFactory.decodeStream(in2, null, opt2);
            if (in2 != null) in2.close();
            if (bmp == null) return "";

            int w = bmp.getWidth(), h = bmp.getHeight();
            float f = Math.min(1f, (float) maxSide / Math.max(w, h));
            if (f < 1f) bmp = Bitmap.createScaledBitmap(bmp, Math.round(w * f), Math.round(h * f), true);

            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, bo);
            return "data:image/jpeg;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    /** 打开系统相机拍照，返回缩略图回填（走 fileChooser 那条路太长，这里直接进相册） */
    @JavascriptInterface
    public boolean openCamera() {
        runOnUiThread(() -> {
            try {
                java.io.File dir = new java.io.File(getCacheDir(), "shots");
                if (!dir.exists()) dir.mkdirs();
                java.io.File f = new java.io.File(dir, "shot_" + System.currentTimeMillis() + ".jpg");
                cameraUri = androidx.core.content.FileProvider.getUriForFile(
                        MainActivity.this, getPackageName() + ".fileprovider", f);
                Intent shot = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
                shot.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, cameraUri);
                shot.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivityForResult(shot, REQ_SHOT);
            } catch (Exception ignored) {}
        });
        return true;
    }

    /** 给某条日程预排一个精确闹钟（App 关掉也会响） */
    @JavascriptInterface
    public boolean scheduleReminder(int id, long atMs, String text) {
        try {
            android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
            if (am == null || atMs <= System.currentTimeMillis()) return false;
            Intent i = new Intent(this, AlarmReceiver.class);
            i.setAction("com.wjb.xinya.REMIND");
            i.putExtra("remind_text", text == null ? "该准备了" : text);
            int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= android.app.PendingIntent.FLAG_IMMUTABLE;
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(this, id, i, flags);
            boolean exact = true;
            if (Build.VERSION.SDK_INT >= 31) exact = am.canScheduleExactAlarms();
            if (exact) am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, atMs, pi);
            else am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, atMs, pi);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public void cancelReminder(int id) {
        try {
            android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(this, AlarmReceiver.class);
            i.setAction("com.wjb.xinya.REMIND");
            int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= android.app.PendingIntent.FLAG_IMMUTABLE;
            am.cancel(android.app.PendingIntent.getBroadcast(this, id, i, flags));
        } catch (Exception ignored) {}
    }

    /** 一次把能弹的权限全要了（通知 / 日历读写 / 麦克风） */
    @JavascriptInterface
    public boolean requestAllPermissions() {
        if (Build.VERSION.SDK_INT < 23) return true;
        java.util.List<String> want = new java.util.ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            want.add(Manifest.permission.RECORD_AUDIO);
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED)
            want.add(Manifest.permission.READ_CALENDAR);
        if (checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED)
            want.add(Manifest.permission.WRITE_CALENDAR);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
            want.add("android.permission.POST_NOTIFICATIONS");
        if (want.isEmpty()) return true;
        requestPermissions(want.toArray(new String[0]), 100);
        return false;
    }

    @JavascriptInterface
    public boolean hasAllPermissions() {
        if (Build.VERSION.SDK_INT < 23) return true;
        boolean ok = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED;
        if (Build.VERSION.SDK_INT >= 33) {
            ok = ok && checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
        }
        return ok;
    }

    /** 统一跳系统页：主线程 + NEW_TASK + 三级兜底，全失败要出声 */
    private void 跳系统页(final Intent 首选, final Intent 次选) {
        runOnUiThread(() -> {
            Intent[] 候选 = new Intent[]{首选, 次选,
                    new Intent(android.provider.Settings.ACTION_SETTINGS)};
            for (Intent i : 候选) {
                if (i == null) continue;
                try {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    return;
                } catch (Exception ignored) { }
            }
            android.widget.Toast.makeText(MainActivity.this,
                    "这台手机没有这个设置页，去「设置」里手动找吧", android.widget.Toast.LENGTH_LONG).show();
        });
    }

    /** 跳「精确闹钟」设置页 */
    @JavascriptInterface
    public boolean openExactAlarmSettings() {
        try {
            Intent 首选 = null;
            if (Build.VERSION.SDK_INT >= 31) {
                首选 = new Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                首选.setData(android.net.Uri.parse("package:" + getPackageName()));
            } else {
                首选 = new Intent(android.provider.Settings.ACTION_SETTINGS);
            }
            Intent 次选 = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            次选.setData(android.net.Uri.parse("package:" + getPackageName()));
            跳系统页(首选, 次选);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 跳「电池优化」：直接请求忽略，弹系统确认框 */
    @JavascriptInterface
    public boolean openBatterySettings() {
        try {
            Intent 首选 = null;
            if (Build.VERSION.SDK_INT >= 23) {
                首选 = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                首选.setData(android.net.Uri.parse("package:" + getPackageName()));
            }
            Intent 次选 = new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            跳系统页(首选, 次选);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public class Bridge {
        @JavascriptInterface
        public boolean notify(String title, String body) {
            try {
                android.app.NotificationManager nm =
                        (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm == null) return false;
                if (Build.VERSION.SDK_INT >= 26) {
                    android.app.NotificationChannel ch = new android.app.NotificationChannel(
                            "xinya_chat", "新芽的消息", android.app.NotificationManager.IMPORTANCE_DEFAULT);
                    nm.createNotificationChannel(ch);
                }
                android.content.Intent open = new android.content.Intent(MainActivity.this, MainActivity.class);
                open.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
                int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 23) flags |= android.app.PendingIntent.FLAG_IMMUTABLE;
                android.app.PendingIntent pi = android.app.PendingIntent.getActivity(MainActivity.this, 1, open, flags);

                android.app.Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                        ? new android.app.Notification.Builder(MainActivity.this, "pao_chat")
                        : new android.app.Notification.Builder(MainActivity.this);
                b.setContentTitle(title == null ? "新芽" : title)
                        .setContentText(body == null ? "" : body)
                        .setSmallIcon(android.R.drawable.stat_notify_chat)
                        .setAutoCancel(true)
                        .setContentIntent(pi);
                nm.notify((int) (System.currentTimeMillis() % 100000), b.build());
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public boolean hasCalendarPermission() {
            if (Build.VERSION.SDK_INT < 23) return true;
            return checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
        }

        @JavascriptInterface
        public boolean askCalendarPermission() {
            return ensureCalendar();
        }

        /** 写一条日程：返回空串=成功，否则是原因 */
        @JavascriptInterface
        public String addEvent(String title, long startMs, long endMs, String where, String note) {
            if (!ensureCalendar()) return "没给日历写入权限";
            if (title == null || title.trim().isEmpty()) return "标题是空的";
            long calId = writableCalendarId();
            if (calId < 0) return "手机里没找到可写的日历";
            if (endMs <= startMs) endMs = startMs + 3600_000L;
            try {
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(CalendarContract.Events.CALENDAR_ID, calId);
                v.put(CalendarContract.Events.TITLE, title.trim());
                v.put(CalendarContract.Events.DTSTART, startMs);
                v.put(CalendarContract.Events.DTEND, endMs);
                v.put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().getID());
                if (where != null && !where.trim().isEmpty()) v.put(CalendarContract.Events.EVENT_LOCATION, where.trim());
                if (note != null && !note.trim().isEmpty()) v.put(CalendarContract.Events.DESCRIPTION, note.trim());
                android.net.Uri u = getContentResolver().insert(CalendarContract.Events.CONTENT_URI, v);
                return u == null ? "写入失败" : "";
            } catch (Exception e) {
                return "写入出错：" + e.getMessage();
            }
        }

        @JavascriptInterface
        public boolean hasCalendarWrite() {
            if (Build.VERSION.SDK_INT < 23) return true;
            return checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED;
        }

        /** 未来 N 天的日程，返回 JSON 数组字符串 */
        @JavascriptInterface
        public String readCalendar(int days) {
            JSONArray arr = new JSONArray();
            if (!hasCalendarPermission()) return arr.toString();
            long now = System.currentTimeMillis();
            long until = now + Math.max(1, days) * 24L * 3600 * 1000;
            String[] cols = {
                    CalendarContract.Events.TITLE,
                    CalendarContract.Events.DTSTART,
                    CalendarContract.Events.DTEND,
                    CalendarContract.Events.EVENT_LOCATION,
                    CalendarContract.Events.ALL_DAY,
            };
            try (android.database.Cursor c = getContentResolver().query(
                    CalendarContract.Events.CONTENT_URI, cols,
                    CalendarContract.Events.DTSTART + " >= ? AND " + CalendarContract.Events.DTSTART + " <= ?"
                            + " AND (" + CalendarContract.Events.DELETED + " = 0 OR " + CalendarContract.Events.DELETED + " IS NULL)",
                    new String[]{String.valueOf(now), String.valueOf(until)},
                    CalendarContract.Events.DTSTART + " ASC")) {
                if (c == null) return arr.toString();
                SimpleDateFormat fmt = new SimpleDateFormat("MM-dd E HH:mm", java.util.Locale.CHINA);
                SimpleDateFormat fmtDay = new SimpleDateFormat("MM-dd", java.util.Locale.CHINA);
                int guard = 0;
                while (c.moveToNext() && guard++ < 60) {
                    String title = c.getString(0);
                    if (title == null || title.trim().isEmpty()) continue;
                    long st = c.getLong(1);
                    long et = c.getLong(2);
                    String loc = c.getString(3);
                    boolean allDay = c.getInt(4) == 1;
                    JSONObject o = new JSONObject();
                    o.put("title", title);
                    o.put("start", allDay ? fmtDay.format(new Date(st)) + " 全天" : fmt.format(new Date(st)));
                    if (!allDay && et > 0) o.put("end", new SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(new Date(et)));
                    if (loc != null && !loc.trim().isEmpty()) o.put("where", loc);
                    arr.put(o);
                }
            } catch (Exception e) {
                // 没日历、没权限、厂商 provider 不一样——都当空
            }
            return arr.toString();
        }

        @JavascriptInterface
        public boolean startRecord() {
            if (recording) return true;
            if (!ensureMic()) return false;
            try {
                int sr = 16000;
                int min = android.media.AudioRecord.getMinBufferSize(sr,
                        android.media.AudioFormat.CHANNEL_IN_MONO,
                        android.media.AudioFormat.ENCODING_PCM_16BIT);
                int bufSize = Math.max(min, 4096);
                recorder = new android.media.AudioRecord(android.media.MediaRecorder.AudioSource.MIC,
                        sr, android.media.AudioFormat.CHANNEL_IN_MONO,
                        android.media.AudioFormat.ENCODING_PCM_16BIT, bufSize * 2);
                if (recorder.getState() != android.media.AudioRecord.STATE_INITIALIZED) return false;
                recBuf = new ByteArrayOutputStream();
                recorder.startRecording();
                recording = true;
                final int bs = bufSize;
                recThread = new Thread(() -> {
                    byte[] tmp = new byte[bs];
                    while (recording) {
                        int n = recorder.read(tmp, 0, bs);
                        if (n > 0) recBuf.write(tmp, 0, n);
                    }
                });
                recThread.start();
                return true;
            } catch (Exception e) {
                recording = false;
                return false;
            }
        }

        @JavascriptInterface
        public String stopRecord() {
            if (!recording) return "";
            recording = false;
            try {
                if (recThread != null) recThread.join(800);
                if (recorder != null) {
                    recorder.stop();
                    recorder.release();
                    recorder = null;
                }
            } catch (Exception ignored) {}
            byte[] pcm = recBuf == null ? new byte[0] : recBuf.toByteArray();
            if (pcm.length == 0) return "";
            byte[] h = wavHeader(pcm.length, 16000);
            byte[] all = new byte[h.length + pcm.length];
            System.arraycopy(h, 0, all, 0, h.length);
            System.arraycopy(pcm, 0, all, h.length, pcm.length);
            return Base64.encodeToString(all, Base64.NO_WRAP);
        }

        @JavascriptInterface
        public boolean nightlyOn() {
            return getSharedPreferences(AlarmReceiver.PREFS, MODE_PRIVATE)
                    .getBoolean(AlarmReceiver.KEY_ON, false);
        }

        @JavascriptInterface
        public boolean setNightly(boolean on) {
            getSharedPreferences(AlarmReceiver.PREFS, MODE_PRIVATE)
                    .edit().putBoolean(AlarmReceiver.KEY_ON, on).apply();
            if (on) AlarmReceiver.schedule(MainActivity.this, false);
            else AlarmReceiver.cancel(MainActivity.this);
            return on;
        }

        @JavascriptInterface
        public boolean canExactAlarm() {
            if (Build.VERSION.SDK_INT >= 31) {
                android.app.AlarmManager am =
                        (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
                return am != null && am.canScheduleExactAlarms();
            }
            return true;
        }

        @JavascriptInterface
        public boolean isRecording() {
            return recording;
        }

        @JavascriptInterface
        public boolean speak(String text) {
            if (!ttsReady || text == null) return false;
            return tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pao") == TextToSpeech.SUCCESS;
        }

        @JavascriptInterface
        public void stopSpeak() {
            if (ttsReady) tts.stop();
        }

        @JavascriptInterface
        public boolean canSpeak() {
            return ttsReady;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShare(intent);
    }

    // 从别的 App 分享文字进来
    private void handleShare(Intent intent) {
        if (intent == null) return;
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null || text.isEmpty()) return;
        final String js = "window.__paoShared && window.__paoShared(" + jsQuote(text) + ");";
        web.postDelayed(() -> web.evaluateJavascript(js, null), 800);
    }

    private static String jsQuote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                default: b.append(c);
            }
        }
        return b.append("\"").toString();
    }

    private static final int REQ_PICK = 9001;
    private static final int REQ_SHOT = 9002;
    private ValueCallback<Uri[]> fileCb;
    private Uri cameraUri;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_SHOT) {
            if (resultCode == RESULT_OK && cameraUri != null) {
                // 拍完把图交给网页（用 dataURL 走，省得再处理 content://）
                final String uriStr = cameraUri.toString();
                web.post(() -> web.evaluateJavascript(
                        "window.__paoShot && window.__paoShot('" + uriStr + "');", null));
            }
            return;
        }
        if (requestCode == REQ_PICK) {
            Uri[] out = null;
            if (resultCode == RESULT_OK) {
                Uri u = (data != null) ? data.getData() : null;
                if (u == null && cameraUri != null) u = cameraUri;   // 刚拍的那张
                if (u != null) out = new Uri[]{u};
            }
            cameraUri = null;
            if (fileCb != null) {
                fileCb.onReceiveValue(out);
                fileCb = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web != null && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }
}
