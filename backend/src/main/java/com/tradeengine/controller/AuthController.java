package com.tradeengine.controller;

import com.tradeengine.config.JwtUtil;
import com.tradeengine.model.User;
import com.tradeengine.repository.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserRepository userRepo;
    private final PasswordEncoder encoder;
    private final JwtUtil jwt;

    @Data
    public static class LoginRequest {
        @Email @NotBlank private String email;
        @NotBlank @Size(min = 6) private String password;
    }

    @Data
    public static class RegisterRequest {
        @Email @NotBlank private String email;
        @NotBlank @Size(min = 6) private String password;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req) {
        User user = userRepo.findByEmail(req.getEmail())
            .orElse(null);
        if (user == null || !encoder.matches(req.getPassword(), user.getPasswordHash())) {
            return ResponseEntity.status(401).body(Map.of("message", "Invalid credentials"));
        }
        String token = jwt.generateToken(user.getId(), user.getEmail());
        return ResponseEntity.ok(Map.of(
            "accessToken", token,
            "refreshToken", token, // MVP: same token
            "expiresIn", 86400
        ));
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest req) {
        if (userRepo.existsByEmail(req.getEmail())) {
            return ResponseEntity.badRequest().body(Map.of("message", "Email already registered"));
        }
        User user = new User();
        user.setEmail(req.getEmail());
        user.setPasswordHash(encoder.encode(req.getPassword()));
        user = userRepo.save(user);

        String token = jwt.generateToken(user.getId(), user.getEmail());
        return ResponseEntity.ok(Map.of(
            "accessToken", token,
            "refreshToken", token,
            "expiresIn", 86400
        ));
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestBody Map<String, String> body) {
        String token = body.get("refreshToken");
        if (token == null || !jwt.isValid(token)) {
            return ResponseEntity.status(401).body(Map.of("message", "Invalid refresh token"));
        }
        var userId = jwt.getUserId(token);
        var user = userRepo.findById(userId).orElse(null);
        if (user == null) return ResponseEntity.status(401).body(Map.of("message", "User not found"));

        String newToken = jwt.generateToken(user.getId(), user.getEmail());
        return ResponseEntity.ok(Map.of(
            "accessToken", newToken,
            "refreshToken", newToken,
            "expiresIn", 86400
        ));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(@RequestAttribute(required = false) String userId) {
        return ResponseEntity.ok(Map.of());
    }
}
