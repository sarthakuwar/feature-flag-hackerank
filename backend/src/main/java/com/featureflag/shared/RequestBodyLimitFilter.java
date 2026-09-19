package com.featureflag.shared;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Rejects request bodies over a fixed size with 413 PAYLOAD_TOO_LARGE before any controller reads
 * them. A declared Content-Length is checked up front; a chunked body has no declared length, so it
 * is read once, up to the limit plus one byte, and replayed to the controller. Runs outside the
 * MVC layer, so it writes the error envelope itself.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestBodyLimitFilter extends OncePerRequestFilter {

	private static final int PAYLOAD_TOO_LARGE = 413;

	private final int maxBodyBytes;

	private final ObjectMapper objectMapper;

	public RequestBodyLimitFilter(@Value("${featureflag.http.max-body-bytes:1048576}") int maxBodyBytes,
			ObjectMapper objectMapper) {
		this.maxBodyBytes = maxBodyBytes;
		this.objectMapper = objectMapper;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		long declaredLength = request.getContentLengthLong();

		if (declaredLength > maxBodyBytes) {
			reject(response);

			return;
		}

		if (declaredLength < 0 && isChunked(request)) {
			byte[] body = request.getInputStream().readNBytes(maxBodyBytes + 1);

			if (body.length > maxBodyBytes) {
				reject(response);

				return;
			}

			chain.doFilter(new ReplayedBodyRequest(request, body), response);

			return;
		}

		chain.doFilter(request, response);
	}

	private boolean isChunked(HttpServletRequest request) {
		String encoding = request.getHeader("Transfer-Encoding");

		return encoding != null && encoding.toLowerCase(Locale.ROOT).contains("chunked");
	}

	private void reject(HttpServletResponse response) throws IOException {
		response.setStatus(PAYLOAD_TOO_LARGE);
		response.setHeader("Connection", "close");
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		objectMapper.writeValue(response.getWriter(), GlobalExceptionHandler.envelope("PAYLOAD_TOO_LARGE",
				"Request body must be at most " + maxBodyBytes + " bytes.", null));
	}

	private static final class ReplayedBodyRequest extends HttpServletRequestWrapper {

		private final byte[] body;

		ReplayedBodyRequest(HttpServletRequest request, byte[] body) {
			super(request);
			this.body = body;
		}

		@Override
		public ServletInputStream getInputStream() {
			ByteArrayInputStream source = new ByteArrayInputStream(body);

			return new ServletInputStream() {
				@Override
				public int read() {
					return source.read();
				}

				@Override
				public int read(byte[] buffer, int offset, int length) {
					return source.read(buffer, offset, length);
				}

				@Override
				public boolean isFinished() {
					return source.available() == 0;
				}

				@Override
				public boolean isReady() {
					return true;
				}

				@Override
				public void setReadListener(ReadListener listener) {
					throw new IllegalStateException("Asynchronous reads are not supported for a replayed body.");
				}
			};
		}

		@Override
		public BufferedReader getReader() {
			String encoding = getCharacterEncoding();
			Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);

			return new BufferedReader(new InputStreamReader(getInputStream(), charset));
		}
	}
}
