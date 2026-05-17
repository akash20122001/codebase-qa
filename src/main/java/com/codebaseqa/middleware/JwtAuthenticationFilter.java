package com.codebaseqa.middleware;

import com.codebaseqa.model.User;
import com.codebaseqa.repository.UserRepository;
import com.codebaseqa.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        
        final String authHeader = request.getHeader("Authorization");
        final String requestUri = request.getRequestURI();
        final String method = request.getMethod();
        
        log.info("=== JWT Filter - {} {} ===", method, requestUri);
        log.info("JwtAuthenticationFilter - Authorization header: {}", authHeader != null ? "Present" : "Missing");
        
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("JwtAuthenticationFilter - No valid Authorization header found");
            filterChain.doFilter(request, response);
            return;
        }

        try {
            final String jwt = authHeader.substring(7);
            log.info("JwtAuthenticationFilter - Extracted JWT token (length: {})", jwt.length());
            
            final String userId = jwtService.extractUserId(jwt);
            log.info("JwtAuthenticationFilter - Extracted userId: {}", userId);

            if (userId != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                log.info("JwtAuthenticationFilter - Validating token...");
                
                if (jwtService.isTokenValid(jwt)) {
                    log.info("JwtAuthenticationFilter - Token is valid, loading user...");
                    
                    // Load the actual User object from database
                    User user = userRepository.findById(UUID.fromString(userId))
                        .orElse(null);
                    
                    if (user != null) {
                        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                                user,  // Set the User object as principal
                                null,
                                new ArrayList<>()
                        );
                        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(authToken);
                        log.info("JwtAuthenticationFilter - Successfully authenticated user: {} (ID: {})", user.getUsername(), user.getId());
                        log.info("JwtAuthenticationFilter - SecurityContext authentication set: {}", SecurityContextHolder.getContext().getAuthentication() != null);
                    } else {
                        log.error("JwtAuthenticationFilter - User not found for ID: {}", userId);
                    }
                } else {
                    log.warn("JwtAuthenticationFilter - Token validation failed");
                }
            } else {
                if (userId == null) {
                    log.warn("JwtAuthenticationFilter - Could not extract userId from token");
                }
                if (SecurityContextHolder.getContext().getAuthentication() != null) {
                    log.info("JwtAuthenticationFilter - User already authenticated");
                }
            }
        } catch (Exception e) {
            log.error("JwtAuthenticationFilter - JWT authentication failed: {}", e.getMessage(), e);
            // Invalid token - continue without authentication
        }

        log.info("JwtAuthenticationFilter - Proceeding to next filter. Auth present: {}", SecurityContextHolder.getContext().getAuthentication() != null);
        filterChain.doFilter(request, response);
    }
}
