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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Three independent ICMP workers, one permanent status notification and
 * one UNIQUE ongoing, live-chronometer notification per confirmed outage.
 * Recovery replaces the SAME outage ID and stays in the notification shade.
 */
public final class PingService extends Service {
    static final String ACTION_START="online.mtradius.mtping.START";
    static final String ACTION_STOP="online.mtradius.mtping.STOP";
    static final String EXTRA_HOST="host";
    static final String EXTRA_HOSTS="hosts";
    static final String EXTRA_INTERVAL="interval";

    private static final String MONITOR_CHANNEL="mt_ping_monitor_v2";
    private static final String ALERT_CHANNEL="mt_ping_disconnect_v1";
    private static final int MONITOR_ID=488;
    private static final int MAX_HOSTS=3;

    private static final class TargetSession {
        final int slot;
        final String host;
        final int cadence;
        final PingState state;
        volatile Thread worker;
        long ledgerId=-1;
        int notificationId=-1;
        long outageStartWall=-1;
        long lastRecoveredMs=-1;
        TargetSession(int slot,String host,int cadence){
            this.slot=slot;this.host=host;this.cadence=cadence;
            this.state=PingState.forSlot(slot);
        }
    }

    private final AtomicInteger epoch=new AtomicInteger();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private volatile TargetSession[] sessions=new TargetSession[0];
    private PowerManager.WakeLock wakeLock;
    private OutageStore outageStore;
    private volatile long lastMonitorNotificationAt=-1;

    private final Runnable notificationTicker=new Runnable(){
        @Override public void run(){
            TargetSession[] active=sessions;
            if(active.length==0)return;
            for(TargetSession session:active){
                PingState.Snapshot snap=session.state.snapshot();
                synchronized(session){
                    if(session.ledgerId>0 && snap.isDown())
                        outageStore.heartbeat(session.ledgerId,
                                System.currentTimeMillis(),snap.downMs);
                }
            }
            publishMonitor();
            handler.postDelayed(this,1000);
        }
    };

    @Override public void onCreate(){
        super.onCreate();
        outageStore=new OutageStore(this);
        outageStore.closeInterrupted();
        NotificationManager manager=notifications();
        NotificationChannel monitor=new NotificationChannel(MONITOR_CHANNEL,
                "MT_TOOLS • المراقبة المستمرة",NotificationManager.IMPORTANCE_LOW);
        monitor.setShowBadge(false);
        manager.createNotificationChannel(monitor);
        NotificationChannel alert=new NotificationChannel(ALERT_CHANNEL,
                "MT_TOOLS • إشعارات الفصل والعودة",NotificationManager.IMPORTANCE_HIGH);
        alert.enableVibration(true);
        manager.createNotificationChannel(alert);
        // Remove only IDs belonging to the old single-event implementation.
        // Never clear past completed events from the new implementation.
        manager.cancel(489);
        manager.cancel(490);
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        String action=intent==null?null:intent.getAction();
        if(ACTION_STOP.equals(action)){
            stopMonitor("stopped",true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if(ACTION_START.equals(action)){
            String[] hosts=intent.getStringArrayExtra(EXTRA_HOSTS);
            if(hosts==null || hosts.length==0)
                hosts=new String[]{intent.getStringExtra(EXTRA_HOST)};
            int interval=intent.getIntExtra(EXTRA_INTERVAL,1000);
            if(interval<200 || interval>60000)interval=1000;
            if(!MonitorTargets.valid(hosts)){
                stopSelf();
                return START_NOT_STICKY;
            }
            saveSettings(hosts,interval);
            startMonitor(hosts,interval);
            return START_STICKY;
        }
        if(getSharedPreferences("settings",MODE_PRIVATE).getBoolean("active",false)){
            android.content.SharedPreferences prefs=getSharedPreferences("settings",MODE_PRIVATE);
            String[] hosts=readSavedHosts();
            int interval=prefs.getInt("interval",1000);
            if(interval<200 || interval>60000)interval=1000;
            if(MonitorTargets.valid(hosts)){
                startMonitor(hosts,interval);
                return START_STICKY;
            }
        }
        stopSelf();
        return START_NOT_STICKY;
    }

    private void saveSettings(String[] hosts,int interval){
        android.content.SharedPreferences.Editor edit=getSharedPreferences(
                "settings",MODE_PRIVATE).edit().putBoolean("active",true)
                .putInt("interval",interval).putString("host",hosts[0])
                .putInt("hostCount",hosts.length);
        for(int i=0;i<MAX_HOSTS;i++)
            edit.putString("host"+i,i<hosts.length?hosts[i]:"");
        edit.apply();
    }

    private String[] readSavedHosts(){
        android.content.SharedPreferences prefs=getSharedPreferences("settings",MODE_PRIVATE);
        int count=prefs.getInt("hostCount",1);
        if(count<1 || count>MAX_HOSTS)count=1;
        String[] hosts=new String[count];
        for(int i=0;i<count;i++)
            hosts[i]=prefs.getString("host"+i,i==0?prefs.getString("host","10.0.0.1"):"");
        return hosts;
    }

    static boolean validTarget(String host){
        if(host==null || host.length()<1 || host.length()>253)return false;
        if(!host.matches("[A-Za-z0-9][A-Za-z0-9.\\-]{0,252}"))return false;
        return !host.endsWith(".") && !host.contains("..");
    }

    private NotificationManager notifications(){
        return (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private void startMonitor(String[] hosts,int interval){
        stopMonitor("stopped",false);
        final int generation=epoch.get();
        outageStore.clearSession();
        new java.io.File(getFilesDir(),"mt_ping.csv").delete();
        new java.io.File(getFilesDir(),"mt_ping_previous.csv").delete();
        for(int i=0;i<MAX_HOSTS;i++)PingState.forSlot(i).stop();

        TargetSession[] next=new TargetSession[hosts.length];
        for(int i=0;i<hosts.length;i++){
            next[i]=new TargetSession(i,hosts[i],interval);
            next[i].state.start(hosts[i]);
        }
        sessions=next;
        lastMonitorNotificationAt=-1;
        startForegroundNow();
        acquireCpu();
        for(TargetSession session:next){
            Thread thread=new Thread(()->workerLoop(generation,session),
                    "MT_TOOLS-PING-"+session.slot);
            session.worker=thread;
            thread.start();
        }
        handler.removeCallbacks(notificationTicker);
        handler.postDelayed(notificationTicker,1000);
    }

    private void workerLoop(int generation,TargetSession session){
        long seq=0,nextAt=SystemClock.elapsedRealtime();
        while(generation==epoch.get() && !Thread.currentThread().isInterrupted()){
            PingSample sample;
            try {
                sample=PingRunner.probe(session.host,++seq);
            }catch(InterruptedException e){
                Thread.currentThread().interrupt();
                break;
            }
            if(generation!=epoch.get())break;
            synchronized(session){
                if(generation!=epoch.get())break;
                PingState.Snapshot before=session.state.snapshot();
                session.state.record(sample);
                PingState.Snapshot after=session.state.snapshot();
                if(!before.isDown() && after.isDown()){
                    startOutage(session,after);
                }else if(before.isDown() && !after.isDown()){
                    long duration=after.outages.isEmpty()?before.downMs:
                            after.outages.get(after.outages.size()-1).durationMs;
                    finishOutage(session,duration,
                            sample.kind==PingSample.Kind.REPLY?"recovered":"error");
                }
                if(before.isDown()!=after.isDown() ||
                        before.verifyingLoss!=after.verifyingLoss)
                    publishMonitor();
            }
            nextAt+=session.cadence;
            long now=SystemClock.elapsedRealtime();
            if(nextAt<now)nextAt=now;
            long sleep=nextAt-now;
            if(sleep>0){
                try{Thread.sleep(sleep);}
                catch(InterruptedException e){Thread.currentThread().interrupt();break;}
            }
        }
    }

    private synchronized int nextNotificationId(){
        android.content.SharedPreferences p=getSharedPreferences(
                "notification_sequence",MODE_PRIVATE);
        int id=p.getInt("next",5000);
        if(id<5000||id>1000000000)id=5000;
        p.edit().putInt("next",id+1).commit();
        return id;
    }

    private void startOutage(TargetSession session,PingState.Snapshot snap){
        session.outageStartWall=snap.downStartWall>0?snap.downStartWall:
                System.currentTimeMillis()-snap.downMs;
        session.ledgerId=outageStore.begin(session.host,session.outageStartWall);
        session.notificationId=nextNotificationId();
        // SystemUI advances this chronometer without needing to constantly
        // rebuild a notification. Android uses the saved wall start.
        Notification alert=new Notification.Builder(this,ALERT_CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("MT_TOOLS • انقطاع الاتصال")
                .setContentText("منقطع • "+session.host+" • مدة الفصل")
                .setStyle(new Notification.BigTextStyle()
                        .bigText("العنوان: "+session.host+"\nالانقطاع مستمر • العداد أعلى الإشعار"))
                .setContentIntent(openIntent())
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setAutoCancel(false)
                .setWhen(session.outageStartWall)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(false)
                .setColor(0xFFFF6278)
                .setCategory(Notification.CATEGORY_STATUS)
                .build();
        notifications().notify(session.notificationId,alert);
    }

    private void finishOutage(TargetSession session,long duration,String reason){
        if(session.ledgerId>0){
            outageStore.finish(session.ledgerId,System.currentTimeMillis(),duration,reason);
            session.ledgerId=-1;
        }
        session.lastRecoveredMs=duration;
        int id=session.notificationId;
        session.notificationId=-1;
        session.outageStartWall=-1;
        if(id<=0)return;
        final boolean recovered="recovered".equals(reason);
        String state=recovered?"عاد الاتصال":
                "stopped".equals(reason)?"توقفت المراقبة":"توقّف الفحص قبل تأكيد العودة";
        String text="العنوان: "+session.host+" • مدة الفصل: "+shortDuration(duration);
        Notification complete=new Notification.Builder(this,ALERT_CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("MT_TOOLS • "+state)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(openIntent())
                .setOnlyAlertOnce(false)
                .setOngoing(false)
                .setAutoCancel(true)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setUsesChronometer(false)
                .setColor(recovered?0xFF3EDAB6:0xFFFFC36A)
                .setCategory(Notification.CATEGORY_STATUS)
                .build();
        // SAME unique ID transforms the ongoing outage into a completed
        // notification. Every subsequent outage gets a NEW ID.
        notifications().notify(id,complete);
    }

    private void acquireCpu(){
        if(wakeLock==null){
            PowerManager pm=(PowerManager)getSystemService(Context.POWER_SERVICE);
            wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "MT_TOOLS:ContinuousMultiPing");
            wakeLock.setReferenceCounted(false);
        }
        if(!wakeLock.isHeld())wakeLock.acquire();
    }

    private void stopMonitor(String reason,boolean userStop){
        epoch.incrementAndGet();
        handler.removeCallbacks(notificationTicker);
        TargetSession[] old=sessions;
        sessions=new TargetSession[0];
        for(TargetSession session:old){
            if(session.worker!=null)session.worker.interrupt();
            synchronized(session){
                PingState.Snapshot snap=session.state.snapshot();
                if(session.notificationId>0)
                    finishOutage(session,snap.downMs,reason);
                session.state.stop();
            }
        }
        for(int i=old.length;i<MAX_HOSTS;i++)PingState.forSlot(i).stop();
        if(wakeLock!=null && wakeLock.isHeld())wakeLock.release();
        if(userStop){
            getSharedPreferences("settings",MODE_PRIVATE)
                    .edit().putBoolean("active",false).apply();
            outageStore.clearSession();
        }
    }

    private PendingIntent openIntent(){
        Intent open=new Intent(this,MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this,100,open,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }

    static String asClock(long millis){
        long seconds=Math.max(0,millis/1000);
        return String.format(Locale.US,"%02d:%02d:%02d",
                seconds/3600,(seconds/60)%60,seconds%60);
    }
    static String shortDuration(long millis){
        if(millis<10000)return String.format(Locale.US,"%.2f ثانية",Math.max(0,millis)/1000.0);
        return asClock(millis);
    }

    private static String lineFor(PingState.Snapshot snap){
        PingSample last=snap.last();
        String status;
        if(snap.isDown())status="منقطع";
        else if(snap.verifyingLoss)status="نبضة مفقودة";
        else if(last==null)status="جار الفحص";
        else if(last.kind==PingSample.Kind.REPLY)
            status=String.format(Locale.US,"متصل • %.2f ms",last.rttMs);
        else if(last.kind==PingSample.Kind.ERROR)status="خطأ الفحص";
        else status="فقد نبضة";
        return snap.host+" • "+status;
    }

    private Notification monitorNotice(){
        TargetSession[] current=sessions;
        Intent stop=new Intent(this,PingService.class).setAction(ACTION_STOP);
        PendingIntent stopAction=PendingIntent.getService(this,101,stop,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        List<String> lines=new ArrayList<>();
        for(TargetSession session:current)
            lines.add(lineFor(session.state.snapshot()));
        if(lines.isEmpty())lines.add("المراقبة متوقفة");
        String collapsed=lines.get(0)+(lines.size()>1?"  •  +"+(lines.size()-1)+" عناوين":"");
        String all=android.text.TextUtils.join("\n",lines);
        return new Notification.Builder(this,MONITOR_CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("MT_TOOLS • مراقبة Ping ("+current.length+")")
                .setContentText(collapsed)
                .setStyle(new Notification.BigTextStyle().bigText(all))
                .setContentIntent(openIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setUsesChronometer(false)
                .setColor(0xFF3EDAB6)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_pause,"إيقاف الفحص",stopAction).build())
                .build();
    }

    private void startForegroundNow(){
        Notification notice=monitorNotice();
        if(Build.VERSION.SDK_INT>=34)
            startForeground(MONITOR_ID,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(MONITOR_ID,notice);
    }

    private synchronized void publishMonitor(){
        if(sessions.length==0)return;
        long now=SystemClock.elapsedRealtime();
        // All target worker threads may finish at once; throttle rebuilds.
        if(lastMonitorNotificationAt>=0 && now-lastMonitorNotificationAt<200)return;
        lastMonitorNotificationAt=now;
        notifications().notify(MONITOR_ID,monitorNotice());
    }

    @Override public void onDestroy(){
        stopMonitor("interrupted",false);
        if(outageStore!=null)outageStore.close();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
