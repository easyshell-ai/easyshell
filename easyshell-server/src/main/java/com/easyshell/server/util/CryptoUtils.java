package com.easyshell.server.util;

import cn.hutool.crypto.Mode;
import cn.hutool.crypto.Padding;
import cn.hutool.crypto.symmetric.AES;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Symmetric encryption for stored credentials (SSH passwords/keys, AI provider keys, bot tokens).
 *
 * <p>The previous implementation zero-padded the configured key to 32 bytes and used a fixed
 * IV with unauthenticated CBC, which made ciphertexts deterministic and tamperable. Values are
 * now encrypted with AES-256-GCM using a fresh random IV per value, and the key is derived with
 * SHA-256 so the full configured entropy is used instead of zero padding.
 *
 * <p>Legacy values (written by the old CBC scheme) can still be read, but everything new is
 * written in the authenticated format prefixed with {@code v2:}.
 */
@Component
public class CryptoUtils {

    private static final String V2_PREFIX = "v2:";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BIT_LENGTH = 128;

    @Value("${easyshell.security.encryption-key}")
    private String encryptionKey;

    private AES legacyAes;
    private byte[] gcmKey;

    @PostConstruct
    public void init() {
        byte[] raw = encryptionKey.getBytes(StandardCharsets.UTF_8);
        this.gcmKey = sha256(raw);

        // Legacy reader only: fixed IV + zero padded key, kept so existing rows still decrypt.
        byte[] legacyKey = new byte[32];
        System.arraycopy(raw, 0, legacyKey, 0, Math.min(raw.length, 32));
        byte[] iv = "easyshell-iv-16b".getBytes(StandardCharsets.UTF_8);
        this.legacyAes = new AES(Mode.CBC, Padding.PKCS5Padding, legacyKey, iv);
    }

    public String encrypt(String plainText) {
        if (plainText == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            SECURE_RANDOM.nextBytes(iv);
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,
                    new javax.crypto.spec.SecretKeySpec(gcmKey, "AES"),
                    new javax.crypto.spec.GCMParameterSpec(TAG_BIT_LENGTH, iv));
            byte[] sealed = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(sealed, 0, out, iv.length, sealed.length);
            return V2_PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt value", e);
        }
    }

    public String decrypt(String encrypted) {
        if (encrypted == null) {
            return null;
        }
        if (encrypted.startsWith(V2_PREFIX)) {
            try {
                byte[] raw = Base64.getDecoder().decode(encrypted.substring(V2_PREFIX.length()));
                if (raw.length <= IV_LENGTH) {
                    throw new IllegalArgumentException("ciphertext too short");
                }
                byte[] iv = new byte[IV_LENGTH];
                System.arraycopy(raw, 0, iv, 0, IV_LENGTH);
                byte[] sealed = new byte[raw.length - IV_LENGTH];
                System.arraycopy(raw, IV_LENGTH, sealed, 0, sealed.length);

                javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(javax.crypto.Cipher.DECRYPT_MODE,
                        new javax.crypto.spec.SecretKeySpec(gcmKey, "AES"),
                        new javax.crypto.spec.GCMParameterSpec(TAG_BIT_LENGTH, iv));
                return new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to decrypt value", e);
            }
        }
        // Legacy CBC value
        return legacyAes.decryptStr(encrypted);
    }

    private static final java.security.SecureRandom SECURE_RANDOM = new java.security.SecureRandom();

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
