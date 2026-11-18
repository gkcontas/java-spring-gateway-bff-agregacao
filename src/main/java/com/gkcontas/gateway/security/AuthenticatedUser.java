package com.gkcontas.gateway.security;

import java.util.Set;

public record AuthenticatedUser(String id, Set<String> roles) {

    public AuthenticatedUser {
        roles = Set.copyOf(roles);
    }

    public String rolesHeader() {
        return String.join(",", roles.stream().sorted().toList());
    }
}
