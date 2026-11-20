package com.wjb.xinya;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;

/**
 * 每晚 00:00 的备份提醒。
 * 只负责「提醒」——真正的整理和备份还是 App 打开时补做，不赌后台能跑完。
 */
public class AlarmReceiver extends BroadcastReceiver {

    public static final String CHANNEL_ID = "pao_backup";
    public static final String PREFS = "pao";
    public static final String KEY_ON = "nightly";
    static final int REQ = 20261117;

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent == null ? "" : String.valueOf(intent.getAction());

        // 日程提醒不依赖「夜间提醒」那个开关
        if ("com.wjb.xinya.REMIND".equals(action)) {
            String text = intent.getStringExtra("remind_text");
            show(ctx, "泡泡提醒你", text == null ? "该准备了" : text, 1000 + (int)(System.currentTimeMillis() % 999));
            return;
        }

        boolean on = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false);
        if (!on) return;

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            schedule(ctx, true);
            return;
        }

        notifyNow(ctx);
        schedule(ctx, false); // 排下一天
    }

    /** 通用通知 */
    public static void show(Context ctx, String title, String body, int id) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "备份提醒", NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(ctx, id, open, flags);
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(ctx, CHANNEL_ID)
                : new Notification.Builder(ctx);
        b.setContentTitle(title).setContentText(body)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true).setContentIntent(pi);
        nm.notify(id, b.build());
    }

    private void notifyNow(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "备份提醒", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("每晚提醒把今天的记忆收一下");
            nm.createNotificationChannel(ch);
        }

        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, flags);

        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(ctx, CHANNEL_ID)
                : new Notification.Builder(ctx);
        b.setContentTitle("该收一下今天的记忆了")
                .setContentText("点一下，本鱼把今天的日记和备份整理好")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setAutoCancel(true)
                .setContentIntent(pi);
        nm.notify(REQ, b.build());
    }

    /** 排下一次 00:00（exact 不行就退回不精确） */
    public static void schedule(Context ctx, boolean atOnce) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent i = new Intent(ctx, AlarmReceiver.class);
        i.setAction("com.wjb.xinya.BACKUP_TICK");
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(ctx, REQ, i, flags);

        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 5);
        c.set(Calendar.MILLISECOND, 0);
        if (!atOnce || c.getTimeInMillis() <= System.currentTimeMillis()) {
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        long at = c.getTimeInMillis();

        boolean exact = true;
        if (Build.VERSION.SDK_INT >= 31) {
            exact = am.canScheduleExactAlarms();
        }
        try {
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    public static void cancel(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(ctx, AlarmReceiver.class);
        i.setAction("com.wjb.xinya.BACKUP_TICK");
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(ctx, REQ, i, flags);
        am.cancel(pi);
    }
}
