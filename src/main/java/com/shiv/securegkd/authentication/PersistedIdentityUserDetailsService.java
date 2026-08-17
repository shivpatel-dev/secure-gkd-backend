package com.shiv.securegkd.authentication;

import java.util.List;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

public class PersistedIdentityUserDetailsService implements UserDetailsService {

    private final AuthenticationIdentityRepository repository;

    public PersistedIdentityUserDetailsService(AuthenticationIdentityRepository repository) {
        this.repository = repository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return repository.findByUsername(username)
                .map(identity -> User.withUsername(identity.getUsername())
                        .password(identity.getPasswordHash())
                        .authorities(List.of())
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }
}
