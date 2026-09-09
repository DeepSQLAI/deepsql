package com.dbaagent.security;

import com.dbaagent.repository.UserRepository;
import com.dbaagent.service.ClientContext;
import com.dbaagent.service.McpTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
@Slf4j
public class McpTokenAuthenticationFilter extends OncePerRequestFilter {

    @Value("${security.auth.enabled:true}")
    private boolean authEnabled;

    private final McpTokenService mcpTokenService;
    private final CustomUserDetailsService userDetailsService;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {

        if (!authEnabled || SecurityContextHolder.getContext().getAuthentication() != null) {
            chain.doFilter(request, response);
            return;
        }

        String authorizationHeader = request.getHeader("Authorization");
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }

        String token = authorizationHeader.substring(7);
        if (!mcpTokenService.looksLikeMcpToken(token)) {
            chain.doFilter(request, response);
            return;
        }

        var authenticated = mcpTokenService.authenticate(token, request.getRemoteAddr());
        if (authenticated.isPresent() && !declaredUserMatches(request, authenticated.get().username())) {
            // The agent runtime keeps ONE MCP subprocess for every profile, and
            // the provisioner rotates its credential on disk. A token belonging
            // to a different user than the one this MCP process was started for
            // means the credential was overwritten by someone else's Agent-tab
            // open — authenticating it here would run this user's tools as that
            // other user (cross-user read + falsified audit attribution).
            // Fail closed: the caller must re-provision, not silently proceed.
            log.warn("Rejecting MCP token for {} — request declares user {}",
                authenticated.get().username(), declaredUser(request));
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"mcp_identity_mismatch\",\"message\":"
                + "\"This Agent session's credential belongs to a different user. "
                + "Reopen the Agent tab to continue.\"}");
            return;
        }

        authenticated.ifPresent(authenticatedToken -> {
            UserDetails userDetails = userDetailsService.loadUserByUsername(authenticatedToken.username());
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                userDetails,
                null,
                userDetails.getAuthorities()
            );
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            log.debug("Authenticated MCP token {} for user {}",
                authenticatedToken.tokenId(), authenticatedToken.username());
        });

        chain.doFilter(request, response);
    }

    /**
     * The user this MCP process was provisioned for, as declared by the caller.
     *
     * <p>The agent provisioner writes {@code DEEPSQL_MCP_USER_ID=<username>} into
     * each profile's MCP server env, and the MCP shim forwards it as
     * {@link ClientContext#HEADER_AGENT}. It is a *claim*, not a credential — it
     * is only ever used to REFUSE a mismatched token, never to grant access, so
     * a forged value cannot widen access beyond what the token already allows.
     */
    private static String declaredUser(HttpServletRequest request) {
        String declared = request.getHeader(ClientContext.HEADER_AGENT);
        return declared == null || declared.isBlank() ? null : declared.trim();
    }

    /**
     * True when the request carries no user claim (editor/CLI installs, curl,
     * every pre-existing caller) or the claim matches the token's owner.
     *
     * <p>Absent claim stays permissive on purpose: {@code DEEPSQL_MCP_USER_ID}
     * defaults to non-username values for editor installs ("cursor",
     * "claude-code", "mcp-phase1"), and those tokens are not agent-provisioned,
     * so there is no shared-credential hazard to guard against. Only a claim
     * that looks like a DeepSQL agent profile identity is enforced.
     */
    private boolean declaredUserMatches(HttpServletRequest request, String tokenOwner) {
        String declared = declaredUser(request);
        if (declared == null || tokenOwner == null) {
            return true;
        }
        if (declared.equalsIgnoreCase(tokenOwner)) {
            return true;
        }
        // The claim differs from the token owner. Enforce only when the claim
        // names a real DeepSQL user — that is the agent-provisioner case, where
        // DEEPSQL_MCP_USER_ID is a username and a mismatch means the shared
        // token file was overwritten by another user's Agent-tab open.
        //
        // Editor/CLI clients put a *tool* name here ("cursor", "claude-desktop",
        // "terminal", "mcp-phase1", or any --caller-agent value), which never
        // resolves to a user, so those callers are unaffected. Matching against
        // the user table rather than a denylist of sentinels keeps free-form
        // --caller-agent values working without a list to maintain.
        return userRepository.findByUsernameIgnoreCase(declared).isEmpty();
    }
}
