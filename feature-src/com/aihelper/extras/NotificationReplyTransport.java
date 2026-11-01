package com.aihelper.extras;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Person;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import com.aihelper.host.HostNotificationEvent;
import com.aihelper.host.HostNotificationMonitor;
import com.aihelper.host.HostNotificationType;

import java.util.List;

/**
 * Submits user-configured host replies through the host app's inline reply action.
 * Automatic capability requires an explicit incoming MessagingStyle sender and
 * SEMANTIC_ACTION_REPLY. Plain notifications and untyped actions remain draft-only.
 * ACCEPTED means Android accepted the PendingIntent dispatch, never guest delivery.
 * Notification/action/listener references are ephemeral and must not be persisted.
 * Missing or stale reply capability is rejected; this transport never opens apps,
 * taps accessibility nodes, searches another conversation, or retries a dispatch.
 */
public final class NotificationReplyTransport implements HostAutomationCore.Sender {
    private final Context appContext;

    public NotificationReplyTransport(Context context) {
        if (context == null) throw new IllegalArgumentException("Context is required");
        Context application = context.getApplicationContext();
        this.appContext = application == null ? context : application;
    }

    /**
     * Call with the actual connected NotificationListenerService, not its application
     * Context, so the sender can later verify that the same notification is active.
     * Guest names come only from a MessagingStyle sender, never notification titles.
     */
    public static HostAutomationCore.IncomingMessage extract(Context context,
                                                             StatusBarNotification sbn) {
        if (context == null || sbn == null) return null;
        try {
            String sourcePackage = sbn.getPackageName();
            if (HostAutomationCore.Platform.forPackage(sourcePackage) == null) return null;
            Notification notification = sbn.getNotification();
            if (notification == null || isSummary(notification)) return null;
            HostNotificationEvent event = HostNotificationMonitor.INSTANCE
                    .classifyNotification(context, sbn);
            if (event == null || event.getType() != HostNotificationType.GUEST_MESSAGE
                    || !sourcePackage.equals(event.getPackageName())) return null;

            Bundle extras = notification.extras;
            Notification.MessagingStyle.Message latest = latestMessage(extras);
            boolean outgoing = isOutgoing(latest, extras);
            if (outgoing) return null;
            String text = latest == null ? "" : cleanText(latest.getText());
            if (text.length() == 0 && extras != null)
                text = cleanText(extras.getCharSequence(Notification.EXTRA_TEXT));
            if (text.length() == 0 && extras != null)
                text = cleanText(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
            if (text.length() == 0) text = cleanText(event.getText());
            if (text.length() == 0 || "Host notification received.".equals(text)) return null;

            String notificationKey = sbn.getKey();
            if (notificationKey == null || notificationKey.length() == 0) return null;
            String shortcut = cleanText(notification.getShortcutId());
            String conversationKey = shortcut.length() == 0
                    ? "notification:" + notificationKey : "shortcut:" + shortcut;
            long receivedAt = latest != null && latest.getTimestamp() > 0L
                    ? latest.getTimestamp() : sbn.getPostTime();
            ReplySelection selection = selectReply(sbn);
            NotificationListenerService listener = context instanceof NotificationListenerService
                    ? (NotificationListenerService) context : null;
            boolean capable = listener != null && selection != null
                    && Validation.hasVerifiedIncomingDirection(latest != null,
                            hasExplicitSender(latest), isSelfSender(latest, extras), outgoing);
            ReplyContext replyContext = capable
                    ? new ReplyContext(listener, sbn, selection, identity(latest,
                            notification.getShortcutId(), sbn.getPostTime())) : null;
            return new HostAutomationCore.IncomingMessage(sourcePackage, notificationKey,
                    conversationKey, text, guestName(latest), receivedAt, true,
                    false, false, capable, replyContext);
        } catch (Exception malformedNotification) {
            // Foreign notification data and unavailable app metadata fail closed.
            return null;
        }
    }

    @Override
    public HostAutomationCore.Delivery send(HostAutomationCore.SendRequest request) {
        boolean dispatchStarted = false;
        try {
            if (request == null || request.message == null || request.platform == null
                    || request.replyText == null || request.replyText.trim().length() == 0
                    || request.replyText.length() > HostAutomationCore.MAX_TEMPLATE_LENGTH)
                return HostAutomationCore.Delivery.REJECTED;
            if (!request.isStillAllowed() || !localControlsAllow())
                return HostAutomationCore.Delivery.CANCELED;
            HostAutomationCore.IncomingMessage message = request.message;
            if (!message.guestIncoming || message.summary || message.outgoing
                    || !message.replyCapable || !(message.replyContext instanceof ReplyContext)
                    || !request.platform.packageName.equals(message.packageName))
                return HostAutomationCore.Delivery.REJECTED;
            ReplyContext original = (ReplyContext) message.replyContext;
            if (!message.packageName.equals(original.sbn.getPackageName())
                    || !message.notificationKey.equals(original.sbn.getKey()))
                return HostAutomationCore.Delivery.REJECTED;

            // Listener disconnection, removal, replacement or changed content is unsent.
            StatusBarNotification[] active = original.listener.getActiveNotifications(
                    new String[] { message.notificationKey });
            StatusBarNotification current = null;
            if (active != null) {
                for (StatusBarNotification candidate : active) {
                    if (candidate != null && message.notificationKey.equals(candidate.getKey())) {
                        if (current != null) return HostAutomationCore.Delivery.REJECTED;
                        current = candidate;
                    }
                }
            }
            if (current == null || !sameNotification(original.sbn, current))
                return HostAutomationCore.Delivery.REJECTED;
            HostAutomationCore.IncomingMessage checked = extract(original.listener, current);
            if (checked == null || !checked.replyCapable
                    || !message.text.equals(checked.text)
                    || !message.conversationKey.equals(checked.conversationKey)
                    || message.receivedAtMillis != checked.receivedAtMillis)
                return HostAutomationCore.Delivery.REJECTED;
            ReplyContext currentContext = (ReplyContext) checked.replyContext;
            ReplySelection reply = currentContext.selection;
            if (!original.selection.action.actionIntent.equals(reply.action.actionIntent)
                    || !original.selection.input.getResultKey().equals(reply.input.getResultKey()))
                return HostAutomationCore.Delivery.REJECTED;
            // A host app may repost an unchanged message while classification runs.
            // A changed post time is safe only with a stable Person identity and
            // the same positive message timestamp. Names alone cannot prove this.
            if (!Validation.sameMessageIdentity(original.identity, currentContext.identity))
                return HostAutomationCore.Delivery.REJECTED;

            Bundle results = new Bundle();
            results.putCharSequence(reply.input.getResultKey(), request.replyText);
            Intent fillIn = new Intent();
            RemoteInput.addResultsToIntent(new RemoteInput[] { reply.input }, fillIn, results);
            RemoteInput.setResultsSource(fillIn, RemoteInput.SOURCE_FREE_FORM_INPUT);
            // Core permit and Android safety preferences are checked at dispatch time.
            if (!request.isStillAllowed() || !localControlsAllow())
                return HostAutomationCore.Delivery.CANCELED;
            dispatchStarted = true;
            reply.action.actionIntent.send(appContext, 0, fillIn);
            return HostAutomationCore.Delivery.ACCEPTED;
        } catch (PendingIntent.CanceledException canceled) {
            return HostAutomationCore.Delivery.REJECTED;
        } catch (SecurityException denied) {
            return HostAutomationCore.Delivery.REJECTED;
        } catch (Exception unexpected) {
            return dispatchStarted ? HostAutomationCore.Delivery.UNKNOWN
                    : HostAutomationCore.Delivery.REJECTED;
        }
    }

    private boolean localControlsAllow() {
        return !appContext.getSharedPreferences("ai_helper_safety", Context.MODE_PRIVATE)
                .getBoolean("stopped", false)
                && appContext.getSharedPreferences("host_notifications", Context.MODE_PRIVATE)
                .getBoolean("enabled", true)
                && !GuestControl.blocked(appContext);
    }

    /** Pure metadata gate, usable in JVM tests without constructing Android objects. */
    public static boolean acceptsReplyMetadata(String sourcePackage, String creatorPackage,
                                               boolean mutable, boolean authenticationRequired,
                                               int textActionCount, int freeformInputCount,
                                               int semanticAction) {
        return Validation.acceptsReplyMetadata(sourcePackage, creatorPackage, mutable,
                authenticationRequired, textActionCount, freeformInputCount, semanticAction);
    }

    /**
     * Android-independent gates. Android semantic constants compile to integers;
     * this nested class can be tested without loading the Android transport class.
     */
    public static final class Validation {
        private Validation() { }

        public static boolean acceptsReplyMetadata(String sourcePackage, String creatorPackage,
                                                   boolean mutable, boolean authenticationRequired,
                                                   int textActionCount, int freeformInputCount,
                                                   int semanticAction) {
            return HostAutomationCore.Platform.forPackage(sourcePackage) != null
                    && sourcePackage.equals(creatorPackage) && mutable && !authenticationRequired
                    && textActionCount == 1 && freeformInputCount == 1
                    && semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY;
        }

        public static boolean hasVerifiedIncomingDirection(boolean messagingMessage,
                                                            boolean explicitSender,
                                                            boolean senderIsSelf,
                                                            boolean outgoing) {
            return messagingMessage && explicitSender && !senderIsSelf && !outgoing;
        }

        /** Ephemeral raw sender/message metadata; never persisted. */
        public static final class MessageIdentity {
            public final boolean personPresent;
            public final String personKey, personUri, senderName, shortcut;
            public final long messageTimestamp, postTime;
            public MessageIdentity(boolean personPresent, String personKey, String personUri,
                                   String senderName, String shortcut,
                                   long messageTimestamp, long postTime) {
                this.personPresent = personPresent;
                this.personKey = personKey; this.personUri = personUri;
                this.senderName = senderName; this.shortcut = shortcut;
                this.messageTimestamp = messageTimestamp; this.postTime = postTime;
            }
        }

        /**
         * Called only after current extraction repeats direction/capability checks
         * and text, conversation, received timestamp and reply action are matched.
         * Every raw identity component, including an absent/blank shortcut, must
         * remain exactly equal. Legacy/name-only senders keep strict post time.
         */
        public static boolean sameMessageIdentity(MessageIdentity original,
                                                   MessageIdentity current) {
            if (original == null || current == null
                    || original.personPresent != current.personPresent
                    || !equal(original.personKey, current.personKey)
                    || !equal(original.personUri, current.personUri)
                    || !equal(original.senderName, current.senderName)
                    || !equal(original.shortcut, current.shortcut)
                    || original.messageTimestamp != current.messageTimestamp) return false;
            boolean stablePerson = original.personPresent
                    && (nonblank(original.personKey) || nonblank(original.personUri));
            if (!stablePerson && !nonblank(original.senderName)) return false;
            return original.postTime == current.postTime
                    || (stablePerson && original.messageTimestamp > 0L);
        }

        private static boolean nonblank(String value) {
            return value != null && value.trim().length() > 0;
        }
        private static boolean equal(String first, String second) {
            return first == null ? second == null : first.equals(second);
        }
    }

    private static ReplySelection selectReply(StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        if (notification == null || isSummary(notification)) return null;
        Notification.Action selected = null;
        RemoteInput selectedInput = null;
        int selectedInputCount = 0;
        int textActionCount = 0;
        if (notification.actions != null) {
            for (Notification.Action action : notification.actions) {
                if (action == null) continue;
                RemoteInput[] inputs = action.getRemoteInputs();
                int inputCount = 0;
                RemoteInput textInput = null;
                if (inputs != null) {
                    for (RemoteInput input : inputs) {
                        if (input != null && input.getAllowFreeFormInput() && !input.isDataOnly()) {
                            inputCount++;
                            textInput = input;
                        }
                    }
                }
                if (inputCount > 0) {
                    textActionCount++;
                    selected = action;
                    selectedInput = textInput;
                    selectedInputCount = inputCount;
                }
            }
        }
        if (selected == null || selected.actionIntent == null || selectedInput == null
                || selectedInput.getResultKey() == null || selectedInput.getResultKey().length() == 0)
            return null;
        PendingIntent pending = selected.actionIntent;
        if (!acceptsReplyMetadata(sbn.getPackageName(), pending.getCreatorPackage(),
                !pending.isImmutable(), selected.isAuthenticationRequired(),
                textActionCount, selectedInputCount, selected.getSemanticAction())
                || pending.getCreatorUid() != sbn.getUid()) return null;
        return new ReplySelection(selected, selectedInput);
    }

    private static boolean sameNotification(StatusBarNotification original,
                                            StatusBarNotification current) {
        return original.getKey().equals(current.getKey())
                && original.getPackageName().equals(current.getPackageName())
                && original.getUid() == current.getUid()
                && !isSummary(current.getNotification());
    }

    private static Validation.MessageIdentity identity(Notification.MessagingStyle.Message message,
                                                         String shortcut, long postTime) {
        if (message == null) return null;
        Person person = message.getSenderPerson();
        CharSequence name = person == null ? message.getSender() : person.getName();
        return new Validation.MessageIdentity(person != null,
                person == null ? null : person.getKey(), person == null ? null : person.getUri(),
                name == null ? null : name.toString(), shortcut, message.getTimestamp(), postTime);
    }

    private static boolean isSummary(Notification notification) {
        return notification == null || (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0;
    }

    private static Notification.MessagingStyle.Message latestMessage(Bundle extras) {
        if (extras == null) return null;
        List<Notification.MessagingStyle.Message> messages =
                Notification.MessagingStyle.Message.getMessagesFromBundleArray(
                        extras.getParcelableArray(Notification.EXTRA_MESSAGES));
        if (messages == null || messages.isEmpty()) return null;
        return messages.get(messages.size() - 1);
    }

    private static boolean isOutgoing(Notification.MessagingStyle.Message latest, Bundle extras) {
        if (latest != null) {
            Person sender = latest.getSenderPerson();
            // Android represents the device user's own MessagingStyle messages with no sender.
            if (sender == null && cleanText(latest.getSender()).length() == 0) return true;
            if (isSelfSender(latest, extras)) return true;
        } else if (extras != null) {
            CharSequence[] history = extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY);
            if (history != null && history.length > 0) return true;
        }
        return false;
    }

    private static boolean hasExplicitSender(Notification.MessagingStyle.Message message) {
        return message != null && (message.getSenderPerson() != null
                || cleanText(message.getSender()).length() > 0);
    }

    private static boolean isSelfSender(Notification.MessagingStyle.Message message, Bundle extras) {
        if (message == null || extras == null) return false;
        Person sender = message.getSenderPerson();
        Person self = extras.getParcelable(Notification.EXTRA_MESSAGING_PERSON, Person.class);
        if (sender != null && self != null) {
            if (sameNonempty(sender.getKey(), self.getKey())
                    || sameNonempty(sender.getUri(), self.getUri())) return true;
            if (sameNonempty(cleanText(sender.getName()), cleanText(self.getName()))) return true;
        }
        return sameNonempty(cleanText(message.getSender()),
                cleanText(extras.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME)));
    }

    private static boolean sameNonempty(String first, String second) {
        return first != null && first.length() > 0 && first.equals(second);
    }

    private static String guestName(Notification.MessagingStyle.Message message) {
        if (message == null) return "there";
        Person person = message.getSenderPerson();
        String name = person == null ? "" : cleanText(person.getName());
        if (name.length() == 0) name = cleanText(message.getSender());
        // Keep foreign sender names as bounded plain text, not template syntax or markup.
        name = name.replaceAll("[\\p{Cntrl}\\r\\n]+", " ").trim();
        return name.length() == 0 || name.length() > 80 ? "there" : name;
    }

    private static String cleanText(CharSequence text) {
        return text == null ? "" : text.toString().trim();
    }

    private static final class ReplySelection {
        final Notification.Action action;
        final RemoteInput input;
        ReplySelection(Notification.Action action, RemoteInput input) {
            this.action = action;
            this.input = input;
        }
    }

    private static final class ReplyContext {
        final NotificationListenerService listener;
        final StatusBarNotification sbn;
        final ReplySelection selection;
        final Validation.MessageIdentity identity;
        ReplyContext(NotificationListenerService listener, StatusBarNotification sbn,
                     ReplySelection selection, Validation.MessageIdentity identity) {
            this.listener = listener;
            this.sbn = sbn;
            this.selection = selection;
            this.identity = identity;
        }
    }
}
