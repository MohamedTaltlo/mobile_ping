package online.mtradius.mtping;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

/** Durable outage ledger. An unfinished row is never counted as a confirmed recovery. */
final class OutageStore extends SQLiteOpenHelper {
    private static final String DB = "mt_ping_outages.db";

    static final class Entry {
        final long id, beganAt, endedAt, durationMs;
        final String host, outcome;
        Entry(long id, String host, long beganAt, long endedAt, long durationMs, String outcome) {
            this.id = id;
            this.host = host;
            this.beganAt = beganAt;
            this.endedAt = endedAt;
            this.durationMs = durationMs;
            this.outcome = outcome;
        }
        boolean active() { return "active".equals(outcome); }
    }

    OutageStore(Context context) {
        super(context.getApplicationContext(), DB, null, 1);
        setWriteAheadLoggingEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE outages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "host TEXT NOT NULL, " +
                "began_at INTEGER NOT NULL, " +
                "ended_at INTEGER NOT NULL DEFAULT 0, " +
                "last_seen INTEGER NOT NULL, " +
                "duration_ms INTEGER NOT NULL DEFAULT 0, " +
                "outcome TEXT NOT NULL DEFAULT 'active')");
        db.execSQL("CREATE INDEX idx_outages_recent ON outages (id DESC)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }

    synchronized long begin(String host, long startedAt) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("host", host);
        cv.put("began_at", startedAt);
        cv.put("last_seen", startedAt);
        cv.put("ended_at", 0);
        cv.put("duration_ms", 0);
        cv.put("outcome", "active");
        return db.insertOrThrow("outages", null, cv);
    }

    synchronized void heartbeat(long id, long wallNow, long elapsedDurationMs) {
        if (id <= 0) return;
        ContentValues cv = new ContentValues();
        cv.put("last_seen", wallNow);
        cv.put("duration_ms", Math.max(0, elapsedDurationMs));
        getWritableDatabase().update("outages", cv, "id=? AND outcome='active'",
                new String[]{Long.toString(id)});
    }

    synchronized void finish(long id, long wallNow, long durationMs, String outcome) {
        if (id <= 0) return;
        if (!("recovered".equals(outcome) || "stopped".equals(outcome) ||
                "error".equals(outcome) || "interrupted".equals(outcome))) {
            throw new IllegalArgumentException("Invalid outage outcome");
        }
        ContentValues cv = new ContentValues();
        cv.put("ended_at", wallNow);
        cv.put("last_seen", wallNow);
        cv.put("duration_ms", Math.max(0, durationMs));
        cv.put("outcome", outcome);
        getWritableDatabase().update("outages", cv, "id=? AND outcome='active'",
                new String[]{Long.toString(id)});
    }

    /** Closes open rows after an unexpected process death at their last measured heartbeat. */
    synchronized void closeInterrupted() {
        getWritableDatabase().execSQL(
            "UPDATE outages SET outcome='interrupted', ended_at=last_seen WHERE outcome='active'");
    }

    private static Entry read(Cursor c) {
        return new Entry(c.getLong(0), c.getString(1), c.getLong(2),
                c.getLong(3), c.getLong(4), c.getString(5));
    }

    synchronized List<Entry> recent(int limit) {
        List<Entry> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,host,began_at,ended_at,duration_ms,outcome FROM outages " +
                "ORDER BY id DESC LIMIT ?", new String[]{Integer.toString(Math.max(1, limit))})) {
            while (c.moveToNext()) list.add(read(c));
        }
        return list;
    }

    synchronized Entry latest(String host) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,host,began_at,ended_at,duration_ms,outcome FROM outages " +
                "WHERE host=? AND outcome<>'active' ORDER BY id DESC LIMIT 1",
                new String[]{host})) {
            return c.moveToNext() ? read(c) : null;
        }
    }

    synchronized int count() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM outages", null)) {
            return c.moveToNext() ? c.getInt(0) : 0;
        }
    }

    /** Starts a new monitoring session with no history from earlier sessions. */
    synchronized void clearSession() {
        getWritableDatabase().delete("outages", null, null);
    }
}
