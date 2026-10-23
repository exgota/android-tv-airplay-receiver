package io.github.airplayreceiver.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

/** Identity and capabilities that the receiver advertises. */
public final class ReceiverConfiguration {

    /**
     * AirPlay feature bits, as advertised by UxPlay: photo, screen mirroring, audio, legacy pairing
     * (bit 27) and FairPlay SAP v2.5. Video URL playback (bit 0) is left off on purpose.
     */
    public static final long FEATURES = 0x5A7FFEE6L;
    public static final String FEATURES_TEXT = "0x5A7FFEE6,0x0";
    public static final String MODEL = "AppleTV3,2";
    public static final String SOURCE_VERSION = "220.68";

    public final String name;
    /** Six bytes, formatted as a MAC address in the "deviceid" record. */
    public final byte[] deviceIdentifier;
    /** 32-byte Ed25519 seed; must persist so paired clients recognize the receiver. */
    public final byte[] identitySeed;
    public final int displayWidth;
    public final int displayHeight;
    public final int maximumFramesPerSecond;
    public final int controlPort;

    public ReceiverConfiguration(String name, byte[] deviceIdentifier, byte[] identitySeed,
                                 int displayWidth, int displayHeight, int maximumFramesPerSecond, int controlPort) {
        this.name = name;
        this.deviceIdentifier = deviceIdentifier;
        this.identitySeed = identitySeed;
        this.displayWidth = displayWidth;
        this.displayHeight = displayHeight;
        this.maximumFramesPerSecond = maximumFramesPerSecond;
        this.controlPort = controlPort;
    }

    public String deviceIdentifierText(boolean withSeparators) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < deviceIdentifier.length; i++) {
            if (withSeparators && i > 0) builder.append(':');
            builder.append(String.format("%02X", deviceIdentifier[i] & 0xFF));
        }
        return builder.toString();
    }

    /** Per-receiver pairing identifier ("pi"), stable because it derives from the device identifier. */
    public String pairingIdentifier() {
        return java.util.UUID.nameUUIDFromBytes(concatenate("pairing", deviceIdentifier)).toString();
    }

    public String displayIdentifier() {
        return java.util.UUID.nameUUIDFromBytes(concatenate("display", deviceIdentifier)).toString();
    }

    private static byte[] concatenate(String label, byte[] value) {
        byte[] prefix = label.getBytes(java.nio.charset.Charset.forName("US-ASCII"));
        byte[] result = new byte[prefix.length + value.length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        System.arraycopy(value, 0, result, prefix.length, value.length);
        return result;
    }

    public String publicKeyHex() {
        return ReceiverLog.hex(LegacyPairing.publicKeyForSeed(identitySeed));
    }

    public String hostName() {
        return "AirPlay-" + deviceIdentifierText(false) + ".local";
    }

    public String raopInstanceName() {
        return deviceIdentifierText(false) + "@" + name;
    }

    public Map<String, String> airPlayTxt() {
        Map<String, String> txt = new LinkedHashMap<>();
        txt.put("deviceid", deviceIdentifierText(true));
        txt.put("features", FEATURES_TEXT);
        txt.put("pw", "false");
        txt.put("flags", "0x4");
        txt.put("model", MODEL);
        txt.put("pk", publicKeyHex());
        txt.put("pi", pairingIdentifier());
        txt.put("srcvers", SOURCE_VERSION);
        txt.put("vv", "2");
        return txt;
    }

    public Map<String, String> raopTxt() {
        Map<String, String> txt = new LinkedHashMap<>();
        txt.put("ch", "2");
        txt.put("cn", "0,1,2,3");
        txt.put("da", "true");
        txt.put("et", "0,3,5");
        txt.put("vv", "2");
        txt.put("ft", FEATURES_TEXT);
        txt.put("am", MODEL);
        txt.put("md", "0,1,2");
        txt.put("rhd", "5.6.0.0");
        txt.put("pw", "false");
        txt.put("sf", "0x4");
        txt.put("sr", "44100");
        txt.put("ss", "16");
        txt.put("sv", "false");
        txt.put("tp", "UDP");
        txt.put("txtvers", "1");
        txt.put("vs", SOURCE_VERSION);
        txt.put("vn", "65537");
        txt.put("pk", publicKeyHex());
        return txt;
    }
}
