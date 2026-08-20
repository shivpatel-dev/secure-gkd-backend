package com.shiv.securegkd;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

public final class JsonRequestBodySizeFilter extends OncePerRequestFilter {

    static final int MAX_JSON_BODY_BYTES = 16 * 1024;

    private static final Pattern ALLOCATION_PATH = Pattern.compile(
            "^/api/games/[^/]+/allocations$"
    );

    private final ObjectMapper objectMapper;

    public JsonRequestBodySizeFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!HttpMethod.POST.matches(request.getMethod()) || !hasJsonContentType(request)) {
            return true;
        }

        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.equals("/api/auth/token")
                && !path.equals("/api/games")
                && !ALLOCATION_PATH.matcher(path).matches();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        byte[] body = request.getInputStream().readNBytes(MAX_JSON_BODY_BYTES + 1);
        if (body.length > MAX_JSON_BODY_BYTES) {
            writePayloadTooLarge(response, request);
            return;
        }

        filterChain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private boolean hasJsonContentType(HttpServletRequest request) {
        String contentType = request.getContentType();
        if (contentType == null) {
            return false;
        }

        try {
            MediaType mediaType = MediaType.parseMediaType(contentType);
            return MediaType.APPLICATION_JSON.isCompatibleWith(mediaType)
                    || mediaType.getSubtype().endsWith("+json");
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void writePayloadTooLarge(
            HttpServletResponse response,
            HttpServletRequest request
    ) throws IOException {
        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getOutputStream(),
                ApiError.of(
                        HttpStatus.PAYLOAD_TOO_LARGE,
                        "Request body too large",
                        request.getRequestURI()
                )
        );
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new CachedBodyServletInputStream(body);
        }

        @Override
        public BufferedReader getReader() {
            Charset charset = getCharacterEncoding() == null
                    ? StandardCharsets.UTF_8
                    : Charset.forName(getCharacterEncoding());
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }

    private static final class CachedBodyServletInputStream extends ServletInputStream {

        private final ByteArrayInputStream delegate;

        private CachedBodyServletInputStream(byte[] body) {
            delegate = new ByteArrayInputStream(body);
        }

        @Override
        public int read() {
            return delegate.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            return delegate.read(bytes, offset, length);
        }

        @Override
        public boolean isFinished() {
            return delegate.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            throw new UnsupportedOperationException("Asynchronous reads are not supported");
        }
    }
}
