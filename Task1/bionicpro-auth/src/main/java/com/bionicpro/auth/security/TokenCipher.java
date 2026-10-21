package com.bionicpro.auth.security;

import com.bionicpro.auth.exception.AuthException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class TokenCipher {
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    public TokenCipher(Path keyFile) throws Exception {
        byte[] keyBytes = Base64.getUrlDecoder().decode(Files.readString(keyFile).strip());
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException("Token encryption key must contain 32 bytes");
        }
        key = new SecretKeySpec(keyBytes, "AES");
    }

    public byte[] encrypt(String token) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(iv.length + ciphertext.length)
                .put(iv)
                .put(ciphertext)
                .array();
        } catch (Exception error) {
            throw new IllegalStateException("Token encryption failed");
        }
    }

    public String decrypt(byte[] encryptedToken) {
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key,
                new GCMParameterSpec(TAG_BITS, encryptedToken, 0, IV_LENGTH));
            byte[] plaintext = cipher.doFinal(encryptedToken, IV_LENGTH, encryptedToken.length - IV_LENGTH);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new AuthException(401, "Сессия завершена");
        }
    }
}
