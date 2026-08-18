package com.shiv.securegkd.authentication;

public enum AuthenticationRole {
    USER,
    ADMIN;

    private static final String AUTHORITY_PREFIX = "ROLE_";

    public String authority() {
        return AUTHORITY_PREFIX + name();
    }

    public static AuthenticationRole fromAuthority(String authority) {
        for (AuthenticationRole role : values()) {
            if (role.authority().equals(authority)) {
                return role;
            }
        }
        throw new IllegalArgumentException("Unsupported application authority: " + authority);
    }
}
