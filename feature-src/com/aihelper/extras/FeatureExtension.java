package com.aihelper.extras;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.provider.Settings;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.Map;
import java.util.EnumMap;
import java.util.Locale;
import com.aihelper.extras.HostAutomationCore.Platform;
import com.aihelper.extras.HostAutomationCore.Config;
import com.aihelper.extras.HostAutomationCore.Mode;

/** Small entry points called from the recovered router and notification listener. */
public final class FeatureExtension {
    private static HostAutomationCore engine;
    private static volatile WeakReference<NotificationListenerService> connectedListener = new WeakReference<NotificationListenerService>(null);
    private static long lastRebindRequest;
    private static final ExecutorService WORKER = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<Runnable>(1), new ThreadFactory() {
        public Thread newThread(Runnable task) { Thread thread = new Thread(task, "EvaGuestRules"); thread.setDaemon(true); return thread; }
    }, new ThreadPoolExecutor.AbortPolicy());
    private static final class GuestWork {
        final Context listener;
        final StatusBarNotification notification;
        GuestWork(Context listener, StatusBarNotification notification) {
            this.listener = listener; this.notification = notification;
        }
    }
    private static final CoalescingWorkQueue<String, GuestWork> QUEUE = new CoalescingWorkQueue<String, GuestWork>(8, WORKER,
        new CoalescingWorkQueue.Handler<GuestWork>() {
            public void process(GuestWork work) { processNotification(work.listener, work.notification); }
        }, new CoalescingWorkQueue.FailureHandler<GuestWork>() {
            public void failed(GuestWork work, RuntimeException error) {
                operationalFailure(work.listener, error instanceof RejectedExecutionException ? "QUEUE_UNAVAILABLE" : "PROCESSING_ERROR");
            }
        });
    private FeatureExtension() {}
    static synchronized HostAutomationCore engine(Context context) {
        if (engine == null) {
            engine = new HostAutomationCore(new EncryptedHostStore(context), new NotificationReplyTransport(context.getApplicationContext()), HostAutomationCore.SYSTEM_CLOCK);
            // This upgrade implements the owner's explicit request to enable
            // autonomous replies once. Later launches respect saved pauses.
            SharedPreferences control = context.getSharedPreferences("eva_guest_control", 0);
            if (control.getInt("smart_requested_version", 0) < 1) {
                for (Platform platform : Platform.values()) {
                    Config old = engine.getConfig(platform);
                    Config smart = old.withMode(Mode.SMART);
                    if (old.mode == Mode.OFF && old.cooldownMillis == 300000L)
                        smart = smart.withSafetyLimits(old.freshnessMillis, 15000L);
                    engine.configure(platform, smart);
                }
                if (!stopped(context) && !engine.isEmergencyStopped()) {
                    engine.setEnabled(true);
                    GuestControl.setBlocked(context, false);
                }
                if (!control.edit().putInt("smart_requested_version", 1).commit()) {
                    GuestControl.setBlocked(context, true);
                    throw new IllegalStateException("Could not persist smart startup");
                }
            }
        }
        return engine;
    }
    public static synchronized String tryHandle(Context context, String message) {
        if (message == null) return null;
        String normalized = message.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        boolean setup = normalized.equals("host setup") || normalized.equals("guest settings")
                || normalized.equals("guest setup") || normalized.equals("smart guest setup");
        boolean enable = normalized.equals("enable automatic guest replies")
                || normalized.equals("enable smart guest replies") || normalized.equals("host smart");
        if (!HostCommands.recognizes(message) && !setup && !enable) return null;
        try {
            if (setup) {
                context.startActivity(new Intent(context, GuestSetupActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return "Guest settings opened. Save the property details once; Eva uses them automatically for new messages.\n\n" + readiness(context);
            }
            String command = message.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
            if (command.equals("host pause") || command.equals("host stop")) GuestControl.setBlocked(context, true);
            HostAutomationCore core = engine(context);
            if (enable) {
                GuestControl.setBlocked(context, true);
                for (Platform platform : Platform.values()) core.configure(platform, core.getConfig(platform).withMode(Mode.SMART));
                core.resume();
                // Explicit enable commands supersede a previously saved STOP.
                // Changing this switch starts no voice or accessibility service.
                if (!context.getSharedPreferences("ai_helper_safety", 0).edit().putBoolean("stopped", false).commit())
                    throw new IllegalStateException("Could not save Eva resume state");
                GuestControl.setBlocked(context, false);
                requestListenerConnection(context);
                showSetupNotification(context);
                return "Smart guest replies are enabled for Airbnb and Booking Pulse. Eva chooses replies herself from saved details.\n\n" + readiness(context);
            }
            String reply = new HostCommands(core).handle(message);
            if (command.equals("host resume") && core.isEnabled() && !core.isEmergencyStopped()) {
                GuestControl.setBlocked(context, false);
                requestListenerConnection(context);
            }
            if (GuestControl.stopWriteFailed()) return "Eva could not save the stop state. Replies are blocked in this process. Disable Eva's notification access before restarting, then check available storage.";
            if (GuestControl.blocked(context) && core.isEnabled()) reply = "Guest automation is paused. Use host resume after setup.\n\n" + reply;
            if (command.equals("host status")) reply += "\n\n" + readiness(context);
            return reply;
        } catch (RuntimeException error) {
            if (GuestControl.stopWriteFailed()) return "Eva could not save the stop state. Replies are blocked in this process. Disable Eva's notification access before restarting, then check available storage.";
            return "Guest automation is unavailable: " + error.getClass().getSimpleName() + ". Your existing Eva commands are still available.";
        }
    }
    public static void onNotificationPosted(final Context listener, final StatusBarNotification notification) {
        if (notification == null || Platform.forPackage(notification.getPackageName()) == null || stopped(listener) || GuestControl.blocked(listener)) return;
        try {
            if (!listener.getSharedPreferences("host_notifications", 0).getBoolean("enabled", true)) return;
            CoalescingWorkQueue.Result result = QUEUE.offer(notification.getKey(), new GuestWork(listener, notification));
            if (result == CoalescingWorkQueue.Result.FULL) operationalFailure(listener, "QUEUE_FULL");
        } catch (RuntimeException failed) {
            operationalFailure(listener, "PROCESSING_ERROR");
        }
    }
    private static void processNotification(Context listener, StatusBarNotification notification) {
        if (stopped(listener) || GuestControl.blocked(listener) || !listener.getSharedPreferences("host_notifications", 0).getBoolean("enabled", true)) return;
        HostAutomationCore.IncomingMessage incoming = NotificationReplyTransport.extract(listener, notification);
        if (incoming == null) return;
        // Store only capability metadata here, never guest text or names.
        listener.getSharedPreferences("eva_host_readiness", 0).edit()
            .putBoolean(notification.getPackageName() + ".reply", incoming.replyCapable)
            .putLong(notification.getPackageName() + ".seen", System.currentTimeMillis()).apply();
        SmartReplyCoordinator.handle(listener, engine(listener), incoming, notification);
    }
    private static void operationalFailure(Context context, String reason) {
        try {
            context.getSharedPreferences("eva_host_readiness", 0).edit()
                .putBoolean("QUEUE_FULL".equals(reason) ? "queue_full" : "processing_error", true).apply();
            OwnerAttention.operationalWarning(context, reason);
        } catch (RuntimeException ignored) { /* Existing listener monitoring must remain available. */ }
    }
    public static void onListenerConnected(NotificationListenerService listener) {
        connectedListener = new WeakReference<NotificationListenerService>(listener);
        try {
            listener.getSharedPreferences("eva_host_readiness", 0).edit().putLong("listener_connected_at", System.currentTimeMillis()).apply();
            // The guest core's freshness, activation and durable duplicate checks
            // apply equally to active notifications discovered after a reconnect.
            StatusBarNotification[] active = listener.getActiveNotifications();
            if (active != null) for (StatusBarNotification notification : active) onNotificationPosted(listener, notification);
        } catch (RuntimeException failed) { operationalFailure(listener, "PROCESSING_ERROR"); }
    }
    public static void onListenerDisconnected(Context listener) {
        if (connectedListener.get() == listener) connectedListener.clear();
        requestListenerConnection(listener);
    }
    static boolean listenerConnected(Context context) {
        return connectedListener.get() != null && hasNotificationAccess(context);
    }
    static synchronized void requestListenerConnection(Context context) {
        try {
            if (!hasNotificationAccess(context) || connectedListener.get() != null) return;
            long now = SystemClock.elapsedRealtime();
            if (lastRebindRequest != 0L && now - lastRebindRequest < 15000L) return;
            lastRebindRequest = now;
            NotificationListenerService.requestRebind(new ComponentName(context.getPackageName(), "com.aihelper.control.UniversalNotificationService"));
        } catch (RuntimeException ignored) { /* The owner can reopen notification access settings. */ }
    }
    private static boolean stopped(Context context) { return context.getSharedPreferences("ai_helper_safety", 0).getBoolean("stopped", false); }
    static boolean hasNotificationAccess(Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
        ComponentName expected = new ComponentName(context.getPackageName(), "com.aihelper.control.UniversalNotificationService");
        if (enabled != null) for (String component : enabled.split(":")) if (expected.equals(ComponentName.unflattenFromString(component))) return true;
        return false;
    }
    static boolean factsConfirmed(Context context, Platform platform) {
        return context.getSharedPreferences("eva_guest_scope", 0).getBoolean(platform.name() + ".confirmed", false);
    }
    static synchronized void saveSmartSetup(Context context, Platform platform, Map<String,String> facts, boolean confirmed) {
        HostAutomationCore core = engine(context);
        Config old = core.getConfig(platform);
        Config next = new Config(Mode.SMART, old.templates, facts, old.quietHours, old.freshnessMillis, old.cooldownMillis);
        GuestControl.setBlocked(context, true);
        SharedPreferences scope = context.getSharedPreferences("eva_guest_scope", 0);
        if (!scope.edit().putBoolean(platform.name() + ".confirmed", false).commit()) throw new IllegalStateException("Could not save property scope");
        core.configure(platform, next);
        if (!scope.edit().putBoolean(platform.name() + ".confirmed", confirmed).commit()) throw new IllegalStateException("Could not save property scope");
        core.resume();
        GuestControl.setBlocked(context, false);
        requestListenerConnection(context);
    }
    /** Explicit one-time import; later restarts never replace the owner's edits. */
    static synchronized boolean importPublicProfile(Context context, String profileId, Map<Platform, Map<String,String>> profile) {
        if (!"bedena-house-public-2026-10-03".equals(profileId) || profile.size() != Platform.values().length)
            throw new IllegalArgumentException("Unexpected public profile");
        SharedPreferences imported = context.getSharedPreferences("eva_guest_profile", 0);
        if (imported.getBoolean(profileId, false)) return false;
        HostAutomationCore core = engine(context);
        Map<Platform, Config> next = new EnumMap<Platform, Config>(Platform.class);
        for (Platform platform : Platform.values()) {
            Config old = core.getConfig(platform);
            if (!old.values.isEmpty()) throw new IllegalStateException("Existing property details need review before importing");
            Map<String,String> facts = profile.get(platform);
            if (facts == null || !"Bedena House".equals(facts.get("property_name"))) throw new IllegalArgumentException("Missing property details");
            next.put(platform, new Config(Mode.SMART, old.templates, facts, old.quietHours, old.freshnessMillis, old.cooldownMillis));
        }
        GuestControl.setBlocked(context, true);
        SharedPreferences scope = context.getSharedPreferences("eva_guest_scope", 0);
        SharedPreferences.Editor clear = scope.edit();
        for (Platform platform : Platform.values()) clear.putBoolean(platform.name() + ".confirmed", false);
        if (!clear.commit()) throw new IllegalStateException("Could not clear property scope");
        for (Platform platform : Platform.values()) core.configure(platform, next.get(platform));
        // The owner confirmed in chat that both apps handle only Bedena House.
        SharedPreferences.Editor confirmed = scope.edit();
        for (Platform platform : Platform.values()) confirmed.putBoolean(platform.name() + ".confirmed", true);
        if (!confirmed.commit() || !imported.edit().putBoolean(profileId, true).commit())
            throw new IllegalStateException("Could not persist public profile confirmation");
        core.resume();
        GuestControl.setBlocked(context, false);
        requestListenerConnection(context);
        showSetupNotification(context);
        return true;
    }
    static String attentionSummary(Context context) { return OwnerAttention.summary(context); }
    static void clearAttention(Context context) { OwnerAttention.clear(context); }
    static void showSetupNotification(Context context) {
        if (hasNotificationAccess(context) && factsConfirmed(context, Platform.AIRBNB)
                && factsConfirmed(context, Platform.PULSE)) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        String channel = "eva_guest_setup";
        manager.createNotificationChannel(new NotificationChannel(channel, "Guest assistant setup", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(context, 7000,
                new Intent(context, GuestSetupActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        manager.notify("eva_guest_setup", 7000, new Notification.Builder(context, channel)
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Eva guest replies")
                .setContentText(factsConfirmed(context, Platform.AIRBNB) && factsConfirmed(context, Platform.PULSE)
                    ? "Property details saved. Tap to enable notification access."
                    : "Tap to save property details and enable notification access.")
                .setContentIntent(open).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build());
    }
    private static String readiness(Context context) {
        StringBuilder out = new StringBuilder("Notification access: ").append(hasNotificationAccess(context) ? "enabled" : "enable in Guest settings");
        out.append("\nGuest listener: ").append(listenerConnected(context) ? "connected" : hasNotificationAccess(context) ? "waiting for Android to connect" : "notification access required");
        if (stopped(context)) out.append("\nEva is stopped. Resume autonomous mode on Eva's main screen to process guest messages.");
        out.append("\nHost alerts: ").append(context.getSharedPreferences("host_notifications", 0).getBoolean("enabled", true) ? "enabled" : "disabled; use enable host alerts");
        SharedPreferences seen = context.getSharedPreferences("eva_host_readiness", 0);
        for (Platform p : Platform.values()) {
            out.append("\n").append(p == Platform.AIRBNB ? "Airbnb" : "Booking Pulse").append(": ");
            try { context.getPackageManager().getPackageInfo(p.packageName, 0); out.append("installed"); }
            catch (android.content.pm.PackageManager.NameNotFoundException missing) { out.append("not found"); }
            out.append(factsConfirmed(context, p) ? " · saved property details in use" : " · property details need setup");
            if (!seen.contains(p.packageName + ".seen")) out.append(" · reply action not checked yet");
            else out.append(seen.getBoolean(p.packageName + ".reply", false) ? " · last guest notification had a reply action" : " · last guest notification needs a draft");
        }
        if (seen.getBoolean("processing_error", false)) out.append("\nA guest-rule processing error occurred; inspect drafts/history before resuming.");
        if (seen.getBoolean("queue_full", false)) out.append("\nA burst of notifications exceeded the queue; check the host inbox for unanswered messages.");
        if (seen.getBoolean("local_model_quarantined", false)) out.append("\nLocal AI needs an Eva restart. Fact-based fallback replies remain available.");
        return out.toString();
    }
}
