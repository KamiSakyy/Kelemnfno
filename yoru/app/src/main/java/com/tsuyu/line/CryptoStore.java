package com.tsuyu.line;

import android.content.SharedPreferences;
import android.security.keystore.*;
import android.util.Base64;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

public final class CryptoStore {
    private static final String ALIAS = "yoru-local-v1";
    private final SecretKey key;

    public CryptoStore() {
        key = initKey();
    }

    public boolean hasKey() {
        return key != null;
    }

    private SecretKey initKey() {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (!ks.containsAlias(ALIAS)) {
                KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
                gen.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build());
                gen.generateKey();
            }
            return (SecretKey) ks.getKey(ALIAS, null);
        } catch (Exception e) {
            return null;
        }
    }

    public JSONObject read(SharedPreferences prefs, String name) {
        String value = prefs.getString(name, "");
        if (value.isEmpty() || key == null) return new JSONObject();
        try {
            byte[] all = Base64.decode(value, Base64.NO_WRAP);
            int len = all[0] & 255;
            if (len < 12 || len > 16 || all.length <= len + 1) return new JSONObject();
            byte[] iv = Arrays.copyOfRange(all, 1, 1 + len);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new JSONObject(new String(c.doFinal(all, 1 + len, all.length - len - 1), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public void write(SharedPreferences prefs, String name, JSONObject json) {
        if (key == null || json == null) return;
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = c.getIV();
            byte[] cipher = c.doFinal(json.toString().getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[1 + iv.length + cipher.length];
            all[0] = (byte) iv.length;
            System.arraycopy(iv, 0, all, 1, iv.length);
            System.arraycopy(cipher, 0, all, 1 + iv.length, cipher.length);
            prefs.edit().putString(name, Base64.encodeToString(all, Base64.NO_WRAP)).apply();
        } catch (Exception ignored) {}
    }
}
