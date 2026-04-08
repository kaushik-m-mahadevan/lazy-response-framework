package com.lazyresponse.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Servlet filter that wraps incoming {@link HttpServletRequest}s with a
 * {@link CachedBodyHttpServletRequest}, enabling the request body to be read multiple times.
 *
 * <p>Registered automatically by the framework's auto-configuration at the highest precedence,
 * ensuring the body is cached before any other filter or servlet processes the request.
 *
 * <p>The {@link com.lazyresponse.interceptor.LazyResponseAspect} depends on this filter
 * being in place to read the raw request body after Spring MVC argument resolution has run.
 *
 * <p>Only {@code application/json} requests are cached. All other content types (file uploads,
 * form posts, plain-text bodies, etc.) pass through unmodified to avoid buffering unnecessary
 * payloads in memory.
 */
public class RequestBodyCachingFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String contentType = request.getContentType();
        // Skip caching when there is no body or the body is not JSON.
        // @LazyResponse endpoints always consume application/json, so this
        // is a safe optimisation that avoids buffering uploads and form posts.
        return contentType == null
                || !contentType.startsWith(MediaType.APPLICATION_JSON_VALUE);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws IOException, ServletException {

        CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request);
        filterChain.doFilter(cachedRequest, response);
    }
}
