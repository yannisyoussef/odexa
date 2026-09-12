package commerce.runtime;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;

/** Tenant and roles come exclusively from a verified JWT, never from request headers. */
public record Actor(UUID tenantId, String subject, Set<String> roles) {
    public Actor {
        Objects.requireNonNull(tenantId, "tenantId");
        if (subject == null || subject.isBlank()) {
            throw forbidden();
        }
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
    }

    public static Actor from(Jwt jwt) {
        if (jwt == null || !(jwt.getClaims().get("tenant_id") instanceof String tenant)
                || !Correlation.isUuid(tenant)) {
            throw forbidden();
        }
        Object subject = jwt.getClaims().get("sub");
        if (!(subject instanceof String name) || name.isBlank()) {
            throw forbidden();
        }
        return new Actor(UUID.fromString(tenant), name, realmRoles(jwt));
    }

    static Set<String> realmRoles(Jwt jwt) {
        Set<String> roles = new HashSet<>();
        Object realm = jwt.getClaims().get("realm_access");
        if (realm instanceof Map<?, ?> claims && claims.get("roles") instanceof Collection<?> values) {
            for (Object value : values) {
                if (value instanceof String role && role.matches("[A-Za-z0-9_-]{1,80}")) {
                    roles.add(role);
                }
            }
        }
        return Set.copyOf(roles);
    }

    /** Requires ANY named role. Platform/support roles receive no implicit tenant bypass. */
    public void requireRole(String... permittedRoles) {
        if (permittedRoles != null) {
            for (String role : permittedRoles) {
                if (role != null && roles.contains(role)) {
                    return;
                }
            }
        }
        throw forbidden();
    }

    private static ApiException forbidden() {
        return new ApiException(403, "FORBIDDEN", "Access is not permitted.");
    }
}
