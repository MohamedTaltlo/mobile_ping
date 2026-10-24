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

    private final AtomicInteger epoch = new AtomicInteger();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile Thread worker;
    private PowerManager.WakeLock wakeLock;
    private OutageStore outageStore;
    private volatile long activeOutageId = -1;
    private volatile long lastCompletedMs = -1;
    private volatile boolean warningActive;
    // Fixed starting timestamp for Android SystemUI's own chronometer.
    private volatile long outageStartedWall = -1;

    private final Runnable notificationTicker = new Runnable() {
        @Override public void run() {
            PingState.Snapshot snapshot = PingState.INSTANCE.snapshot();
            if (!snapshot.running) return;
            if (snapshot.isDown()) {
                long now = System.currentTimeMillis();
                if (activeOutageId > 0) {
                    outageStore.heartbeat(activeOutageId, now, snapshot.downMs);
                }
                publishMonitor(snapshot);
                if (warningActive) publishAlert(snapshot, false, false);
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
                MONITOR_CHANNEL, "MT_PING • المراقبة المستمرة", NotificationManager.IMPORTANCE_LOW);
        monitor.setDescription("إظهار حالة الاتصال ومدة الانقطاع بشكل مباشر");
        monitor.setShowBadge(false);
        manager.createNotificationChannel(monitor);

        NotificationChannel alert = new NotificationChannel(
                ALERT_CHANNEL, "MT_PING • تنبيهات انقطاع الاتصال", NotificationManager.IMPORTANCE_HIGH);
        alert.setDescription("تنبيه عند فقد الاتصال بالعنوان الجاري فحصه");
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
            if (interval != 1000 && interval != 2000 && interval != 5000) interval = 1000;
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
        // Finish a previous measurement rather than silently losing an open outage.
        if (activeOutageId > 0) finishOutage(PingState.INSTANCE.snapshot().downMs, "stopped");
        PingState.INSTANCE.start(host);
        OutageStore.Entry latest = outageStore.latest(host);
        lastCompletedMs = latest == null ? -1 : latest.durationMs;
        activeOutageId = -1;
        warningActive = false;
        outageStartedWall = -1;
        notificationManager().cancel(ALERT_ID);
        startForegroundNow("بدء فحص " + host);
        acquireCpu();

        final PingLog csv = new PingLog(this);
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

                csv.append(host, sample);
                if (!before.isDown() && after.isDown()) {
                    outageStartedWall = Math.max(1L, System.currentTimeMillis() - after.downMs);
                    activeOutageId = outageStore.begin(host, outageStartedWall);
                    warningActive = true;
                    publishAlert(after, false, true);
                } else if (before.isDown() && !after.isDown() && activeOutageId > 0) {
                    long duration = lastOutageDuration(after, before.downMs);
                    finishOutage(duration, sample.kind == PingSample.Kind.REPLY ? "recovered" : "error");
                    if (warningActive) publishAlert(after, true, false);
                    warningActive = false;
                    outageStartedWall = -1;
                }
                publishMonitor(after);

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
        if (activeOutageId > 0) {
            finishOutage(before.downMs, reason);
            publishAlert(before, true, false);
            warningActive = false;
            outageStartedWall = -1;
        }
        PingState.INSTANCE.stop();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (userStop) getSharedPreferences("settings", MODE_PRIVATE)
                .edit().putBoolean("active", false).apply();
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

    private Notification monitorNotice(String details, boolean disconnected) {
        Intent stop = new Intent(this, PingService.class).setAction(ACTION_STOP);
        PendingIntent stopAction = PendingIntent.getService(this, 101, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, MONITOR_CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(disconnected ? "MT_PING • الاتصال منقطع" : "MT_PING • مراقبة الاتصال")
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
            details = "منقطع منذ " + asClock(snapshot.downMs) + " • " + snapshot.host + " • العداد المباشر بجانب الإشعار";
        } else if (last != null && last.kind == PingSample.Kind.REPLY) {
            details = String.format(Locale.US, "متصل • %.2f مللي ثانية • %s", last.rttMs, snapshot.host);
        } else if (last != null && last.kind == PingSample.Kind.ERROR) {
            details = "تعذّر إجراء الفحص • " + snapshot.host;
        } else {
            details = "جاري فحص " + snapshot.host;
        }
        if (lastCompletedMs >= 0) {
            details += " • آخر فصل: " + asClock(lastCompletedMs);
        }
        notificationManager().notify(MONITOR_ID, monitorNotice(details, snapshot.isDown()));
    }

    private void publishAlert(PingState.Snapshot snapshot, boolean closed, boolean newAlert) {
        String title;
        String text;
        if (!closed) {
            title = "انقطاع الاتصال • MT_PING";
            text = "العنوان: " + snapshot.host + " • مدة الفصل: " + asClock(snapshot.downMs);
        } else {
            title = "انتهت متابعة الانقطاع • MT_PING";
            PingSample last = snapshot.last();
            if (last != null && last.kind == PingSample.Kind.REPLY) {
                title = "عاد الاتصال • MT_PING";
            }
            text = "العنوان: " + snapshot.host + " • آخر فصل استمر: " +
                    asClock(Math.max(0L, lastCompletedMs));
        }
        if (newAlert) notificationManager().cancel(ALERT_ID);
        Notification.Builder builder = new Notification.Builder(this, ALERT_CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(openIntent())
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(true)
                .setColor(closed ? 0xFF3EDAB6 : 0xFFFF6278)
                .setCategory(Notification.CATEGORY_STATUS);
        if (!closed) {
            long started = outageStartedWall > 0 ? outageStartedWall :
                    Math.max(1L, System.currentTimeMillis() - snapshot.downMs);
            builder.setWhen(started).setShowWhen(true)
                    .setUsesChronometer(true).setChronometerCountDown(false);
        } else {
            builder.setWhen(System.currentTimeMillis()).setShowWhen(true)
                    .setUsesChronometer(false);
        }
        // The high-importance channel alerts on the transition. Subsequent
        // updates are silent; the Android system chronometer keeps counting.
        notificationManager().notify(ALERT_ID, builder.build());
    }

    @Override public void onDestroy() {
        stopMonitor("interrupted", false);
        if (outageStore != null) outageStore.close();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
