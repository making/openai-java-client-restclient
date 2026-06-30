package am.ik.openai.client.restclient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A minimal HTTP server built on the JDK's {@link HttpServer} for exercising
 * {@link RestClientHttpClient} in tests. It records the last received request and returns
 * a configurable response. No external test dependency is required.
 */
final class MockHttpServer implements AutoCloseable {

	private final HttpServer server;

	private volatile @org.jspecify.annotations.Nullable RecordedRequest lastRequest;

	private volatile int responseStatus = 200;

	private volatile byte[] responseBody = new byte[0];

	private final Map<String, String> responseHeaders = new LinkedHashMap<>();

	private final CountDownLatch requestReceived = new CountDownLatch(1);

	private volatile long responseDelayMillis = 0;

	private volatile @org.jspecify.annotations.Nullable List<byte[]> sseChunks;

	private volatile int sseGateAfterIndex = -1;

	private final CountDownLatch sseGateReached = new CountDownLatch(1);

	private final CountDownLatch sseGateReleased = new CountDownLatch(1);

	MockHttpServer() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/", exchange -> {
			try {
				String method = exchange.getRequestMethod();
				URI uri = exchange.getRequestURI();
				Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
				exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, List.copyOf(values)));
				byte[] body = exchange.getRequestBody().readAllBytes();
				this.lastRequest = new RecordedRequest(method, uri.getPath(), uri.getRawQuery(), headers, body);
				this.requestReceived.countDown();

				List<byte[]> chunks = this.sseChunks;
				if (chunks != null) {
					handleSseStream(exchange, chunks);
					return;
				}

				// Optionally delay the response so that a test can cancel an in-flight
				// request before it completes.
				long delay = this.responseDelayMillis;
				if (delay > 0) {
					try {
						Thread.sleep(delay);
					}
					catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
						return;
					}
				}

				this.responseHeaders.forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
				byte[] responsePayload = this.responseBody;
				exchange.sendResponseHeaders(this.responseStatus,
						responsePayload.length == 0 ? -1 : responsePayload.length);
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(responsePayload);
				}
			}
			finally {
				exchange.close();
			}
		});
		this.server.start();
	}

	String baseUrl() {
		return "http://127.0.0.1:" + this.server.getAddress().getPort();
	}

	void respondWith(int status, @org.jspecify.annotations.Nullable String contentType, String body,
			Map<String, String> extraHeaders) {
		this.responseStatus = status;
		this.responseBody = body.getBytes(StandardCharsets.UTF_8);
		this.responseHeaders.clear();
		if (contentType != null) {
			this.responseHeaders.put("Content-Type", contentType);
		}
		this.responseHeaders.putAll(extraHeaders);
	}

	/**
	 * Makes the server wait the given number of milliseconds before sending the response,
	 * so that a test can observe and cancel an in-flight request.
	 * @param millis the delay in milliseconds
	 */
	void delayResponseBy(long millis) {
		this.responseDelayMillis = millis;
	}

	/**
	 * Configures the server to send a {@code text/event-stream} response using chunked
	 * transfer encoding. Each entry is written as a separate chunk and flushed
	 * immediately, so a client that consumes the body incrementally observes events one
	 * at a time. After writing the chunk at {@code gateAfterIndex}, the server blocks
	 * until {@link #releaseStream()} is called, which lets a test prove that the client
	 * receives the earlier events before the rest of the stream is sent.
	 * @param chunks the raw SSE payloads to send, in order
	 * @param gateAfterIndex the index of the chunk after which the server pauses, or a
	 * negative value to never pause
	 */
	void streamSse(List<String> chunks, int gateAfterIndex) {
		List<byte[]> encoded = new ArrayList<>();
		for (String chunk : chunks) {
			encoded.add(chunk.getBytes(StandardCharsets.UTF_8));
		}
		this.sseChunks = List.copyOf(encoded);
		this.sseGateAfterIndex = gateAfterIndex;
	}

	/**
	 * Waits until the streaming response has reached the gate configured by
	 * {@link #streamSse(List, int)}, i.e. the gated chunk has been sent and the server is
	 * paused before sending the remaining chunks.
	 * @param millis the maximum time to wait in milliseconds
	 * @return {@code true} if the gate was reached within the timeout
	 */
	boolean awaitStreamGate(long millis) throws InterruptedException {
		return this.sseGateReached.await(millis, TimeUnit.MILLISECONDS);
	}

	/**
	 * Releases a streaming response that is paused at the gate, allowing the server to
	 * send the remaining chunks.
	 */
	void releaseStream() {
		this.sseGateReleased.countDown();
	}

	private void handleSseStream(HttpExchange exchange, List<byte[]> chunks) throws IOException {
		exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
		// A response length of 0 selects chunked transfer encoding; the body is
		// terminated
		// by closing the stream.
		exchange.sendResponseHeaders(200, 0);
		try (OutputStream out = exchange.getResponseBody()) {
			for (int i = 0; i < chunks.size(); i++) {
				out.write(chunks.get(i));
				out.flush();
				if (i == this.sseGateAfterIndex) {
					this.sseGateReached.countDown();
					try {
						this.sseGateReleased.await();
					}
					catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
						return;
					}
				}
			}
		}
	}

	/**
	 * Waits until the server has received a request.
	 * @param millis the maximum time to wait in milliseconds
	 * @return {@code true} if a request was received within the timeout
	 */
	boolean awaitRequest(long millis) throws InterruptedException {
		return this.requestReceived.await(millis, java.util.concurrent.TimeUnit.MILLISECONDS);
	}

	RecordedRequest lastRequest() {
		RecordedRequest request = this.lastRequest;
		if (request == null) {
			throw new IllegalStateException("No request has been recorded yet");
		}
		return request;
	}

	@Override
	public void close() {
		this.server.stop(0);
	}

	/**
	 * A single recorded inbound request.
	 */
	record RecordedRequest(String method, String path, @org.jspecify.annotations.Nullable String rawQuery,
			Map<String, List<String>> headers, byte[] body) {

		String bodyAsString() {
			return new String(this.body, StandardCharsets.UTF_8);
		}

		@org.jspecify.annotations.Nullable
		String firstHeader(String name) {
			List<String> values = this.headers.get(name);
			return (values == null || values.isEmpty()) ? null : values.get(0);
		}
	}

}
