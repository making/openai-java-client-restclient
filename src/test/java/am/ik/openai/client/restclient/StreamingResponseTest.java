package am.ik.openai.client.restclient;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import am.ik.openai.client.restclient.MockHttpServer.RecordedRequest;
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

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link RestClientHttpClient} streams Server-Sent Events incrementally.
 * The SDK parses the SSE stream itself by reading the
 * {@link com.openai.core.http.HttpResponse} body line by line, so the body must be
 * exposed as a live, non-buffered stream rather than being read to completion first.
 */
class StreamingResponseTest {

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
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	void consumesServerSentEventsIncrementally() throws Exception {
		// Three chunks: two content deltas and the terminating [DONE] marker. The server
		// pauses after the first event (gate index 0) until the test releases it.
		List<String> chunks = List.of("data: " + chunkJson("Hello", null) + "\n\n",
				"data: " + chunkJson(" there", "stop") + "\n\n", "data: [DONE]\n\n");
		this.server.streamSse(chunks, 0);

		RestClientHttpClient httpClient = RestClientHttpClient.builder()
			.restClientBuilder(RestClient.builder().requestFactory(new JdkClientHttpRequestFactory()))
			.build();
		ClientOptions options = ClientOptions.builder()
			.apiKey("test-key")
			.baseUrl(this.server.baseUrl())
			.httpClient(httpClient)
			.build();

		try (httpClient) {
			OpenAIClient client = new OpenAIClientImpl(options);

			try (StreamResponse<ChatCompletionChunk> stream = client.chat()
				.completions()
				.createStreaming(ChatCompletionCreateParams.builder()
					.model(ChatModel.GPT_4O_MINI)
					.addUserMessage("Hi")
					.build())) {
				Iterator<ChatCompletionChunk> events = stream.stream().iterator();

				// The first event is delivered while the server is still paused before
				// sending the rest, which only works if the body is streamed rather than
				// buffered to completion first.
				ChatCompletionChunk first = events.next();
				assertThat(first.choices().get(0).delta().content().orElseThrow()).isEqualTo("Hello");
				assertThat(this.server.awaitStreamGate(2000)).isTrue();

				// Let the server send the remaining events, then drain them.
				this.server.releaseStream();
				StringBuilder content = new StringBuilder("Hello");
				while (events.hasNext()) {
					events.next().choices().get(0).delta().content().ifPresent(content::append);
				}
				assertThat(content.toString()).isEqualTo("Hello there");
			}
		}

		RecordedRequest recorded = this.server.lastRequest();
		assertThat(recorded.method()).isEqualTo("POST");
		assertThat(recorded.path()).isEqualTo("/chat/completions");
		assertThat(recorded.firstHeader("Accept")).isEqualTo("text/event-stream");
	}

	private static String chunkJson(String content, @org.jspecify.annotations.Nullable String finishReason) {
		String finish = (finishReason == null) ? "null" : "\"" + finishReason + "\"";
		return """
				{"id":"chatcmpl-test","object":"chat.completion.chunk","created":0,"model":"gpt-4o-mini",\
				"choices":[{"index":0,"delta":{"content":"%s"},"finish_reason":%s}]}""".formatted(content, finish);
	}

}
