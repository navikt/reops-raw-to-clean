# reops-raw-to-clean
Consumes raw analytics events from Kafka, strips PII and sensitive data, and republishes clean events.

## Data flow

```
[reops-event-proxy] ──► Kafka: team-researchops.analytics-raw
                                        │
                              [reops-raw-to-clean]
                              FilterService (PII redaction)
                                        │
                        Kafka: team-researchops.analytics-clean
                                        │
                        [reops-clean-to-enriched] ──► ...
```

No HTTP calls out. No database. Pure Kafka-to-Kafka pipeline.

On processing failure: retry topic (up to 2 attempts, 300 s backoff) → dead letter topic (`-dlt`).

## Prerequisites

- Java 25

## Quick start

### Run tests (no external dependencies)

Tests use Spring's embedded Kafka — no Docker required.

```bash
./gradlew test
```

### Run locally with Kafka

**1. Start Kafka:**
```bash
docker compose -f .compose/docker-compose.yaml up -d
```

Kafka UI available at http://localhost:18950

**2. Start the app:**
```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

Local topics: `raw` (input) → `clean` (output). Both are auto-created on startup.

### Produce a test event

**Via Kafka UI** (easiest): open http://localhost:18950 → topic `raw` → Produce Message.
Set a string key, paste the JSON body below.

**Via CLI:**
```bash
docker exec -it kafka-broker-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 \
  --topic raw \
  --property "parse.key=true" \
  --property "key.separator=:"
```
Paste (key:value on one line):
```
my-key:{"type":"visit","payload":{"website":"550e8400-e29b-41d4-a716-446655440000","hostname":"nav.no","screen":"1920x1080","language":"nb","title":"Test Event","url":"/test","referrer":""}}
```

Optional Kafka headers: `user-agent`, `x-forwarded-for`, `x-opt-out-filters`.

### Verify output

**Via Kafka UI**: topic `clean` → Messages tab.

**Via CLI:**
```bash
docker exec -it kafka-broker-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic clean \
  --from-beginning
```

## Config

### Env vars (Kubernetes only — injected by Nais)

| Variable | Description |
|---|---|
| `KAFKA_BROKERS` | Kafka broker endpoints |
| `KAFKA_TRUSTSTORE_PATH` | Path to JKS truststore (SSL) |
| `KAFKA_KEYSTORE_PATH` | Path to PKCS12 keystore (SSL) |
| `KAFKA_CREDSTORE_PASSWORD` | Password for truststore + keystore |
| `SPRING_PROFILES_ACTIVE` | Set to `kubernetes` in cluster |

### Topics

| Profile | Input | Output |
|---|---|---|
| `local` | `raw` | `clean` |
| `kubernetes` | `team-researchops.analytics-raw` | `team-researchops.analytics-clean` |

### Local dev overrides (`application-local.yaml`)

- Port: `8084`
- Kafka: `localhost:9094` (PLAINTEXT, no SSL)
- Listener concurrency: `1`
- Retry backoff: `1 s` (vs 300 s in prod)

## Running the full pipeline locally

To run the end-to-end pipeline (`reops-event-proxy` → `reops-raw-to-clean` → `reops-clean-to-enriched`), use the shared Kafka setup in `reops-event-proxy`:

```bash
cd ../reops-event-proxy
docker compose -f .compose/docker-compose.yaml up -d
```

Then start each service with `--args='--spring.profiles.active=local'` in its own terminal.

## Build

```bash
./gradlew build        # compile + test
./gradlew bootRun      # run with default profile (needs Kafka at localhost:9094)
./gradlew nativeCompile  # GraalVM native image (requires GraalVM JDK 25)
```

Or via Docker (native image):
```bash
docker build -t reops-raw-to-clean .
docker run --network host -e SPRING_PROFILES_ACTIVE=local reops-raw-to-clean
```
