package com.example.beamapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            Log.i(TAG, "Boot completed — starting BeamService with autostart");
            Intent svc = new Intent(context, BeamService.class);
            svc.putExtra("autostart", true);
            context.startForegroundService(svc);
        }
    }
}
