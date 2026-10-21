package com.reed.fcmguard;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

public class GuardService extends Service {
    // v2 intentionally uses a new channel id. Android does not allow an app to raise
    // an existing channel from IMPORTANCE_MIN to IMPORTANCE_LOW after creation. When
    // HyperOS later bans the active channel (importance -> NONE) we delete it and
    // rotate to the next id (v3, v4, ...), which comes back at IMPORTANCE_LOW so the
    // notification stays silent and foldable instead of vanishing for good.
    private static final String CHANNEL_ID = "fcm_guard_persistent_v2";
    private static final String PREF_CHANNEL_ID = "channel_id";
    private static final String PREF_CHANNEL_GEN = "channel_gen";
    private static final int NOTIFICATION_ID = 426;
    private static final long FALLBACK_INTERVAL_MS = 30L * 60L * 1000L;
    // Per-process flag so callers can tell the service is already up and skip a
    // redundant (re)start — each startForegroundService is logged/reported by HyperOS.
    static volatile boolean RUNNING = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ContentObserver observer;
    private boolean foreground;

    private final Runnable fallbackCheck = new Runnable() {
        @Override public void run() {
            repair(false);
            // Re-post the ongoing notification so a shade removal (HyperOS fold/clear)
            // is undone even when the whitelist itself did not change.
            if (foreground) reassertNotification();
            handler.postDelayed(this, FALLBACK_INTERVAL_MS);
        }
    };

    private final Runnable repairDebounced = new Runnable() {
        @Override public void run() {
            repair(true);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        RUNNING = true;
        applyExecutionMode();
        registerObserver();
        SettingsGuard.rememberIfUseful(this);
        handler.post(fallbackCheck);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        RUNNING = true;
        SettingsGuard.setProtectionEnabled(this, true);
        registerObserver();
        applyExecutionMode();
        handler.removeCallbacks(fallbackCheck);
        handler.post(fallbackCheck);
        return START_STICKY;
    }

    private void repair(boolean notifyFailure) {
        SettingsGuard.Result result = SettingsGuard.repair(this);
        if (result.changed) {
            FcmReconnect.kick(this);
            if (foreground) refreshNotification(getString(R.string.notification_repaired));
        } else if (!result.success && notifyFailure && foreground) {
            refreshNotification(result.message);
        }
    }

    private void registerObserver() {
        ContentResolver resolver = getContentResolver();
        try {
            if (observer != null) resolver.unregisterContentObserver(observer);
        } catch (Throwable ignored) {}

        observer = new ContentObserver(handler) {
            @Override public void onChange(boolean selfChange, Uri uri) {
                handler.removeCallbacks(repairDebounced);
                handler.postDelayed(repairDebounced, 400L);
            }
        };
        resolver.registerContentObserver(
                Settings.System.getUriFor(SettingsGuard.getConfiguredKey(this)),
                false,
                observer
        );
    }

    private void applyExecutionMode() {
        if (SettingsGuard.usePersistentNotification(this)) {
            ensureNotificationChannel(this);
            startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_active)));
            foreground = true;
        } else {
            if (foreground) stopForeground(true);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIFICATION_ID);
            foreground = false;
        }
    }

    /**
     * Returns the active channel id, rotating to a fresh id when the current channel
     * has been banned (importance NONE) by the user or HyperOS. A newly created channel
     * comes back at IMPORTANCE_LOW, so the notification stays silent and foldable.
     */
    static String activeChannelId(Context context) {
        String id = context.getSharedPreferences(SettingsGuard.PREFS, Context.MODE_PRIVATE)
                .getString(PREF_CHANNEL_ID, CHANNEL_ID);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel ch = nm.getNotificationChannel(id);
                if (ch != null && ch.getImportance() == NotificationManager.IMPORTANCE_NONE) {
                    nm.deleteNotificationChannel(id);
                    SharedPreferences p = context.getSharedPreferences(SettingsGuard.PREFS, Context.MODE_PRIVATE);
                    int gen = p.getInt(PREF_CHANNEL_GEN, 2) + 1;
                    id = "fcm_guard_persistent_v" + gen;
                    p.edit().putString(PREF_CHANNEL_ID, id).putInt(PREF_CHANNEL_GEN, gen).apply();
                }
            }
        }
        return id;
    }

    /**
     * Creates the visible-but-silent foreground-service channel. Returns true only
     * when this call created the channel for the first time.
     */
    public static boolean ensureNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return false;

        String id = activeChannelId(context);
        boolean created = nm.getNotificationChannel(id) == null;
        NotificationChannel channel = new NotificationChannel(
                id,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(context.getString(R.string.notification_channel_description));
        channel.setShowBadge(false);
        channel.enableVibration(false);
        channel.enableLights(false);
        channel.setSound(null, null);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        nm.createNotificationChannel(channel);
        return created;
    }

    /** True when Android will actually place the foreground notification in the shade. */
    public static boolean canShowPersistentNotification(Context context) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !nm.areNotificationsEnabled()) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String id = context.getSharedPreferences(SettingsGuard.PREFS, Context.MODE_PRIVATE)
                    .getString(PREF_CHANNEL_ID, CHANNEL_ID);
            NotificationChannel channel = nm.getNotificationChannel(id);
            return channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
        }
        return true;
    }

    @Override public void onDestroy() {
        RUNNING = false;
        handler.removeCallbacksAndMessages(null);
        try {
            if (observer != null) getContentResolver().unregisterContentObserver(observer);
        } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    /** Re-asserts the ongoing foreground notification (recreates the channel if it was banned). */
    private void reassertNotification() {
        if (!SettingsGuard.usePersistentNotification(this)) return;
        ensureNotificationChannel(this);
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_active)));
        foreground = true;
    }

    private void refreshNotification(String text) {
        if (!foreground) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, activeChannelId(this));
        } else {
            builder = new Notification.Builder(this).setPriority(Notification.PRIORITY_LOW);
        }

        return builder
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(pi)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .build();
    }
}
