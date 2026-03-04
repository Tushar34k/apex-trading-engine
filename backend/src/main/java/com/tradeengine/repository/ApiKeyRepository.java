package com.tradeengine.repository;

import com.tradeengine.model.UserApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<UserApiKey, UUID> {
    List<UserApiKey> findByUserId(UUID userId);
}
