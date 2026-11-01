package com.aihelper.extras;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.service.notification.StatusBarNotification;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.aihelper.extras.HostAutomationCore.Record;
import com.aihelper.extras.HostAutomationCore.Platform;

/** Encrypted, bounded owner handoffs; incoming guest text is never persisted. */
final class OwnerAttention {
    private static final String CHANNEL = "eva_guest_attention";
    private static final String OPERATIONAL_KEY = "@operational";
    private static final String OPERATIONAL_TAG = "eva_guest_operational";
    private static final int OPERATIONAL_ID = 7002;
    private OwnerAttention() {}
    private static EncryptedHostStore store(Context context) {
        return new EncryptedHostStore(context, "eva_guest_attention", "eva_guest_attention_v1");
    }
    static synchronized void record(Context context, Record record, String reason,
                                    StatusBarNotification notification) {
        Map<String,String> items = store(context).load();
        if (items.containsKey(record.fingerprint)) return;
        // Restore chronological ordering independently of JSONObject key order.
        List<String> evicted = new ArrayList<String>();
        while (guestCount(items) >= 40) {
            String oldest = null; long stamp = Long.MAX_VALUE;
            for (Map.Entry<String,String> item : items.entrySet()) {
                if (OPERATIONAL_KEY.equals(item.getKey())) continue;
                long value = time(item.getValue());
                if (oldest == null || value < stamp) { oldest = item.getKey(); stamp = value; }
            }
            items.remove(oldest);
            evicted.add(oldest);
        }
        items.put(record.fingerprint, System.currentTimeMillis() + "|" + record.platform.name() + "|" + reason);
        store(context).save(items);
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        // Cancel old alerts only after their replacement snapshot is durable.
        for (String fingerprint : evicted) manager.cancel(fingerprint, 7001);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Guest requests needing you", NotificationManager.IMPORTANCE_HIGH));
        PendingIntent open = null;
        if (notification != null && notification.getNotification() != null) {
            PendingIntent candidate = notification.getNotification().contentIntent;
            if (candidate != null && record.platform.packageName.equals(candidate.getCreatorPackage())
                    && notification.getUid() == candidate.getCreatorUid()) open = candidate;
        }
        if (open == null) open = PendingIntent.getActivity(context, 0,
                new Intent(context, GuestSetupActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String label = record.platform == Platform.AIRBNB ? "Airbnb" : "Booking Pulse";
        String detail = describe(reason);
        Notification alert = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(label + " needs your help")
                .setContentText(detail).setStyle(new Notification.BigTextStyle().bigText(detail))
                .setContentIntent(open).setAutoCancel(true).setCategory(Notification.CATEGORY_MESSAGE)
                .setVisibility(Notification.VISIBILITY_PRIVATE).build();
        manager.notify(record.fingerprint, 7001, alert);
    }
    /** One bounded type/time warning; never receives guest text or an exception message. */
    static synchronized void operationalWarning(Context context, String reason) {
        if (!operationalReason(reason)) return;
        try {
            Map<String,String> items = store(context).load();
            items.put(OPERATIONAL_KEY, System.currentTimeMillis() + "|" + reason);
            store(context).save(items);
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Guest requests needing you", NotificationManager.IMPORTANCE_HIGH));
            PendingIntent open = PendingIntent.getActivity(context, OPERATIONAL_ID,
                    new Intent(context, GuestSetupActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            String detail = describeOperational(reason);
            manager.notify(OPERATIONAL_TAG, OPERATIONAL_ID, new Notification.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Eva guest replies need attention")
                    .setContentText(detail).setStyle(new Notification.BigTextStyle().bigText(detail))
                    .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_ERROR)
                    .setVisibility(Notification.VISIBILITY_PRIVATE).build());
        } catch (RuntimeException unavailable) {
            // Persistence already activates the durable stop latch on failure.
            // Reporting must not crash the notification listener.
        }
    }
    static synchronized String summary(Context context) {
        Map<String,String> items = store(context).load();
        if (items.isEmpty()) return "No guest requests need your attention yet.";
        int guests = guestCount(items);
        StringBuilder out = new StringBuilder(guests == 0 ? "No guest requests need your attention yet."
                : guests + " guest requests need your attention:");
        int shown = 0;
        for (Map.Entry<String,String> item : items.entrySet()) {
            if (OPERATIONAL_KEY.equals(item.getKey())) continue;
            String value = item.getValue();
            String[] fields = value.split("\\|", 3);
            if (fields.length != 3) continue;
            out.append("\n").append("AIRBNB".equals(fields[1]) ? "Airbnb" : "Booking Pulse")
                    .append(" · ").append(describe(fields[2]));
            if (++shown == 10) break;
        }
        String warning = items.get(OPERATIONAL_KEY);
        if (warning != null) {
            String[] fields = warning.split("\\|", 2);
            if (fields.length == 2 && operationalReason(fields[1])) {
                out.append("\n\n").append(describeOperational(fields[1]));
                long stamp = time(warning);
                if (stamp > 0L) out.append("\nLast issue: ").append(DateFormat.getDateTimeInstance(
                        DateFormat.SHORT, DateFormat.SHORT).format(new Date(stamp)));
            }
        }
        return out.toString();
    }
    static synchronized void clear(Context context) {
        Map<String,String> items = store(context).load();
        store(context).save(new LinkedHashMap<String,String>());
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            for (String fingerprint : items.keySet())
                if (!OPERATIONAL_KEY.equals(fingerprint)) manager.cancel(fingerprint, 7001);
            manager.cancel(OPERATIONAL_TAG, OPERATIONAL_ID);
        }
    }
    private static int guestCount(Map<String,String> items) {
        return items.size() - (items.containsKey(OPERATIONAL_KEY) ? 1 : 0);
    }
    private static boolean operationalReason(String reason) {
        return "QUEUE_FULL".equals(reason) || "QUEUE_UNAVAILABLE".equals(reason) || "PROCESSING_ERROR".equals(reason);
    }
    private static String describeOperational(String reason) {
        if ("QUEUE_FULL".equals(reason)) return "Several guest messages arrived at once. Check Airbnb and Booking Pulse for unanswered messages.";
        if ("QUEUE_UNAVAILABLE".equals(reason)) return "Eva could not start processing a guest message. Check the host inbox and guest settings.";
        return "Eva could not finish processing a guest message. Check the host inbox and guest settings.";
    }
    private static long time(String value) {
        try { return Long.parseLong(value.substring(0, value.indexOf('|'))); }
        catch (RuntimeException malformed) { return 0L; }
    }
    private static String describe(String reason) {
        if ("EMERGENCY".equals(reason)) return "An urgent guest request needs your attention. Open the conversation.";
        if ("BOOKING_CHANGE".equals(reason)) return "A guest requested a booking or arrival change.";
        if ("FINANCIAL_REQUEST".equals(reason)) return "A guest requested a refund, cancellation or payment decision.";
        if ("SENSITIVE_ACCESS".equals(reason)) return "A guest needs help accessing the property.";
        if ("NO_CONFIGURED_FACTS".equals(reason) || "PARTIAL_CONFIGURED_FACTS".equals(reason)) return "A guest asked for details Eva has not been given yet.";
        if ("NO_REPLY_CAPABILITY".equals(reason)) return "This message has no supported automatic reply. Open the conversation to respond.";
        if ("NO_SUPPORTED_TOPIC".equals(reason)) return "A guest asked something Eva could not answer from the saved details.";
        if ("QUIET_HOURS".equals(reason)) return "A guest message arrived during your quiet hours.";
        return "A guest request needs your review. Open the conversation.";
    }
}
