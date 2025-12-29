package org.example;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;

/**
 * Utility for loading AES-GCM encrypted ONNX models. The key is supplied via
 * the {@code SESSION_KEY} environment variable as a hex-encoded 256-bit value.
 * The IV is stored as the first 12 bytes of the .enc file.
 */
public final class ModelCryptoUtil {

    private ModelCryptoUtil() {}

    /**
     * Load model bytes from the classpath. If a file with suffix ".enc" exists
     * it will be decrypted using AES-GCM and the session key from the
     * environment. Otherwise the plain resource is returned.
     */
    public static byte[] loadModelBytes(String resource) throws IOException, GeneralSecurityException {
        ClassLoader cl = ModelCryptoUtil.class.getClassLoader();
        String encName = resource + ".enc";
        try (InputStream enc = cl.getResourceAsStream(encName)) {
            if (enc != null) {
                byte[] encrypted = enc.readAllBytes();
                SecretKey key = loadKey();
                return decrypt(encrypted, key);
            }
        }
        try (InputStream plain = cl.getResourceAsStream(resource)) {
            if (plain == null) {
                throw new FileNotFoundException("Model resource not found: " + resource);
            }
            return plain.readAllBytes();
        }
    }

    /**
     * Decrypt AES-GCM data. The first 12 bytes are the IV, followed by
     * ciphertext and authentication tag.
     */
    public static byte[] decrypt(byte[] encrypted, SecretKey key) throws GeneralSecurityException {
        ByteBuffer bb = ByteBuffer.wrap(encrypted);
        byte[] iv = new byte[12];
        bb.get(iv);
        byte[] cipher = new byte[bb.remaining()];
        bb.get(cipher);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        return c.doFinal(cipher);
    }

    /**
     * Load the AES key from the {@code SESSION_KEY} environment variable. If
     * missing, a fixed demo key is used.
     */
    static SecretKey loadKey() {
        String hex = System.getenv("SESSION_KEY");
        if (hex == null) {
            hex = "80d494858dbfd646d9f91f6792194fd76b7025a723b2ad2cb59d30d3ea46c1c1"; // demo key
        }
        byte[] keyBytes = hexStringToBytes(hex);
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException("SESSION_KEY must be 64 hex characters");
        }
        return new SecretKeySpec(keyBytes, "AES");
    }

    private static byte[] hexStringToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = Character.digit(hex.charAt(i), 16);
            int lo = Character.digit(hex.charAt(i + 1), 16);
            data[i / 2] = (byte) ((hi << 4) + lo);
        }
        return data;
    }
}