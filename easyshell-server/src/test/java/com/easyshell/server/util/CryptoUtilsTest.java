package com.easyshell.server.util;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CryptoUtilsTest {

    private CryptoUtils newUtils(String key) {
        CryptoUtils utils = new CryptoUtils();
        ReflectionTestUtils.setField(utils, "encryptionKey", key);
        utils.init();
        return utils;
    }

    @Test
    void roundTrip_preservesValue() {
        CryptoUtils utils = newUtils("unit-test-encryption-key");
        String secret = "ssh-rsa AAAA...very-secret";

        String encrypted = utils.encrypt(secret);

        assertThat(encrypted).startsWith("v2:");
        assertThat(encrypted).doesNotContain(secret);
        assertThat(utils.decrypt(encrypted)).isEqualTo(secret);
    }

    @Test
    void samePlaintext_producesDifferentCiphertext() {
        CryptoUtils utils = newUtils("unit-test-encryption-key");

        String first = utils.encrypt("same input");
        String second = utils.encrypt("same input");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void tamperedCiphertext_isRejected() {
        CryptoUtils utils = newUtils("unit-test-encryption-key");
        String encrypted = utils.encrypt("integrity matters");
        byte[] raw = Base64.getDecoder().decode(encrypted.substring("v2:".length()));
        raw[raw.length - 1] ^= 0x01;
        String tampered = "v2:" + Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> utils.decrypt(tampered))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void legacyCbcValue_stillDecrypts() {
        // Value produced by the previous (static IV, zero padded key) implementation.
        CryptoUtils legacy = new CryptoUtils();
        ReflectionTestUtils.setField(legacy, "encryptionKey", "unit-test-encryption-key");
        legacy.init();
        cn.hutool.crypto.symmetric.AES oldAes = new cn.hutool.crypto.symmetric.AES(
                cn.hutool.crypto.Mode.CBC,
                cn.hutool.crypto.Padding.PKCS5Padding,
                padKey("unit-test-encryption-key".getBytes(StandardCharsets.UTF_8)),
                "easyshell-iv-16b".getBytes(StandardCharsets.UTF_8));
        String legacyCiphertext = oldAes.encryptBase64("legacy secret");

        assertThat(newUtils("unit-test-encryption-key").decrypt(legacyCiphertext))
                .isEqualTo("legacy secret");
    }

    private static byte[] padKey(byte[] raw) {
        byte[] key = new byte[32];
        System.arraycopy(raw, 0, key, 0, Math.min(raw.length, 32));
        return key;
    }
}
