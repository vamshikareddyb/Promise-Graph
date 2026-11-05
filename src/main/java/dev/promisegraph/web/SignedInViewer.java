package dev.promisegraph.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Team mode: the viewer is whoever is signed in, never what the browser claims. The
 * {@code X-PG-Viewer} header the API reads is replaced with the session's user, or removed when
 * nobody is signed in (so every data request answers 401).
 */
@Component
@ConditionalOnProperty(prefix = "promisegraph.auth", name = "mode", havingValue = "google")
public class SignedInViewer extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        Object user = session == null ? null : session.getAttribute(AuthController.USER);
        String viewer = user == null ? null : user.toString();
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override
            public String getHeader(String name) {
                return ApiController.VIEWER.equalsIgnoreCase(name) ? viewer : super.getHeader(name);
            }

            @Override
            public Enumeration<String> getHeaders(String name) {
                if (ApiController.VIEWER.equalsIgnoreCase(name)) {
                    return viewer == null ? Collections.emptyEnumeration() : Collections.enumeration(java.util.List.of(viewer));
                }
                return super.getHeaders(name);
            }
        }, response);
    }
}
