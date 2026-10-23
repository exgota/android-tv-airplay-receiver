package io.github.airplayreceiver.protocol;

import java.math.BigInteger;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Key derivation and decryption for the mirroring (AES-CTR) and audio (AES-CBC) streams. */
public final class MediaKeys {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private MediaKeys() {
    }

    /**
     * Hashes the FairPlay AES key with the pairing secret, as AirPlay does whenever pair-verify ran.
     */
    public static byte[] sessionKey(byte[] fairPlayKey, byte[] sharedSecret) throws Exception {
        if (sharedSecret == null) {
            return fairPlayKey.clone();
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-512");
        digest.update(fairPlayKey);
        digest.update(sharedSecret);
        return Arrays.copyOf(digest.digest(), 16);
    }

    /** Formats a 64-bit plist integer as the unsigned decimal string AirPlay uses. */
    public static String unsignedDecimal(long value) {
        if (value >= 0) {
            return Long.toString(value);
        }
        return new BigInteger(1, new byte[]{
                (byte) (value >>> 56), (byte) (value >>> 48), (byte) (value >>> 40), (byte) (value >>> 32),
                (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value}).toString();
    }

    /** AES-128-CTR keystream for the mirroring TCP stream; state carries across packets. */
    public static final class MirrorDecryptor {
        private final Cipher blockCipher;
        private final byte[] counter;
        private final byte[] keystream = new byte[16];
        private int keystreamPosition = 16;
        private byte[] counterBlocks = new byte[0];
        private byte[] keystreamBlocks = new byte[0];

        public MirrorDecryptor(byte[] sessionKey, long streamConnectionId) throws Exception {
            String connection = unsignedDecimal(streamConnectionId);
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            digest.update(("AirPlayStreamKey" + connection).getBytes(UTF_8));
            digest.update(sessionKey, 0, 16);
            byte[] key = Arrays.copyOf(digest.digest(), 16);
            digest.update(("AirPlayStreamIV" + connection).getBytes(UTF_8));
            digest.update(sessionKey, 0, 16);
            counter = Arrays.copyOf(digest.digest(), 16);

            blockCipher = Cipher.getInstance("AES/ECB/NoPadding");
            blockCipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        }

        public void decrypt(byte[] data, int offset, int length) throws Exception {
            int position = offset;
            int end = offset + length;
            while (position < end && keystreamPosition < 16) {
                data[position++] ^= keystream[keystreamPosition++];
            }
            int remaining = end - position;
            if (remaining == 0) {
                return;
            }
            int blocks = (remaining + 15) / 16;
            int bytes = blocks * 16;
            if (counterBlocks.length < bytes) {
                counterBlocks = new byte[bytes];
                keystreamBlocks = new byte[bytes];
            }
            for (int block = 0; block < blocks; block++) {
                System.arraycopy(counter, 0, counterBlocks, block * 16, 16);
                incrementCounter();
            }
            blockCipher.doFinal(counterBlocks, 0, bytes, keystreamBlocks, 0);
            for (int i = 0; i < remaining; i++) {
                data[position + i] ^= keystreamBlocks[i];
            }
            int used = remaining % 16;
            if (used != 0) {
                System.arraycopy(keystreamBlocks, bytes - 16, keystream, 0, 16);
                keystreamPosition = used;
            } else {
                keystreamPosition = 16;
            }
        }

        private void incrementCounter() {
            for (int i = 15; i >= 0; i--) {
                counter[i]++;
                if (counter[i] != 0) {
                    break;
                }
            }
        }
    }

    /** AES-128-CBC per RTP packet with a fixed IV; a trailing partial block is sent in clear. */
    public static final class AudioDecryptor {
        private final Cipher cipher;
        private final SecretKeySpec key;
        private final IvParameterSpec iv;

        public AudioDecryptor(byte[] sessionKey, byte[] initializationVector) throws Exception {
            cipher = Cipher.getInstance("AES/CBC/NoPadding");
            key = new SecretKeySpec(sessionKey, "AES");
            iv = new IvParameterSpec(initializationVector);
        }

        public void decrypt(byte[] data, int offset, int length) throws Exception {
            int encryptedLength = length / 16 * 16;
            if (encryptedLength == 0) {
                return;
            }
            cipher.init(Cipher.DECRYPT_MODE, key, iv);
            byte[] plain = cipher.doFinal(data, offset, encryptedLength);
            System.arraycopy(plain, 0, data, offset, encryptedLength);
        }
    }
}
