# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this
repository.

**Build Commands:**

```bash
./mvnw clean spring-javaformat:apply compile                    # Compile application
./mvnw spring-javaformat:apply test                             # Run all tests
```

## Design Requirements
- **Package**: `am.ik.openai.client.restclient` - Main package

This library provides a Spring `RestClient` based implementation of the OpenAI Java SDK's
`com.openai.core.http.HttpClient` interface. It lets you use the official OpenAI Java SDK
without depending on OkHttp (the only client the SDK ships out of the box).

## Implemented Features

- `RestClientHttpClient` - a `com.openai.core.http.HttpClient` implementation backed by
  Spring's `RestClient`. Wire it into the SDK via
  `ClientOptions.builder().httpClient(...)`.
  - Synchronous `execute` and asynchronous `executeAsync` (offloaded to an executor).
  - The underlying `ClientHttpRequestFactory` (and therefore socket timeouts, connection
    pooling and proxy handling) is chosen by the caller. Supply a `RestClient` or a
    `RestClient.Builder` (configure the `ClientHttpRequestFactory` on the builder itself)
    via the client builder.
  - Streams response bodies (no buffering) and returns the response for any status code
    without throwing, so the SDK core decorators (retry, logging, SSE, error handling)
    work as expected.
  - Emits the `X-Stainless-Read-Timeout` / `X-Stainless-Timeout` telemetry headers.

## Development Requirements

### Prerequisites

- Java 17+

### Code Standards

- No external dependencies except for testing libraries
- Use builder pattern if the number of arguments is more than two
- Write javadoc and comments in English
- Spring Java Format enforced via Maven plugin
- All code must pass formatting validation before commit
- Use Java 17 compatible features (avoid Java 21+ specific APIs)
- Use modern Java technics as much as possible like Java Records, Pattern Matching, Text Block etc ...
- Be sure to avoid circular references between classes and packages.

### Documentation

- Specify when this library should be used
- The explanations are written from the perspective of the API user, with plenty of code examples to make usage easy to understand.
- No need for excessive advertising
- There is no need to use emojis or flashy expressions, just write simply and honestly.

### Testing Strategy

- JUnit 5 with AssertJ
- All tests must pass before completing tasks
- Code examples in the README must be tested to ensure they work.

### After Task completion

- Ensure all code is formatted using `./mvnw spring-javaformat:apply`
- Run full test suite with `./mvnw test`
- For every task, notify that the task is complete and ready for review by the following command:

```
osascript -e 'display notification "<Message Body>" with title "<Message Title>"’
```
