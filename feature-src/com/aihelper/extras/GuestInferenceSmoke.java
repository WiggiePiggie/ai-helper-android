package com.aihelper.extras;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import java.util.EnumSet;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Arrays;
import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/**
 * Self-target instrumentation. The default operation tests raw-model classification
 * with synthetic data and opens no UI. Explicit status initializes the owner-requested
 * upgrade and returns control metadata; open_setup launches only Eva's setup activity.
 * No operation reads guest notifications, invokes a reply transport or sends a message.
 */
public final class GuestInferenceSmoke extends Instrumentation {
    private static final String[] EXAMPLES = {
            "check_in", "checkout_paraphrase", "parking_wifi", "refund", "greek_wifi", "greeting"
    };
    private String operation = "classify";
    private String exampleName = "check_in";

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        if (arguments != null) {
            operation = arguments.getString("operation", "classify");
            exampleName = arguments.getString("example", "check_in");
        }
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        boolean passed = false;
        long started = System.nanoTime();
        try {
            result.putString("operation", operation);
            if ("enable_smart".equals(operation)) {
                // Owner-authorized setup operation, without reading or sending messages.
                String reply = FeatureExtension.tryHandle(getTargetContext(), "enable automatic guest replies");
                HostAutomationCore core = FeatureExtension.engine(getTargetContext());
                passed = core.isEnabled() && !core.isEmergencyStopped() && !GuestControl.blocked(getTargetContext())
                        && !getTargetContext().getSharedPreferences("ai_helper_safety", 0).getBoolean("stopped", false);
                for (HostAutomationCore.Platform platform : HostAutomationCore.Platform.values())
                    passed &= core.getConfig(platform).mode == HostAutomationCore.Mode.SMART;
                result.putBoolean("enabled", passed);
                result.putString("control_reply", reply);
            } else if ("status".equals(operation)) {
                HostAutomationCore core = FeatureExtension.engine(getTargetContext());
                result.putBoolean("enabled", core.isEnabled());
                result.putBoolean("emergency_stopped", core.isEmergencyStopped());
                result.putBoolean("blocked", GuestControl.blocked(getTargetContext()));
                result.putBoolean("notification_access", FeatureExtension.hasNotificationAccess(getTargetContext()));
                result.putBoolean("listener_connected", FeatureExtension.listenerConnected(getTargetContext()));
                result.putBoolean("eva_stopped", getTargetContext().getSharedPreferences("ai_helper_safety", 0).getBoolean("stopped", false));
                int confirmedPlatforms = 0;
                for (HostAutomationCore.Platform platform : HostAutomationCore.Platform.values()) {
                    HostAutomationCore.Config config = core.getConfig(platform);
                    boolean confirmed = FeatureExtension.factsConfirmed(getTargetContext(), platform);
                    int factCount = 0;
                    for (String value : config.values.values())
                        if (value != null && value.trim().length() > 0) factCount++;
                    result.putString(platform.name() + "_mode", config.mode.name());
                    result.putBoolean(platform.name() + "_facts_confirmed", confirmed);
                    result.putInt(platform.name() + "_configured_fact_count", factCount);
                    if (confirmed) confirmedPlatforms++;
                }
                result.putInt("confirmed_platforms", confirmedPlatforms);
                passed = true;
            } else if ("open_setup".equals(operation)) {
                Activity setup = startActivitySync(new Intent(getTargetContext(), GuestSetupActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                waitForIdleSync();
                final int[] fields = new int[] { 0 };
                runOnMainSync(new Runnable() {
                    public void run() { fields[0] = editFields(setup.getWindow().getDecorView()); }
                });
                result.putInt("edit_field_count", fields[0]);
                result.putBoolean("setup_opened", true);
                passed = setup instanceof GuestSetupActivity && fields[0] == 13;
            } else if ("import_bedena".equals(operation)) {
                JSONObject profile = readPublicProfile();
                JSONObject platforms = profile.getJSONObject("platforms");
                Map<HostAutomationCore.Platform,Map<String,String>> values = new EnumMap<HostAutomationCore.Platform,Map<String,String>>(HostAutomationCore.Platform.class);
                java.util.List<String> allowed = Arrays.asList("property_name", "check_in_time", "check_out_time",
                    "wifi_details", "parking_details", "house_rules", "breakfast_details", "pet_policy");
                for (HostAutomationCore.Platform platform : HostAutomationCore.Platform.values()) {
                    JSONObject facts = platforms.getJSONObject(platform.name()).getJSONObject("facts");
                    Map<String,String> copy = new LinkedHashMap<String,String>();
                    java.util.Iterator<String> keys = facts.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        Object value = facts.get(key);
                        if (!allowed.contains(key) || !(value instanceof String)) throw new IllegalArgumentException("Unsupported public fact");
                        copy.put(key, (String)value);
                    }
                    values.put(platform, copy);
                }
                boolean imported = FeatureExtension.importPublicProfile(getTargetContext(), profile.getString("profile_id"), values);
                result.putBoolean("imported", imported);
                HostAutomationCore core = FeatureExtension.engine(getTargetContext());
                passed = core.isEnabled() && !GuestControl.blocked(getTargetContext());
                for (HostAutomationCore.Platform platform : HostAutomationCore.Platform.values()) {
                    HostAutomationCore.Config saved = core.getConfig(platform);
                    passed &= FeatureExtension.factsConfirmed(getTargetContext(), platform) && "Bedena House".equals(saved.values.get("property_name"));
                    result.putInt(platform.name() + "_configured_fact_count", saved.values.size());
                }
            } else if ("property_preview".equals(operation)) {
                HostAutomationCore core = FeatureExtension.engine(getTargetContext());
                passed = true;
                for (HostAutomationCore.Platform platform : HostAutomationCore.Platform.values()) {
                    Map<String,String> facts = core.getConfig(platform).values;
                    SmartGuestPolicy.Decision checkIn = SmartGuestPolicy.plan("What time is check-in?", facts);
                    SmartGuestPolicy.Decision wifi = SmartGuestPolicy.plan("Is Wi-Fi available?", facts);
                    SmartGuestPolicy.Decision password = SmartGuestPolicy.plan("What is the Wi-Fi password?", facts);
                    boolean previewPassed = FeatureExtension.factsConfirmed(getTargetContext(), platform)
                        && checkIn.kind == SmartGuestPolicy.Kind.ROUTINE && "GROUNDED_ROUTINE".equals(checkIn.reason)
                        && "GROUNDED_ROUTINE".equals(wifi.reason) && password.allowedFacts.get("wifi_password") == null
                        && !"GROUNDED_ROUTINE".equals(password.reason) && !facts.containsKey("wifi_name");
                    result.putString(platform.name() + "_check_in", checkIn.allowedFacts.get("check_in_time"));
                    result.putBoolean(platform.name() + "_public_preview_passed", previewPassed);
                    passed &= previewPassed;
                }
            } else if ("classify".equals(operation)) {
                Example example = example(exampleName);
                if (example == null) result.putString("error_class", "UnsupportedExample");
                else {
                    Bundle classified = classify(example);
                    result.putAll(classified);
                    passed = classified.getBoolean("passed", false);
                }
            } else if ("classify_suite".equals(operation)) {
                int passedCount = 0;
                for (String name : EXAMPLES) {
                    Bundle classified = classify(example(name));
                    result.putBundle(name, classified);
                    if (classified.getBoolean("passed", false)) passedCount++;
                    // Per-scenario progress contains synthetic output only.
                    sendStatus(0, classified);
                }
                result.putInt("scenario_count", EXAMPLES.length);
                result.putInt("passed_count", passedCount);
                passed = passedCount == EXAMPLES.length;
            } else {
                result.putString("error_class", "UnsupportedOperation");
            }
        } catch (Throwable failed) {
            // No input, output or exception message is logged on failure.
            result.putString("error_class", failed.getClass().getSimpleName());
        }
        result.putLong("elapsed_ms", (System.nanoTime() - started) / 1000000L);
        result.putBoolean("passed", passed);
        finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private JSONObject readPublicProfile() throws Exception {
        File file = new File(getTargetContext().getFilesDir(), "bedena-house-public-facts.json");
        if (file.length() < 1 || file.length() > 32768) throw new IllegalArgumentException("Unexpected profile length");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] block = new byte[2048];
            int count;
            while ((count = input.read(block)) != -1) {
                if (bytes.size() + count > 32768) throw new IllegalArgumentException("Unexpected profile length");
                bytes.write(block, 0, count);
            }
        }
        return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
    }

    private static int editFields(android.view.View view) {
        int count = view instanceof android.widget.EditText ? 1 : 0;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) count += editFields(group.getChildAt(index));
        }
        return count;
    }

    private Bundle classify(Example example) {
        Bundle result = new Bundle();
        result.putString("example", example.id);
        boolean passed = false;
        long started = System.nanoTime();
        try {
            String user = "{\"guest_message\":" + JSONObject.quote(example.message) + "}";
            String raw = GuestLocalInference.generate(getTargetContext(),
                    SmartIntent.SYSTEM_PROMPT, user, 90000L);
            result.putBoolean("available", raw != null);
            result.putBoolean("quarantined", GuestLocalInference.isQuarantined());
            if (raw != null) {
                result.putString("raw_result", raw.substring(0, Math.min(raw.length(), 500)));
                SmartIntent classification = SmartIntent.parse(raw);
                result.putBoolean("parsed", classification != null);
                passed = classification != null && classification.topics.equals(example.topics)
                        && classification.language == example.language
                        && classification.ownerRequired == example.ownerRequired;
            }
        } catch (Throwable failed) {
            result.putString("error_class", failed.getClass().getSimpleName());
        }
        result.putLong("elapsed_ms", (System.nanoTime() - started) / 1000000L);
        result.putBoolean("passed", passed);
        return result;
    }

    private static Example example(String id) {
        if ("check_in".equals(id)) return new Example(id,
                "Hello, what is the usual check-in time?", SmartGuestPolicy.Language.ENGLISH, false,
                SmartGuestPolicy.Topic.CHECK_IN);
        if ("checkout_paraphrase".equals(id)) return new Example(id,
                "By when do we need to leave the room on our last morning?", SmartGuestPolicy.Language.ENGLISH, false,
                SmartGuestPolicy.Topic.CHECK_OUT);
        if ("parking_wifi".equals(id)) return new Example(id,
                "Where can we park the car, and what is the Wi-Fi password?", SmartGuestPolicy.Language.ENGLISH, false,
                SmartGuestPolicy.Topic.PARKING, SmartGuestPolicy.Topic.WIFI);
        if ("refund".equals(id)) return new Example(id,
                "I would like a full refund.", SmartGuestPolicy.Language.ENGLISH, true,
                SmartGuestPolicy.Topic.FINANCIAL);
        if ("greek_wifi".equals(id)) return new Example(id,
                "Ποιος είναι ο κωδικός του Wi-Fi;", SmartGuestPolicy.Language.GREEK, false,
                SmartGuestPolicy.Topic.WIFI);
        if ("greeting".equals(id)) return new Example(id,
                "Hello, thank you!", SmartGuestPolicy.Language.ENGLISH, false);
        return null;
    }

    private static final class Example {
        final String id, message;
        final SmartGuestPolicy.Language language;
        final boolean ownerRequired;
        final EnumSet<SmartGuestPolicy.Topic> topics = EnumSet.noneOf(SmartGuestPolicy.Topic.class);
        Example(String id, String message, SmartGuestPolicy.Language language, boolean ownerRequired,
                SmartGuestPolicy.Topic... topics) {
            this.id = id; this.message = message; this.language = language;
            this.ownerRequired = ownerRequired;
            for (SmartGuestPolicy.Topic topic : topics) this.topics.add(topic);
        }
    }
}
