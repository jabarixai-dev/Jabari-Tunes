package com.jabari.tunes;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;

/**
 * Foreground service that owns the system media session and notification, so Jabari Tunes shows
 * play/pause/next/previous in the notification drop-down and on the lock screen, and keeps playing
 * in the background. The audio itself still plays in the web view; button presses are sent back to it.
 */
public class PlaybackService extends Service {
    static PlaybackService instance;
    static State pending = new State();

    static class State {
        String title = "", artist = "";
        boolean playing = false;
        long pos = 0, dur = 0;
        Bitmap art = null;
        boolean setArt = false;
    }

    static final String CH = "jt_playback";
    static final int ID = 4242;
    static final String A_PLAY = "jt.play", A_PAUSE = "jt.pause", A_NEXT = "jt.next", A_PREV = "jt.prev";

    private MediaSession session;
    private String title = "", artist = "";
    private boolean playing = false, foreground = false;
    private long pos = 0, dur = 0;
    private Bitmap art = null;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CH, "Music playback", NotificationManager.IMPORTANCE_LOW);
            c.setShowBadge(false);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
        }
        session = new MediaSession(this, "JabariTunes");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { MediaControlPlugin.emit("play", 0); }
            @Override public void onPause() { MediaControlPlugin.emit("pause", 0); }
            @Override public void onStop() { MediaControlPlugin.emit("pause", 0); }
            @Override public void onSkipToNext() { MediaControlPlugin.emit("next", 0); }
            @Override public void onSkipToPrevious() { MediaControlPlugin.emit("prev", 0); }
            @Override public void onSeekTo(long p) { MediaControlPlugin.emit("seek", p); }
        });
        session.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (a == null || "start".equals(a)) {
            State s = pending;
            title = s.title; artist = s.artist; playing = s.playing; pos = s.pos; dur = s.dur;
            if (s.setArt) art = s.art;
            show(true);
        } else if (a.startsWith("jt.")) {
            MediaControlPlugin.emit(a.substring(3), 0);
        }
        return START_NOT_STICKY;
    }

    void update(State s) {
        title = s.title; artist = s.artist; playing = s.playing; pos = s.pos; dur = s.dur;
        if (s.setArt) art = s.art;
        show(false);
    }

    private void show(boolean mustForeground) {
        MediaMetadata.Builder mb = new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, dur);
        if (art != null) {
            mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
            mb.putBitmap(MediaMetadata.METADATA_KEY_ART, art);
        }
        session.setMetadata(mb.build());
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
            | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO;
        session.setPlaybackState(new PlaybackState.Builder()
            .setActions(actions)
            .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, pos, playing ? 1f : 0f)
            .build());

        Notification n = build();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (playing || mustForeground) {
            startFg(n);
            if (!playing && foreground) { stopForeground(false); foreground = false; }
        } else {
            if (foreground) { stopForeground(false); foreground = false; }
            nm.notify(ID, n);
        }
    }

    private void startFg(Notification n) {
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(ID, n);
            foreground = true;
        } catch (Exception ignored) { }
    }

    private Notification build() {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        Intent li = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (li != null) {
            b.setContentIntent(PendingIntent.getActivity(this, 0, li, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        }
        b.setSmallIcon(android.R.drawable.stat_sys_headset)
            .setContentTitle(title)
            .setContentText(artist)
            .setOngoing(playing)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC);
        if (art != null) b.setLargeIcon(art);
        b.addAction(android.R.drawable.ic_media_previous, "Previous", act(A_PREV));
        b.addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
            playing ? "Pause" : "Play", act(playing ? A_PAUSE : A_PLAY));
        b.addAction(android.R.drawable.ic_media_next, "Next", act(A_NEXT));
        b.setStyle(new Notification.MediaStyle()
            .setMediaSession(session.getSessionToken())
            .setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }

    private PendingIntent act(String a) {
        return PendingIntent.getService(this, a.hashCode(), new Intent(this, PlaybackService.class).setAction(a),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    void shutdown() {
        stopForeground(true);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(ID);
        stopSelf();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        shutdown();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        if (session != null) { session.setActive(false); session.release(); }
        super.onDestroy();
    }
}
