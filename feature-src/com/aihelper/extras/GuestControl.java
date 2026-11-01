package com.aihelper.extras;

import android.content.Context;
import android.content.SharedPreferences;

/** Separate durable stop latch survives a failed encrypted snapshot update. */
public final class GuestControl {
    private static volatile boolean stopWriteFailed;
    private GuestControl() {}
    private static SharedPreferences preferences(Context context) { return context.getApplicationContext().getSharedPreferences("eva_guest_control", Context.MODE_PRIVATE); }
    public static boolean blocked(Context context) { return stopWriteFailed || preferences(context).getBoolean("blocked", false); }
    public static boolean stopWriteFailed() { return stopWriteFailed; }
    public static void setBlocked(Context context, boolean value) {
        if (!preferences(context).edit().putBoolean("blocked", value).commit()) {
            stopWriteFailed = true;
            throw new IllegalStateException("Could not persist guest automation stop state");
        }
        stopWriteFailed = false;
    }
}
