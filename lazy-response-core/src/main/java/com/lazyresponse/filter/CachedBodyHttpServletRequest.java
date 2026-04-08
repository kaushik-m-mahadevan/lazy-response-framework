package com.lazyresponse.filter;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.util.StreamUtils;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * An {@link HttpServletRequestWrapper} that eagerly reads and caches the request body,
 * allowing the body to be read multiple times.
 *
 * <p>Standard {@link HttpServletRequest#getInputStream()} can only be consumed once.
 * This wrapper reads the body upfront in the constructor and provides a fresh
 * {@link ByteArrayInputStream} on every subsequent call to {@link #getInputStream()}.
 *
 * <p>This is required so that the {@link com.lazyresponse.interceptor.LazyResponseAspect}
 * can read the body after Spring MVC has had an opportunity to process the request
 * (even though Spring MVC does not read the body for {@code @LazyResponse} methods,
 * since those parameters do not carry {@code @RequestBody}).
 *
 * <p>Registered automatically by the framework's auto-configuration via
 * {@link RequestBodyCachingFilter}.
 */
public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] cachedBody;

    public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
        super(request);
        this.cachedBody = StreamUtils.copyToByteArray(request.getInputStream());
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream byteStream = new ByteArrayInputStream(cachedBody);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return byteStream.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                throw new UnsupportedOperationException("Async reading is not supported by the body cache.");
            }

            @Override
            public int read() {
                return byteStream.read();
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream()));
    }

    /** Returns the eagerly cached raw body bytes. */
    public byte[] getCachedBody() {
        return cachedBody;
    }
}
