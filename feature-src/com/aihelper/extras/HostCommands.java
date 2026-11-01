package com.aihelper.extras;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.aihelper.extras.HostAutomationCore.*;
import com.aihelper.extras.HostAutomationCore.Record;

/** Commands configure explicit rules; previews never invoke the sender. */
public final class HostCommands {
    private final HostAutomationCore core;
    private static final Pattern SET = Pattern.compile("^host\\s+(airbnb|pulse|booking)\\s+(template|value)\\s+([a-z_]+)\\s*:\\s*(.+)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern MODE = Pattern.compile("^host\\s+(airbnb|pulse|booking)\\s+mode\\s+(off|draft|auto|smart)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PREVIEW = Pattern.compile("^host\\s+(airbnb|pulse|booking)\\s+preview\\s+([a-z_]+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUIET = Pattern.compile("^host\\s+(airbnb|pulse|booking)\\s+quiet\\s+(off|([0-2][0-9]):([0-5][0-9])-([0-2][0-9]):([0-5][0-9]))$", Pattern.CASE_INSENSITIVE);

    public HostCommands(HostAutomationCore core) { this.core = core; }
    public static boolean recognizes(String raw) {
        return raw != null && raw.trim().toLowerCase(Locale.ROOT).matches("(?s)host(?:\\s.*)?");
    }
    public String handle(String raw) {
        if (!recognizes(raw)) return null;
        String command = raw.trim(), lower = command.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        try {
            if (lower.equals("host") || lower.equals("host help")) return help();
            if (lower.equals("host status")) return status();
            if (lower.equals("host pause")) { core.setEnabled(false); return "Guest automation paused. Templates and drafts are saved."; }
            if (lower.equals("host resume")) { core.resume(); return "Guest automation enabled for the modes below. Only new incoming notifications are eligible.\n" + status(); }
            if (lower.equals("host stop")) { core.emergencyStop(); return "Guest automation stopped. Use host resume when ready."; }
            if (lower.equals("host drafts")) return drafts();
            if (lower.equals("host history")) return history();
            if (lower.startsWith("host discard ")) return discard(lower.substring(13).trim());
            Matcher m = MODE.matcher(command);
            if (m.matches()) {
                Platform p = platform(m.group(1)); Mode mode = Mode.valueOf(m.group(2).toUpperCase(Locale.ROOT));
                core.configure(p, core.getConfig(p).withMode(mode));
                return label(p) + " mode: " + mode + ". " + (core.isEnabled() ? "Applies to new incoming messages." : "Use host resume to start processing.")
                        + (mode == Mode.AUTO || mode == Mode.SMART ? " Sending requires a supported notification reply action; other messages become drafts." : "");
            }
            m = SET.matcher(command);
            if (m.matches()) {
                Platform p = platform(m.group(1)); Config config = core.getConfig(p); String name = m.group(3).toLowerCase(Locale.ROOT);
                boolean template = m.group(2).equalsIgnoreCase("template");
                core.configure(p, template ? config.withTemplate(kind(name), m.group(4)) : config.withValue(name, m.group(4)));
                return label(p) + " " + (template ? "template " : "value ") + name + " saved. Use host " + p.name().toLowerCase(Locale.ROOT) + " preview general to review the reply.";
            }
            m = PREVIEW.matcher(command);
            if (m.matches()) {
                Platform p = platform(m.group(1)); Config config = core.getConfig(p); String template = config.templates.get(kind(m.group(2)));
                if (template == null) return "Set this template first. Try host help.";
                Map<String,String> values = new LinkedHashMap<String,String>(config.values); values.put("guest_name", "Guest");
                return "Preview only · " + label(p) + " · example name: Guest\n\n" + HostAutomationCore.renderTemplate(template, values);
            }
            m = QUIET.matcher(command);
            if (m.matches()) {
                Platform p = platform(m.group(1)); QuietHours hours;
                if (m.group(2).equalsIgnoreCase("off")) hours = QuietHours.disabled();
                else {
                    int start = minute(m.group(3), m.group(4)), end = minute(m.group(5), m.group(6));
                    hours = new QuietHours(true, start, end, ZoneId.systemDefault().getId());
                }
                core.configure(p, core.getConfig(p).withQuietHours(hours));
                return label(p) + " quiet hours " + (hours.enabled ? m.group(2) + " (" + hours.zoneId + "). Messages received then become drafts." : "disabled.");
            }
            return "I did not change any rules. Use host help for exact commands.";
        } catch (IllegalArgumentException | IllegalStateException error) {
            return "Rule could not be saved: " + error.getMessage();
        } catch (HostAutomationCore.PersistenceException error) {
            return "Guest automation could not save its settings and has stopped. Restart Eva and check host status.";
        }
    }
    private static int minute(String hour, String minute) {
        int h = Integer.parseInt(hour); if (h > 23) throw new IllegalArgumentException("Quiet-hour clocks must be 00:00 to 23:59");
        return h * 60 + Integer.parseInt(minute);
    }
    private static TemplateKind kind(String name) { return TemplateKind.valueOf(name.toUpperCase(Locale.ROOT)); }
    private static Platform platform(String name) { return name.equalsIgnoreCase("airbnb") ? Platform.AIRBNB : Platform.PULSE; }
    private static String label(Platform p) { return p == Platform.AIRBNB ? "Airbnb" : "Booking Pulse"; }
    private static String clock(int minute) { return String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60); }
    public String status() {
        StringBuilder out = new StringBuilder("Guest automation: ").append(core.isEmergencyStopped() ? "STOPPED" : core.isEnabled() ? "ON" : "PAUSED");
        for (Platform p : Platform.values()) {
            Config c = core.getConfig(p);
            out.append("\n").append(label(p)).append(": ").append(c.mode).append(" · ").append(c.values.size()).append(" saved details");
            if (c.quietHours.enabled) out.append(" · quiet ").append(clock(c.quietHours.startMinute)).append("–").append(clock(c.quietHours.endMinute)).append(" ").append(c.quietHours.zoneId);
        }
        return out.append("\nDrafts: ").append(core.drafts().size()).append(" · history: ").append(core.history().size())
                .append("\nSUBMITTED means accepted by the host app's reply action. Guest delivery is not confirmed.").toString();
    }
    private String drafts() {
        List<Draft> items = core.drafts(); if (items.isEmpty()) return "No guest drafts yet.";
        StringBuilder out = new StringBuilder("Guest drafts (latest 10):");
        for (int i = items.size() - 1; i >= Math.max(0, items.size() - 10); i--) {
            Draft d = items.get(i); out.append("\n\n").append(d.fingerprint.substring(0,12)).append(" · ").append(label(d.platform)).append(" · ").append(d.status).append(" · ").append(d.reason);
            if (!d.replyText.isEmpty()) out.append("\n").append(d.replyText);
        }
        return out.append("\n\nOpen the original host message to review/send a draft. host discard <id> removes a draft.").toString();
    }
    private String history() {
        List<Record> items = core.history(); if (items.isEmpty()) return "No guest automation history yet.";
        StringBuilder out = new StringBuilder("Guest reply history (latest 10):");
        for (int i = items.size() - 1; i >= Math.max(0, items.size() - 10); i--) {
            Record r = items.get(i); out.append("\n").append(label(r.platform)).append(" · ").append(r.status).append(" · ").append(r.reason);
        }
        return out.append("\nSUBMITTED confirms app submission only. REVIEW_REQUIRED items are never retried automatically.").toString();
    }
    private String discard(String id) {
        if (!id.matches("[a-f0-9]{12,64}")) return "Use the draft id shown by host drafts.";
        Draft match = null;
        for (Draft d : core.drafts()) if (d.fingerprint.startsWith(id)) {
            if (match != null) return "Draft id is ambiguous; use its full id.";
            match = d;
        }
        if (match == null) return "No matching draft.";
        core.discardDraft(match.fingerprint); return "Draft removed.";
    }
    public static String help() {
        return "Eva guest assistant · Airbnb and Booking Pulse\n\n"
            + "Smart mode reads new messages in the background, identifies questions with local AI and replies using saved property facts. It does not need a command for each message.\n\n"
            + "1. host setup — one-time property details, modes and notification access\n"
            + "2. Save details for each host app; smart replies are enabled automatically.\n"
            + "3. Use host status to check readiness.\n\n"
            + "Use pulse in place of airbnb for Booking Pulse. Modes: off, draft, auto, smart. Smart chooses the wording without a custom template. Sending requires a supported notification reply action.\n\n"
            + "Templates: general, check_in, check_out, wifi, parking.\n"
            + "Example: host pulse template wifi: The Wi-Fi is {wifi_name} and the password is {wifi_password}.\n"
            + "Set facts: host pulse value wifi_name: YOUR NETWORK\n"
            + "Preview: host pulse preview wifi\n"
            + "Quiet hours: host pulse quiet 22:00-08:00 (or off)\n\n"
            + "host status · host drafts · host history · host pause · host stop\n"
            + "Rules apply to eligible conversations across the selected app. Confirm the scope in Guest settings before facts are used. Smart mode combines questions, acknowledges missing details and alerts you for decisions. Unavailable replies become drafts.";
    }
}
