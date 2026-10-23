package io.github.airplayreceiver.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Base64;

import java.security.SecureRandom;


/** Persistent receiver identity and user preferences. */
final class ReceiverSettings {

    private static final String FILE = "receiver_settings";
    private static final String KEY_NAME = "name";
    private static final String KEY_DEVICE_IDENTIFIER = "device_identifier";
    private static final String KEY_IDENTITY_SEED = "identity_seed";
    private static final String KEY_START_ON_BOOT = "start_on_boot";
    private static final String KEY_VIDEO_HEIGHT = "video_height";
    private static final String KEY_REMOVED_HIGH_DEFINITION = "full_high_definition";

    private final Context context;
    private final SharedPreferences preferences;

    ReceiverSettings(Context context) {
        this.context = context.getApplicationContext();
        this.preferences = this.context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    String name() {
        return preferences.getString(KEY_NAME, defaultName());
    }

    /** "Fire TV" on Amazon devices, otherwise the model name, such as "Chromecast". */
    private static String defaultName() {
        if ("Amazon".equalsIgnoreCase(Build.MANUFACTURER)) {
            return "Fire TV";
        }
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        return model.isEmpty() ? "Android TV" : model;
    }

    void setName(String name) {
        preferences.edit().putString(KEY_NAME, name).apply();
    }

    boolean startOnBoot() {
        return preferences.getBoolean(KEY_START_ON_BOOT, true);
    }

    void setStartOnBoot(boolean enabled) {
        preferences.edit().putBoolean(KEY_START_ON_BOOT, enabled).apply();
    }

    /** The mirroring height senders are asked for: 720, 1080 (default) or 2160. */
    int videoHeight() {
        return preferences.getInt(KEY_VIDEO_HEIGHT, 1080);
    }

    void setVideoHeight(int height) {
        preferences.edit().putInt(KEY_VIDEO_HEIGHT, height).remove(KEY_REMOVED_HIGH_DEFINITION).apply();
    }

    /** A random, locally administered unicast MAC-style identifier, created once. */
    byte[] deviceIdentifier() {
        byte[] value = readBytes(KEY_DEVICE_IDENTIFIER, 6);
        value[0] = (byte) ((value[0] & 0xFC) | 0x02);
        return value;
    }

    /** The Ed25519 seed behind the advertised public key, created once. */
    byte[] identitySeed() {
        return readBytes(KEY_IDENTITY_SEED, 32);
    }

    private byte[] readBytes(String key, int length) {
        String stored = preferences.getString(key, null);
        if (stored != null) {
            byte[] decoded = Base64.decode(stored, Base64.NO_WRAP);
            if (decoded.length == length) {
                return decoded;
            }
        }
        byte[] value = new byte[length];
        new SecureRandom().nextBytes(value);
        if (length == 6) {
            value[0] = (byte) ((value[0] & 0xFC) | 0x02);
        }
        preferences.edit().putString(key, Base64.encodeToString(value, Base64.NO_WRAP)).commit();
        return value;
    }
}
