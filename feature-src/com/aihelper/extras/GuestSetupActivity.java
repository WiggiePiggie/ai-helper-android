package com.aihelper.extras;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.aihelper.extras.HostAutomationCore.Platform;

/** One-time owner setup. Preview is local and never invokes a sender or model. */
public final class GuestSetupActivity extends Activity {
    private static final String LISTENER = "com.aihelper.control.UniversalNotificationService";
    private static final String[] KEYS = {"property_name", "property_address", "check_in_time",
        "check_out_time", "wifi_details", "wifi_name", "wifi_password", "parking_details", "house_rules",
        "directions", "breakfast_details", "pet_policy"};
    private static final String[] LABELS = {"Property name", "Property address", "Check-in time",
        "Check-out time", "Wi-Fi availability", "Wi-Fi network", "Wi-Fi password", "Parking", "House rules",
        "Directions", "Breakfast", "Pet policy"};

    private final Map<String, EditText> fields = new LinkedHashMap<String, EditText>();
    private final Map<Platform, Map<String, String>> drafts = new EnumMap<Platform, Map<String, String>>(Platform.class);
    private final Map<Platform, Boolean> confirmations = new EnumMap<Platform, Boolean>(Platform.class);
    private final List<View> controls = new ArrayList<View>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Platform selected;
    private Spinner platform;
    private CheckBox confirmed;
    private EditText question;
    private TextView status, result, preview, attention;
    private boolean loading, busy, destroyed;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("Eva Guest Assistant");
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(12), dp(20), dp(24));
        scroll.addView(form, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        text(form, "Eva Guest Assistant", 24);
        text(form, "Save your property details once. Eva uses them to answer new guest messages automatically.", 16);
        text(form, "Automatic replies need notification access and a Reply action on the guest notification. Messages without Reply stay for your attention.", 15);
        status = text(form, "", 15);
        button(form, "Notification access", new View.OnClickListener() {
            public void onClick(View v) { openNotificationAccess(); }
        });

        text(form, "Guest app", 16);
        platform = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
            android.R.layout.simple_spinner_item, new String[]{"Airbnb", "Booking Pulse"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        platform.setAdapter(adapter);
        platform.setSaveEnabled(false);
        add(form, platform);
        controls.add(platform);

        text(form, "These details apply to every guest conversation in the selected app. Notifications do not identify the property. Enter only details that fit all those conversations, and leave unknown details blank.", 15);
        for (int i = 0; i < KEYS.length; i++) {
            text(form, LABELS[i], 16);
            final EditText input = new EditText(this);
            input.setContentDescription(LABELS[i]);
            input.setSaveEnabled(false);
            boolean password = "wifi_password".equals(KEYS[i]);
            boolean multiline = "property_address".equals(KEYS[i]) || KEYS[i].endsWith("details")
                || "house_rules".equals(KEYS[i]) || "directions".equals(KEYS[i]) || "pet_policy".equals(KEYS[i]);
            input.setInputType(InputType.TYPE_CLASS_TEXT | (password ? InputType.TYPE_TEXT_VARIATION_PASSWORD
                : multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                : "wifi_name".equals(KEYS[i]) ? InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS : InputType.TYPE_TEXT_FLAG_CAP_SENTENCES));
            if (multiline) { input.setMinLines(2); input.setMaxLines(5); }
            else input.setSingleLine(true);
            if (password) input.setTransformationMethod(PasswordTransformationMethod.getInstance());
            input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
            input.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (!loading && confirmed != null) {
                        confirmed.setChecked(false);
                        preview.setText("");
                        result.setText("Details changed. Confirm and save them to update automatic replies.");
                    }
                }
                public void afterTextChanged(Editable value) { }
            });
            fields.put(KEYS[i], input);
            controls.add(input);
            add(form, input);
        }
        confirmed = new CheckBox(this);
        confirmed.setSaveEnabled(false);
        add(form, confirmed);
        controls.add(confirmed);
        text(form, "Saving enables smart replies for this app. Until you confirm the details, Eva sends acknowledgements without property information. Refunds, booking changes, access codes and urgent issues need your attention.", 15);
        result = text(form, "", 15);
        button(form, "Save and enable smart replies", new View.OnClickListener() {
            public void onClick(View v) { save(); }
        });
        text(form, "Pause and resume affect both guest apps. Resume uses the details already saved.", 15);
        button(form, "Pause replies", new View.OnClickListener() {
            public void onClick(View v) { control("host pause"); }
        });
        button(form, "Resume replies", new View.OnClickListener() {
            public void onClick(View v) {
                control("host resume");
            }
        });
        text(form, "Try a reply", 20);
        text(form, "Enter an example guest question. The preview uses the details currently in this form and sends nothing.", 15);
        question = new EditText(this);
        question.setHint("What time is check-in?");
        question.setContentDescription("Example guest question");
        question.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        question.setMaxLines(4);
        add(form, question);
        button(form, "Preview reply", new View.OnClickListener() {
            public void onClick(View v) { previewReply(); }
        });
        preview = text(form, "", 16);
        preview.setTextIsSelectable(true);
        text(form, "Needs your attention", 20);
        attention = text(form, "", 15);
        button(form, "Mark requests reviewed", new View.OnClickListener() {
            public void onClick(View v) { clearAttention(); }
        });

        Platform initial = Platform.AIRBNB;
        if (state != null) {
            try { initial = Platform.valueOf(state.getString("selected", Platform.AIRBNB.name())); }
            catch (IllegalArgumentException invalid) { initial = Platform.AIRBNB; }
            for (Platform p : Platform.values()) {
                Bundle saved = state.getBundle("draft." + p.name());
                if (saved != null) {
                    Map<String, String> values = new LinkedHashMap<String, String>();
                    for (String key : KEYS) values.put(key, saved.getString(key, ""));
                    drafts.put(p, values);
                    confirmations.put(p, Boolean.valueOf(saved.getBoolean("confirmed", false)));
                }
            }
        }
        platform.setSelection(initial == Platform.AIRBNB ? 0 : 1);
        switchPlatform(initial);
        platform.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Platform next = position == 0 ? Platform.AIRBNB : Platform.PULSE;
                if (next != selected) switchPlatform(next);
            }
            public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private void switchPlatform(Platform next) {
        if (selected != null) rememberDraft();
        selected = next;
        loading = true;
        try {
            Map<String, String> values = drafts.get(next);
            if (values == null) values = FeatureExtension.engine(this).getConfig(next).values;
            for (String key : KEYS) {
                String value = values.get(key);
                fields.get(key).setText(value == null ? "" : value);
                fields.get(key).setError(null);
            }
            Boolean draftConfirmation = confirmations.get(next);
            confirmed.setChecked(draftConfirmation == null ? FeatureExtension.factsConfirmed(this, next)
                : draftConfirmation.booleanValue());
            confirmed.setText("I confirm these details apply to every guest conversation in " + appName(next) + ".");
            result.setText("");
            preview.setText("");
        } catch (RuntimeException unavailable) {
            for (EditText field : fields.values()) field.setText("");
            confirmed.setChecked(false);
            result.setText("Saved details are unavailable. Pause replies and try again before saving new details.");
        } finally { loading = false; }
        refreshStatus();
    }

    private Map<String, String> formValues() {
        Map<String, String> values = new LinkedHashMap<String, String>();
        for (String key : KEYS) values.put(key, fields.get(key).getText().toString());
        return values;
    }

    private Map<String, String> validatedValues() {
        Map<String, String> values = formValues();
        for (int i = 0; i < KEYS.length; i++) {
            EditText input = fields.get(KEYS[i]);
            input.setError(null);
            String value = values.get(KEYS[i]);
            boolean wifi = "wifi_name".equals(KEYS[i]) || "wifi_password".equals(KEYS[i]);
            if (value.length() > SmartGuestPolicy.MAX_FACT_LENGTH) {
                input.setError("Keep this detail to " + SmartGuestPolicy.MAX_FACT_LENGTH + " characters.");
                input.requestFocus();
                result.setText(LABELS[i] + " is too long. Your text has been kept for editing.");
                return null;
            }
            for (int j = 0; j < value.length(); j++) {
                char c = value.charAt(j);
                if (wifi && (c == '\n' || c == '\r' || c == '\t')) {
                    input.setError("Use one line without tabs or line breaks.");
                    input.requestFocus();
                    result.setText("Please check " + LABELS[i].toLowerCase(java.util.Locale.ROOT) + ". Spaces are kept exactly as entered.");
                    return null;
                }
                if (Character.isISOControl(c) && c != '\n' && c != '\t') {
                    input.setError("Remove unsupported control characters.");
                    input.requestFocus();
                    result.setText("Please check " + LABELS[i].toLowerCase(java.util.Locale.ROOT) + ".");
                    return null;
                }
            }
            if (!wifi && (value.indexOf('{') >= 0 || value.indexOf('}') >= 0)) {
                input.setError("Use plain text without { or }.");
                input.requestFocus();
                result.setText("Please check " + LABELS[i].toLowerCase(java.util.Locale.ROOT) + ". Your text has been kept for editing.");
                return null;
            }
            if (value.trim().isEmpty()) values.remove(KEYS[i]);
        }
        return values;
    }

    private void save() {
        if (busy) return;
        final Map<String, String> values = validatedValues();
        if (values == null) return;
        final boolean savingConfirmation = confirmed.isChecked();
        final Platform savingPlatform = selected;
        setBusy(true);
        result.setText("Saving setup...");
        worker.execute(new Runnable() {
            public void run() {
                String message;
                boolean success = false;
                try {
                    FeatureExtension.saveSmartSetup(GuestSetupActivity.this, savingPlatform, values, savingConfirmation);
                    int savedCount = FeatureExtension.engine(GuestSetupActivity.this).getConfig(savingPlatform).values.size();
                    success = true;
                    message = "Saved " + savedCount + " property " + (savedCount == 1 ? "detail" : "details") + " for " + appName(savingPlatform) + ". "
                        + (savingConfirmation ? "Smart replies can use these details for new messages."
                            : "Automatic acknowledgements are enabled. Confirm and save to let replies use your property details.")
                        + " Notification access and Reply support are required.";
                } catch (Exception failure) {
                    message = "Setup could not be saved. Check the reply status and try again before resuming.";
                }
                final String feedback = message;
                final boolean saved = success;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (destroyed) return;
                        setBusy(false);
                        if (saved) rememberDraft();
                        result.setText(feedback);
                        refreshStatus();
                    }
                });
            }
        });
    }

    private void control(final String command) {
        if (busy) return;
        setBusy(true);
        worker.execute(new Runnable() {
            public void run() {
                String reply;
                try {
                    reply = FeatureExtension.tryHandle(GuestSetupActivity.this, command);
                    HostAutomationCore core = FeatureExtension.engine(GuestSetupActivity.this);
                    if ("host pause".equals(command) && GuestControl.blocked(GuestSetupActivity.this)
                            && !GuestControl.stopWriteFailed())
                        reply = "Automatic replies are paused.";
                    else if ("host resume".equals(command) && core.isEnabled() && !core.isEmergencyStopped()
                            && !GuestControl.blocked(GuestSetupActivity.this))
                        reply = "Replies resumed for saved modes. New messages need notification access and Reply support.";
                }
                catch (RuntimeException failure) { reply = "Could not update reply controls. Check notification access and try again."; }
                final String feedback = reply == null ? "Reply controls are unavailable." : reply;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (destroyed) return;
                        setBusy(false);
                        result.setText(feedback);
                        refreshStatus();
                    }
                });
            }
        });
    }

    private void previewReply() {
        Map<String, String> values = validatedValues();
        if (values == null) return;
        String incoming = question.getText().toString();
        if (incoming.trim().isEmpty()) { question.setError("Enter an example guest question."); return; }
        if (incoming.length() > 10000) { question.setError("Keep the example question to 10,000 characters."); return; }
        question.setError(null);
        SmartGuestPolicy.Decision decision = SmartGuestPolicy.plan(incoming, values);
        preview.setText((decision.kind == SmartGuestPolicy.Kind.OWNER_REQUIRED ? "Needs your attention\n\n" : "Preview reply\n\n")
            + decision.fallbackReply + "\n\nPreview only. Nothing was sent.");
    }

    private void clearAttention() {
        if (busy) return;
        setBusy(true);
        worker.execute(new Runnable() {
            public void run() {
                String message;
                try {
                    FeatureExtension.clearAttention(GuestSetupActivity.this);
                    message = "Requests marked as reviewed. No guest message was sent.";
                } catch (Exception failure) {
                    message = "Requests could not be marked as reviewed. Try again.";
                }
                final String feedback = message;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (destroyed) return;
                        setBusy(false);
                        result.setText(feedback);
                        refreshStatus();
                    }
                });
            }
        });
    }

    private void openNotificationAccess() {
        Intent detail = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                new ComponentName(getPackageName(), LISTENER).flattenToString());
        try { startActivity(detail); }
        catch (android.content.ActivityNotFoundException unavailable) {
            try { startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
            catch (android.content.ActivityNotFoundException missing) {
                result.setText("Open Android Settings, then Notification access, and enable Eva Recovery.");
            }
        }
    }

    private void refreshStatus() {
        if (status == null || selected == null) return;
        try {
            HostAutomationCore core = FeatureExtension.engine(this);
            boolean evaStopped = getSharedPreferences("ai_helper_safety", MODE_PRIVATE).getBoolean("stopped", false);
            boolean paused = evaStopped || GuestControl.blocked(this) || !core.isEnabled() || core.isEmergencyStopped();
            HostAutomationCore.Config saved = core.getConfig(selected);
            boolean smart = saved.mode == HostAutomationCore.Mode.SMART;
            boolean facts = FeatureExtension.factsConfirmed(this, selected);
            android.content.SharedPreferences seen = getSharedPreferences("eva_host_readiness", MODE_PRIVATE);
            String replySupport = !seen.contains(selected.packageName + ".seen") ? "not checked yet"
                : seen.getBoolean(selected.packageName + ".reply", false) ? "available on last guest notification" : "unavailable on last guest notification";
            status.setText(appName(selected) + ": " + (paused ? "replies paused" : smart
                ? facts ? "smart replies enabled" : "automatic acknowledgements enabled" : "saved mode: " + saved.mode.name().toLowerCase(java.util.Locale.ROOT))
                + "\nSaved property details: " + saved.values.size() + (facts ? " · confirmed" : " · not confirmed")
                + "\nNotification access: " + (FeatureExtension.hasNotificationAccess(this) ? "enabled" : "not enabled")
                + "\nGuest listener: " + (FeatureExtension.listenerConnected(this) ? "connected"
                    : FeatureExtension.hasNotificationAccess(this) ? "waiting for Android to connect" : "notification access required")
                + "\nGuest alerts: " + (getSharedPreferences("host_notifications", MODE_PRIVATE).getBoolean("enabled", true) ? "enabled" : "disabled")
                + "\nReply support: " + replySupport
                + (evaStopped ? "\nResume autonomous mode on Eva's main screen to start replies." : ""));
            String summary = FeatureExtension.attentionSummary(this);
            attention.setText(summary == null || summary.trim().isEmpty() ? "No requests need your attention." : summary);
        } catch (RuntimeException unavailable) {
            status.setText("Saved setup is unavailable. Check available storage and try again.");
            attention.setText("Requests could not be loaded.");
        }
    }

    private void setBusy(boolean value) {
        busy = value;
        for (View view : controls) view.setEnabled(!value);
    }

    private void rememberDraft() {
        if (selected == null) return;
        drafts.put(selected, formValues());
        confirmations.put(selected, Boolean.valueOf(confirmed.isChecked()));
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        rememberDraft();
        if (selected != null) state.putString("selected", selected.name());
        for (Map.Entry<Platform, Map<String, String>> draft : drafts.entrySet()) {
            Bundle saved = new Bundle();
            for (String key : KEYS) saved.putString(key, draft.getValue().get(key));
            saved.putBoolean("confirmed", Boolean.TRUE.equals(confirmations.get(draft.getKey())));
            state.putBundle("draft." + draft.getKey().name(), saved);
        }
        super.onSaveInstanceState(state);
    }

    @Override protected void onResume() { super.onResume(); FeatureExtension.requestListenerConnection(this); refreshStatus(); }
    @Override protected void onDestroy() { destroyed = true; worker.shutdown(); super.onDestroy(); }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    private static String appName(Platform value) { return value == Platform.AIRBNB ? "Airbnb" : "Booking Pulse"; }
    private void add(LinearLayout form, View view) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        layout.bottomMargin = dp(10);
        form.addView(view, layout);
    }
    private TextView text(LinearLayout form, String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); add(form, view); return view;
    }
    private void button(LinearLayout form, String label, View.OnClickListener listener) {
        Button button = new Button(this); button.setText(label); button.setOnClickListener(listener); controls.add(button); add(form, button);
    }
}
