package com.aihelper.extras;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure intent/text fixes for Eva's existing tools. Does not execute or persist anything. */
public final class ExistingCommandFixes {
    private ExistingCommandFixes() {}

    private static final Pattern POLITE = Pattern.compile(
        "(?i)^(?:could you please|would you please|can you please|could you|would you|can you|please)\\s+");
    private static final Pattern NOTE_ADD = Pattern.compile(
        "(?is)^(?:add|create|save|take|make|write)\\s+(?:(?:a|this|the|my)\\s+)?note(?=\\s|:|$)(.*)$");
    private static final Pattern NOTE_DELETE = Pattern.compile(
        "(?is)^(?:delete|remove|forget)\\s+(?:(?:the|my)\\s+)?note\\s+about\\s+(.+)$");
    private static final Set<String> MEMORY_LISTS = new HashSet<String>(Arrays.asList(
        "what do you remember", "what do you remember about me", "what have you remembered",
        "what have you saved", "show my memory", "show my memories", "show memories",
        "show saved memories", "list memories", "list my memories", "show notes", "list notes",
        "show my notes", "list my notes", "show saved notes", "list saved notes"));
    private static final Pattern TIMER_PREFIX = Pattern.compile(
        "(?is)^(?:set|start|create|make)\\s+(?:a\\s+)?timer(?:\\s+for)?\\s+(.+)$");
    private static final Pattern TIMER_SUFFIX = Pattern.compile(
        "(?is)^(?:(?:set|start|create|make)\\s+(?:a\\s+)?|give\\s+me\\s+(?:a\\s+)?)?(.+?)\\s+timer$");
    private static final Pattern TIMER_CONTROL = Pattern.compile(
        "(?i)^(?:(?:cancel|stop|remove|delete|clear|dismiss|reset|pause|resume|continue)\\s+(?:(?:the|my|active)\\s+)?timer|timer\\s+(?:cancel|stop|remove|delete|clear|dismiss|reset|pause|resume|continue))$");
    private static final Pattern DURATION_PART = Pattern.compile(
        "(?i)\\G\\s*(?:and\\s+|,\\s*)?(.+?)\\s*[- ]?\\s*(seconds?|minutes?|hours?)\\b");
    private static final Pattern WEB_PREFIX = Pattern.compile(
        "(?is)^(search\\s+the\\s+web\\s+for|search\\s+web\\s+for|web\\s+search\\s+for|search\\s+google\\s+for|google\\s+search\\s+for|google\\s+for|google|look\\s+up\\s+online|look\\s+up|search\\s+online\\s+for|open\\s+website|open\\s+web\\s+site|open\\s+url|open\\s+link|go\\s+to|visit)\\s+(.+)$");
    private static final Pattern CLIPBOARD_PREFIX = Pattern.compile(
        "(?is)^(copy\\s+(?:(?:this|that|it|text)\\s+)?to\\s+clipboard|copy\\s+clipboard|put\\s+(?:(?:this|that|it)\\s+)?on\\s+clipboard)\\s+(.+)$");
    private static final Pattern CLIPBOARD_SUFFIX = Pattern.compile(
        "(?is)^(copy|put)\\s+(.+?)\\s+(to\\s+(?:the\\s+)?clipboard|on\\s+(?:the\\s+)?clipboard)$");
    private static final Pattern NUMERIC_TIME = Pattern.compile(
        "(?i)\\bat\\s+([+-]?\\d+)(?::(\\d+))?(?:\\s*(am|pm)\\b)?");
    private static final Pattern NUMERIC_ALARM_TIME = Pattern.compile(
        "(?i)\\b(?:at|for|alarm(?:\\s+(?:for|at))?|wake me(?: up)?)\\s+([+-]?\\d+)(?::(\\d+))?(?:\\s*(am|pm)\\b)?");
    private static final Map<String, Integer> SMALL = new HashMap<String, Integer>();
    private static final Map<String, Integer> TENS = new HashMap<String, Integer>();
    static {
        String[] words = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight",
            "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
            "seventeen", "eighteen", "nineteen"};
        for (int i = 0; i < words.length; i++) SMALL.put(words[i], i);
        SMALL.put("a", 1);
        SMALL.put("an", 1);
        String[] tens = {"twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};
        for (int i = 0; i < tens.length; i++) TENS.put(tens[i], (i + 2) * 10);
    }

    /** Returns an existing command spelling, preserving unrelated requests and payload punctuation. */
    public static String normalizeMessage(String raw) {
        if (raw == null) return null;
        String original = raw.trim();
        String candidate = stripPolite(original);
        String key = commandKey(candidate);
        if (MEMORY_LISTS.contains(key)) return "list memories";
        Matcher note = NOTE_ADD.matcher(candidate);
        if (note.matches()) {
            String body = note.group(1).trim();
            if (body.startsWith(":")) body = body.substring(1).trim();
            if (!body.isEmpty()) return "make a note " + body;
            return original;
        }
        Matcher delete = NOTE_DELETE.matcher(candidate);
        if (delete.matches()) return "forget memory about " + delete.group(1).trim();
        Matcher clipboard = CLIPBOARD_PREFIX.matcher(candidate);
        if (clipboard.matches()) return canonicalPrefix(clipboard.group(1)) + " " + clipboard.group(2);
        Matcher clipboardSuffix = CLIPBOARD_SUFFIX.matcher(candidate);
        if (clipboardSuffix.matches()) return canonicalPrefix(clipboardSuffix.group(1)) + " " +
            clipboardSuffix.group(2) + " " + canonicalPrefix(clipboardSuffix.group(3));
        Matcher web = WEB_PREFIX.matcher(candidate);
        if (web.matches()) return canonicalPrefix(web.group(1)) + " " + web.group(2);
        String duration = timerDuration(candidate);
        String parsed = duration == null ? null : normalizeDuration(duration);
        return parsed == null ? original : "set timer for " + parsed;
    }

    /** Rejects clearly non-calendar intents before the legacy broad calendar branches. */
    public static boolean isCalendarCommandAllowed(String raw) {
        if (raw == null) return false;
        String key = commandKey(stripPolite(raw.trim()));
        if (key.isEmpty()) return false;
        if (key.matches("^(?:remember|forget)(?:\\s.*)?$") ||
            key.matches("^(?:add|create|save|make|take|write|schedule|delete|remove|cancel|complete|finish)\\s+(?:(?:a|an|the|my|this|that|all|of|saved|local)\\s+)*(?:notes?|memory|memories|tasks?|todos?|to[- ]dos?)(?:\\b|:).*$")) return false;
        if (key.matches("^(?:cancel|delete|remove|add|schedule)\\s+(?:(?:a|an|the|my|this|that|all|of|saved|local)\\s+)*(?:sms|texts?|messages?|whatsapp|viber|contacts?|photos?|files?|emails?|accounts?|apps?|alarms?|timers?|subscriptions?)(?:\\b|:).*$")) return false;
        if (key.matches("^(?:remind me|(?:set|create|add)\\s+(?:an?\\s+|my\\s+)?reminder)(?:\\s.*)?$")) return true;
        if (key.matches("^(?:cancel|delete|remove)\\s+(?:it|that)$")) return true;
        if (key.matches("^(?:cancel|delete|remove)\\s+.+$")) return true;
        if (key.matches("^(?:add|schedule|create|put)\\s+.+$")) {
            return Pattern.compile("\\b(?:calendar|event|appointment|meeting)\\b|\\bat\\s+\\d{1,2}\\b")
                .matcher(key).find();
        }
        return key.matches("^(?:what(?:'s|s| is) on my calendar(?: today| tomorrow)?|show my calendar(?: today| tomorrow)?|open my calendar|what do i have (?:today|tomorrow)|do i have anything (?:today|tomorrow)|what(?:'s| is) my schedule(?: today| tomorrow)?|show(?: me)? my schedule(?: today| tomorrow)?|tell me my schedule(?: today| tomorrow)?|what appointments do i have (?:today|tomorrow)|any appointments (?:today|tomorrow)|what meetings do i have (?:today|tomorrow)|any meetings (?:today|tomorrow)|calendar (?:today|tomorrow)|show calendar (?:today|tomorrow))$");
    }

    /** Anchors actual media controls and existing now-playing requests. */
    public static boolean isMediaCommandAllowed(String raw) {
        if (raw == null) return false;
        String key = commandKey(stripPolite(raw.trim()));
        if (key.isEmpty() || Pattern.compile("\\b(?:timer|alarm)\\b").matcher(key).find()) return false;
        if (key.matches("^(?:play|pause|resume|stop|next|previous|skip)$")) return true;
        if (key.matches("^(?:tell me )?(?:what is playing|what's playing|whats playing|now playing)(?:\\s+(?:on|in)\\s+(?:spotify|youtube music|the music player))?(?: now| right now)?$")) return true;
        String media = "(?:music|song|track|media|playback|spotify|youtube music|podcast|audio)";
        if (key.matches("^(?:pause|stop|resume|continue)\\s+(?:the\\s+)?" + media + "(?:\\s+(?:now|please))?$")) return true;
        if (key.matches("^(?:next|previous|skip)\\s+(?:the\\s+)?(?:song|track|music|podcast)(?:\\s+please)?$")) return true;
        if (key.matches("^(?:go back (?:a )?track|back track|last song|skip (?:to )?(?:the )?next(?: (?:song|track))?)$")) return true;
        if (key.matches("^play\\s+.+$")) {
            return Pattern.compile("\\b" + media + "\\b").matcher(key).find();
        }
        return false;
    }

    /** Optional router guard: never allow partial numeric matches in malformed timer requests. */
    public static boolean isTimerCommandAllowed(String raw) {
        if (raw == null) return false;
        String candidate = stripPolite(raw.trim());
        String key = commandKey(candidate);
        if (TIMER_CONTROL.matcher(key).matches()) return true;
        String duration = timerDuration(candidate);
        return duration != null && normalizeDuration(duration) != null;
    }

    /** Nullable validation reply, scoped to explicit scheduling requests only. */
    public static String validationError(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String candidate = stripPolite(raw.trim());
        String key = commandKey(candidate);
        if (isExplicitTimerCreation(candidate)) {
            String duration = timerDuration(candidate);
            String normalized = duration == null ? null : normalizeDuration(duration);
            if (normalized == null) return "I couldn't understand the timer duration. Try: start a twenty five minute timer.";
            long seconds = 0L;
            Matcher parts = Pattern.compile("(\\d+) (seconds?|minutes?|hours?)").matcher(normalized);
            try {
                while (parts.find()) {
                    long amount = Long.parseLong(parts.group(1));
                    long factor = parts.group(2).startsWith("hour") ? 3600L : parts.group(2).startsWith("minute") ? 60L : 1L;
                    seconds = Math.addExact(seconds, Math.multiplyExact(amount, factor));
                }
            } catch (ArithmeticException e) {
                return "Timer duration must be between 1 second and 24 hours.";
            }
            return seconds < 1L || seconds > 86400L ? "Timer duration must be between 1 second and 24 hours." : null;
        }
        if (!isExplicitTimeScheduling(key)) return null;
        Matcher relative = Pattern.compile("\\bin\\s+([a-z0-9 -]+?)\\s+(minutes?|hours?)\\b").matcher(key);
        boolean relativeReminder = key.matches("^(?:remind me|(?:set|create|add)\\s+(?:an?\\s+|my\\s+)?reminder)\\b.*") && relative.find();
        // A relative reminder's remaining clock/date words belong to its task text.
        if (relativeReminder) {
            Long amount = parseNumber(relative.group(1).trim());
            if (amount == null) return "I couldn't understand the reminder duration.";
            if (amount < 1L) return "Reminder duration must be greater than zero.";
            long factor = relative.group(2).startsWith("hour") ? 3600000L : 60000L;
            if (amount > (Long.MAX_VALUE - System.currentTimeMillis()) / factor) return "That reminder duration is too large.";
            Matcher secondPart = Pattern.compile("^(?:and\\s+|,\\s*)?(.+?)\\s+(minutes?|hours?)\\b")
                .matcher(key.substring(relative.end()).trim());
            if (secondPart.find() && parseNumber(secondPart.group(1).trim()) != null) {
                return "Please use one reminder duration, for example: remind me in 90 minutes to call Mom.";
            }
            return null;
        }
        Matcher time = (isAlarmScheduling(key) ? NUMERIC_ALARM_TIME : NUMERIC_TIME).matcher(key);
        boolean foundTime = time.find();
        String dateScope = key;
        if (foundTime) {
            Matcher bodyConnector = Pattern.compile("\\s+(?:to|about|that)\\s+").matcher(key);
            bodyConnector.region(time.end(), key.length());
            if (bodyConnector.find()) dateScope = key.substring(0, bodyConnector.start());
        }
        if (hasUnsupportedDatePhrase(dateScope)) {
            return "I can use today or tomorrow for this command. Please give a supported day and time.";
        }
        // The legacy deterministic scheduler consumes the first numeric time only.
        if (foundTime) {
            String hourText = time.group(1);
            String minuteText = time.group(2);
            String ampm = time.group(3);
            if (hourText.startsWith("+") || hourText.startsWith("-")) return "That time isn't valid.";
            int hour;
            int minute = 0;
            try {
                hour = Integer.parseInt(hourText);
                if (minuteText != null) minute = Integer.parseInt(minuteText);
            } catch (NumberFormatException e) {
                return "That time isn't valid.";
            }
            if (hourText.length() > 2 || (ampm != null ? hour < 1 || hour > 12 : hour > 23)) return "That time isn't valid.";
            if (minuteText != null && (minuteText.length() != 2 || minute > 59)) return "That time isn't valid. Use two digits for minutes, for example 9:05 AM.";
            if (time.end() < key.length()) {
                char next = key.charAt(time.end());
                if (next == ':' || Character.isLetterOrDigit(next)) return "That time isn't valid.";
            }
        }
        return null;
    }

    private static boolean isExplicitTimerCreation(String candidate) {
        String key = commandKey(candidate);
        if (TIMER_CONTROL.matcher(key).matches()) return false;
        if (key.matches("^(?:set|start|create|make)\\s+(?:a\\s+)?timer(?:\\s.*)?$")) return true;
        if (!key.endsWith(" timer")) return false;
        String numberStart = "(?:\\d+|zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety)";
        if (key.matches("^(?:(?:set|start|create|make)\\s+|give\\s+me\\s+)?(?:a\\s+)?" + numberStart + "\\b.*")) return true;
        if (key.matches("^(?:a|an)\\s+(?:second|minute|hour)\\b.*")) return true;
        return key.matches("^(?:(?:set|start|create|make)\\s+|give\\s+me\\s+)(?:a\\s+)?.*\\b(?:seconds?|minutes?|hours?)\\b.* timer$");
    }

    private static boolean isExplicitTimeScheduling(String key) {
        if (NOTE_ADD.matcher(key).matches() || NOTE_DELETE.matcher(key).matches() || key.startsWith("remember ")) return false;
        if (key.matches("^(?:remind me|(?:set|create|add)\\s+(?:an?\\s+|my\\s+)?reminder)\\b.*")) return true;
        if (isAlarmScheduling(key)) return true;
        return key.matches("^(?:add|schedule|create|put)\\s+.+$") && isCalendarCommandAllowed(key);
    }

    private static boolean isAlarmScheduling(String key) {
        return key.matches("^(?:(?:set|put|create|make)(?: me)?(?: an)? alarm|wake me(?: up)?|alarm (?:for|at))\\b.*");
    }

    private static boolean hasUnsupportedDatePhrase(String key) {
        String weekday = "(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)";
        return Pattern.compile("\\b(?:next|this|on)\\s+" + weekday + "\\b(?!['’])|\\b" + weekday + "\\s+at\\b|\\bat\\s+\\d{1,2}(?::\\d+)?\\s*(?:am|pm)?\\s+(?:on\\s+)?" + weekday + "\\b(?!['’])|\\b(?:next|this)\\s+(?:week|month|year|weekend)\\b|\\b(?:tonight|yesterday)\\b|\\bon\\s+\\d{1,2}[/-]\\d{1,2}(?:[/-]\\d{2,4})?\\b|\\b\\d{4}-\\d{1,2}-\\d{1,2}\\b|\\b(?:january|february|march|april|may|june|july|august|september|october|november|december)\\s+\\d{1,2}\\b")
            .matcher(key).find();
    }

    private static String canonicalPrefix(String prefix) {
        return prefix.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String stripPolite(String text) {
        return POLITE.matcher(text).replaceFirst("").trim();
    }

    private static String commandKey(String text) {
        String key = text.trim().toLowerCase(Locale.ROOT);
        while (!key.isEmpty() && ".?!,;".indexOf(key.charAt(key.length() - 1)) >= 0) {
            key = key.substring(0, key.length() - 1).trim();
        }
        return key.replaceAll("\\s+", " ");
    }

    private static String timerDuration(String candidate) {
        String text = candidate.trim();
        while (!text.isEmpty() && ".?!,;".indexOf(text.charAt(text.length() - 1)) >= 0) {
            text = text.substring(0, text.length() - 1).trim();
        }
        Matcher prefix = TIMER_PREFIX.matcher(text);
        if (prefix.matches()) return prefix.group(1).trim();
        Matcher suffix = TIMER_SUFFIX.matcher(text);
        return suffix.matches() ? suffix.group(1).trim() : null;
    }

    private static String normalizeDuration(String body) {
        if (body.isEmpty() || body.length() > 200) return null;
        Matcher parts = DURATION_PART.matcher(body);
        List<String> normalized = new ArrayList<String>();
        int end = 0;
        while (parts.find()) {
            if (parts.start() != end) return null;
            if (normalized.isEmpty() && !body.substring(parts.start(), parts.start(1)).trim().isEmpty()) return null;
            String numberText = parts.group(1).trim();
            Long number = parseNumber(numberText);
            if (number == null) return null;
            String separator = body.substring(parts.end(1), parts.start(2));
            if (!numberText.matches("\\d+") && separator.isEmpty()) return null;
            normalized.add(number + " " + parts.group(2).toLowerCase(Locale.ROOT));
            end = parts.end();
        }
        if (normalized.isEmpty() || !body.substring(end).trim().isEmpty()) return null;
        StringBuilder result = new StringBuilder();
        for (String part : normalized) {
            if (result.length() != 0) result.append(' ');
            result.append(part);
        }
        return result.toString();
    }

    private static Long parseNumber(String raw) {
        if (raw.matches("\\d+")) {
            try { return Long.valueOf(raw); } catch (NumberFormatException e) { return null; }
        }
        if (!raw.matches("[A-Za-z]+(?:[ -]+[A-Za-z]+)*")) return null;
        String[] words = raw.toLowerCase(Locale.ROOT).replace('-', ' ').trim().split("\\s+");
        int thousand = -1;
        for (int i = 0; i < words.length; i++) {
            if (words[i].equals("thousand")) {
                if (thousand != -1) return null;
                thousand = i;
            }
        }
        if (thousand == -1) {
            Integer value = belowThousand(words, 0, words.length);
            return value == null ? null : value.longValue();
        }
        Integer head = belowThousand(words, 0, thousand);
        if (head == null || head <= 0) return null;
        int tailStart = thousand + 1;
        if (tailStart == words.length) return head.longValue() * 1000L;
        if (words[tailStart].equals("and")) tailStart++;
        Integer tail = belowThousand(words, tailStart, words.length);
        return tail == null ? null : head.longValue() * 1000L + tail.longValue();
    }

    private static Integer belowThousand(String[] words, int start, int end) {
        if (start >= end) return null;
        if (start + 1 < end && words[start + 1].equals("hundred")) {
            Integer head = SMALL.get(words[start]);
            if (head == null || head < 1 || head > 9) return null;
            int rest = start + 2;
            if (rest == end) return head * 100;
            if (words[rest].equals("and")) rest++;
            Integer tail = belowHundred(words, rest, end);
            return tail == null ? null : head * 100 + tail;
        }
        return belowHundred(words, start, end);
    }

    private static Integer belowHundred(String[] words, int start, int end) {
        if (start >= end) return null;
        if (end - start == 1) {
            Integer small = SMALL.get(words[start]);
            return small == null ? TENS.get(words[start]) : small;
        }
        if (end - start == 2) {
            Integer tens = TENS.get(words[start]);
            Integer ones = SMALL.get(words[start + 1]);
            return tens != null && ones != null && ones >= 1 && ones <= 9 ? tens + ones : null;
        }
        return null;
    }
}
