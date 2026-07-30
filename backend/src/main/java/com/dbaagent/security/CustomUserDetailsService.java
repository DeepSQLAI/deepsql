package com.dbaagent.security;

import com.dbaagent.model.Permission;
import com.dbaagent.model.Role;
import com.dbaagent.model.User;
import com.dbaagent.repository.UserRepository;
import com.dbaagent.service.PermissionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PermissionService permissionService;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with username: " + username));

        Collection<GrantedAuthority> authorities = buildAuthorities(user);

        return new org.springframework.security.core.userdetails.User(
                user.getUsername(),
                user.getPassword(),
                authorities
        );
    }

    /**
     * Load user entity by username.
     * Used when we need the full User object, not just UserDetails.
     */
    public Optional<User> loadUserEntityByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    /**
     * Build Spring Security authorities from user's role and permissions.
     * Includes both ROLE_xxx authority and individual permission authorities.
     */
    private Collection<GrantedAuthority> buildAuthorities(User user) {
        List<GrantedAuthority> authorities = new ArrayList<>();

        Role role = user.getRoleEnum();

        // Add role authority (e.g., ROLE_ADMIN, ROLE_DEVELOPER)
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role.name()));

        // Add individual permission authorities
        for (Permission permission : permissionService.getEffectivePermissions(role)) {
            authorities.add(new SimpleGrantedAuthority(permission.name()));
        }

        return authorities;
    }
}
