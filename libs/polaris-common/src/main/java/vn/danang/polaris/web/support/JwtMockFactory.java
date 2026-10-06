package vn.danang.polaris.web.support;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;

import vn.danang.polaris.config.PolarisPermissions;
import vn.danang.polaris.config.PolarisRoles;

/*
Use final for java idiom: design and document for inheritance, or else prohibit it.
 */
public final class JwtMockFactory {

    private JwtMockFactory() {
    }

    public static JwtRequestPostProcessor user() {
        return SecurityMockMvcRequestPostProcessors.jwt();
    }

    public static JwtRequestPostProcessor withPermissions(String... permissions) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String perm : permissions) {
            authorities.add(new SimpleGrantedAuthority("PERM_" + perm));
            authorities.add(new SimpleGrantedAuthority("PERMISSION_" + perm));
        }
        return SecurityMockMvcRequestPostProcessors.jwt().authorities(authorities);
    }

    public static JwtRequestPostProcessor withAuthorities(String... authorities) {
        return SecurityMockMvcRequestPostProcessors.jwt()
                .authorities(Arrays.stream(authorities)
                        .<GrantedAuthority>map(SimpleGrantedAuthority::new)
                        .toList());
    }

    public static JwtRequestPostProcessor customerSuccess() {
        return SecurityMockMvcRequestPostProcessors.jwt()
                .authorities(List.of(
                        new SimpleGrantedAuthority("ROLE_" + PolarisRoles.CUSTOMER_SUCCESS),
                        new SimpleGrantedAuthority("ROLE_CUSTOMER_SUCCESS"),
                        new SimpleGrantedAuthority("PERM_" + PolarisPermissions.CUSTOMER_READ),
                        new SimpleGrantedAuthority("PERMISSION_" + PolarisPermissions.CUSTOMER_READ),
                        new SimpleGrantedAuthority("PERM_" + PolarisPermissions.CUSTOMER_WRITE),
                        new SimpleGrantedAuthority("PERMISSION_" + PolarisPermissions.CUSTOMER_WRITE)
                ));
    }

    public static JwtRequestPostProcessor productCatalog() {
        return SecurityMockMvcRequestPostProcessors.jwt()
                .authorities(List.of(
                        new SimpleGrantedAuthority("ROLE_" + PolarisRoles.PRODUCT_CATALOG),
                        new SimpleGrantedAuthority("ROLE_PRODUCT_CATALOG"),
                        new SimpleGrantedAuthority("PERM_" + PolarisPermissions.CATALOG_READ),
                        new SimpleGrantedAuthority("PERMISSION_" + PolarisPermissions.CATALOG_READ),
                        new SimpleGrantedAuthority("PERM_" + PolarisPermissions.CATALOG_WRITE),
                        new SimpleGrantedAuthority("PERMISSION_" + PolarisPermissions.CATALOG_WRITE)
                ));
    }

    public static JwtRequestPostProcessor inventory() {
        return SecurityMockMvcRequestPostProcessors.jwt()
                .authorities(List.of(
                        new SimpleGrantedAuthority("ROLE_" + PolarisRoles.INVENTORY),
                        new SimpleGrantedAuthority("ROLE_INVENTORY"),
                        new SimpleGrantedAuthority("PERM_" + PolarisPermissions.INVENTORY_READ),
                        new SimpleGrantedAuthority("PERMISSION_" + PolarisPermissions.INVENTORY_READ),
                        new SimpleGrantedAuthority("PERM_" + PolarisPermissions.INVENTORY_WRITE),
                        new SimpleGrantedAuthority("PERMISSION_" + PolarisPermissions.INVENTORY_WRITE)
                ));
    }

    public static JwtRequestPostProcessor purchaseManagement() {
        return SecurityMockMvcRequestPostProcessors.jwt()
                .authorities(List.of(
                        new SimpleGrantedAuthority("ROLE_" + PolarisRoles.PURCHASE_MANAGEMENT),
                        new SimpleGrantedAuthority("ROLE_PURCHASE_MANAGEMENT"),
                        new SimpleGrantedAuthority("PERM_" + PolarisPermissions.ORDER_READ),
                        new SimpleGrantedAuthority("PERMISSION_" + PolarisPermissions.ORDER_READ),
                        new SimpleGrantedAuthority("PERM_" + PolarisPermissions.ORDER_WRITE),
                        new SimpleGrantedAuthority("PERMISSION_" + PolarisPermissions.ORDER_WRITE)
                ));
    }

    /**
     * Self-service shopper token: identity claims ({@code sub}, verified {@code email}) plus the
     * permissions of the {@code shopper} realm role. Has no staff role.
     */
    public static JwtRequestPostProcessor shopper(String subject, String email) {
        return SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(jwt -> jwt.subject(subject).claim("email", email).claim("email_verified", true))
                .authorities(List.of(
                        new SimpleGrantedAuthority("ROLE_" + PolarisRoles.SHOPPER),
                        new SimpleGrantedAuthority("ROLE_SHOPPER"),
                        new SimpleGrantedAuthority("PERM_" + PolarisPermissions.CATALOG_READ),
                        new SimpleGrantedAuthority("PERMISSION_" + PolarisPermissions.CATALOG_READ),
                        new SimpleGrantedAuthority("PERM_" + PolarisPermissions.ORDER_READ),
                        new SimpleGrantedAuthority("PERMISSION_" + PolarisPermissions.ORDER_READ),
                        new SimpleGrantedAuthority("PERM_" + PolarisPermissions.ORDER_WRITE),
                        new SimpleGrantedAuthority("PERMISSION_" + PolarisPermissions.ORDER_WRITE)
                ));
    }

    public static JwtRequestPostProcessor admin() {
        return SecurityMockMvcRequestPostProcessors.jwt()
                .authorities(List.of(
                        new SimpleGrantedAuthority("ROLE_" + PolarisRoles.ADMIN),
                        new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority("PERM_" + PolarisPermissions.CATALOG_READ),
                        new SimpleGrantedAuthority("PERMISSION_" + PolarisPermissions.CATALOG_READ),
                        new SimpleGrantedAuthority("PERM_" + PolarisPermissions.CATALOG_WRITE),
                        new SimpleGrantedAuthority("PERMISSION_" + PolarisPermissions.CATALOG_WRITE),
                        new SimpleGrantedAuthority("PERM_" + PolarisPermissions.ORDER_READ),
                        new SimpleGrantedAuthority("PERMISSION_" + PolarisPermissions.ORDER_READ),
                        new SimpleGrantedAuthority("PERM_" + PolarisPermissions.ORDER_WRITE),
                        new SimpleGrantedAuthority("PERMISSION_" + PolarisPermissions.ORDER_WRITE),
                        new SimpleGrantedAuthority("PERM_" + PolarisPermissions.CUSTOMER_READ),
                        new SimpleGrantedAuthority("PERMISSION_" + PolarisPermissions.CUSTOMER_READ),
                        new SimpleGrantedAuthority("PERM_" + PolarisPermissions.CUSTOMER_WRITE),
                        new SimpleGrantedAuthority("PERMISSION_" + PolarisPermissions.CUSTOMER_WRITE),
                        new SimpleGrantedAuthority("PERM_" + PolarisPermissions.INVENTORY_READ),
                        new SimpleGrantedAuthority("PERMISSION_" + PolarisPermissions.INVENTORY_READ),
                        new SimpleGrantedAuthority("PERM_" + PolarisPermissions.INVENTORY_WRITE),
                        new SimpleGrantedAuthority("PERMISSION_" + PolarisPermissions.INVENTORY_WRITE)
                ));
    }
}
