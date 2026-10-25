package online.mtradius.mtping;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Foreground ICMP worker with a once-per-outage warning and a 1-second notification timer.
 * Duration is measured with monotonic elapsedRealtime(), not the changeable wall clock.
 */
public final class PingService extends Service {
    static final String ACTION_START = "online.mtradius.mtping.START";
    static final String ACTION_STOP = "online.mtradius.mtping.STOP";
    static final String EXTRA_HOST = "host";
    static final String EXTRA_INTERVAL = "interval";

    private static final String MONITOR_CHANNEL = "mt_ping_monitor_v2";
    private static final String ALERT_CHANNEL = "mt_ping_disconnect_v1";
    private static final int MONITOR_ID = 488;
    private static final int ALERT_ID = 489;
    private static final int RECOVERY_ID = 490;

    private final AtomicInteger epoch = new AtomicInteger();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile Thread worker;
    private PowerManager.WakeLock wakeLock;
    private OutageStore outageStore;
    private volatile long activeOutageId = -1;
    private volatile long lastCompletedMs = -1;
    private volatile boolean warningActive;
    private volatile long lastMonitorNotificationAt = -1;
    // Fixed starting timestamp for Android SystemUI's own chronometer.
    private volatile long outageStartedWall = -1;

    private final Runnable notificationTicker = new Runnable() {
        @Override public void run() {
            PingState.Snapshot snapshot = PingState.INSTANCE.snapshot();
            if (!snapshot.running) return;
            if (snapshot.isDown() && activeOutageId > 0) {
                // Persist progress only. Both SystemUI chronometers count from
                // the same fixed timestamp without rebuilding notifications.
                outageStore.heartbeat(activeOutageId,
                        System.currentTimeMillis(), snapshot.downMs);
            }
            handler.postDelayed(this, 1000L);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        outageStore = new OutageStore(this);
        outageStore.closeInterrupted();
        NotificationManager manager = notificationManager();

        NotificationChannel monitor = new NotificationChannel(
                MONITOR_CHANNEL, "MT_TOOLS • المراقبة المستمرة", NotificationManager.IMPORTANCE_LOW);
        monitor.setDescription("إظهار حالة الاتصال ومدة الانقطاع بشكل مباشر");
        monitor.setShowBadge(false);
        manager.createNotificationChannel(monitor);

        NotificationChannel alert = new NotificationChannel(
                ALERT_CHANNEL, "MT_TOOLS • تنبيهات انقطاع الاتصال", NotificationManager.IMPORTANCE_HIGH);
        alert.setDescription("تنبيه عند فقد الاتصال بالعنوان الجار فحصه");
        alert.enableVibration(true);
        manager.createNotificationChannel(alert);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopMonitor("stopped", true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            String host = intent.getStringExtra(EXTRA_HOST);
            int interval = intent.getIntExtra(EXTRA_INTERVAL, 1000);
            if (!validTarget(host)) {
                stopSelf();
                return START_NOT_STICKY;
            }
            // User-defined cadence in milliseconds. Timeout probes may take longer
            // than the requested period; avoid reporting that as a fixed-rate timer.
            if (interval < 200 || interval > 60000) interval = 1000;
            getSharedPreferences("settings", MODE_PRIVATE).edit()
                    .putString("host", host).putInt("interval", interval)
                    .putBoolean("active", true).apply();
            startMonitor(host, interval);
            return START_STICKY;
        }
        // Android may re-create the service following process eviction.
        if (getSharedPreferences("settings", MODE_PRIVATE).getBoolean("active", false)) {
            String host = getSharedPreferences("settings", MODE_PRIVATE).getString("host", "10.0.0.1");
            int interval = getSharedPreferences("settings", MODE_PRIVATE).getInt("interval", 1000);
            if (validTarget(host)) {
                if (interval < 200 || interval > 60000) interval = 1000;
                startMonitor(host, interval);
                return START_STICKY;
            }
        }
        stopSelf();
        return START_NOT_STICKY;
    }

    static boolean validTarget(String host) {
        if (host == null || host.length() < 1 || host.length() > 253) return false;
        if (!host.matches("[A-Za-z0-9][A-Za-z0-9.\\-]{0,252}")) return false;
        return !host.endsWith(".") && !host.contains("..");
    }

    private NotificationManager notificationManager() {
        return (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private void startMonitor(final String host, final int intervalMillis) {
        final int generation = epoch.incrementAndGet();
        Thread previous = worker;
        if (previous != null) previous.interrupt();
        // Every explicit start/restart begins a completely new session.
        // Do not carry a past outage or exported history into the new run.
        outageStore.clearSession();
        new java.io.File(getFilesDir(), "mt_ping.csv").delete();
        new java.io.File(getFilesDir(), "mt_ping_previous.csv").delete();
        PingState.INSTANCE.start(host);
        lastCompletedMs = -1;
        activeOutageId = -1;
        warningActive = false;
        outageStartedWall = -1;
        lastMonitorNotificationAt = -1;
        notificationManager().cancel(ALERT_ID);
        notificationManager().cancel(RECOVERY_ID);
        startForegroundNow("بدء فحص " + host);
        acquireCpu();

        Thread thread = new Thread(() -> {
            long sequence = 0;
            long nextAt = SystemClock.elapsedRealtime();
            while (generation == epoch.get() && !Thread.currentThread().isInterrupted()) {
                PingSample sample;
                try {
                    sample = PingRunner.probe(host, ++sequence);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (generation != epoch.get()) break;

                PingState.Snapshot before = PingState.INSTANCE.snapshot();
                PingState.INSTANCE.record(sample);
                PingState.Snapshot after = PingState.INSTANCE.snapshot();

                if (!before.isDown() && after.isDown()) {
                    // Only two consecutive lost probes count as an outage.
                    // Both notifications refer to the exact same monotonic start.
                    outageStartedWall = after.downStartWall > 0
                            ? after.downStartWall : System.currentTimeMillis() - after.downMs;
                    activeOutageId = outageStore.begin(host, outageStartedWall);
                    warningActive = true;
                    publishAlert(after, false, true);
                } else if (before.isDown() && !after.isDown()) {
                    // Never leave an active disconnect alert/chronometer visible
                    // after the first successful reply or a probe error.
                    notificationManager().cancel(ALERT_ID);
                    long duration = lastOutageDuration(after, before.downMs);
                    finishOutage(duration, sample.kind == PingSample.Kind.REPLY ? "recovered" : "error");
                    if (warningActive && sample.kind == PingSample.Kind.REPLY)
                        publishAlert(after, true, false);
                    warningActive = false;
                    outageStartedWall = -1;
                }
                // Keep both platform chronometers untouched while disconnected:
                // rebuilding the notification every packet makes SystemUI redraw
                // at different moments and causes visibly out-of-sync counters.
                if (!after.isDown() || !before.isDown()) {
                    long monoNow=SystemClock.elapsedRealtime();
                    boolean transition=before.isDown()!=after.isDown() ||
                            before.verifyingLoss!=after.verifyingLoss;
                    if(transition || lastMonitorNotificationAt<0 ||
                            monoNow-lastMonitorNotificationAt>=1000L)
                        publishMonitor(after);
                }

                nextAt += intervalMillis;
                long now = SystemClock.elapsedRealtime();
                if (nextAt < now) nextAt = now;
                long sleep = nextAt - now;
                if (sleep > 0) {
                    try {
                        Thread.sleep(sleep);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, "MT_PING-ICMP");
        worker = thread;
        thread.start();
        handler.removeCallbacks(notificationTicker);
        handler.postDelayed(notificationTicker, 1000L);
    }

    private static long lastOutageDuration(PingState.Snapshot after, long fallback) {
        if (after.outages.isEmpty()) return fallback;
        return after.outages.get(after.outages.size() - 1).durationMs;
    }

    private void finishOutage(long duration, String reason) {
        if (activeOutageId <= 0) return;
        outageStore.finish(activeOutageId, System.currentTimeMillis(), duration, reason);
        lastCompletedMs = duration;
        activeOutageId = -1;
    }

    private void acquireCpu() {
        if (wakeLock == null) {
            PowerManager manager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MT_PING:ContinuousICMP");
            wakeLock.setReferenceCounted(false);
        }
        if (!wakeLock.isHeld()) wakeLock.acquire();
    }

    private void stopMonitor(String reason, boolean userStop) {
        epoch.incrementAndGet();
        handler.removeCallbacks(notificationTicker);
        Thread thread = worker;
        worker = null;
        if (thread != null) thread.interrupt();
        PingState.Snapshot before = PingState.INSTANCE.snapshot();
        if (activeOutageId > 0) finishOutage(before.downMs, reason);
        // Service stop is not proof of restored connectivity.
        notificationManager().cancel(ALERT_ID);
        warningActive = false;
        outageStartedWall = -1;
        PingState.INSTANCE.stop();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (userStop) {
            getSharedPreferences("settings", MODE_PRIVATE)
                    .edit().putBoolean("active", false).apply();
            // Closing a monitoring session removes every recorded outage.
            outageStore.clearSession();
            notificationManager().cancel(ALERT_ID);
            notificationManager().cancel(RECOVERY_ID);
            lastCompletedMs = -1;
        }
    }

    private PendingIntent openIntent() {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 100, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static String asClock(long durationMs) {
        long seconds = Math.max(0L, durationMs / 1000L);
        return String.format(Locale.US, "%02d:%02d:%02d",
                seconds / 3600L, (seconds / 60L) % 60L, seconds % 60L);
    }

    static String shortDuration(long durationMs) {
        if (durationMs < 0) return "—";
        if (durationMs < 1000) {
            return String.format(Locale.US, "%.2f ثانية", durationMs / 1000.0);
        }
        return asClock(durationMs);
    }

    private Notification monitorNotice(String details, boolean disconnected) {
        Intent stop = new Intent(this, PingService.class).setAction(ACTION_STOP);
        PendingIntent stopAction = PendingIntent.getService(this, 101, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, MONITOR_CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(disconnected ? "MT_TOOLS • الاتصال منقطع" : "MT_TOOLS • مراقبة الاتصال")
                .setContentText(details)
                .setStyle(new Notification.BigTextStyle().bigText(details))
                .setContentIntent(openIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setColor(disconnected ? 0xFFFF6278 : 0xFF3EDAB6)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_pause, "إيقاف الفحص", stopAction).build());
        if (disconnected) {
            // SystemUI increments this time itself even when Android throttles
            // NotificationManager.notify(), the screen is off, or Activity is paused.
            long started = outageStartedWall > 0 ? outageStartedWall :
                    Math.max(1L, System.currentTimeMillis() - PingState.INSTANCE.snapshot().downMs);
            builder.setWhen(started).setShowWhen(true)
                    .setUsesChronometer(true).setChronometerCountDown(false);
        } else {
            builder.setShowWhen(false).setUsesChronometer(false);
        }
        return builder.build();
    }

    private void startForegroundNow(String details) {
        Notification notification = monitorNotice(details, false);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(MONITOR_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(MONITOR_ID, notification);
        }
    }

    private void publishMonitor(PingState.Snapshot snapshot) {
        PingSample last = snapshot.last();
        String details;
        if (snapshot.isDown()) {
            details = "انقطاع مؤكّد • " + snapshot.host + " • العداد المباشر";
        } else if (snapshot.verifyingLoss) {
            details = "نبضة مفقودة • " + snapshot.host + " • جار التأكد من الاتصال";
        } else if (last != null && last.kind == PingSample.Kind.REPLY) {
            details = String.format(Locale.US, "متصل • %.2f مللي ثانية • %s", last.rttMs, snapshot.host);
        } else if (last != null && last.kind == PingSample.Kind.ERROR) {
            details = "تعذّر إجراء الفحص • " + snapshot.host;
        } else {
            details = "جار فحص " + snapshot.host;
        }
        if (lastCompletedMs >= 0) {
            details += " • آخر فصل: " + shortDuration(lastCompletedMs);
        }
        lastMonitorNotificationAt = SystemClock.elapsedRealtime();
        notificationManager().notify(MONITOR_ID, monitorNotice(details, snapshot.isDown()));
    }

    private void publishAlert(PingState.Snapshot snapshot, boolean recovered, boolean fresh) {
        final NotificationManager manager=notificationManager();
        if (!recovered) {
            // Single lost probes only increment the packet-loss counter.
            // Never launch an ongoing high-importance notification for those.
            manager.cancel(RECOVERY_ID);
            String text="العنوان: "+snapshot.host+
                    " • انقطاع مؤكّد بعد فقد نبضتين متتاليتين. العداد المباشر في إشعار المراقبة.";
            Notification alert=new Notification.Builder(this,ALERT_CHANNEL)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle("MT_TOOLS • انقطاع الاتصال")
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(openIntent())
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    // Only the foreground monitor owns a running chronometer.
                    // An independent SystemUI alert clock was continuing to show
                    // 00:07 after a single lost packet recovered.
                    .setShowWhen(true)
                    .setWhen(System.currentTimeMillis())
                    .setUsesChronometer(false)
                    .setColor(0xFFFF6278)
                    .setCategory(Notification.CATEGORY_STATUS)
                    .build();
            manager.notify(ALERT_ID,alert);
            return;
        }
        manager.cancel(ALERT_ID);
        String text="العنوان: "+snapshot.host+" • مدة الفصل: "+shortDuration(lastCompletedMs);
        Notification recoveredNotice=new Notification.Builder(this,ALERT_CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("MT_TOOLS • عاد الاتصال")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(openIntent())
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis())
                .setUsesChronometer(false)
                .setColor(0xFF3EDAB6)
                .setCategory(Notification.CATEGORY_STATUS)
                .build();
        manager.notify(RECOVERY_ID,recoveredNotice);
    }

    @Override public void onDestroy() {
        stopMonitor("interrupted", false);
        if (outageStore != null) outageStore.close();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
