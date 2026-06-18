package com.otter.service;

import com.otter.domain.User;
import com.otter.repository.UserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class UserService implements UserDetailsService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User u = userRepository.findByEmailIgnoreCase(email)
            .orElseThrow(() -> new UsernameNotFoundException("No user with email " + email));
        return org.springframework.security.core.userdetails.User.builder()
            .username(u.getEmail())
            .password(u.getPasswordHash())
            .disabled(!u.isEnabled())
            .authorities(List.of(new SimpleGrantedAuthority("ROLE_USER")))
            .build();
    }

    @Transactional
    public User signup(String email, String rawPassword, String displayName) {
        String normalized = email == null ? "" : email.trim().toLowerCase();
        if (normalized.isEmpty() || !normalized.contains("@")) {
            throw new IllegalArgumentException("A valid email is required");
        }
        if (rawPassword == null || rawPassword.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters");
        }
        if (userRepository.existsByEmailIgnoreCase(normalized)) {
            throw new IllegalArgumentException("An account with that email already exists");
        }
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail(normalized);
        u.setPasswordHash(passwordEncoder.encode(rawPassword));
        u.setDisplayName(displayName == null || displayName.isBlank() ? null : displayName.trim());
        return userRepository.save(u);
    }

    @Transactional(readOnly = true)
    public User getByEmail(String email) {
        return userRepository.findByEmailIgnoreCase(email)
            .orElseThrow(() -> new UsernameNotFoundException("No user with email " + email));
    }
}
