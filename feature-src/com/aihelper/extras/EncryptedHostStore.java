package com.aihelper.extras;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** App-private encrypted snapshot, synchronously committed before reply dispatch. */
public final class EncryptedHostStore implements HostAutomationCore.Store {
    private final String alias;
    private final SharedPreferences preferences;
    private final Context appContext;
    public EncryptedHostStore(Context context) {
        this(context, "eva_guest_rules", "eva_guest_rules_v1");
    }
    public EncryptedHostStore(Context context, String preferenceName, String alias) {
        appContext = context.getApplicationContext();
        this.alias = alias;
        preferences = appContext.getSharedPreferences(preferenceName, Context.MODE_PRIVATE);
    }
    private SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(alias)) {
            if (!create) throw new IllegalStateException("Encrypted guest-rule key is unavailable");
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            return generator.generateKey();
        }
        return (SecretKey)store.getKey(alias, null);
    }
    @Override public synchronized Map<String,String> load() {
        String data = preferences.getString("snapshot", null);
        if (data == null) return new LinkedHashMap<String,String>();
        try {
            JSONObject envelope = new JSONObject(data);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)));
            JSONObject object = new JSONObject(new String(cipher.doFinal(Base64.decode(envelope.getString("data"), Base64.NO_WRAP)), StandardCharsets.UTF_8));
            Map<String,String> result = new LinkedHashMap<String,String>(); Iterator<String> keys = object.keys();
            while (keys.hasNext()) { String name = keys.next(); result.put(name, object.getString(name)); }
            return result;
        } catch (Exception error) {
            GuestControl.setBlocked(appContext, true);
            throw new IllegalStateException("Guest settings could not be decrypted", error);
        }
    }
    @Override public synchronized void save(Map<String,String> snapshot) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key(true));
            byte[] encrypted = cipher.doFinal(new JSONObject(snapshot).toString().getBytes(StandardCharsets.UTF_8));
            JSONObject envelope = new JSONObject(); envelope.put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
            envelope.put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP));
            if (!preferences.edit().putString("snapshot", envelope.toString()).commit()) throw new IllegalStateException("Guest settings commit failed");
        } catch (Exception error) {
            try { GuestControl.setBlocked(appContext, true); }
            catch (RuntimeException latchFailure) {
                throw new IllegalStateException("Could not save guest settings or durably stop automation", latchFailure);
            }
            throw new IllegalStateException("Guest settings could not be saved; durable stop is active", error);
        }
    }
}
