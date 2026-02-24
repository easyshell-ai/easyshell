package com.easyshell.server.util;

import cn.hutool.crypto.Mode;
import cn.hutool.crypto.Padding;
import cn.hutool.crypto.symmetric.AES;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class CryptoUtils {

    @Value("${easyshell.security.encryption-key:easyshell-default-encryption-key-32b}")
    private String encryptionKey;

    private AES aes;

    @PostConstruct
    public void init() {
        // Ensure key is exactly 32 bytes for AES-256
        byte[] keyBytes = new byte[32];
        byte[] raw = encryptionKey.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(raw, 0, keyBytes, 0, Math.min(raw.length, 32));
        // AES-256-CBC with PKCS5Padding
        byte[] iv = "easyshell-iv-16b".getBytes(StandardCharsets.UTF_8);
        this.aes = new AES(Mode.CBC, Padding.PKCS5Padding, keyBytes, iv);
    }

    public String encrypt(String plainText) {
        return aes.encryptBase64(plainText);
    }

    public String decrypt(String encryptedBase64) {
        return aes.decryptStr(encryptedBase64);
    }
}
