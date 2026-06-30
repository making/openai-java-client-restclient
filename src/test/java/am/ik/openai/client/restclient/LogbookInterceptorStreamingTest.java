package am.ik.openai.client.restclient;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.core.http.StreamResponse;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.zalando.logbook.Correlation;
import org.zalando.logbook.HttpRequest;
import org.zalando.logbook.HttpResponse;
import org.zalando.logbook.Logbook;
import org.zalando.logbook.Precorrelation;
import org.zalando.logbook.Sink;
import org.zalando.logbook.Strategy;
import org.zalando.logbook.core.DefaultStrategy;
import org.zalando.logbook.core.WithoutBodyStrategy;
import org.zalando.logbook.spring.LogbookClientHttpRequestInterceptor;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Demonstrates how a body-logging
 * {@link org.springframework.http.client.ClientHttpRequestInterceptor} such as Zalando
 * Logbook's {@link LogbookClientHttpRequestInterceptor} interacts with SSE streaming.
 * When the response body is logged, Logbook reads the whole body inside the interceptor
 * before the SDK ever sees the response, which destroys streaming; when response body
 * logging is disabled, the body is left untouched and streaming still works.
 */
class LogbookInterceptorStreamingTest {

	private MockHttpServer server;

	@BeforeEach
	void setUp() throws IOException {
		this.server = new MockHttpServer();
	}

	@AfterEach
	void tearDown() {
		this.server.close();
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	void responseBodyLoggingBlocksStreaming() throws Exception {
		// Pause the server after the first event. A streaming client should be able to
		// read
		// that event while the server is paused, but Logbook cannot: it buffers the whole
		// body before returning the response.
		this.server.streamSse(sseChunks(), 0);

		Logbook logbook = Logbook.builder().strategy(new DefaultStrategy()).sink(new BodyReadingSink()).build();
		RestClientHttpClient httpClient = newHttpClient(logbook);
		ClientOptions options = clientOptions(httpClient);

		ExecutorService worker = Executors.newSingleThreadExecutor();
		try {
			Future<?> streaming = worker.submit(() -> {
				OpenAIClient client = new OpenAIClientImpl(options);
				try (StreamResponse<ChatCompletionChunk> stream = client.chat()
					.completions()
					.createStreaming(chatParams())) {
					stream.stream().forEach(chunk -> {
					});
				}
			});

			// The server has sent the first event and is paused at the gate.
			assertThat(this.server.awaitStreamGate(5000)).isTrue();

			// Even so, the streaming call makes no progress: Logbook is blocked reading
			// the
			// body to completion, so nothing is delivered while the server is paused.
			assertThatThrownBy(() -> streaming.get(1500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

			// Let the server finish so the blocked call can complete and resources are
			// freed.
			this.server.releaseStream();
			streaming.get(20, TimeUnit.SECONDS);
		}
		finally {
			worker.shutdownNow();
			httpClient.close();
		}
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	void disablingResponseBodyLoggingKeepsStreaming() throws Exception {
		this.server.streamSse(sseChunks(), 0);

		// WithoutBodyStrategy tells Logbook not to read the response body, so the
		// interceptor
		// returns immediately and the body stays a live stream.
		Logbook logbook = Logbook.builder().strategy(new WithoutBodyStrategy()).sink(new BodyReadingSink()).build();
		RestClientHttpClient httpClient = newHttpClient(logbook);
		ClientOptions options = clientOptions(httpClient);

		try (httpClient) {
			OpenAIClient client = new OpenAIClientImpl(options);
			try (StreamResponse<ChatCompletionChunk> stream = client.chat()
				.completions()
				.createStreaming(chatParams())) {
				Iterator<ChatCompletionChunk> events = stream.stream().iterator();

				// The first event arrives while the server is still paused before sending
				// the
				// rest, proving the body is streamed even with the interceptor in place.
				ChatCompletionChunk first = events.next();
				assertThat(first.choices().get(0).delta().content().orElseThrow()).isEqualTo("Hello");
				assertThat(this.server.awaitStreamGate(2000)).isTrue();

				this.server.releaseStream();
				StringBuilder content = new StringBuilder("Hello");
				while (events.hasNext()) {
					events.next().choices().get(0).delta().content().ifPresent(content::append);
				}
				assertThat(content.toString()).isEqualTo("Hello there");
			}
		}
	}

	private RestClientHttpClient newHttpClient(Logbook logbook) {
		return RestClientHttpClient.builder()
			.restClientBuilder(RestClient.builder()
				.requestFactory(new JdkClientHttpRequestFactory())
				.requestInterceptor(new LogbookClientHttpRequestInterceptor(logbook)))
			.build();
	}

	private ClientOptions clientOptions(RestClientHttpClient httpClient) {
		return ClientOptions.builder().apiKey("test-key").baseUrl(this.server.baseUrl()).httpClient(httpClient).build();
	}

	private static ChatCompletionCreateParams chatParams() {
		return ChatCompletionCreateParams.builder().model(ChatModel.GPT_4O_MINI).addUserMessage("Hi").build();
	}

	private static List<String> sseChunks() {
		return List.of("data: " + chunkJson("Hello", null) + "\n\n", "data: " + chunkJson(" there", "stop") + "\n\n",
				"data: [DONE]\n\n");
	}

	private static String chunkJson(String content, @org.jspecify.annotations.Nullable String finishReason) {
		String finish = (finishReason == null) ? "null" : "\"" + finishReason + "\"";
		return """
				{"id":"chatcmpl-test","object":"chat.completion.chunk","created":0,"model":"gpt-4o-mini",\
				"choices":[{"index":0,"delta":{"content":"%s"},"finish_reason":%s}]}""".formatted(content, finish);
	}

	/**
	 * An always-active {@link Sink} that reads the request and response bodies, emulating
	 * a sink that logs them. Whether the read actually consumes the body is decided by
	 * the configured {@link Strategy}: the default strategy offers the body (so it is
	 * read), whereas {@link WithoutBodyStrategy} does not.
	 */
	private static final class BodyReadingSink implements Sink {

		@Override
		public boolean isActive() {
			return true;
		}

		@Override
		public void write(Precorrelation precorrelation, HttpRequest request) throws IOException {
			request.getBody();
		}

		@Override
		public void write(Correlation correlation, HttpRequest request, HttpResponse response) throws IOException {
			response.getBody();
		}

	}

}
