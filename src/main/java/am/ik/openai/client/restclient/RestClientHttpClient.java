package am.ik.openai.client.restclient;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import com.openai.core.RequestOptions;
import com.openai.core.Timeout;
import com.openai.core.http.Headers;
import com.openai.core.http.HttpClient;
import com.openai.core.http.HttpMethod;
import com.openai.core.http.HttpRequest;
import com.openai.core.http.HttpRequestBody;
import com.openai.core.http.HttpResponse;
import com.openai.core.http.QueryParams;
import com.openai.errors.OpenAIIoException;
import org.jspecify.annotations.Nullable;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.StreamingHttpOutputMessage;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * A {@link HttpClient} implementation for the OpenAI Java SDK backed by Spring's
 * {@link RestClient}.
 * <p>
 * This implementation lets you use the official OpenAI Java SDK without pulling in the
 * OkHttp dependency that the default client requires. Wire it into the SDK through
 * {@code ClientOptions.builder().httpClient(...)}.
 * <p>
 * The underlying {@link ClientHttpRequestFactory} (and therefore the actual socket
 * timeouts, connection pooling and proxy handling) is chosen by the caller. Supply a
 * fully configured {@link RestClient} via {@link Builder#restClient(RestClient)}, or a
 * {@link RestClient.Builder} via {@link Builder#restClientBuilder(RestClient.Builder)}
 * (configure the {@link ClientHttpRequestFactory} on the builder itself). When nothing is
 * supplied, {@link RestClient#builder()} with its default request factory is used.
 * <p>
 * Cross-cutting concerns such as retries, logging, SSE parsing and phantom-reachable
 * closing are handled by the SDK core decorators, so this class only fulfills the raw
 * {@link HttpClient} contract.
 * <p>
 * {@link #executeAsync} offloads the blocking call to an {@link Executor}. The executor
 * is also chosen by the caller via {@link Builder#executor(Executor)}. When using Spring
 * Boot, inject the auto-configured task executor so that virtual threads are used when
 * {@code spring.threads.virtual.enabled=true}: <pre>{@code
 * &#64;Bean
 * RestClientHttpClient openAiHttpClient(RestClient.Builder restClientBuilder,
 *         &#64;Qualifier("applicationTaskExecutor") Executor executor) {
 *     return RestClientHttpClient.builder()
 *         .restClientBuilder(restClientBuilder)
 *         .executor(executor)
 *         .build();
 * }
 * }</pre> When no executor is supplied, an internal platform-thread pool is used and shut
 * down on {@link #close()}.
 * <p>
 * Cancelling the future returned by {@link #executeAsync} interrupts the worker thread to
 * abort the in-flight request, similar to OkHttp's {@code call.cancel()}. This is
 * honoured when the backend is the JDK {@code HttpClient}; factories based on
 * {@code HttpURLConnection} (such as {@code SimpleClientHttpRequestFactory}) do not
 * respond to interruption.
 */
public final class RestClientHttpClient implements HttpClient {

	private static final String READ_TIMEOUT_HEADER = "X-Stainless-Read-Timeout";

	private static final String TIMEOUT_HEADER = "X-Stainless-Timeout";

	private final RestClient restClient;

	private final Executor executor;

	private final boolean ownsExecutor;

	private final Timeout defaultTimeout;

	private RestClientHttpClient(RestClient restClient, @Nullable Executor executor, Timeout defaultTimeout) {
		this.restClient = restClient;
		// Create an executor only when the caller did not supply one, and own only what
		// we
		// created so that a caller-supplied executor is never shut down by us.
		this.ownsExecutor = (executor == null);
		this.executor = (executor != null) ? executor : Executors.newCachedThreadPool();
		this.defaultTimeout = defaultTimeout;
	}

	/**
	 * Returns a mutable builder for constructing a {@link RestClientHttpClient}.
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	@Override
	public HttpResponse execute(HttpRequest request, RequestOptions requestOptions) {
		try {
			RestClient.RequestBodySpec spec = this.restClient
				.method(org.springframework.http.HttpMethod.valueOf(request.method().name()))
				.uri(buildUri(request));
			spec.headers(headers -> applyHeaders(headers, request, requestOptions));
			HttpRequestBody body = request.body();
			if (body != null) {
				applyBody(spec, body);
			}
			else if (requiresBody(request.method())) {
				spec.body(new byte[0]);
			}
			return spec.exchange((clientRequest, clientResponse) -> toHttpResponse(clientResponse), false);
		}
		catch (ResourceAccessException ex) {
			throw new OpenAIIoException("Request failed", (ex.getCause() instanceof IOException io) ? io : ex);
		}
		finally {
			HttpRequestBody body = request.body();
			if (body != null) {
				body.close();
			}
		}
	}

	@Override
	public CompletableFuture<HttpResponse> executeAsync(HttpRequest request, RequestOptions requestOptions) {
		// RestClient is synchronous, so the blocking call is offloaded to an executor.
		// To approximate OkHttp's call.cancel(), the worker thread is captured so that
		// cancelling the returned future interrupts it. Whether the interrupt actually
		// aborts the in-flight request depends on the backend: the JDK HttpClient
		// (JdkClientHttpRequestFactory) honours interruption, whereas factories based on
		// HttpURLConnection (e.g. SimpleClientHttpRequestFactory) do not.
		CompletableFuture<HttpResponse> result = new CompletableFuture<>();
		AtomicReference<@Nullable Thread> worker = new AtomicReference<>();
		this.executor.execute(() -> {
			worker.set(Thread.currentThread());
			try {
				if (result.isDone()) {
					// Cancelled before the worker started running.
					return;
				}
				HttpResponse response = execute(request, requestOptions);
				if (!result.complete(response)) {
					// The future was cancelled while the request was in flight; close the
					// orphaned response to avoid leaking the connection.
					response.close();
				}
			}
			catch (Throwable ex) {
				result.completeExceptionally(ex);
			}
			finally {
				worker.set(null);
				// Clear a possibly-set interrupt flag before the thread is returned to
				// the
				// executor (relevant for pooled executors that reuse threads).
				Thread.interrupted();
			}
		});
		result.whenComplete((response, error) -> {
			if (result.isCancelled()) {
				Thread thread = worker.get();
				if (thread != null) {
					thread.interrupt();
				}
			}
		});
		return result;
	}

	@Override
	public void close() {
		// Only shut down the executor we created. A caller-supplied executor, RestClient
		// and request factory are owned by the caller and left untouched.
		if (this.ownsExecutor && this.executor instanceof ExecutorService executorService) {
			executorService.shutdown();
		}
	}

	private static URI buildUri(HttpRequest request) {
		UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(request.baseUrl());
		for (String segment : request.pathSegments()) {
			builder.pathSegment(segment);
		}
		QueryParams queryParams = request.queryParams();
		for (String key : queryParams.keys()) {
			for (String value : queryParams.values(key)) {
				builder.queryParam(key, value);
			}
		}
		return builder.encode().build().toUri();
	}

	private void applyHeaders(HttpHeaders springHeaders, HttpRequest request, RequestOptions requestOptions) {
		Headers headers = request.headers();
		for (String name : headers.names()) {
			for (String value : headers.values(name)) {
				springHeaders.add(name, value);
			}
		}
		Timeout timeout = (requestOptions.getTimeout() != null) ? requestOptions.getTimeout() : this.defaultTimeout;
		if (!hasHeader(headers, READ_TIMEOUT_HEADER)) {
			Duration read = timeout.read();
			if (!read.isZero()) {
				springHeaders.add(READ_TIMEOUT_HEADER, Long.toString(read.toSeconds()));
			}
		}
		if (!hasHeader(headers, TIMEOUT_HEADER)) {
			Duration requestTimeout = timeout.request();
			if (!requestTimeout.isZero()) {
				springHeaders.add(TIMEOUT_HEADER, Long.toString(requestTimeout.toSeconds()));
			}
		}
	}

	private static void applyBody(RestClient.RequestBodySpec spec, HttpRequestBody body) {
		String contentType = body.contentType();
		if (contentType != null) {
			spec.contentType(MediaType.parseMediaType(contentType));
		}
		long contentLength = body.contentLength();
		if (contentLength >= 0) {
			spec.header(HttpHeaders.CONTENT_LENGTH, Long.toString(contentLength));
		}
		spec.body((StreamingHttpOutputMessage.Body) body::writeTo);
	}

	private static HttpResponse toHttpResponse(ClientHttpResponse clientResponse) throws IOException {
		int statusCode = clientResponse.getStatusCode().value();
		Headers headers = toHeaders(clientResponse.getHeaders());
		InputStream body = clientResponse.getBody();
		return new RestClientHttpResponse(statusCode, headers, body, clientResponse);
	}

	private static Headers toHeaders(HttpHeaders httpHeaders) {
		Headers.Builder builder = Headers.builder();
		for (String name : httpHeaders.headerNames()) {
			List<String> values = httpHeaders.get(name);
			if (values != null) {
				for (String value : values) {
					builder.put(name, value);
				}
			}
		}
		return builder.build();
	}

	private static boolean hasHeader(Headers headers, String name) {
		for (String existing : headers.names()) {
			if (existing.equalsIgnoreCase(name)) {
				return true;
			}
		}
		return false;
	}

	private static boolean requiresBody(HttpMethod method) {
		return switch (method) {
			case POST, PUT, PATCH -> true;
			default -> false;
		};
	}

	/**
	 * {@link HttpResponse} backed by a Spring {@link ClientHttpResponse}. The response is
	 * left open so that the body can be streamed; {@link #close()} releases it.
	 */
	private static final class RestClientHttpResponse implements HttpResponse {

		private final int statusCode;

		private final Headers headers;

		private final InputStream body;

		private final ClientHttpResponse clientResponse;

		private RestClientHttpResponse(int statusCode, Headers headers, InputStream body,
				ClientHttpResponse clientResponse) {
			this.statusCode = statusCode;
			this.headers = headers;
			this.body = body;
			this.clientResponse = clientResponse;
		}

		@Override
		public int statusCode() {
			return this.statusCode;
		}

		@Override
		public Headers headers() {
			return this.headers;
		}

		@Override
		public InputStream body() {
			return this.body;
		}

		@Override
		public void close() {
			this.clientResponse.close();
		}

	}

	/**
	 * A builder for {@link RestClientHttpClient}.
	 */
	public static final class Builder {

		private @Nullable RestClient restClient;

		private RestClient.@Nullable Builder restClientBuilder;

		private @Nullable Executor executor;

		private Timeout timeout = Timeout.builder().build();

		private Builder() {
		}

		/**
		 * Use a fully configured {@link RestClient}. Takes precedence over
		 * {@link #restClientBuilder(RestClient.Builder)}.
		 * @param restClient the client to use
		 * @return this builder
		 */
		public Builder restClient(RestClient restClient) {
			this.restClient = restClient;
			return this;
		}

		/**
		 * Build the {@link RestClient} from the given builder. Configure the HTTP backend
		 * (a {@link ClientHttpRequestFactory} and its timeouts, connection pooling and
		 * proxy handling) on the builder itself via
		 * {@link RestClient.Builder#requestFactory(ClientHttpRequestFactory)}.
		 * @param restClientBuilder the builder to use
		 * @return this builder
		 */
		public Builder restClientBuilder(RestClient.Builder restClientBuilder) {
			this.restClientBuilder = restClientBuilder;
			return this;
		}

		/**
		 * Use the given {@link Executor} for {@link #executeAsync}. Supplying the
		 * Spring-managed task executor (for example {@code applicationTaskExecutor})
		 * makes asynchronous calls run on virtual threads when
		 * {@code spring.threads.virtual.enabled=true}. A caller-supplied executor is not
		 * shut down by {@link #close()}. When not set, an internal platform-thread pool
		 * is created and shut down on {@link #close()}.
		 * @param executor the executor to use
		 * @return this builder
		 */
		public Builder executor(Executor executor) {
			this.executor = executor;
			return this;
		}

		/**
		 * The timeout used to derive the {@code X-Stainless-Read-Timeout} and
		 * {@code X-Stainless-Timeout} telemetry headers when a request does not carry a
		 * per-request timeout. Actual socket timeouts are enforced by the configured
		 * {@link ClientHttpRequestFactory}, not by this value.
		 * @param timeout the default timeout
		 * @return this builder
		 */
		public Builder timeout(Timeout timeout) {
			this.timeout = timeout;
			return this;
		}

		/**
		 * Convenience overload that sets the overall request timeout.
		 * @param timeout the overall request timeout
		 * @return this builder
		 * @see Timeout#request()
		 */
		public Builder timeout(Duration timeout) {
			this.timeout = Timeout.builder().request(timeout).build();
			return this;
		}

		/**
		 * Builds an immutable {@link RestClientHttpClient}.
		 * @return a new client
		 */
		public RestClientHttpClient build() {
			return new RestClientHttpClient(resolveRestClient(), this.executor, this.timeout);
		}

		private RestClient resolveRestClient() {
			if (this.restClient != null) {
				return this.restClient;
			}
			RestClient.Builder builder = (this.restClientBuilder != null) ? this.restClientBuilder
					: RestClient.builder();
			return builder.build();
		}

	}

}
