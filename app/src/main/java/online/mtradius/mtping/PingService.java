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
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

public final class PingService extends Service {
    static final String ACTION_START = "online.mtradius.mtping.START";
    static final String ACTION_STOP = "online.mtradius.mtping.STOP";
    static final String EXTRA_HOST = "host";
    static final String EXTRA_INTERVAL = "interval";
    private static final String CHANNEL_ID = "mt_ping_monitor_v1";
    private static final int NOTIFICATION_ID = 488;
    private final AtomicInteger epoch = new AtomicInteger();
    private volatile Thread worker;
    private PowerManager.WakeLock wakeLock;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "MT_PING monitoring",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Ongoing foreground ICMP monitor");
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopMonitor(true);
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
            interval = (interval == 1000 || interval == 2000 || interval == 5000) ? interval : 1000;
            getSharedPreferences("settings", MODE_PRIVATE).edit()
                    .putString("host", host).putInt("interval", interval)
                    .putBoolean("active", true).apply();
            startMonitor(host, interval);
            return START_STICKY;
        }
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

    private void startMonitor(final String host, final int intervalMillis) {
        final int generation = epoch.incrementAndGet();
        Thread prev = worker;
        if (prev != null) prev.interrupt();

        PingState.INSTANCE.start(host);
        startForegroundNow("Starting ICMP probe to " + host);
        holdCpu();
        final PingLog log = new PingLog(this);
        Thread thread = new Thread(() -> {
            long seq = 0;
            long next = SystemClock.elapsedRealtime();
            while (generation == epoch.get() && !Thread.currentThread().isInterrupted()) {
                PingSample sample;
                try {
                    sample = PingRunner.probe(host, ++seq);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (generation != epoch.get()) break;
                PingState.INSTANCE.record(sample);
                log.append(host, sample);
                refreshNotification();

                next += intervalMillis;
                long now = SystemClock.elapsedRealtime();
                if (next < now) next = now;
                long wait = next - now;
                if (wait > 0) {
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, "MT_PING-ICMP");
        worker = thread;
        thread.start();
    }

    private void holdCpu() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MT_PING:ActiveICMP");
            wakeLock.setReferenceCounted(false);
        }
        if (!wakeLock.isHeld()) wakeLock.acquire();
    }

    private void stopMonitor(boolean userStop) {
        epoch.incrementAndGet();
        Thread thread = worker;
        worker = null;
        if (thread != null) thread.interrupt();
        PingState.INSTANCE.stop();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (userStop) getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("active", false).apply();
    }

    private Notification makeNotification(String status) {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openIntent = PendingIntent.getActivity(this, 100, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, PingService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 101, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("MT_PING • Live Monitor")
                .setContentText(status)
                .setStyle(new Notification.BigTextStyle().bigText(status))
                .setContentIntent(openIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setColor(0xFF3EDAB6)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_pause, "Stop", stopIntent).build())
                .build();
    }

    private void startForegroundNow(String status) {
        Notification notice = makeNotification(status);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notice);
        }
    }

    private void refreshNotification() {
        PingState.Snapshot snap = PingState.INSTANCE.snapshot();
        PingSample last = snap.last();
        if (last == null) return;
        String message;
        if (last.kind == PingSample.Kind.REPLY) {
            message = String.format(Locale.US, "%s • %.2f ms • loss %.1f%%",
                    snap.host, last.rttMs, snap.sent == 0 ? 0 : (snap.lost * 100.0 / snap.sent));
        } else if (last.kind == PingSample.Kind.TIMEOUT) {
            message = snap.host + " • DOWN " + (snap.downMs / 1000) + "s • loss " + snap.lost;
        } else {
            message = snap.host + " • ERROR: " + last.detail;
        }
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIFICATION_ID, makeNotification(message));
    }

    @Override public void onDestroy() {
        stopMonitor(false);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
