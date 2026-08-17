package com.shiv.securegkd.authentication;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthenticationIdentityRepository extends JpaRepository<AuthenticationIdentity, Long> {

    Optional<AuthenticationIdentity> findByUsername(String username);
}
