package com.livevote.votes.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.livevote.votes.common.ConflictException;
import com.livevote.votes.common.UnauthorizedException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class IdentityServiceTest {

    @Mock
    private UserRepository userRepository;

    private PasswordEncoder passwordEncoder;
    private JwtService jwtService;
    private IdentityService identityService;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setSecret("test-jwt-secret-with-at-least-32-bytes-length");
        jwtProperties.setExpirationSeconds(7200);
        jwtService = new JwtService(jwtProperties);
        identityService = new IdentityService(userRepository, passwordEncoder, jwtService, jwtProperties);
    }

    @Test
    void registerShouldHashPasswordAndReturnToken() {
        UUID generatedId = UUID.randomUUID();
        when(userRepository.existsByEmailIgnoreCase("presenter@livevote.test")).thenReturn(false);
        when(userRepository.saveAndFlush(any(UserEntity.class))).thenAnswer(invocation -> {
            UserEntity entity = invocation.getArgument(0);
            ReflectionTestUtils.setField(entity, "id", generatedId);
            return entity;
        });

        AuthResponse response = identityService.register(new RegisterRequest(" Presenter@LiveVote.Test ", "Password123!"));

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).saveAndFlush(captor.capture());
        UserEntity saved = captor.getValue();

        assertThat(saved.getEmail()).isEqualTo("presenter@livevote.test");
        assertThat(saved.getPasswordHash()).isNotEqualTo("Password123!");
        assertThat(passwordEncoder.matches("Password123!", saved.getPasswordHash())).isTrue();
        assertThat(response.userId()).isEqualTo(generatedId);
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(7200);
        assertThat(jwtService.extractUserId(response.token())).isEqualTo(generatedId);
    }

    @Test
    void registerShouldThrowConflictWhenEmailAlreadyExists() {
        when(userRepository.existsByEmailIgnoreCase("taken@livevote.test")).thenReturn(true);

        assertThatThrownBy(() -> identityService.register(new RegisterRequest("taken@livevote.test", "Password123!")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void loginShouldThrowUnauthorizedOnInvalidPassword() {
        UserEntity user = new UserEntity("user@livevote.test", passwordEncoder.encode("CorrectPass1!"), Instant.now());
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        when(userRepository.findByEmailIgnoreCase("user@livevote.test")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> identityService.login(new LoginRequest("user@livevote.test", "WrongPass1!")))
                .isInstanceOf(UnauthorizedException.class);
    }
}
