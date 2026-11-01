package com.aihelper.extras;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Dependency-free smoke tests for the Java-only Eva components.
 * These do not exercise Android notifications, APK builds or model inference.
 */
public final class ExtensionPolicySmoke {
    private static int passed = 0;

    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError("FAIL: " + name);
        passed++;
        System.out.println("PASS: " + name);
    }

    private static Map<String, String> facts() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("check_in_time", "15:00");
        values.put("wifi_name", "DemoNetwork");
        values.put("wifi_password", "Demo-WiFi-Only");
        values.put("parking_details", "Free parking");
        return values;
    }

    private static void testPolicy() {
        SmartGuestPolicy.Decision normal = SmartGuestPolicy.plan("When is check-in?", facts());
        check(normal.kind == SmartGuestPolicy.Kind.ROUTINE
                && "15:00".equals(normal.allowedFacts.get("check_in_time")),
                "configured routine check-in fact");

        SmartGuestPolicy.Decision refund = SmartGuestPolicy.plan("Please refund my payment.", facts());
        check(refund.kind == SmartGuestPolicy.Kind.OWNER_REQUIRED
                && refund.allowedFacts.isEmpty(), "refund needs owner review");

        SmartGuestPolicy.Decision access = SmartGuestPolicy.plan("What is the door code?", facts());
        check(access.kind == SmartGuestPolicy.Kind.OWNER_REQUIRED
                && access.allowedFacts.isEmpty(), "access code needs owner review");

        SmartGuestPolicy.Decision emergency = SmartGuestPolicy.plan("There is a fire!", facts());
        check(emergency.kind == SmartGuestPolicy.Kind.OWNER_REQUIRED,
                "emergency needs owner review");

        SmartGuestPolicy.Decision noFacts = SmartGuestPolicy.plan("When is check-in?", Map.of());
        check(noFacts.kind == SmartGuestPolicy.Kind.ACKNOWLEDGE
                && noFacts.allowedFacts.isEmpty(), "never invent missing property facts");

        SmartGuestPolicy.Decision wifiName = SmartGuestPolicy.plan("What is the Wi-Fi network name?", facts());
        check("DemoNetwork".equals(wifiName.allowedFacts.get("wifi_name"))
                && !wifiName.allowedFacts.containsKey("wifi_password"),
                "network-name question does not expose Wi-Fi password");

        Map<String, String> unsafe = facts();
        unsafe.put("parking_details", "The door code 1234 is next to the parking gate");
        SmartGuestPolicy.Decision parking = SmartGuestPolicy.plan("Is parking available?", unsafe);
        check(parking.kind == SmartGuestPolicy.Kind.ACKNOWLEDGE
                && parking.allowedFacts.isEmpty(),
                "access codes embedded in other facts are not disclosed");

        SmartGuestPolicy.Decision modelEscalation = SmartGuestPolicy.plan(
                "When is check-in?", facts(), null, null, true);
        check(modelEscalation.kind == SmartGuestPolicy.Kind.OWNER_REQUIRED,
                "model escalation cannot be overridden by routine rule");
    }

    private static void testIntentParser() {
        SmartIntent valid = SmartIntent.parse(
            "{\"topics\":[\"CHECK_IN\"],\"language\":\"ENGLISH\",\"owner_required\":false}");
        check(valid != null && valid.topics.contains(SmartGuestPolicy.Topic.CHECK_IN)
                && !valid.ownerRequired, "valid strict intent JSON");

        check(SmartIntent.parse("The answer is {\"topics\":[],\"language\":\"ENGLISH\",\"owner_required\":false}") == null,
                "reject prose surrounding JSON");
        check(SmartIntent.parse("{\"topics\":[\"GREETING\"],\"language\":\"ENGLISH\",\"owner_required\":false}") == null,
                "reject unsupported topics");
        check(SmartIntent.parse("{\"topics\":[],\"language\":\"ENGLISH\",\"language\":\"GREEK\",\"owner_required\":false}") == null,
                "reject duplicate JSON keys");
        check(SmartIntent.parse("{\"topics\":[],\"language\":\"ENGLISH\"}") == null,
                "reject missing required fields");
    }

    private static void testQueue() {
        List<Runnable> scheduled = new ArrayList<>();
        Executor deferred = scheduled::add;
        List<String> processed = new ArrayList<>();
        CoalescingWorkQueue<String, String> queue = new CoalescingWorkQueue<>(
            2, deferred, processed::add, (value, error) -> {
                throw new AssertionError("Unexpected failure callback", error);
            });

        check(queue.offer("guest-a", "old") == CoalescingWorkQueue.Result.ENQUEUED,
                "queue adds first pending item");
        check(queue.offer("guest-a", "latest") == CoalescingWorkQueue.Result.REPLACED,
                "queue coalesces updates to the same pending key");
        check(queue.offer("guest-b", "second") == CoalescingWorkQueue.Result.ENQUEUED,
                "queue accepts second unique key");
        check(queue.offer("guest-c", "overflow") == CoalescingWorkQueue.Result.FULL,
                "queue enforces bounded capacity");
        check(scheduled.size() == 1, "only one drain is scheduled");
        scheduled.get(0).run();
        check(processed.equals(List.of("latest", "second")),
                "drain processes latest value in insertion order");
    }

    public static void main(String[] args) {
        testPolicy();
        testIntentParser();
        testQueue();
        System.out.println("All " + passed + " checks passed.");
    }
}
