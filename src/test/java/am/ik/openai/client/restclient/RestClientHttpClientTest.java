package am.ik.openai.client.restclient;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import am.ik.openai.client.restclient.MockHttpServer.RecordedRequest;
import com.openai.core.RequestOptions;
import com.openai.core.Timeout;
import com.openai.core.http.HttpMethod;
import com.openai.core.http.HttpRequest;
import com.openai.core.http.HttpRequestBody;
import com.openai.core.http.HttpResponse;
import com.openai.errors.OpenAIIoException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RestClientHttpClientTest {

	private MockHttpServer server;

	private RestClientHttpClient client;

	@BeforeEach
	void setUp() throws IOException {
		this.server = new MockHttpServer();
		this.client = RestClientHttpClient.builder()
			.restClientBuilder(RestClient.builder().requestFactory(new JdkClientHttpRequestFactory()))
			.build();
	}

	@AfterEach
	void tearDown() {
		this.client.close();
		this.server.close();
	}

	@Test
	void getBuildsMethodPathAndMultiValuedQueryParams() {
		HttpRequest request = HttpRequest.builder()
			.method(HttpMethod.GET)
			.baseUrl(this.server.baseUrl())
			.addPathSegments("v1", "models")
			.putQueryParams("ids", List.of("a", "b"))
			.build();

		try (HttpResponse response = this.client.execute(request)) {
			assertThat(response.statusCode()).isEqualTo(200);
		}

		RecordedRequest recorded = this.server.lastRequest();
		assertThat(recorded.method()).isEqualTo("GET");
		assertThat(recorded.path()).isEqualTo("/v1/models");
		assertThat(recorded.rawQuery()).isEqualTo("ids=a&ids=b");
	}

	@Test
	void sendsMultipleHeaderValues() {
		HttpRequest request = HttpRequest.builder()
			.method(HttpMethod.GET)
			.baseUrl(this.server.baseUrl())
			.addPathSegment("v1")
			.putHeaders("X-Custom", List.of("first", "second"))
			.build();

		try (HttpResponse response = this.client.execute(request)) {
			assertThat(response.statusCode()).isEqualTo(200);
		}

		RecordedRequest recorded = this.server.lastRequest();
		assertThat(recorded.headers().get("X-Custom")).containsExactly("first", "second");
	}

	@Test
	void injectsStainlessTimeoutHeaders() {
		HttpRequest request = HttpRequest.builder()
			.method(HttpMethod.GET)
			.baseUrl(this.server.baseUrl())
			.addPathSegment("v1")
			.build();

		try (HttpResponse response = this.client.execute(request)) {
			assertThat(response.statusCode()).isEqualTo(200);
		}

		RecordedRequest recorded = this.server.lastRequest();
		// Default Timeout: request() = 10 minutes, read() defaults to request().
		assertThat(recorded.firstHeader("X-Stainless-Read-Timeout")).isEqualTo("600");
		assertThat(recorded.firstHeader("X-Stainless-Timeout")).isEqualTo("600");
	}

	@Test
	void perRequestTimeoutOverridesStainlessHeaders() {
		HttpRequest request = HttpRequest.builder()
			.method(HttpMethod.GET)
			.baseUrl(this.server.baseUrl())
			.addPathSegment("v1")
			.build();
		RequestOptions options = RequestOptions.builder()
			.timeout(Timeout.builder().read(Duration.ofSeconds(30)).request(Duration.ofSeconds(90)).build())
			.build();

		try (HttpResponse response = this.client.execute(request, options)) {
			assertThat(response.statusCode()).isEqualTo(200);
		}

		RecordedRequest recorded = this.server.lastRequest();
		assertThat(recorded.firstHeader("X-Stainless-Read-Timeout")).isEqualTo("30");
		assertThat(recorded.firstHeader("X-Stainless-Timeout")).isEqualTo("90");
	}

	@Test
	void postSendsBodyWithContentTypeAndClosesBody() {
		RecordingRequestBody body = new RecordingRequestBody("{\"key\":\"value\"}".getBytes(StandardCharsets.UTF_8),
				"application/json", true);
		HttpRequest request = HttpRequest.builder()
			.method(HttpMethod.POST)
			.baseUrl(this.server.baseUrl())
			.addPathSegment("v1")
			.body(body)
			.build();

		try (HttpResponse response = this.client.execute(request)) {
			assertThat(response.statusCode()).isEqualTo(200);
		}

		RecordedRequest recorded = this.server.lastRequest();
		assertThat(recorded.method()).isEqualTo("POST");
		assertThat(recorded.bodyAsString()).isEqualToNormalizingWhitespace("""
				{"key":"value"}""");
		assertThat(recorded.firstHeader("Content-Type")).isEqualTo("application/json");
		assertThat(body.isClosed()).isTrue();
	}

	@Test
	void mapsResponseStatusHeadersAndBody() throws IOException {
		this.server.respondWith(201, "application/json", "{\"ok\":true}", Map.of("X-Request-Id", "req-123"));
		HttpRequest request = HttpRequest.builder()
			.method(HttpMethod.GET)
			.baseUrl(this.server.baseUrl())
			.addPathSegment("v1")
			.build();

		try (HttpResponse response = this.client.execute(request)) {
			assertThat(response.statusCode()).isEqualTo(201);
			assertThat(response.headers().values("X-Request-Id")).containsExactly("req-123");
			assertThat(response.requestId()).hasValue("req-123");
			String bodyText = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
			assertThat(bodyText).isEqualToNormalizingWhitespace("""
					{"ok":true}""");
		}
	}

	@Test
	void returnsResponseForErrorStatusWithoutThrowing() throws IOException {
		this.server.respondWith(500, "application/json", "{\"error\":\"boom\"}", Map.of());
		HttpRequest request = HttpRequest.builder()
			.method(HttpMethod.GET)
			.baseUrl(this.server.baseUrl())
			.addPathSegment("v1")
			.build();

		try (HttpResponse response = this.client.execute(request)) {
			assertThat(response.statusCode()).isEqualTo(500);
			String bodyText = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
			assertThat(bodyText).isEqualToNormalizingWhitespace("""
					{"error":"boom"}""");
		}
	}

	@Test
	void executeAsyncCompletesWithResponse() throws Exception {
		HttpRequest request = HttpRequest.builder()
			.method(HttpMethod.GET)
			.baseUrl(this.server.baseUrl())
			.addPathSegment("v1")
			.build();

		CompletableFuture<HttpResponse> future = this.client.executeAsync(request);
		try (HttpResponse response = future.get()) {
			assertThat(response.statusCode()).isEqualTo(200);
		}
	}

	@Test
	void executeAsyncRunsOnSuppliedExecutor() throws Exception {
		AtomicReference<String> threadName = new AtomicReference<>();
		Executor executor = task -> new Thread(() -> {
			threadName.set(Thread.currentThread().getName());
			task.run();
		}, "openai-async-test").start();
		try (RestClientHttpClient customClient = RestClientHttpClient.builder()
			.restClientBuilder(RestClient.builder().requestFactory(new JdkClientHttpRequestFactory()))
			.executor(executor)
			.build()) {
			HttpRequest request = HttpRequest.builder()
				.method(HttpMethod.GET)
				.baseUrl(this.server.baseUrl())
				.addPathSegment("v1")
				.build();

			try (HttpResponse response = customClient.executeAsync(request).get()) {
				assertThat(response.statusCode()).isEqualTo(200);
			}
		}
		assertThat(threadName.get()).isEqualTo("openai-async-test");
	}

	@Test
	void executeAsyncCancellationAbortsInFlightRequest() throws Exception {
		// The server holds the response far longer than the test waits, so the only way
		// the worker can finish quickly is if cancellation interrupts the blocking call.
		this.server.delayResponseBy(TimeUnit.SECONDS.toMillis(10));
		CountDownLatch workerDone = new CountDownLatch(1);
		Executor executor = task -> new Thread(() -> {
			try {
				task.run();
			}
			finally {
				workerDone.countDown();
			}
		}, "openai-cancel-test").start();

		try (RestClientHttpClient customClient = RestClientHttpClient.builder()
			.restClientBuilder(RestClient.builder().requestFactory(new JdkClientHttpRequestFactory()))
			.executor(executor)
			.build()) {
			HttpRequest request = HttpRequest.builder()
				.method(HttpMethod.GET)
				.baseUrl(this.server.baseUrl())
				.addPathSegment("v1")
				.build();

			CompletableFuture<HttpResponse> future = customClient.executeAsync(request);
			assertThat(this.server.awaitRequest(TimeUnit.SECONDS.toMillis(5))).isTrue();

			boolean cancelled = future.cancel(true);

			assertThat(cancelled).isTrue();
			assertThat(future.isCancelled()).isTrue();
			// The worker must finish well before the server's 10s delay, proving the
			// in-flight request was interrupted rather than left running.
			assertThat(workerDone.await(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	@Test
	void streamsLengthUnknownBodyUsingChunkedTransfer() {
		// A 32 KB payload with a deterministic pattern so the assertion is stable.
		byte[] payload = new byte[32 * 1024];
		for (int i = 0; i < payload.length; i++) {
			payload[i] = (byte) (i % 251);
		}
		// contentLength() == -1 and repeatable() == false models a streaming upload
		// (e.g. multipart/form-data) whose size is not known in advance.
		StreamingRequestBody body = new StreamingRequestBody(payload, "multipart/form-data; boundary=test-boundary");
		HttpRequest request = HttpRequest.builder()
			.method(HttpMethod.POST)
			.baseUrl(this.server.baseUrl())
			.addPathSegments("v1", "files")
			.body(body)
			.build();

		try (HttpResponse response = this.client.execute(request)) {
			assertThat(response.statusCode()).isEqualTo(200);
		}

		RecordedRequest recorded = this.server.lastRequest();
		assertThat(recorded.method()).isEqualTo("POST");
		assertThat(recorded.path()).isEqualTo("/v1/files");
		// The whole payload must arrive even though it was streamed in many small chunks.
		assertThat(recorded.body()).isEqualTo(payload);
		// Spring's MediaType serialises parameters without a space after the semicolon.
		assertThat(recorded.firstHeader("Content-Type")).isEqualTo("multipart/form-data;boundary=test-boundary");
		// No Content-Length: the length was unknown, so the request used chunked
		// transfer.
		assertThat(recorded.firstHeader("Content-Length")).isNull();
		assertThat(recorded.firstHeader("Transfer-Encoding")).isEqualTo("chunked");
		assertThat(body.isClosed()).isTrue();
	}

	@Test
	void wrapsConnectionFailureInOpenAIIoException() {
		// Point at a closed port to force a connection failure.
		String unreachableBaseUrl = this.server.baseUrl();
		this.server.close();
		HttpRequest request = HttpRequest.builder()
			.method(HttpMethod.GET)
			.baseUrl(unreachableBaseUrl)
			.addPathSegment("v1")
			.build();

		assertThatThrownBy(() -> this.client.execute(request)).isInstanceOf(OpenAIIoException.class)
			.hasMessage("Request failed");
	}

	/**
	 * A test {@link HttpRequestBody} that records whether it was closed.
	 */
	static final class RecordingRequestBody implements HttpRequestBody {

		private final byte[] content;

		private final String contentType;

		private final boolean repeatable;

		private final AtomicBoolean closed = new AtomicBoolean();

		RecordingRequestBody(byte[] content, String contentType, boolean repeatable) {
			this.content = content;
			this.contentType = contentType;
			this.repeatable = repeatable;
		}

		@Override
		public void writeTo(OutputStream outputStream) {
			try {
				outputStream.write(this.content);
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}

		@Override
		public String contentType() {
			return this.contentType;
		}

		@Override
		public long contentLength() {
			return this.content.length;
		}

		@Override
		public boolean repeatable() {
			return this.repeatable;
		}

		@Override
		public void close() {
			this.closed.set(true);
		}

		boolean isClosed() {
			return this.closed.get();
		}

	}

	/**
	 * A test {@link HttpRequestBody} that streams its payload in small chunks with an
	 * unknown length ({@code contentLength() == -1}) and is not repeatable, mimicking a
	 * multipart upload. Records whether it was closed.
	 */
	static final class StreamingRequestBody implements HttpRequestBody {

		private static final int CHUNK_SIZE = 1024;

		private final byte[] content;

		private final String contentType;

		private final AtomicBoolean closed = new AtomicBoolean();

		StreamingRequestBody(byte[] content, String contentType) {
			this.content = content;
			this.contentType = contentType;
		}

		@Override
		public void writeTo(OutputStream outputStream) {
			try {
				for (int offset = 0; offset < this.content.length; offset += CHUNK_SIZE) {
					int length = Math.min(CHUNK_SIZE, this.content.length - offset);
					outputStream.write(this.content, offset, length);
					outputStream.flush();
				}
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}

		@Override
		public String contentType() {
			return this.contentType;
		}

		@Override
		public long contentLength() {
			// Unknown length forces chunked transfer encoding.
			return -1;
		}

		@Override
		public boolean repeatable() {
			return false;
		}

		@Override
		public void close() {
			this.closed.set(true);
		}

		boolean isClosed() {
			return this.closed.get();
		}

	}

}
