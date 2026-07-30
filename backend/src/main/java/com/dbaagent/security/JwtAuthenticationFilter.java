package com.dbaagent.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import com.dbaagent.service.AuthSessionService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Value("${security.auth.enabled:true}")
    private boolean authEnabled;

    @Value("${security.cookie.name:auth_token}")
    private String cookieName;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private CustomUserDetailsService userDetailsService;

    @Autowired
    private AuthSessionService authSessionService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String requestPath = request.getRequestURI();
        boolean isBrainRequest = requestPath != null && requestPath.contains("/brain/");
        // Bypass authentication if disabled
        if (!authEnabled) {
            // Set a default authentication context with ADMIN role for dev mode
            List<org.springframework.security.core.GrantedAuthority> devAuthorities = new ArrayList<>();
            devAuthorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"));
            // Add all permissions for admin
            devAuthorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority("VIEW_DASHBOARD"));
            devAuthorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority("VIEW_SCHEMA"));
            devAuthorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority("VIEW_SLOW_QUERIES"));
            devAuthorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority("VIEW_BRAIN"));
            devAuthorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority("EXECUTE_QUERIES"));
            devAuthorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority("USE_CHAT"));
            devAuthorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority("RUN_ANALYSIS"));
            devAuthorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority("EXECUTE_PLAYBOOKS"));
            devAuthorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority("USE_INDEX_ADVISOR"));
            devAuthorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority("MANAGE_CONNECTIONS"));
            devAuthorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority("MANAGE_USERS"));
            devAuthorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority("MANAGE_INVITE_CODES"));
            devAuthorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority("MANAGE_SETTINGS"));

            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    "admin", null, devAuthorities);
            SecurityContextHolder.getContext().setAuthentication(auth);
            chain.doFilter(request, response);
            return;
        }

        final String authorizationHeader = request.getHeader("Authorization");
        if (isBrainRequest) {
            log.info("Brain auth check: path={}, hasAuthHeader={}, hasCookies={}",
                requestPath,
                authorizationHeader != null,
                request.getCookies() != null);
        }

        String username = null;
        String jwt = null;
        String sessionId = null;

        if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
            jwt = authorizationHeader.substring(7);
            if (!jwt.startsWith(com.dbaagent.service.McpTokenService.TOKEN_PREFIX)) {
                username = extractUsernameSafely(jwt);
                sessionId = extractSessionIdSafely(jwt);
            }
        } else {
            Cookie[] cookies = request.getCookies();
            if (cookies != null) {
                for (Cookie cookie : cookies) {
                    if (cookieName.equals(cookie.getName())) {
                        jwt = cookie.getValue();
                        username = extractUsernameSafely(jwt);
                        sessionId = extractSessionIdSafely(jwt);
                        break;
                    }
                }
            }
        }

        if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {

            UserDetails userDetails;
            try {
                userDetails = this.userDetailsService.loadUserByUsername(username);
            } catch (org.springframework.security.core.userdetails.UsernameNotFoundException e) {
                chain.doFilter(request, response);
                return;
            }

            if (sessionId != null && jwtUtil.validateToken(jwt, userDetails) && authSessionService.findValidSession(sessionId).isPresent()) {

                UsernamePasswordAuthenticationToken usernamePasswordAuthenticationToken = new UsernamePasswordAuthenticationToken(
                        userDetails, null, userDetails.getAuthorities());
                usernamePasswordAuthenticationToken
                        .setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(usernamePasswordAuthenticationToken);
                request.setAttribute("auth.sessionId", sessionId);
                if (isBrainRequest) {
                    log.info("Brain auth success: path={}, user={}", requestPath, username);
                }
            } else if (isBrainRequest) {
                log.warn("Brain auth failed token validation: path={}, user={}, sessionId={}", requestPath, username, sessionId);
            }
        } else if (isBrainRequest) {
            log.warn("Brain auth missing user: path={}, username={}", requestPath, username);
        }
        chain.doFilter(request, response);
    }

    private String extractUsernameSafely(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return jwtUtil.extractUsername(token);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractSessionIdSafely(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return jwtUtil.extractSessionId(token);
        } catch (Exception e) {
            return null;
        }
    }
}
