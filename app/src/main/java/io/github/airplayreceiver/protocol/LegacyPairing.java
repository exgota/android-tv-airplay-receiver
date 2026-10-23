package io.github.airplayreceiver.protocol;

import net.i2p.crypto.eddsa.EdDSAEngine;
import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAParameterSpec;
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;

import org.whispersystems.curve25519.Curve25519;
import org.whispersystems.curve25519.Curve25519KeyPair;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AirPlay "legacy" pairing: pair-setup exchanges Ed25519 public keys, and the two pair-verify
 * steps run an X25519 key agreement whose shared secret later salts the media keys.
 */
public final class LegacyPairing {

    private static final String TAG = "Pairing";
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final EdDSAParameterSpec ED25519 = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);

    private final EdDSAPrivateKey identityPrivateKey;
    private final byte[] identityPublicKey;

    private byte[] clientEdPublicKey;
    private byte[] clientEcdhPublicKey;
    private byte[] serverEcdhPublicKey;
    private byte[] sharedSecret;
    private boolean verified;

    /** @param identitySeed persistent 32-byte Ed25519 seed; its public key is advertised as "pk" */
    public LegacyPairing(byte[] identitySeed) {
        identityPrivateKey = new EdDSAPrivateKey(new EdDSAPrivateKeySpec(identitySeed, ED25519));
        identityPublicKey = identityPrivateKey.getAbyte();
    }

    public static byte[] publicKeyForSeed(byte[] identitySeed) {
        return new EdDSAPrivateKey(new EdDSAPrivateKeySpec(identitySeed, ED25519)).getAbyte();
    }

    public byte[] pairSetup() {
        return identityPublicKey.clone();
    }

    /** Handles one pair-verify request. Returns the response body (possibly empty). */
    public byte[] pairVerify(byte[] request) throws Exception {
        if (request.length < 4) {
            throw new IllegalArgumentException("pair-verify request too short");
        }
        if (request[0] == 1) {
            if (request.length != 4 + 32 + 32) {
                throw new IllegalArgumentException("pair-verify step 1 has length " + request.length);
            }
            clientEcdhPublicKey = Arrays.copyOfRange(request, 4, 36);
            clientEdPublicKey = Arrays.copyOfRange(request, 36, 68);

            Curve25519 curve = Curve25519.getInstance(Curve25519.JAVA);
            Curve25519KeyPair keyPair = curve.generateKeyPair();
            serverEcdhPublicKey = keyPair.getPublicKey();
            sharedSecret = curve.calculateAgreement(clientEcdhPublicKey, keyPair.getPrivateKey());

            byte[] signedData = concatenate(serverEcdhPublicKey, clientEcdhPublicKey);
            EdDSAEngine signer = new EdDSAEngine(MessageDigest.getInstance("SHA-512"));
            signer.initSign(identityPrivateKey);
            byte[] signature = signer.signOneShot(signedData);

            byte[] encryptedSignature = createCipher().doFinal(signature);
            verified = false;
            return concatenate(serverEcdhPublicKey, encryptedSignature);
        }

        if (request.length != 4 + 64 || sharedSecret == null) {
            throw new IllegalArgumentException("pair-verify step 2 out of order or length " + request.length);
        }
        Cipher cipher = createCipher();
        cipher.update(new byte[64]);
        byte[] signature = cipher.doFinal(request, 4, 64);

        EdDSAEngine verifier = new EdDSAEngine(MessageDigest.getInstance("SHA-512"));
        verifier.initVerify(new EdDSAPublicKey(new EdDSAPublicKeySpec(clientEdPublicKey, ED25519)));
        verified = verifier.verifyOneShot(concatenate(clientEcdhPublicKey, serverEcdhPublicKey), signature);
        ReceiverLog.i(TAG, "pair-verify signature valid: " + verified);
        return new byte[0];
    }

    public boolean isVerified() {
        return verified;
    }

    /** X25519 shared secret from pair-verify, or {@code null} when the client skipped pairing. */
    public byte[] getSharedSecret() {
        return sharedSecret;
    }

    private Cipher createCipher() throws Exception {
        byte[] key = sha512Prefix("Pair-Verify-AES-Key", sharedSecret);
        byte[] iv = sha512Prefix("Pair-Verify-AES-IV", sharedSecret);
        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher;
    }

    private static byte[] sha512Prefix(String label, byte[] secret) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-512");
        digest.update(label.getBytes(UTF_8));
        digest.update(secret);
        return Arrays.copyOf(digest.digest(), 16);
    }

    private static byte[] concatenate(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
