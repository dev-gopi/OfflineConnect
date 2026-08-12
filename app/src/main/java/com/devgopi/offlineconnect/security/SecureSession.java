package com.devgopi.offlineconnect.security;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Ephemeral ECDH key agreement followed by authenticated AES-256-GCM frame encryption. */
public final class SecureSession {
    private static final int HANDSHAKE_MAGIC = 0x4F435332; // OCS2
    private static final int MAX_PUBLIC_KEY_BYTES = 512;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    public static final int FRAME_OVERHEAD_BYTES = IV_BYTES + TAG_BITS / Byte.SIZE;
    private static final byte[] HKDF_INFO = "OfflineConnect transport v2"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private final SecretKey encryptionKey;
    private final SecureRandom random = new SecureRandom();

    private SecureSession(SecretKey encryptionKey) {
        this.encryptionKey = encryptionKey;
    }

    /** Both peers may call this simultaneously: each writes its public key, then reads the peer. */
    public static SecureSession establish(DataInputStream input, DataOutputStream output)
            throws IOException {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair local = generator.generateKeyPair();
            byte[] localPublic = local.getPublic().getEncoded();
            output.writeInt(HANDSHAKE_MAGIC);
            output.writeInt(localPublic.length);
            output.write(localPublic);
            output.flush();

            if (input.readInt() != HANDSHAKE_MAGIC) throw new IOException("Unsupported secure session");
            int length = input.readInt();
            if (length <= 0 || length > MAX_PUBLIC_KEY_BYTES) {
                throw new IOException("Invalid peer public key");
            }
            byte[] remoteEncoded = new byte[length];
            input.readFully(remoteEncoded);
            PublicKey remote = KeyFactory.getInstance("EC")
                    .generatePublic(new X509EncodedKeySpec(remoteEncoded));
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(local.getPrivate());
            agreement.doPhase(remote, true);
            byte[] sharedSecret = agreement.generateSecret();
            byte[] salt = orderedDigest(localPublic, remoteEncoded);
            byte[] key = hkdf(sharedSecret, salt, HKDF_INFO, 32);
            Arrays.fill(sharedSecret, (byte) 0);
            SecretKey sessionKey = new SecretKeySpec(key, "AES");
            Arrays.fill(key, (byte) 0);
            return new SecureSession(sessionKey);
        } catch (GeneralSecurityException exception) {
            throw new IOException("Secure session negotiation failed", exception);
        }
    }

    public byte[] encrypt(byte[] plaintext, byte[] associatedData) throws IOException {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(associatedData);
            byte[] ciphertext = cipher.doFinal(plaintext);
            byte[] result = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, result, 0, iv.length);
            System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);
            return result;
        } catch (GeneralSecurityException exception) {
            throw new IOException("Frame encryption failed", exception);
        }
    }

    public byte[] decrypt(byte[] encrypted, byte[] associatedData) throws IOException {
        if (encrypted.length < FRAME_OVERHEAD_BYTES) throw new IOException("Invalid encrypted frame");
        try {
            byte[] iv = Arrays.copyOfRange(encrypted, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(associatedData);
            return cipher.doFinal(encrypted, IV_BYTES, encrypted.length - IV_BYTES);
        } catch (GeneralSecurityException exception) {
            throw new IOException("Frame authentication failed", exception);
        }
    }

    public static byte[] frameAad(byte type, String id, long timestamp, long duration,
                                  boolean edited) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(bytes);
        data.writeByte(type);
        data.writeUTF(id);
        data.writeLong(timestamp);
        data.writeLong(duration);
        data.writeBoolean(edited);
        data.flush();
        return bytes.toByteArray();
    }

    private static byte[] orderedDigest(byte[] first, byte[] second)
            throws GeneralSecurityException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        if (compare(first, second) <= 0) { digest.update(first); digest.update(second); }
        else { digest.update(second); digest.update(first); }
        return digest.digest();
    }

    private static int compare(byte[] first, byte[] second) {
        int length = Math.min(first.length, second.length);
        for (int index = 0; index < length; index++) {
            int difference = (first[index] & 0xff) - (second[index] & 0xff);
            if (difference != 0) return difference;
        }
        return first.length - second.length;
    }

    private static byte[] hkdf(byte[] input, byte[] salt, byte[] info, int length)
            throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] pseudoRandomKey = mac.doFinal(input);
        byte[] result = new byte[length];
        byte[] previous = new byte[0];
        int written = 0;
        for (int counter = 1; written < length; counter++) {
            mac.init(new SecretKeySpec(pseudoRandomKey, "HmacSHA256"));
            mac.update(previous);
            mac.update(info);
            mac.update((byte) counter);
            previous = mac.doFinal();
            int count = Math.min(previous.length, length - written);
            System.arraycopy(previous, 0, result, written, count);
            written += count;
        }
        Arrays.fill(pseudoRandomKey, (byte) 0);
        Arrays.fill(previous, (byte) 0);
        return result;
    }
}
