package am.ik.openai.client.restclient;

import java.io.IOException;
import java.util.Map;

import am.ik.openai.client.restclient.MockHttpServer.RecordedRequest;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the wiring shown in the README works end to end against a mock OpenAI
 * endpoint, so the documented example stays correct.
 */
class ReadmeExampleTest {

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
	void wiresRestClientHttpClientIntoTheSdk() {
		// A minimal but valid chat completion response for the SDK to parse.
		this.server.respondWith(200, "application/json", """
				{
				  "id": "chatcmpl-test",
				  "object": "chat.completion",
				  "created": 0,
				  "model": "gpt-4o-mini",
				  "choices": [
				    {
				      "index": 0,
				      "message": { "role": "assistant", "content": "Hello there" },
				      "finish_reason": "stop"
				    }
				  ]
				}
				""", Map.of());

		// The README wiring: build the RestClient-backed HttpClient and hand it to the
		// SDK.
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

			ChatCompletion completion = client.chat()
				.completions()
				.create(ChatCompletionCreateParams.builder()
					.model(ChatModel.GPT_4O_MINI)
					.addUserMessage("Hello")
					.build());

			assertThat(completion.choices().get(0).message().content().orElseThrow()).isEqualTo("Hello there");
		}

		RecordedRequest recorded = this.server.lastRequest();
		assertThat(recorded.method()).isEqualTo("POST");
		assertThat(recorded.path()).isEqualTo("/chat/completions");
		assertThat(recorded.firstHeader("Authorization")).isEqualTo("Bearer test-key");
	}

}
