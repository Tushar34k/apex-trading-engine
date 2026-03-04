package com.tradeengine.controller;

import com.tradeengine.model.User;
import com.tradeengine.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepo;

    @GetMapping("/me")
    public ResponseEntity<?> me() {
        UUID userId = getUserId();
        User user = userRepo.findById(userId).orElseThrow();
        return ResponseEntity.ok(Map.of(
            "id", user.getId(),
            "email", user.getEmail(),
            "roles", List.of("USER"),
            "isActive", user.isActive(),
            "createdAt", user.getCreatedAt().toString(),
            "updatedAt", user.getCreatedAt().toString()
        ));
    }

    static UUID getUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
