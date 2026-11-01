package com.aihelper.extras;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Android-independent incoming guest-message rules. The Android adapter must
 * verify message direction, guest context and RemoteInput before constructing an
 * event. It must never substitute UI navigation for a missing reply capability.
 *
 * Store.save must synchronously commit the complete snapshot or throw. A durable
 * PENDING reservation is made before calling Sender. Uncertain send outcomes and
 * interrupted reservations require human review; they are never retried here.
 * Accepted means the transport accepted a request, not that a guest received it.
 *
 * History stores hashes, timestamps and status, without incoming message text or
 * guest/conversation names. Drafts contain only generated replies. Store adapters
 * must use app-private storage (and encryption when appropriate for configured
 * property secrets). No reply-context object is persisted.
 */
public final class HostAutomationCore {
    public static final int MAX_TEMPLATE_LENGTH = 2000;
    public static final int MAX_VALUE_LENGTH = 512;
    public static final int MAX_HISTORY = 256;
    public static final int MAX_DRAFTS = 40;
    public static final long MAX_FRESHNESS_MILLIS = 24L * 60L * 60L * 1000L;
    private static final long FUTURE_SKEW_MILLIS = 2L * 60L * 1000L;
    private static final long DRAFT_RETENTION_MILLIS = 7L * MAX_FRESHNESS_MILLIS;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_]+)\\}");
    private static final Pattern CHECK_IN = Pattern.compile("\\b(?:check[ -]?in|arrival|arrive|arriving)\\b");
    private static final Pattern CHECK_OUT = Pattern.compile("\\b(?:check[ -]?out|departure|departing)\\b");
    private static final Pattern WIFI = Pattern.compile("\\b(?:wi[ -]?fi|internet)\\b");
    private static final Pattern PARKING = Pattern.compile("\\b(?:parking|car park|garage)\\b");
    public static final Set<String> PLACEHOLDERS;
    static {
        Set<String> names = new HashSet<String>();
        Collections.addAll(names, "guest_name", "property_name", "host_name", "check_in_time",
                "check_out_time", "check_in_date", "check_out_date", "wifi_name",
                "wifi_password", "wifi_details", "parking_details", "house_rules", "property_address",
                "directions", "breakfast_details", "pet_policy");
        PLACEHOLDERS = Collections.unmodifiableSet(names);
    }

    public enum Platform {
        AIRBNB("com.airbnb.android"), PULSE("com.booking.hotelmanager");
        public final String packageName;
        Platform(String packageName) { this.packageName = packageName; }
        public static Platform forPackage(String packageName) {
            for (Platform platform : values()) {
                if (platform.packageName.equals(packageName)) return platform;
            }
            return null;
        }
    }
    public enum Mode { OFF, DRAFT, AUTO, SMART }
    public enum TemplateKind { GENERAL, CHECK_IN, CHECK_OUT, WIFI, PARKING }
    public enum Status { DRAFT, PENDING, SUBMITTED, FAILED, UNSUPPORTED_REPLY, REVIEW_REQUIRED }
    public enum Delivery { ACCEPTED, REJECTED, UNKNOWN, CANCELED }
    public enum Action { SKIPPED, QUEUED, SUBMITTED, FAILED, REVIEW_REQUIRED }
    public enum Reason {
        NONE, UNSUPPORTED_PLATFORM, DISABLED, EMERGENCY_STOP, PLATFORM_OFF,
        SUMMARY, OUTGOING, NOT_GUEST, INVALID_CONTEXT, STALE, FUTURE_TIMESTAMP, BEFORE_ACTIVATION,
        DUPLICATE, QUIET_HOURS, COOLDOWN, MISSING_TEMPLATE, MISSING_VALUE,
        AMBIGUOUS_REQUEST, NO_REPLY_CAPABILITY, PERSISTENCE_FAILURE, SENDER_REJECTED,
        DELIVERY_UNCERTAIN, HISTORY_FULL, PAUSED_BEFORE_SEND, REPLY_TOO_LONG,
        SMART_REPLY_UNAVAILABLE, CONFIG_CHANGED
    }
    public interface Clock { long nowMillis(); }
    public static final Clock SYSTEM_CLOCK = new Clock() {
        @Override public long nowMillis() { return System.currentTimeMillis(); }
    };
    /** Implement save with a synchronous commit; asynchronous apply is unsafe. */
    public interface Store {
        Map<String, String> load();
        void save(Map<String, String> snapshot);
    }
    public interface Sender { Delivery send(SendRequest request) throws Exception; }

    public static final class PersistenceException extends RuntimeException {
        public PersistenceException(String message, Throwable cause) { super(message, cause); }
    }

    public static final class QuietHours {
        public final boolean enabled;
        public final int startMinute;
        public final int endMinute;
        public final String zoneId;
        public QuietHours(boolean enabled, int startMinute, int endMinute, String zoneId) {
            if (startMinute < 0 || startMinute > 1439 || endMinute < 0 || endMinute > 1439)
                throw new IllegalArgumentException("Quiet hours must use minutes 0..1439");
            if (enabled && startMinute == endMinute)
                throw new IllegalArgumentException("Quiet-hours start and end must differ");
            if (zoneId == null) throw new IllegalArgumentException("Timezone is required");
            try { ZoneId.of(zoneId); }
            catch (java.time.DateTimeException error) { throw new IllegalArgumentException("Invalid quiet-hours timezone", error); }
            this.enabled = enabled; this.startMinute = startMinute; this.endMinute = endMinute;
            this.zoneId = zoneId;
        }
        public boolean contains(long nowMillis) {
            if (!enabled) return false;
            java.time.ZonedDateTime local = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.of(zoneId));
            int minute = local.getHour() * 60 + local.getMinute();
            return startMinute < endMinute ? minute >= startMinute && minute < endMinute
                    : minute >= startMinute || minute < endMinute;
        }
        public static QuietHours disabled() { return new QuietHours(false, 0, 0, "UTC"); }
    }

    /** Immutable settings. Templates and property values must be entered by the user. */
    public static final class Config {
        public final Mode mode;
        public final Map<TemplateKind, String> templates;
        public final Map<String, String> values;
        public final QuietHours quietHours;
        public final long freshnessMillis;
        public final long cooldownMillis;
        public Config(Mode mode, Map<TemplateKind, String> templates, Map<String, String> values,
                      QuietHours quietHours, long freshnessMillis, long cooldownMillis) {
            if (mode == null || templates == null || values == null || quietHours == null)
                throw new IllegalArgumentException("Config fields cannot be null");
            if (freshnessMillis < 1000L || freshnessMillis > MAX_FRESHNESS_MILLIS)
                throw new IllegalArgumentException("Freshness must be between 1 second and 24 hours");
            if (cooldownMillis < 0L || cooldownMillis > MAX_FRESHNESS_MILLIS)
                throw new IllegalArgumentException("Cooldown must be between zero and 24 hours");
            EnumMap<TemplateKind, String> copiedTemplates = new EnumMap<TemplateKind, String>(TemplateKind.class);
            for (Map.Entry<TemplateKind, String> entry : templates.entrySet()) {
                if (entry.getKey() == null) throw new IllegalArgumentException("Template kind is required");
                validateTemplate(entry.getValue());
                copiedTemplates.put(entry.getKey(), entry.getValue().trim());
            }
            LinkedHashMap<String, String> copiedValues = new LinkedHashMap<String, String>();
            for (Map.Entry<String, String> entry : values.entrySet()) {
                validateValue(entry.getKey(), entry.getValue());
                if ("guest_name".equals(entry.getKey()))
                    throw new IllegalArgumentException("Guest name comes from verified event context");
                copiedValues.put(entry.getKey(), factText(entry.getKey(), entry.getValue()));
            }
            if (mode == Mode.AUTO && !copiedTemplates.containsKey(TemplateKind.GENERAL))
                throw new IllegalArgumentException("Set an explicit general template before enabling AUTO");
            this.mode = mode; this.templates = Collections.unmodifiableMap(copiedTemplates);
            this.values = Collections.unmodifiableMap(copiedValues); this.quietHours = quietHours;
            this.freshnessMillis = freshnessMillis; this.cooldownMillis = cooldownMillis;
        }
        public static Config defaults() {
            return new Config(Mode.OFF, Collections.<TemplateKind, String>emptyMap(),
                    Collections.<String, String>emptyMap(), QuietHours.disabled(), 10L * 60L * 1000L, 5L * 60L * 1000L);
        }
        public Config withMode(Mode value) {
            return new Config(value, templates, values, quietHours, freshnessMillis, cooldownMillis);
        }
        public Config withTemplate(TemplateKind kind, String template) {
            EnumMap<TemplateKind, String> copy = new EnumMap<TemplateKind, String>(TemplateKind.class);
            copy.putAll(templates); copy.put(kind, template);
            return new Config(mode, copy, values, quietHours, freshnessMillis, cooldownMillis);
        }
        public Config withoutTemplate(TemplateKind kind) {
            EnumMap<TemplateKind, String> copy = new EnumMap<TemplateKind, String>(TemplateKind.class);
            copy.putAll(templates); copy.remove(kind);
            return new Config(mode, copy, values, quietHours, freshnessMillis, cooldownMillis);
        }
        public Config withValue(String name, String value) {
            Map<String, String> copy = new LinkedHashMap<String, String>(values); copy.put(name, value);
            return new Config(mode, templates, copy, quietHours, freshnessMillis, cooldownMillis);
        }
        public Config withoutValue(String name) {
            Map<String, String> copy = new LinkedHashMap<String, String>(values); copy.remove(name);
            return new Config(mode, templates, copy, quietHours, freshnessMillis, cooldownMillis);
        }
        public Config withQuietHours(QuietHours value) {
            return new Config(mode, templates, values, value, freshnessMillis, cooldownMillis);
        }
        public Config withSafetyLimits(long freshness, long cooldown) {
            return new Config(mode, templates, values, quietHours, freshness, cooldown);
        }
    }

    public static final class IncomingMessage {
        public final String packageName, notificationKey, conversationKey, text, guestName;
        public final long receivedAtMillis;
        public final boolean guestIncoming, summary, outgoing, replyCapable;
        public final Object replyContext;
        public final Map<String, String> contextValues;
        final String preparedReply;
        final Config preparedConfig;
        public IncomingMessage(String packageName, String notificationKey, String conversationKey,
                               String text, long receivedAtMillis, boolean guestIncoming,
                               boolean summary, boolean outgoing, boolean replyCapable,
                               Object replyContext, Map<String, String> contextValues) {
            this(packageName, notificationKey, conversationKey, text, receivedAtMillis,
                    guestIncoming, summary, outgoing, replyCapable, replyContext, contextValues, null, null);
        }
        private IncomingMessage(String packageName, String notificationKey, String conversationKey,
                               String text, long receivedAtMillis, boolean guestIncoming,
                               boolean summary, boolean outgoing, boolean replyCapable,
                               Object replyContext, Map<String, String> contextValues,
                               String preparedReply, Config preparedConfig) {
            this.packageName = packageName; this.notificationKey = notificationKey;
            this.conversationKey = conversationKey; this.text = text;
            this.receivedAtMillis = receivedAtMillis; this.guestIncoming = guestIncoming;
            this.summary = summary; this.outgoing = outgoing; this.replyCapable = replyCapable;
            this.replyContext = replyContext;
            this.contextValues = Collections.unmodifiableMap(new LinkedHashMap<String, String>(
                    contextValues == null ? Collections.<String, String>emptyMap() : contextValues));
            this.guestName = this.contextValues.get("guest_name");
            this.preparedReply = preparedReply;
            this.preparedConfig = preparedConfig;
        }
        public IncomingMessage(String packageName, String notificationKey, String conversationKey,
                               String text, String guestName, long receivedAtMillis, boolean guestIncoming,
                               boolean summary, boolean outgoing, boolean replyCapable, Object replyContext) {
            this(packageName, notificationKey, conversationKey, text, receivedAtMillis, guestIncoming,
                    summary, outgoing, replyCapable, replyContext, guestName == null
                            ? Collections.<String, String>emptyMap() : Collections.singletonMap("guest_name", guestName));
        }
        IncomingMessage prepared(String reply, Config config) {
            return new IncomingMessage(packageName, notificationKey, conversationKey, text,
                    receivedAtMillis, guestIncoming, summary, outgoing, replyCapable, replyContext,
                    contextValues, reply, config);
        }
    }
    private interface Permit { boolean allowed(); }
    public static final class SendRequest {
        public final Platform platform;
        public final TemplateKind templateKind;
        public final IncomingMessage message;
        public final String replyText, fingerprint;
        private final Permit permit;
        private SendRequest(Platform platform, TemplateKind kind, IncomingMessage message,
                            String replyText, String fingerprint, Permit permit) {
            this.platform = platform; this.templateKind = kind; this.message = message;
            this.replyText = replyText; this.fingerprint = fingerprint; this.permit = permit;
        }
        /** The Android Sender should check immediately before PendingIntent.send. */
        public boolean isStillAllowed() { return permit.allowed(); }
    }
    public static final class Record {
        public final String fingerprint, contentFingerprint, conversationHash;
        public final Platform platform;
        public final TemplateKind templateKind;
        public final Status status;
        public final Reason reason;
        public final long receivedAtMillis, updatedAtMillis;
        private Record(String fingerprint, String contentFingerprint, String conversationHash,
                       Platform platform, TemplateKind kind, Status status, Reason reason,
                       long receivedAtMillis, long updatedAtMillis) {
            this.fingerprint = fingerprint; this.contentFingerprint = contentFingerprint;
            this.conversationHash = conversationHash; this.platform = platform; this.templateKind = kind;
            this.status = status; this.reason = reason; this.receivedAtMillis = receivedAtMillis;
            this.updatedAtMillis = updatedAtMillis;
        }
        private Record withState(Status value, Reason why, long now) {
            return new Record(fingerprint, contentFingerprint, conversationHash, platform, templateKind,
                    value, why, receivedAtMillis, now);
        }
    }
    public static final class Draft {
        public final String fingerprint, replyText;
        public final Platform platform;
        public final TemplateKind templateKind;
        public final Status status;
        public final Reason reason;
        public final long createdAtMillis;
        private Draft(Record record, String replyText, long createdAtMillis) {
            this.fingerprint = record.fingerprint; this.replyText = replyText;
            this.platform = record.platform; this.templateKind = record.templateKind;
            this.status = record.status; this.reason = record.reason; this.createdAtMillis = createdAtMillis;
        }
    }
    public static final class Result {
        public final Action action;
        public final Reason reason;
        public final Record record;
        public final Draft draft;
        private Result(Action action, Reason reason, Record record, Draft draft) {
            this.action = action; this.reason = reason; this.record = record; this.draft = draft;
        }
        private static Result skip(Reason why) { return new Result(Action.SKIPPED, why, null, null); }
    }

    private final Object monitor = new Object();
    private final Store store;
    private final Sender sender;
    private final Clock clock;
    private final EnumMap<Platform, Config> configs = new EnumMap<Platform, Config>(Platform.class);
    private final EnumMap<Platform, Long> activationAt = new EnumMap<Platform, Long>(Platform.class);
    private final LinkedHashMap<String, Record> records = new LinkedHashMap<String, Record>();
    private final LinkedHashMap<String, Draft> drafts = new LinkedHashMap<String, Draft>();
    private final LinkedHashMap<String, Long> conversationAttempts = new LinkedHashMap<String, Long>();
    private boolean enabled, stopped;
    private long generation;

    public HostAutomationCore(Store store, Sender sender, Clock clock) {
        if (store == null || sender == null || clock == null) throw new IllegalArgumentException("Dependencies are required");
        this.store = store; this.sender = sender; this.clock = clock;
        for (Platform platform : Platform.values()) {
            configs.put(platform, Config.defaults()); activationAt.put(platform, Long.valueOf(0L));
        }
        synchronized (monitor) {
            try {
                Map<String, String> loaded = store.load();
                if (loaded != null && !loaded.isEmpty()) restore(loaded);
                boolean interrupted = false;
                for (Record record : new ArrayList<Record>(records.values())) {
                    if (record.status == Status.PENDING) {
                        Record recovered = record.withState(Status.REVIEW_REQUIRED, Reason.DELIVERY_UNCERTAIN, clock.nowMillis());
                        records.put(recovered.fingerprint, recovered);
                        Draft draft = drafts.get(recovered.fingerprint);
                        queue(recovered, draft == null ? "" : draft.replyText,
                                draft == null ? clock.nowMillis() : draft.createdAtMillis);
                        interrupted = true;
                    }
                }
                prune(clock.nowMillis());
                if (interrupted) persist();
            } catch (RuntimeException error) {
                enabled = false; stopped = true;
                throw new PersistenceException("Host automation could not restore durable state", error);
            }
        }
    }

    public Config getConfig(Platform platform) { synchronized (monitor) { return requirePlatform(platform); } }
    public long getActivationAtMillis(Platform platform) {
        synchronized (monitor) { requirePlatform(platform); return activationAt.get(platform).longValue(); }
    }
    public void configure(Platform platform, Config config) {
        if (config == null) throw new IllegalArgumentException("Config is required");
        synchronized (monitor) {
            requirePlatform(platform); configs.put(platform, config);
            activationAt.put(platform, Long.valueOf(clock.nowMillis())); generation++; persist();
        }
    }
    public boolean isEnabled() { synchronized (monitor) { return enabled; } }
    public boolean isEmergencyStopped() { synchronized (monitor) { return stopped; } }
    public void setEnabled(boolean value) {
        synchronized (monitor) {
            if (value && stopped) throw new IllegalStateException("Emergency stop active; explicitly resume first");
            if (value && !enabled) activateAll();
            enabled = value; generation++; persist();
        }
    }
    public void emergencyStop() {
        synchronized (monitor) { stopped = true; enabled = false; generation++; persist(); }
    }
    /** Explicit user command to clear an emergency stop and enable processing. */
    public void resume() {
        synchronized (monitor) { stopped = false; enabled = true; activateAll(); generation++; persist(); }
    }
    public List<Record> history() {
        synchronized (monitor) { return Collections.unmodifiableList(new ArrayList<Record>(records.values())); }
    }
    public List<Draft> drafts() {
        synchronized (monitor) { return Collections.unmodifiableList(new ArrayList<Draft>(drafts.values())); }
    }
    public void discardDraft(String fingerprint) {
        synchronized (monitor) {
            Draft existing = drafts.get(fingerprint);
            if (existing != null && existing.status == Status.PENDING)
                throw new IllegalStateException("Cannot discard a pending send");
            if (drafts.remove(fingerprint) != null) persist();
        }
    }
    public Map<String, String> exportState() { synchronized (monitor) { return snapshot(); } }

    /** Cheap gate before local generation; onIncoming repeats authoritative checks. */
    public boolean needsSmartReply(IncomingMessage message) {
        synchronized (monitor) {
            Platform platform = message == null ? null : Platform.forPackage(message.packageName);
            if (platform == null || stopped || !enabled || configs.get(platform).mode != Mode.SMART
                    || message.summary || message.outgoing || !message.guestIncoming || !validContext(message)) return false;
            Config config = configs.get(platform); long now = clock.nowMillis();
            if (message.receivedAtMillis <= 0L || message.receivedAtMillis < activationAt.get(platform)
                    || now - message.receivedAtMillis > config.freshnessMillis
                    || message.receivedAtMillis > now + FUTURE_SKEW_MILLIS) return false;
            prune(now);
            String conversation = hash(platform.packageName, message.conversationKey);
            String content = hash(platform.packageName, conversation, normalize(message.text));
            for (Record record : records.values()) if (record.contentFingerprint.equals(content)) return false;
            return records.size() < MAX_HISTORY;
        }
    }

    private static boolean automatic(Mode mode) { return mode == Mode.AUTO || mode == Mode.SMART; }

    public Result onIncoming(final IncomingMessage message) {
        final SendRequest request;
        final String reply;
        final long reservationGeneration;
        synchronized (monitor) {
            Platform platform = message == null ? null : Platform.forPackage(message.packageName);
            if (platform == null) return Result.skip(Reason.UNSUPPORTED_PLATFORM);
            if (stopped) return Result.skip(Reason.EMERGENCY_STOP);
            if (!enabled) return Result.skip(Reason.DISABLED);
            Config config = configs.get(platform);
            if (config.mode == Mode.OFF) return Result.skip(Reason.PLATFORM_OFF);
            if (message.summary) return Result.skip(Reason.SUMMARY);
            if (message.outgoing) return Result.skip(Reason.OUTGOING);
            if (!message.guestIncoming) return Result.skip(Reason.NOT_GUEST);
            if (!validContext(message)) return Result.skip(Reason.INVALID_CONTEXT);
            long now = clock.nowMillis();
            final long eventGeneration = generation;
            if (message.receivedAtMillis > now + FUTURE_SKEW_MILLIS) return Result.skip(Reason.FUTURE_TIMESTAMP);
            if (message.receivedAtMillis <= 0L || now - message.receivedAtMillis > config.freshnessMillis)
                return Result.skip(Reason.STALE);
            if (message.receivedAtMillis < activationAt.get(platform).longValue())
                return Result.skip(Reason.BEFORE_ACTIVATION);
            prune(now);
            final String conversationHash = hash(platform.packageName, message.conversationKey);
            String normalizedText = normalize(message.text);
            final String fingerprint = hash(platform.packageName, message.notificationKey, conversationHash, normalizedText);
            String contentFingerprint = hash(platform.packageName, conversationHash, normalizedText);
            for (Record record : records.values()) {
                if (record.fingerprint.equals(fingerprint) || record.contentFingerprint.equals(contentFingerprint))
                    return new Result(Action.SKIPPED, Reason.DUPLICATE, record, drafts.get(record.fingerprint));
            }
            if (records.size() >= MAX_HISTORY || allDraftSlotsPending()
                    || (!conversationAttempts.containsKey(conversationHash) && conversationAttempts.size() >= MAX_HISTORY))
                return Result.skip(Reason.HISTORY_FULL);
            TemplateKind kind = config.mode == Mode.SMART ? TemplateKind.GENERAL : classify(message.text);
            Reason why = kind == null ? Reason.AMBIGUOUS_REQUEST : Reason.NONE;
            String rendered = "";
            if (config.mode == Mode.SMART) {
                if (message.preparedConfig != config) why = Reason.CONFIG_CHANGED;
                else if (message.preparedReply == null || message.preparedReply.trim().isEmpty()) why = Reason.SMART_REPLY_UNAVAILABLE;
                else if (message.preparedReply.length() > MAX_TEMPLATE_LENGTH || containsUnsafeControl(message.preparedReply)) why = Reason.REPLY_TOO_LONG;
                else rendered = message.preparedReply.trim();
            } else if (why == Reason.NONE) {
                String template = config.templates.get(kind);
                if (template == null) why = Reason.MISSING_TEMPLATE;
                else {
                    Map<String, String> values = new LinkedHashMap<String, String>(config.values);
                    if (message.contextValues.containsKey("guest_name")) values.put("guest_name", message.contextValues.get("guest_name"));
                    try { rendered = renderTemplate(template, values); }
                    catch (MissingValue error) { why = Reason.MISSING_VALUE; }
                    catch (IllegalArgumentException error) { why = Reason.REPLY_TOO_LONG; }
                }
            }
            if (why == Reason.NONE && config.quietHours.contains(now)) why = Reason.QUIET_HOURS;
            Long lastAttempt = conversationAttempts.get(conversationHash);
            if (why == Reason.NONE && lastAttempt != null && now - lastAttempt.longValue() < config.cooldownMillis)
                why = Reason.COOLDOWN;
            if (why == Reason.NONE && automatic(config.mode) && (!message.replyCapable || message.replyContext == null))
                why = Reason.NO_REPLY_CAPABILITY;
            boolean send = why == Reason.NONE && automatic(config.mode);
            Status status = send ? Status.PENDING : why == Reason.NO_REPLY_CAPABILITY ? Status.UNSUPPORTED_REPLY : Status.DRAFT;
            Record record = new Record(fingerprint, contentFingerprint, conversationHash, platform, kind, status, why,
                    message.receivedAtMillis, now);
            records.put(fingerprint, record);
            queue(record, rendered, now);
            if (send) conversationAttempts.put(conversationHash, Long.valueOf(now));
            try { persist(); }
            catch (PersistenceException error) {
                Record failure = record.withState(Status.FAILED, Reason.PERSISTENCE_FAILURE, now);
                records.put(fingerprint, failure); queue(failure, rendered, now);
                return new Result(Action.FAILED, Reason.PERSISTENCE_FAILURE, failure, drafts.get(fingerprint));
            }
            if (!send) return new Result(Action.QUEUED, why, record, drafts.get(fingerprint));
            reply = rendered;
            reservationGeneration = eventGeneration;
            request = new SendRequest(platform, kind, message, reply, fingerprint, new Permit() {
                @Override public boolean allowed() {
                    synchronized (monitor) {
                        Record active = records.get(fingerprint);
                        Config current = configs.get(Platform.forPackage(message.packageName));
                        long currentNow = clock.nowMillis();
                        return enabled && !stopped && generation == reservationGeneration
                                && automatic(current.mode) && !current.quietHours.contains(currentNow)
                                && currentNow - message.receivedAtMillis <= current.freshnessMillis
                                && message.receivedAtMillis <= currentNow + FUTURE_SKEW_MILLIS
                                && message.receivedAtMillis >= activationAt.get(currentPlatform(message)).longValue()
                                && active != null && active.status == Status.PENDING;
                    }
                }
            });
        }
        if (!request.isStillAllowed()) return finish(request, Delivery.CANCELED, reply);
        Delivery delivery;
        try { delivery = sender.send(request); }
        catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            delivery = Delivery.UNKNOWN;
        }
        if (delivery == null) delivery = Delivery.UNKNOWN;
        return finish(request, delivery, reply);
    }

    private Result finish(SendRequest request, Delivery delivery, String reply) {
        synchronized (monitor) {
            Record original = records.get(request.fingerprint);
            if (original == null) throw new IllegalStateException("Pending reservation disappeared");
            long now = clock.nowMillis();
            Status status = delivery == Delivery.ACCEPTED ? Status.SUBMITTED : delivery == Delivery.REJECTED ? Status.FAILED
                    : delivery == Delivery.CANCELED ? Status.DRAFT : Status.REVIEW_REQUIRED;
            Reason reason = delivery == Delivery.ACCEPTED ? Reason.NONE : delivery == Delivery.REJECTED ? Reason.SENDER_REJECTED
                    : delivery == Delivery.CANCELED ? Reason.PAUSED_BEFORE_SEND : Reason.DELIVERY_UNCERTAIN;
            Record record = original.withState(status, reason, now);
            records.put(record.fingerprint, record);
            if (status == Status.SUBMITTED) drafts.remove(record.fingerprint);
            else queue(record, reply, drafts.get(record.fingerprint).createdAtMillis);
            try { persist(); }
            catch (PersistenceException error) {
                // The durable state may still be PENDING even after an accepted
                // transport request. Keep a review item and never retry it.
                record = record.withState(Status.REVIEW_REQUIRED, Reason.DELIVERY_UNCERTAIN, now);
                records.put(record.fingerprint, record); queue(record, reply, now);
                return new Result(Action.REVIEW_REQUIRED, Reason.DELIVERY_UNCERTAIN, record, drafts.get(record.fingerprint));
            }
            Action action = status == Status.SUBMITTED ? Action.SUBMITTED : status == Status.FAILED ? Action.FAILED
                    : status == Status.REVIEW_REQUIRED ? Action.REVIEW_REQUIRED : Action.QUEUED;
            return new Result(action, reason, record, drafts.get(record.fingerprint));
        }
    }

    /** Return null for messages matching several property topics: require review. */
    public static TemplateKind classify(String text) {
        if (text == null) return TemplateKind.GENERAL;
        String normalized = text.toLowerCase(Locale.ROOT);
        TemplateKind chosen = null;
        int count = 0;
        if (CHECK_IN.matcher(normalized).find()) { chosen = TemplateKind.CHECK_IN; count++; }
        if (CHECK_OUT.matcher(normalized).find()) { chosen = TemplateKind.CHECK_OUT; count++; }
        if (WIFI.matcher(normalized).find()) { chosen = TemplateKind.WIFI; count++; }
        if (PARKING.matcher(normalized).find()) { chosen = TemplateKind.PARKING; count++; }
        return count > 1 ? null : count == 0 ? TemplateKind.GENERAL : chosen;
    }
    public static void validateTemplate(String template) {
        if (template == null || template.trim().isEmpty()) throw new IllegalArgumentException("Template cannot be empty");
        if (template.length() > MAX_TEMPLATE_LENGTH) throw new IllegalArgumentException("Template exceeds 2000 characters");
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuffer remainder = new StringBuffer();
        while (matcher.find()) {
            if (!PLACEHOLDERS.contains(matcher.group(1))) throw new IllegalArgumentException("Unknown placeholder: " + matcher.group(1));
            matcher.appendReplacement(remainder, "");
        }
        matcher.appendTail(remainder);
        if (remainder.indexOf("{") >= 0 || remainder.indexOf("}") >= 0)
            throw new IllegalArgumentException("Malformed template placeholder");
        if (containsUnsafeControl(template)) throw new IllegalArgumentException("Template contains control characters");
    }
    private static void validateValue(String name, String value) {
        if (!PLACEHOLDERS.contains(name)) throw new IllegalArgumentException("Unknown placeholder value: " + name);
        if (value == null || value.trim().isEmpty() || value.length() > MAX_VALUE_LENGTH || containsUnsafeControl(value))
            throw new IllegalArgumentException("Placeholder value must contain 1..512 visible characters");
        boolean credential = "wifi_name".equals(name) || "wifi_password".equals(name);
        if (credential && (value.indexOf('\n') >= 0 || value.indexOf('\t') >= 0))
            throw new IllegalArgumentException("Wi-Fi credentials must be single-line text");
        if (!credential && (value.indexOf('{') >= 0 || value.indexOf('}') >= 0))
            throw new IllegalArgumentException("Placeholder values cannot contain template braces");
    }
    private static String factText(String name, String value) {
        return "wifi_name".equals(name) || "wifi_password".equals(name) ? value : value.trim();
    }
    private static boolean containsUnsafeControl(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isISOControl(character) && character != '\n' && character != '\t') return true;
        }
        return false;
    }
    public static String renderTemplate(String template, Map<String, String> values) {
        validateTemplate(template);
        if (values == null) throw new IllegalArgumentException("Values are required");
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuffer reply = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1), value = values.get(key);
            if (value == null || value.trim().isEmpty()) throw new MissingValue(key);
            validateValue(key, value);
            matcher.appendReplacement(reply, Matcher.quoteReplacement(factText(key, value)));
        }
        matcher.appendTail(reply);
        String result = reply.toString().trim();
        if (result.isEmpty() || result.length() > MAX_TEMPLATE_LENGTH) throw new IllegalArgumentException("Rendered reply exceeds limit");
        return result;
    }
    private static final class MissingValue extends IllegalArgumentException {
        MissingValue(String key) { super("Missing configured value: " + key); }
    }
    private static boolean validContext(IncomingMessage message) {
        if (message.notificationKey == null || message.notificationKey.trim().isEmpty() || message.notificationKey.length() > 1024
                || message.conversationKey == null || message.conversationKey.trim().isEmpty() || message.conversationKey.length() > 1024
                || message.text == null || message.text.trim().isEmpty() || message.text.length() > 10000) return false;
        for (Map.Entry<String, String> entry : message.contextValues.entrySet()) {
            // Property facts are accepted only in explicit user configuration.
            if (!"guest_name".equals(entry.getKey())) return false;
            try { validateValue(entry.getKey(), entry.getValue()); }
            catch (IllegalArgumentException error) { return false; }
        }
        return true;
    }
    private static String normalize(String text) { return text.trim().replaceAll("\\s+", " "); }
    private static String hash(String... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                // Length-prefix every field to avoid delimiter collisions.
                digest.update(new byte[]{(byte)(bytes.length >>> 24), (byte)(bytes.length >>> 16),
                        (byte)(bytes.length >>> 8), (byte)bytes.length});
                digest.update(bytes);
            }
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value & 0xFF));
            return result.toString();
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private Config requirePlatform(Platform platform) {
        if (platform == null || !configs.containsKey(platform)) throw new IllegalArgumentException("Allowlisted platform is required");
        return configs.get(platform);
    }
    private static Platform currentPlatform(IncomingMessage message) { return Platform.forPackage(message.packageName); }
    private void activateAll() {
        long now = clock.nowMillis();
        for (Platform platform : Platform.values()) activationAt.put(platform, Long.valueOf(now));
    }
    private boolean allDraftSlotsPending() {
        if (drafts.size() < MAX_DRAFTS) return false;
        for (Draft draft : drafts.values()) if (draft.status != Status.PENDING) return false;
        return true;
    }
    private void queue(Record record, String reply, long createdAt) {
        if (!drafts.containsKey(record.fingerprint) && drafts.size() >= MAX_DRAFTS) {
            String evict = null;
            for (Draft draft : drafts.values()) if (draft.status != Status.PENDING) { evict = draft.fingerprint; break; }
            if (evict == null) throw new IllegalStateException("All draft slots are pending");
            drafts.remove(evict);
        }
        drafts.put(record.fingerprint, new Draft(record, reply, createdAt));
    }
    private void prune(long now) {
        for (Record record : new ArrayList<Record>(records.values())) {
            if (record.status != Status.PENDING && now - record.receivedAtMillis > MAX_FRESHNESS_MILLIS + FUTURE_SKEW_MILLIS)
                records.remove(record.fingerprint);
        }
        for (Draft draft : new ArrayList<Draft>(drafts.values())) {
            if (draft.status != Status.PENDING && now - draft.createdAtMillis > DRAFT_RETENTION_MILLIS) drafts.remove(draft.fingerprint);
        }
        for (String key : new ArrayList<String>(conversationAttempts.keySet())) {
            if (now - conversationAttempts.get(key).longValue() > MAX_FRESHNESS_MILLIS + FUTURE_SKEW_MILLIS)
                conversationAttempts.remove(key);
        }
    }
    private void persist() {
        try { store.save(snapshot()); }
        catch (RuntimeException error) {
            enabled = false; stopped = true; generation++;
            throw new PersistenceException("Cannot durably persist host automation; processing stopped", error);
        }
    }
    private Map<String, String> snapshot() {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        out.put("schema", "2"); out.put("enabled", Boolean.toString(enabled)); out.put("stopped", Boolean.toString(stopped));
        for (Platform platform : Platform.values()) {
            Config config = configs.get(platform); String prefix = "config." + platform.name() + ".";
            out.put(prefix + "mode", config.mode.name());
            out.put(prefix + "activation", activationAt.get(platform).toString());
            out.put(prefix + "freshness", Long.toString(config.freshnessMillis));
            out.put(prefix + "cooldown", Long.toString(config.cooldownMillis));
            out.put(prefix + "quiet.enabled", Boolean.toString(config.quietHours.enabled));
            out.put(prefix + "quiet.start", Integer.toString(config.quietHours.startMinute));
            out.put(prefix + "quiet.end", Integer.toString(config.quietHours.endMinute));
            out.put(prefix + "quiet.zone", config.quietHours.zoneId);
            for (Map.Entry<TemplateKind, String> entry : config.templates.entrySet()) out.put(prefix + "template." + entry.getKey().name(), entry.getValue());
            for (Map.Entry<String, String> entry : config.values.entrySet()) out.put(prefix + "value." + entry.getKey(), entry.getValue());
        }
        int index = 0;
        for (Record record : records.values()) {
            String prefix = "record." + index++ + ".";
            out.put(prefix + "id", record.fingerprint); out.put(prefix + "content", record.contentFingerprint);
            out.put(prefix + "conversation", record.conversationHash); out.put(prefix + "platform", record.platform.name());
            out.put(prefix + "template", record.templateKind == null ? "" : record.templateKind.name());
            out.put(prefix + "status", record.status.name()); out.put(prefix + "reason", record.reason.name());
            out.put(prefix + "received", Long.toString(record.receivedAtMillis)); out.put(prefix + "updated", Long.toString(record.updatedAtMillis));
        }
        out.put("record.count", Integer.toString(index)); index = 0;
        for (Draft draft : drafts.values()) {
            String prefix = "draft." + index++ + ".";
            out.put(prefix + "id", draft.fingerprint); out.put(prefix + "reply", draft.replyText);
            out.put(prefix + "platform", draft.platform.name()); out.put(prefix + "template", draft.templateKind == null ? "" : draft.templateKind.name());
            out.put(prefix + "status", draft.status.name()); out.put(prefix + "reason", draft.reason.name());
            out.put(prefix + "created", Long.toString(draft.createdAtMillis));
        }
        out.put("draft.count", Integer.toString(index)); index = 0;
        for (Map.Entry<String, Long> attempt : conversationAttempts.entrySet()) {
            String prefix = "attempt." + index++ + ".";
            out.put(prefix + "conversation", attempt.getKey()); out.put(prefix + "time", attempt.getValue().toString());
        }
        out.put("attempt.count", Integer.toString(index));
        return Collections.unmodifiableMap(out);
    }
    private void restore(Map<String, String> in) {
        if (!"2".equals(in.get("schema"))) throw new IllegalArgumentException("Unsupported state schema");
        enabled = bool(in, "enabled"); stopped = bool(in, "stopped");
        if (stopped) enabled = false;
        for (Platform platform : Platform.values()) {
            String prefix = "config." + platform.name() + ".";
            EnumMap<TemplateKind, String> templates = new EnumMap<TemplateKind, String>(TemplateKind.class);
            for (TemplateKind kind : TemplateKind.values()) if (in.containsKey(prefix + "template." + kind.name())) templates.put(kind, in.get(prefix + "template." + kind.name()));
            Map<String, String> values = new LinkedHashMap<String, String>();
            for (String name : PLACEHOLDERS) if (in.containsKey(prefix + "value." + name)) values.put(name, in.get(prefix + "value." + name));
            QuietHours quiet = new QuietHours(bool(in, prefix + "quiet.enabled"), integer(in, prefix + "quiet.start", 1439),
                    integer(in, prefix + "quiet.end", 1439), required(in, prefix + "quiet.zone"));
            configs.put(platform, new Config(Mode.valueOf(required(in, prefix + "mode")), templates, values, quiet,
                    number(in, prefix + "freshness"), number(in, prefix + "cooldown")));
            activationAt.put(platform, Long.valueOf(number(in, prefix + "activation")));
        }
        for (int index = 0, count = integer(in, "record.count", MAX_HISTORY); index < count; index++) {
            String prefix = "record." + index + ".", kind = required(in, prefix + "template");
            Record record = new Record(hashValue(in, prefix + "id"), hashValue(in, prefix + "content"),
                    hashValue(in, prefix + "conversation"), Platform.valueOf(required(in, prefix + "platform")),
                    kind.isEmpty() ? null : TemplateKind.valueOf(kind), Status.valueOf(required(in, prefix + "status")),
                    Reason.valueOf(required(in, prefix + "reason")), number(in, prefix + "received"), number(in, prefix + "updated"));
            if (records.put(record.fingerprint, record) != null) throw new IllegalArgumentException("Duplicate persisted record");
        }
        for (int index = 0, count = integer(in, "draft.count", MAX_DRAFTS); index < count; index++) {
            String prefix = "draft." + index + ".", kind = required(in, prefix + "template");
            String id = hashValue(in, prefix + "id"), reply = required(in, prefix + "reply");
            if (reply.length() > MAX_TEMPLATE_LENGTH || containsUnsafeControl(reply)) throw new IllegalArgumentException("Invalid persisted draft");
            Record record = new Record(id, id, id, Platform.valueOf(required(in, prefix + "platform")),
                    kind.isEmpty() ? null : TemplateKind.valueOf(kind), Status.valueOf(required(in, prefix + "status")),
                    Reason.valueOf(required(in, prefix + "reason")), 0L, 0L);
            if (record.status == Status.SUBMITTED) throw new IllegalArgumentException("Submitted reply cannot remain a draft");
            if (drafts.put(id, new Draft(record, reply, number(in, prefix + "created"))) != null)
                throw new IllegalArgumentException("Duplicate persisted draft");
        }
        for (int index = 0, count = integer(in, "attempt.count", MAX_HISTORY); index < count; index++) {
            String prefix = "attempt." + index + ".";
            String conversation = hashValue(in, prefix + "conversation");
            if (conversationAttempts.put(conversation, Long.valueOf(number(in, prefix + "time"))) != null)
                throw new IllegalArgumentException("Duplicate persisted cooldown");
        }
    }
    private static String required(Map<String, String> in, String key) {
        String value = in.get(key); if (value == null) throw new IllegalArgumentException("Missing persisted field: " + key); return value;
    }
    private static boolean bool(Map<String, String> in, String key) {
        String value = required(in, key);
        if (!"true".equals(value) && !"false".equals(value)) throw new IllegalArgumentException("Invalid persisted boolean");
        return Boolean.parseBoolean(value);
    }
    private static long number(Map<String, String> in, String key) {
        long value = Long.parseLong(required(in, key));
        if (value < 0L) throw new IllegalArgumentException("Negative persisted time"); return value;
    }
    private static int integer(Map<String, String> in, String key, int maximum) {
        long value = number(in, key);
        if (value > maximum) throw new IllegalArgumentException("Persisted count exceeds limit"); return (int)value;
    }
    private static String hashValue(Map<String, String> in, String key) {
        String value = required(in, key);
        if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid persisted hash"); return value;
    }
}
