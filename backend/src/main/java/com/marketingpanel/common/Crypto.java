package com.marketingpanel.common;

import com.marketingpanel.config.Settings;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM for tokens, PAN, UPI and bank numbers. Output = keyId(1) | iv(12) | ciphertext+tag.
 * The key id prefix supports key rotation: decryption picks the key by id.
 */
@Component
public class Crypto {
    private static final byte KEY_ID = 1;
    private final SecretKeySpec key;
    private final SecureRandom rnd = new SecureRandom();
    private static Crypto instance;

    public Crypto(Settings s) {
        byte[] k = Base64.getDecoder().decode(s.encryptionKey);
        if (k.length != 32) throw new IllegalStateException("app.security.encryption-key must be 32 bytes (base64)");
        this.key = new SecretKeySpec(k, "AES");
        instance = this;
    }

    public static Crypto get() {
        return instance;
    }

    public byte[] encrypt(String plain) {
        byte[] iv = new byte[12];
        rnd.nextBytes(iv);
        return encryptWithIv(plain, iv);
    }

    /** Deterministic variant (same input -> same output) so equality lookups work, e.g. one PAN per promoter. */
    public byte[] encryptDeterministic(String plain) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getEncoded(), "HmacSHA256"));
            byte[] full = mac.doFinal(("det:" + plain).getBytes(StandardCharsets.UTF_8));
            byte[] iv = new byte[12];
            System.arraycopy(full, 0, iv, 0, 12);
            return encryptWithIv(plain, iv);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] encryptWithIv(String plain, byte[] iv) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1 + iv.length + ct.length).put(KEY_ID).put(iv).put(ct).array();
        } catch (Exception e) {
            throw new IllegalStateException("encrypt failed", e);
        }
    }

    public String decrypt(byte[] data) {
        try {
            ByteBuffer bb = ByteBuffer.wrap(data);
            byte keyId = bb.get();
            if (keyId != KEY_ID) throw new IllegalStateException("unknown key id " + keyId);
            byte[] iv = new byte[12];
            bb.get(iv);
            byte[] ct = new byte[bb.remaining()];
            bb.get(ct);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("decrypt failed", e);
        }
    }

    /** URL-safe string form for tokens we hand to the client (stateless signup token). */
    public String seal(String plain) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(encrypt(plain));
    }

    public String unseal(String token) {
        return decrypt(Base64.getUrlDecoder().decode(token));
    }
}
