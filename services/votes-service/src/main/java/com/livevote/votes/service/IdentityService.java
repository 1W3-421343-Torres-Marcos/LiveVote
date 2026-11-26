package com.livevote.votes.service;

import com.livevote.votes.config.JwtProperties;
import com.livevote.votes.dto.AuthResponse;
import com.livevote.votes.dto.LoginRequest;
import com.livevote.votes.dto.RegisterRequest;
import com.livevote.votes.entity.UserEntity;
import com.livevote.votes.exception.ConflictException;
import com.livevote.votes.exception.UnauthorizedException;
import com.livevote.votes.repository.UserRepository;
import com.livevote.votes.security.JwtService;
import java.time.Instant;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles presenter registration, password verification, and JWT issuance.
 */
@Service
public class IdentityService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;

    public IdentityService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            JwtProperties jwtProperties
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String normalizedEmail = normalizeEmail(request.email());
        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new ConflictException("Email is already registered");
        }

        String passwordHash = passwordEncoder.encode(request.password());
        UserEntity user = new UserEntity(normalizedEmail, passwordHash, Instant.now());

        try {
            UserEntity saved = userRepository.saveAndFlush(user);
            return buildAuthResponse(saved);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("Email is already registered");
        }
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String normalizedEmail = normalizeEmail(request.email());
        UserEntity user = userRepository.findByEmailIgnoreCase(normalizedEmail)
                .orElseThrow(() -> new UnauthorizedException("Invalid email or password"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new UnauthorizedException("Invalid email or password");
        }

        return buildAuthResponse(user);
    }

    private AuthResponse buildAuthResponse(UserEntity user) {
        String token = jwtService.generateToken(user.getId(), user.getEmail());
        return new AuthResponse(
                token,
                "Bearer",
                jwtProperties.getExpirationSeconds(),
                user.getId(),
                user.getEmail()
        );
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
