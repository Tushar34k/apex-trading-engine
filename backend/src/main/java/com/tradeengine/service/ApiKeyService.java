package com.tradeengine.service;

import com.tradeengine.model.UserApiKey;
import com.tradeengine.repository.ApiKeyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private final ApiKeyRepository repo;

    @Value("${encryption.aes-key}")
    private String aesKey;

    public List<UserApiKey> listForUser(UUID userId) {
        return repo.findByUserId(userId);
    }

    public UserApiKey addKey(UUID userId, String exchange, String label,
                             String apiKey, String apiSecret, String permissions) {
        UserApiKey entity = new UserApiKey();
        entity.setUserId(userId);
        entity.setExchange(exchange);
        entity.setLabel(label);
        entity.setApiKeyEncrypted(encrypt(apiKey));
        entity.setApiSecretEncrypted(encrypt(apiSecret));
        entity.setPermissions(permissions);
        return repo.save(entity);
    }

    public void deleteKey(UUID keyId, UUID userId) {
        repo.findById(keyId).ifPresent(k -> {
            if (k.getUserId().equals(userId)) repo.delete(k);
        });
    }

    public String decryptApiKey(UserApiKey key) {
        return decrypt(key.getApiKeyEncrypted());
    }

    public String decryptApiSecret(UserApiKey key) {
        return decrypt(key.getApiSecretEncrypted());
    }

    private String encrypt(String plaintext) {
        try {
            byte[] keyBytes = aesKey.getBytes(StandardCharsets.UTF_8);
            byte[] key32 = new byte[32];
            System.arraycopy(keyBytes, 0, key32, 0, Math.min(keyBytes.length, 32));
            SecretKeySpec keySpec = new SecretKeySpec(key32, "AES");

            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    private String decrypt(String ciphertext) {
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext);
            byte[] keyBytes = aesKey.getBytes(StandardCharsets.UTF_8);
            byte[] key32 = new byte[32];
            System.arraycopy(keyBytes, 0, key32, 0, Math.min(keyBytes.length, 32));
            SecretKeySpec keySpec = new SecretKeySpec(key32, "AES");

            byte[] iv = new byte[12];
            byte[] encrypted = new byte[combined.length - 12];
            System.arraycopy(combined, 0, iv, 0, 12);
            System.arraycopy(combined, 12, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }
}
