# openai-java-client-restclient

A Spring `RestClient` based implementation of the OpenAI Java SDK's
`com.openai.core.http.HttpClient` interface.

The [official OpenAI Java SDK](https://github.com/openai/openai-java) ships with a single
HTTP client implementation backed by OkHttp. This library provides an alternative backed by
Spring's `RestClient` (from `spring-web`), so you can use the SDK without adding OkHttp to
your dependencies.

## When to use this

Use this library when:

- You are already on the Spring stack and want the OpenAI SDK to reuse Spring's HTTP
  infrastructure (`ClientHttpRequestFactory`, connection pooling, proxy settings) instead of
  OkHttp.
- You want to avoid pulling in the OkHttp dependency.
- You want asynchronous calls to run on a Spring-managed executor, including virtual threads
  when `spring.threads.virtual.enabled=true`.

If you have no objection to OkHttp, the SDK's default client (`openai-java`) is simpler and
you do not need this library.

## Requirements

- Java 17+
- `com.openai:openai-java-core` (the SDK core, without the OkHttp client)
- `org.springframework:spring-web`

## Installation

Maven:

```xml
<dependency>
    <groupId>am.ik.openai</groupId>
    <artifactId>openai-java-client-restclient</artifactId>
    <version>1.0.0</version>
</dependency>
```

Gradle:

```groovy
implementation 'am.ik.openai:openai-java-client-restclient:1.0.0'
```

Depend on `openai-java-core` rather than `openai-java` so that OkHttp is not pulled in. In a
Spring Boot application `spring-web` is usually already on the classpath via the starters.

## Usage

Build a `RestClientHttpClient` and pass it to the SDK. As described in the
[SDK documentation](https://github.com/openai/openai-java#custom-httpclient), a custom
`HttpClient` is supplied to `ClientOptions` and used to construct `OpenAIClientImpl` (or
`OpenAIClientAsyncImpl`).

```java
import am.ik.openai.client.restclient.RestClientHttpClient;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

RestClientHttpClient httpClient = RestClientHttpClient.builder()
    .restClientBuilder(RestClient.builder().requestFactory(new JdkClientHttpRequestFactory()))
    .build();

ClientOptions options = ClientOptions.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .httpClient(httpClient)
    .build();

OpenAIClient client = new OpenAIClientImpl(options);
```

From here you use the SDK exactly as documented. Cross-cutting concerns such as retries,
logging, Server-Sent Events parsing and error handling are provided by the SDK core, not by
this library.

## Choosing the HTTP backend

The actual socket timeouts, connection pooling and proxy handling come from the
`ClientHttpRequestFactory` of the `RestClient` you supply. This library does not pick one for
you. There are two ways to provide a client.

Supply a `RestClient.Builder` and configure the request factory on it (in Spring Boot you can
inject the auto-configured `RestClient.Builder`):

```java
RestClientHttpClient httpClient = RestClientHttpClient.builder()
    .restClientBuilder(RestClient.builder().requestFactory(new JdkClientHttpRequestFactory()))
    .build();
```

Or supply a fully configured `RestClient`:

```java
RestClient restClient = RestClient.builder()
    .requestFactory(new JdkClientHttpRequestFactory())
    .build();

RestClientHttpClient httpClient = RestClientHttpClient.builder()
    .restClient(restClient)
    .build();
```

When neither is supplied, `RestClient.builder()` with Spring's default request factory is
used.

Note on cancellation: cancelling the `CompletableFuture` returned by `executeAsync`
interrupts the worker thread to abort the in-flight request. This is honoured by the JDK
`HttpClient` (`JdkClientHttpRequestFactory`); factories based on `HttpURLConnection` (such as
`SimpleClientHttpRequestFactory`) do not respond to interruption.

## Builder options

`RestClientHttpClient.builder()` returns a builder with the following options.

| Method | Description |
| --- | --- |
| `restClient(RestClient)` | Use a fully configured `RestClient`. Takes precedence over `restClientBuilder`. |
| `restClientBuilder(RestClient.Builder)` | Build the `RestClient` from this builder. Configure the `ClientHttpRequestFactory` on the builder itself. |
| `executor(Executor)` | Executor used for `executeAsync`. Pass the Spring task executor (for example `applicationTaskExecutor`) to run async calls on virtual threads when `spring.threads.virtual.enabled=true`. A caller-supplied executor is not shut down by `close()`. When not set, an internal platform-thread pool is created and shut down on `close()`. |
| `timeout(Timeout)` / `timeout(Duration)` | Default timeout used to derive the `X-Stainless-Read-Timeout` / `X-Stainless-Timeout` telemetry headers when a request carries no per-request timeout. Actual socket timeouts are enforced by the configured `ClientHttpRequestFactory`, not by this value. |

## Lifecycle

`RestClientHttpClient` is `AutoCloseable`. `close()` shuts down the executor only when this
library created it; a caller-supplied `RestClient` and `Executor` are left for the caller to
manage. When you register the client as a Spring bean, the container calls `close()` on
shutdown.

## License

Licensed under the Apache License, Version 2.0.
