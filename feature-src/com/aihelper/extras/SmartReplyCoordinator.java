package com.aihelper.extras;

import android.content.Context;
import android.service.notification.StatusBarNotification;
import java.util.Collections;
import java.util.Map;
import org.json.JSONObject;
import com.aihelper.extras.HostAutomationCore.*;

/** Background understanding is isolated from Eva's owner-command router. */
final class SmartReplyCoordinator {
    private SmartReplyCoordinator() {}

    static Result handle(Context context, HostAutomationCore core, IncomingMessage incoming,
                         StatusBarNotification notification) {
        Platform platform = Platform.forPackage(incoming.packageName);
        Config config = core.getConfig(platform);
        if (config.mode != Mode.SMART || !core.needsSmartReply(incoming)) return core.onIncoming(incoming);
        Map<String,String> facts = FeatureExtension.factsConfirmed(context, platform)
                ? config.values : Collections.<String,String>emptyMap();
        // Hard policy decides urgent/sensitive requests immediately. Model hints
        // broaden routine language coverage but cannot downgrade this decision.
        SmartGuestPolicy.Decision policy = SmartGuestPolicy.plan(incoming.text, facts);
        if (policy.kind != SmartGuestPolicy.Kind.OWNER_REQUIRED && incoming.replyCapable
                && !"BENIGN_ACKNOWLEDGEMENT".equals(policy.reason)
                && !config.quietHours.contains(System.currentTimeMillis())
                && !GuestControl.blocked(context)) {
            String output = GuestLocalInference.generate(context, SmartIntent.SYSTEM_PROMPT,
                    "{\"guest_message\":" + JSONObject.quote(incoming.text) + "}", 35000L);
            SmartIntent intent = SmartIntent.parse(output);
            if (intent != null) policy = SmartGuestPolicy.plan(incoming.text, facts,
                    intent.topics, policy.language == SmartGuestPolicy.Language.ENGLISH
                            ? intent.language : policy.language, intent.ownerRequired);
            context.getSharedPreferences("eva_host_readiness", 0).edit()
                    .putBoolean("local_understanding", intent != null)
                    .putBoolean("local_model_quarantined", GuestLocalInference.isQuarantined()).apply();
        }
        // Only the facts-based policy's text is eligible to send. The model never
        // supplies a property fact, action, recipient or executable command.
        // The visible assistant signature also keeps credential whitespace away
        // from a host app's message-edge trimming.
        Result result = core.onIncoming(incoming.prepared(policy.fallbackReply + "\n\n— Eva", config));
        if (result.record != null && result.action != Action.SKIPPED
                && (policy.kind == SmartGuestPolicy.Kind.OWNER_REQUIRED
                || "PARTIAL_CONFIGURED_FACTS".equals(policy.reason)
                || "NO_CONFIGURED_FACTS".equals(policy.reason)
                || "NO_SUPPORTED_TOPIC".equals(policy.reason)
                || result.action != Action.SUBMITTED)) {
            String reason = result.action == Action.SUBMITTED ? policy.reason : result.reason.name();
            OwnerAttention.record(context, result.record, reason, notification);
        }
        return result;
    }
}
