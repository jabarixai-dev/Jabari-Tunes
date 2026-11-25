package com.jabari.tunes;

import android.os.Bundle;
import android.webkit.WebView;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(MediaScanPlugin.class);
        super.onCreate(savedInstanceState);
    }

    // Keep the web view (and the music) running when the screen turns off or the app is minimised.
    @Override
    public void onPause() {
        super.onPause();
        if (getBridge() != null) {
            WebView wv = getBridge().getWebView();
            if (wv != null) {
                wv.onResume();
                wv.resumeTimers();
            }
        }
    }
}
