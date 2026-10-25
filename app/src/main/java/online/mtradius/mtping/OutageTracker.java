package online.mtradius.mtping;

/**
 * Monotonic, deterministic outage detector. One lost ICMP packet is a packet
 * loss, not enough evidence to claim the whole link is down. Two consecutive
 * failed probes confirm an outage; its start is anchored to the first miss.
 * No Android dependencies so notification timing can be regression-tested.
 */
final class OutageTracker {
    static final class Change {
        final boolean started, ended;
        final long durationMs, startWall;
        Change(boolean started,boolean ended,long durationMs,long startWall){
            this.started=started;this.ended=ended;this.durationMs=durationMs;
            this.startWall=startWall;
        }
    }

    private long firstFailureMono=-1,firstFailureWall=-1;
    private long downSince=-1,downStartWall=-1,completedMs;
    private int consecutiveMisses;

    void reset(){
        firstFailureMono=firstFailureWall=downSince=downStartWall=-1;
        completedMs=0;
        consecutiveMisses=0;
    }

    Change record(PingSample.Kind kind,long monoNow,long wallNow) {
        if(kind==PingSample.Kind.TIMEOUT){
            if(consecutiveMisses==0) {
                firstFailureMono=monoNow;
                firstFailureWall=wallNow;
            }
            consecutiveMisses++;
            if(consecutiveMisses>=2 && downSince<0){
                downSince=firstFailureMono;
                downStartWall=firstFailureWall;
                return new Change(true,false,0,downStartWall);
            }
            return new Change(false,false,0,downStartWall);
        }

        // A successful probe (or local command error) closes only a confirmed
        // outage. Discard a single unconfirmed loss.
        consecutiveMisses=0;
        firstFailureMono=firstFailureWall=-1;
        if(downSince>=0){
            long duration=Math.max(0,monoNow-downSince);
            long previousWall=downStartWall;
            completedMs+=duration;
            downSince=downStartWall=-1;
            return new Change(false,true,duration,previousWall);
        }
        return new Change(false,false,0,-1);
    }

    Change stop(long monoNow) {
        consecutiveMisses=0;
        firstFailureMono=firstFailureWall=-1;
        if(downSince<0)return new Change(false,false,0,-1);
        long duration=Math.max(0,monoNow-downSince);
        long previousWall=downStartWall;
        completedMs+=duration;
        downSince=downStartWall=-1;
        return new Change(false,true,duration,previousWall);
    }
    boolean isDown(){return downSince>=0;}
    boolean verifyingLoss(){return consecutiveMisses==1 && downSince<0;}
    long downStartWall(){return downStartWall;}
    long downMs(long monoNow){return downSince<0?0:Math.max(0,monoNow-downSince);}
    long totalDownMs(long monoNow){return completedMs+downMs(monoNow);}
}
