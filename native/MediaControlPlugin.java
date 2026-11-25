package com.jabari.tunes;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.util.Base64;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

/** Bridge between the web player and the native media notification / lock-screen controls. */
@CapacitorPlugin(
    name = "MediaControl",
    permissions = { @Permission(strings = { Manifest.permission.POST_NOTIFICATIONS }, alias = "notif") }
)
public class MediaControlPlugin extends Plugin {
    static MediaControlPlugin instance;

    @Override
    public void load() { instance = this; }

    /** Called by the service when a notification / lock-screen button is pressed. */
    static void emit(String action, long pos) {
        if (instance == null) return;
        JSObject o = new JSObject();
        o.put("action", action);
        o.put("position", pos);
        instance.notifyListeners("action", o);
    }

    @PluginMethod
    public void ensurePermission(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 33 && getPermissionState("notif") != PermissionState.GRANTED) {
            requestPermissionForAlias("notif", call, "permDone");
            return;
        }
        call.resolve();
    }

    @PermissionCallback
    private void permDone(PluginCall call) { call.resolve(); }

    @PluginMethod
    public void update(PluginCall call) {
        PlaybackService.State s = new PlaybackService.State();
        s.title = call.getString("title", "");
        s.artist = call.getString("artist", "");
        s.playing = Boolean.TRUE.equals(call.getBoolean("playing", false));
        Double p = call.getDouble("position", 0.0);
        Double d = call.getDouble("duration", 0.0);
        s.pos = p == null ? 0 : p.longValue();
        s.dur = d == null ? 0 : d.longValue();
        if (call.hasOption("art")) {
            s.setArt = true;
            String b64 = call.getString("art", "");
            if (b64 != null && !b64.isEmpty()) {
                try {
                    byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                    Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    s.art = bmp;
                } catch (Exception ignored) { }
            }
        }
        PlaybackService svc = PlaybackService.instance;
        if (svc != null) {
            svc.update(s);
        } else if (s.playing) {
            PlaybackService.pending = s;
            Context c = getContext();
            Intent i = new Intent(c, PlaybackService.class).setAction("start");
            try {
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
                else c.startService(i);
            } catch (Exception ignored) { }
        }
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        PlaybackService svc = PlaybackService.instance;
        if (svc != null) svc.shutdown();
        call.resolve();
    }
}
