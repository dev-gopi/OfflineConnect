package com.devgopi.offlineconnect.security;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encrypts local message data with authenticated AES-256-GCM.
 *
 * <p>The key is generated inside Android Keystore and is therefore non-exportable. Version 2
 * records authenticate immutable database metadata as additional authenticated data (AAD), which
 * prevents an encrypted body from being copied to a different message row without detection.
 * Legacy version 1 records remain readable so installations can migrate without data loss.</p>
 */
public final class EncryptionManager {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "offline_connect_messages_v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String FORMAT_V2 = "v2";
    private static final String SEPARATOR = ":";
    private static final int TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BYTES = TAG_BITS / Byte.SIZE;

    /** Encrypts plaintext and cryptographically binds it to the supplied message metadata. */
    public String encrypt(String plaintext, String associatedData) throws GeneralSecurityException {
        if (plaintext == null || associatedData == null) {
            throw new GeneralSecurityException("Encryption input cannot be null");
        }
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
        byte[] plaintextBytes = plaintext.getBytes(StandardCharsets.UTF_8);
        try {
            byte[] ciphertext = cipher.doFinal(plaintextBytes);
            return FORMAT_V2 + SEPARATOR + encode(cipher.getIV()) + SEPARATOR + encode(ciphertext);
        } finally {
            // Reduce the lifetime of the extra plaintext copy created for the cipher operation.
            Arrays.fill(plaintextBytes, (byte) 0);
        }
    }

    /** Decrypts current records and legacy records created before metadata authentication. */
    public String decrypt(String encoded, String associatedData) throws GeneralSecurityException {
        if (encoded == null || associatedData == null) {
            throw new GeneralSecurityException("Decryption input cannot be null");
        }
        String[] parts = encoded.split(SEPARATOR, -1);
        boolean currentFormat = parts.length == 3 && FORMAT_V2.equals(parts[0]);
        boolean legacyFormat = parts.length == 2;
        if (!currentFormat && !legacyFormat) throw invalidFormat(null);

        byte[] iv = decode(parts[currentFormat ? 1 : 0]);
        byte[] ciphertext = decode(parts[currentFormat ? 2 : 1]);
        if (iv.length != GCM_IV_BYTES || ciphertext.length < GCM_TAG_BYTES) {
            throw invalidFormat(null);
        }
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(TAG_BITS, iv));
        if (currentFormat) cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
        byte[] plaintext = cipher.doFinal(ciphertext);
        try {
            return new String(plaintext, StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    /** Returns true when a successfully decrypted legacy record should be rewritten as v2. */
    public boolean needsMigration(String encoded) {
        return encoded != null && !encoded.startsWith(FORMAT_V2 + SEPARATOR);
    }

    private static String encode(byte[] value) {
        return Base64.encodeToString(value, Base64.NO_WRAP);
    }

    private static byte[] decode(String value) throws GeneralSecurityException {
        try {
            return Base64.decode(value, Base64.NO_WRAP);
        } catch (IllegalArgumentException exception) {
            throw invalidFormat(exception);
        }
    }

    private static GeneralSecurityException invalidFormat(Exception cause) {
        return new GeneralSecurityException("Invalid encrypted message format", cause);
    }

    private SecretKey getOrCreateKey() throws GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        try {
            keyStore.load(null);
        } catch (java.io.IOException e) {
            throw new GeneralSecurityException("Unable to load Android Keystore", e);
        }
        SecretKey existing = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        if (existing != null) return existing;

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
