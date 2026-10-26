# LLM API Gateway

A small OpenAI-compatible gateway in Spring Boot 3 and Kotlin. Callers authenticate with a gateway API key. The process holds the upstream provider key, rate-limits each caller, retries transient upstream failures, and keeps an estimated token cost for the key that made the call.

This is a portfolio project by Sandeep Vedavyas. It is a single service with an in-memory budget, meant to show the shape of a production gateway rather than to be a multi-tenant billing system.

## Why a gateway

Applications should not ship a provider key, a retry policy, and a price table in every client.

- The provider key stays on the server. Rotating it does not require a client release.
- Every caller has its own key and its own requests-per-minute budget.
- Token usage and an estimated USD cost are recorded per caller key.
- The upstream can be any server that speaks the OpenAI chat-completions HTTP API: OpenAI, Groq, Together, vLLM, or Ollama (`/v1`).
- Request logs carry a correlation id and do not include the prompt.

```mermaid
flowchart LR
    Client["Caller"]
    subgraph Gateway["LLM API Gateway"]
        Req["Request id"]
        Auth["API key check"]
        Limit["Token bucket"]
        Policy["Validate and account"]
        Adapter["OpenAI-compatible client"]
    end
    Upstream["Upstream chat API"]
    Usage["In-memory usage"]
    Metrics["Micrometer"]

    Client --> Req --> Auth --> Limit --> Policy --> Adapter --> Upstream
    Policy --> Usage
    Policy --> Metrics
```

```mermaid
sequenceDiagram
    participant C as Caller
    participant G as Gateway
    participant U as Upstream
    C->>G: POST /v1/chat/completions
    G->>G: Authenticate, rate limit, validate
    G->>U: POST /v1/chat/completions
    alt transient upstream failure before a body
        U-->>G: 429 or 502 or 503 or 504
        G->>U: Retry with backoff
    end
    U-->>G: Completion and token usage
    G->>G: Estimate cost, store usage, record metrics
    G-->>C: Same completion, plus X-Request-Id
```

## Run it in five minutes

Requirements: Java 21, and a shell. Docker is optional.

```bash
git clone https://github.com/sandeepvedavyas/llm-api-gateway.git
cd llm-api-gateway
cp .env.example .env
./gradlew bootRun
```

The process listens on port 8080. Health does not need a caller key:

```bash
curl -s http://localhost:8080/actuator/health
```

Chat and usage do. The default caller key is `local-dev-key-change-me`. Change it before anyone else can reach the port.

### Without an OpenAI account

`scripts/dev-upstream.py` is a tiny OpenAI-shaped server for a local demo. The test suite does not use it. In another terminal:

```bash
python3 scripts/dev-upstream.py
```

Stop the gateway and start it against that server:

```bash
export OPENAI_BASE_URL=http://127.0.0.1:8099
export OPENAI_API_KEY=sk-local-demo
./gradlew bootRun
```

### Against a real provider

Put the provider key in the environment. It is never written to the repo.

```bash
export OPENAI_API_KEY=sk-...
export OPENAI_BASE_URL=https://api.openai.com   # or http://localhost:11434/v1 for Ollama
./gradlew bootRun
```

If `OPENAI_API_KEY` is empty the process still boots. Completions return **503** `provider_not_configured` until it is set.

### Docker

Compose reads `.env` from the project directory for substitution. The container gets JSON logs.

```bash
cp .env.example .env
# set OPENAI_API_KEY in .env if you want a real upstream
docker compose up --build
```

## Call the API

Non-streaming:

```bash
curl -s http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer local-dev-key-change-me" \
  -H "Content-Type: application/json" \
  -H "X-Request-Id: demo-1" \
  -d '{
    "model": "gpt-4o-mini",
    "messages": [{"role": "user", "content": "Say hello in one sentence."}]
  }'
```

Streaming (`stream: true` is Server-Sent Events and ends with `data: [DONE]`):

```bash
curl -N http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer local-dev-key-change-me" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4o-mini",
    "stream": true,
    "messages": [{"role": "user", "content": "Say hello in one sentence."}]
  }'
```

Usage for the authenticated caller key. Counts below are illustrative:

```bash
curl -s http://localhost:8080/v1/usage \
  -H "Authorization: Bearer local-dev-key-change-me"
```

```json
{
  "api_key_id": "local",
  "request_count": 1,
  "prompt_tokens": 12,
  "completion_tokens": 9,
  "total_tokens": 21,
  "estimated_cost_usd": "0.00000720",
  "by_model": [
    {
      "model": "gpt-4o-mini",
      "request_count": 1,
      "prompt_tokens": 12,
      "completion_tokens": 9,
      "total_tokens": 21,
      "estimated_cost_usd": "0.00000720"
    }
  ]
}
```

Other routes:

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/` | no | Pointers to health, docs, and the API |
| `GET` | `/actuator/health` | no | Liveness. Stays up when the provider key is missing |
| `GET` | `/actuator/prometheus` | no | Micrometer scrape |
| `GET` | `/swagger-ui.html` | no | Swagger UI |
| `GET` | `/v1/models` | yes | Models that have a configured price |
| `POST` | `/v1/chat/completions` | yes | OpenAI-compatible chat |
| `GET` | `/v1/usage` | yes | Usage for the authenticated key only |

Errors use an OpenAI-shaped body and always include the request id:

```json
{
  "error": {
    "message": "Rate limit exceeded for this API key",
    "type": "rate_limit_error",
    "code": "rate_limit_exceeded",
    "request_id": "demo-1"
  }
}
```

Successful responses also return `X-Request-Id`. Send your own id if it matches `[A-Za-z0-9._-]{1,128}`; anything else is replaced so a caller cannot inject a header.

A client `Idempotency-Key` with the same character set is forwarded upstream. The gateway does not dedupe with it yet.

## Configuration

| Variable | Default | Meaning |
| --- | --- | --- |
| `GATEWAY_KEY_ID` | `local` | Id stored on usage and metrics. Not the secret. |
| `GATEWAY_API_KEY` | `local-dev-key-change-me` | Caller bearer token. |
| `GATEWAY_RPM` | `60` | Requests per minute for that key. Burst equals one minute. |
| `GATEWAY_API_KEYS` | empty | Replaces the single key. Format: `id\|secret\|rpm,id\|secret\|rpm`. |
| `OPENAI_API_KEY` | empty | Upstream bearer token. |
| `OPENAI_BASE_URL` | `https://api.openai.com` | Upstream origin. A base that already ends in `/v1` is not doubled. |
| `OPENAI_TIMEOUT` | `60s` | Upstream response timeout. |
| `PORT` | `8080` | Listen port. |

Prices live in `src/main/resources/application.yml` under `gateway.pricing`. They are sample rates for local estimates. Replace them before using the numbers for chargeback. A model id such as `gpt-4o-mini-2024-07-18` uses the longest configured prefix (`gpt-4o-mini`). Unknown models use the default price.

Set `SPRING_PROFILES_ACTIVE=json` for one-line JSON logs. The console pattern otherwise includes the MDC request id.

## What the request path does

1. Assign or accept `X-Request-Id` and put it on the Reactor context, the SLF4J MDC, the response, and the upstream call.
2. Require `Authorization: Bearer <gateway key>`. Comparison is a SHA-256 digest compare, so it does not stop at the first differing character. The presented key is not logged.
3. Take one token from that key's in-memory bucket. The response carries `X-RateLimit-Limit-Requests` and `X-RateLimit-Remaining-Requests`. A rejection is **429** with `Retry-After`.
4. Require a model and a non-empty `messages` array. Extra JSON fields (`temperature`, `tools`, `response_format`, and the rest) are forwarded unchanged.
5. Call the upstream with the provider key. Non-streaming responses are passed through, including fields this service does not model.
6. For `stream: true`, set `stream_options.include_usage` so the final event can be accounted, then write `text/event-stream` frames as `data: ...`.
7. Record prompt tokens, completion tokens, and `tokens * usd_per_million / 1_000_000` on the caller key. Counters and a latency timer go to Micrometer.

Retries run only when the attempt can still be repeated: HTTP 408, 429, 500, 502, 503, and 504, plus a connection failure. Backoff starts at 200ms, doubles, and caps at 2 seconds. A `Retry-After` on the upstream response is honored up to that cap. Read timeouts are not retried. A streaming call is not retried after the first event, because the caller would see duplicate tokens.

Upstream **401/403** becomes **502** (`upstream_auth`). That is the gateway's key, not the caller's. Upstream **429** after retries becomes **503** (`upstream_rate_limited`) so it is not confused with the caller's own **429**.

## Observability

- `gateway.chat.requests` and `gateway.chat.latency`, tagged with key id, model, stream, and outcome.
- `gateway.tokens` tagged with model and direction.
- `gateway.estimated.cost.usd` tagged with model.
- `gateway.chat.rejected` for auth failures and caller rate limits.

Model tags are truncated to 64 characters. A public internet deployment should still treat model as a cardinality risk, and it should not leave `/actuator/prometheus` open. This v1 leaves metrics unauthenticated so a local scrape works.

`/actuator/health` reports `upstreamProvider.details.upstream` as `configured` or `missing_api_key` and stays **UP** either way, so a missing provider key does not crash a container restart loop. The chat route is what fails closed.

## Tests

```bash
./gradlew test
```

Unit tests cover the token bucket (including a concurrent burst), price selection, the API-key parser, request validation, and SSE reassembly. `MockWebServer` covers the real WebClient: auth header, usage parsing, retry on 503, no retry on 400, no retry on a read timeout, and streaming `include_usage`. `@SpringBootTest` covers auth, usage accounting, SSE, 404, the dotted model id `gpt-4.1`, and a one-request rate limit.

## Layout

```
src/main/kotlin/com/sandeepvedavyas/llmgateway
  api/            HTTP, filters, error body
  application/    one chat use case: call, account, measure
  auth/           key registry and constant-time compare
  config/         properties, WebClient, OpenAPI
  domain/         validation, token bucket, cost, usage store
  observability/  request id, metrics, health detail
  provider/       LlmProvider plus the OpenAI-compatible client
```

WebFlux is the only HTTP stack. The upstream call is non-blocking, and a streaming response does not occupy a servlet thread for the whole generation.

## Design trade-offs

**One process, in memory.** The rate limiter and the usage ledger live in the JVM. A second replica has its own buckets and forgets usage on restart. That is the right v1: the policy is easy to test and there is no database to operate. It is the wrong production topology. The next step is a shared bucket (Bucket4j with Redis) and an append-only usage table.

**Cost is an estimate, not an invoice.** Prices are a local table, prefix matching can be wrong, and the provider's bill is the source of truth. The ledger is precise enough to spot a runaway caller. It is not precise enough to invoice one.

**Retries can double-charge.** A lost response after the provider accepted the call is not something a gateway can see. Read timeouts are excluded for that reason. Status-code retries remain, with a hard cap of two extra attempts, because a 503 usually means the provider did not finish the call. Full protection needs an idempotency store. Forwarding `Idempotency-Key` is the hook, not the feature.

**The gateway is an application, not a route table.** Spring Cloud Gateway could proxy the bytes. It would still need this code for auth, per-key budgets, `include_usage`, and cost. Keeping one Spring Boot service makes that policy ordinary Kotlin with ordinary tests.

**WebFlux rather than MVC.** Streaming and the upstream call share one non-blocking client. The cost is a smaller set of libraries that feel natural, and filters that must return `Mono` instead of throwing. MVC with virtual threads would also be a defensible v1. This codebase does not mix the two.

**Fail closed on caller auth, fail open on health.** A missing or wrong caller key is 401. A missing provider key does not flip the process unhealthy, because orchestrators would then kill the only instance that can tell you why. Chat returns 503 until the key exists.

**Prompts are not logged.** Success logs are key id, model, token counts, estimated cost, and latency. Upstream error text is truncated to 300 characters and can still echo user input. Do not ship that log stream to a system you would not trust with prompts.

## Failure modes

| Situation | Caller sees |
| --- | --- |
| No `Authorization` header, or unknown key | 401 `invalid_api_key` |
| Bucket empty | 429 `rate_limit_exceeded` and `Retry-After` |
| Malformed JSON or missing `model` | 400 `invalid_request_error` |
| Provider key unset | 503 `provider_not_configured` |
| Upstream rejected the body (400, 404, 422) | Same status, upstream message |
| Upstream auth failed | 502 `upstream_auth` |
| Upstream rate limit survived retries | 503 `upstream_rate_limited` |
| Connection failed, retries exhausted | 502 |
| Upstream read timeout | 504, no retry |
| Stream breaks after the first byte | An SSE error object, then `data: [DONE]`, if the response is already committed |

## What is intentionally out of scope

- Redis or Postgres, and anything that survives a restart
- A hard monthly budget that rejects once spend crosses a line
- More than one upstream protocol (Anthropic's native API, AWS Bedrock)
- Azure's `api-key` header
- Per-route admin auth in front of Prometheus
- Distributed tracing export (OpenTelemetry is the natural addition)
- Spring Boot 4. This project stays on Boot 3.5, the last 3.x line, because that was the target. Boot 4 is a migration, not a requirement for the design.

A sensible production follow-up is Fly.io or Cloud Run: one container, the env vars above, a private address for `/actuator/prometheus`, and a move of the usage ledger off the heap before running more than one machine.
