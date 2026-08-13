package com.dbaagent.config;

import com.dbaagent.security.CustomUserDetailsService;
import com.dbaagent.security.JwtAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    @Autowired
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Autowired
    private com.dbaagent.security.McpTokenAuthenticationFilter mcpTokenAuthenticationFilter;

    @Autowired
    private CustomUserDetailsService customUserDetailsService;

    @Value("${security.auth.enabled:true}")
    private boolean authEnabled;

    /**
     * Localhost only. Every deployment that serves a browser from anywhere else must set
     * {@code CORS_ALLOWED_ORIGINS} (or {@code cors.allowed.origins}) explicitly.
     *
     * <p>This default used to list the operating company's production hostnames. That is a
     * property default in Java, where {@code SelfHostPropertiesSafetyTest} — which reads
     * only {@code application*.properties} — could not see it, and it ships to every reader
     * of the public repository. {@code CorsAllowlistSafetyTest} now scans this file too.
     */
    @Value("${cors.allowed.origins:http://localhost:3000,http://localhost:3001,http://localhost:3002,http://127.0.0.1:3000,http://127.0.0.1:3001,http://127.0.0.1:3002}")
    private String corsAllowedOrigins;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()));

        if (authEnabled) {
            // Production mode: Require authentication
            http
                            .authorizeHttpRequests(auth -> auth
                            // Allow async dispatches (SSE streaming completion) without re-auth
                            .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                            .requestMatchers(
                                    "/auth/login",
                                    "/auth/signup",
                                    "/auth/email/start",
                                    "/auth/email/verify",
                                    "/auth/google/start",
                                    "/auth/google/callback",
                                    "/auth/mfa/enroll/start",
                                    "/auth/mfa/enroll/confirm",
                                    "/auth/mfa/verify",
                                    "/auth/refresh",
                                    "/auth/invite/preview",
                                    "/auth/invite/accept",
                                    "/auth/beta-signup",
                                    // CLI authorization: token issuance endpoints are
                                    // unauthenticated (caller is a logged-out CLI). The
                                    // /approve, /deny, /pending and device/{approve,deny,pending}
                                    // endpoints require an authenticated browser session.
                                    "/auth/cli/authorize",
                                    "/auth/cli/exchange",
                                    "/auth/cli/device/code",
                                    "/auth/cli/device/token",
                                    // Actuator: only liveness/readiness without auth. metrics/prometheus
                                    // require an authenticated session (see application-prod.properties).
                                    "/actuator/health",
                                    "/actuator/health/**",
                                    "/error",
                                    "/admin/bootstrap/link",
                                    "/users/admin/bootstrap",
                                    "/users/admin/reset",
                                    "/invite-codes/validate/**",
                                    // Onboarding: public endpoints — status check and first-run
                                    // initialization don't require an existing user/token
                                    "/setup/status",
                                    "/setup/initialize",
                                    // Internal test-suite token issuance — validated by pre-shared
                                    // INTERNAL_TEST_TOKEN secret, not by user credentials
                                    "/auth/internal/token",
                                    // Public dashboard sharing — the share token is the authorization;
                                    // these endpoints only resolve while the dashboard is published,
                                    // and every query is server-enforced read-only.
                                    "/public/**"
                            ).permitAll()
                            // Admin endpoints require ADMIN role
                            .requestMatchers("/admin/**").hasRole("ADMIN")
                            .requestMatchers("/users", "/users/**").hasRole("ADMIN")
                            // Invite code management (except validate) requires ADMIN role
                            .requestMatchers("/invite-codes", "/invite-codes/**")
                                .hasRole("ADMIN")
                            .anyRequest().authenticated())
                    .sessionManagement(session -> session
                            .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .exceptionHandling(ex -> ex
                            .authenticationEntryPoint((request, response, authException) -> {
                                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                                response.setContentType("application/json");
                                response.getWriter().write("{\"message\":\"Unauthorized - Please login\"}");
                            }))
                    .addFilterBefore(mcpTokenAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                    .addFilterAfter(jwtAuthenticationFilter, com.dbaagent.security.McpTokenAuthenticationFilter.class);
        } else {
            // Development mode: Permit all requests
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        }

        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(HttpSecurity http) throws Exception {
        AuthenticationManagerBuilder auth = http.getSharedObject(AuthenticationManagerBuilder.class);
        auth.userDetailsService(customUserDetailsService)
                .passwordEncoder(passwordEncoder());
        return auth.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        List<String> allowedOriginPatterns = Arrays.stream(corsAllowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .collect(Collectors.toList());
        configuration.setAllowedOriginPatterns(allowedOriginPatterns);
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(Arrays.asList(
                "Authorization",
                "Content-Type",
                "X-Requested-With",
                "X-Admin-Bootstrap-Secret"));
        configuration.setExposedHeaders(List.of("Authorization"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
