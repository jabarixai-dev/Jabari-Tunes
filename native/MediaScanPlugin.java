package com.jabari.tunes;

import android.Manifest;
import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Lists the music on the phone (MediaStore) and copies chosen songs into the app. */
@CapacitorPlugin(
    name = "MediaScan",
    permissions = {
        @Permission(strings = { Manifest.permission.READ_MEDIA_AUDIO }, alias = "audio"),
        @Permission(strings = { Manifest.permission.READ_EXTERNAL_STORAGE }, alias = "legacy")
    }
)
public class MediaScanPlugin extends Plugin {

    private String alias() {
        return Build.VERSION.SDK_INT >= 33 ? "audio" : "legacy";
    }

    @PluginMethod
    public void hasPermission(PluginCall call) {
        JSObject o = new JSObject();
        o.put("granted", getPermissionState(alias()) == PermissionState.GRANTED);
        call.resolve(o);
    }

    @PluginMethod
    public void scan(PluginCall call) {
        if (getPermissionState(alias()) != PermissionState.GRANTED) {
            requestPermissionForAlias(alias(), call, "afterPermission");
            return;
        }
        doScan(call);
    }

    @PermissionCallback
    private void afterPermission(PluginCall call) {
        if (getPermissionState(alias()) == PermissionState.GRANTED) doScan(call);
        else call.reject("denied");
    }

    private void doScan(PluginCall call) {
        String[] proj = {
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DISPLAY_NAME
        };
        // Songs only: skip short clips, ringtones, alarms, notification sounds, WhatsApp audio and recordings.
        String sel = MediaStore.Audio.Media.DURATION + ">=45000 AND "
            + MediaStore.Audio.Media.IS_RINGTONE + "=0 AND "
            + MediaStore.Audio.Media.IS_NOTIFICATION + "=0 AND "
            + MediaStore.Audio.Media.IS_ALARM + "=0";
        if (Build.VERSION.SDK_INT >= 29) {
            String rp = MediaStore.Audio.Media.RELATIVE_PATH;
            sel += " AND (" + rp + " IS NULL OR (" + rp + " NOT LIKE '%WhatsApp%' AND " + rp + " NOT LIKE '%Recordings%'))";
        }
        JSArray songs = new JSArray();
        try (Cursor c = getContext().getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, sel, null,
                MediaStore.Audio.Media.DATE_ADDED + " DESC")) {
            if (c != null) {
                while (c.moveToNext()) {
                    JSObject s = new JSObject();
                    s.put("id", String.valueOf(c.getLong(0)));
                    s.put("title", c.getString(1));
                    s.put("artist", c.getString(2));
                    s.put("album", c.getString(3));
                    s.put("duration", c.getLong(4));
                    s.put("size", c.getLong(5));
                    s.put("name", c.getString(6));
                    songs.put(s);
                }
            }
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
            return;
        }
        JSObject out = new JSObject();
        out.put("songs", songs);
        call.resolve(out);
    }

    @PluginMethod
    public void copyToCache(PluginCall call) {
        String id = call.getString("id");
        String name = call.getString("name", "track.mp3");
        if (id == null) { call.reject("missing id"); return; }
        Uri uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, Long.parseLong(id));
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : ".mp3";
        File out = new File(getContext().getCacheDir(), "imp_" + id + ext);
        try (InputStream in = getContext().getContentResolver().openInputStream(uri);
             OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
            return;
        }
        JSObject o = new JSObject();
        o.put("path", out.getAbsolutePath());
        call.resolve(o);
    }

    @PluginMethod
    public void cleanup(PluginCall call) {
        String p = call.getString("path");
        if (p != null && p.startsWith(getContext().getCacheDir().getAbsolutePath())) new File(p).delete();
        call.resolve();
    }
}
