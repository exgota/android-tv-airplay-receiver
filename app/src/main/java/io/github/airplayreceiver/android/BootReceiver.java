package io.github.airplayreceiver.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the receiver service after the device boots, when the user has left that enabled. */
public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) && new ReceiverSettings(context).startOnBoot()) {
            ReceiverService.start(context);
        }
    }
}
