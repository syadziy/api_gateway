# API Gateway

Reactive API Gateway untuk `centralized_alert`, `scheduler`, `audit_log`, dan `usermanagement`. Gateway menjadi
public entry point, sedangkan load balancing default dilakukan oleh Kubernetes Service/VIP agar
tidak terjadi double load balancing.

## Stack

- Java 21
- Spring Boot 4.0.7
- Spring Cloud 2025.1.2
- Spring Cloud Gateway Server WebFlux 5.0.2 dan Reactor Netty
- Spring Security OAuth2 Resource Server
- Redis reactive rate limiter
- Kafka audit event publisher
- Resilience4j circuit breaker dan bulkhead
- Actuator, Prometheus, Micrometer Tracing, dan ECS JSON logging

Project sengaja tidak menggunakan `sdk_util`: library tersebut saat ini membawa komponen
MVC/Servlet. Gateway menyediakan filter, error writer, security, dan structured logger reactive
sendiri. Maven Enforcer menggagalkan build bila `spring-webmvc`, `spring-boot-starter-web`, atau
`sdk-util` masuk ke dependency tree.

## Route

| Route ID | Public path | Default upstream | Authorization |
| --- | --- | --- | --- |
| `centralized-alert` | `/api/v1/alert`, `/api/v1/alert/**` | `centralized-alert:9003` | `SCOPE_alert.write`; recipient dashboard memakai `SCOPE_alert.read-recipients`/`SCOPE_alert.manage-recipients` |
| `centralized-alert-websocket` | `/ws/alerts` | `ws://centralized-alert:9003` | Handshake public; JWT dan `alert:read-notifications` divalidasi pada STOMP `CONNECT` oleh downstream |
| `scheduler` | tasks, task-groups, schedules, histories | `scheduler:9002` | scheduler read/manage scope |
| `audit-log` | `/api/v1/audit-logs/**` | `audit-log:9004` | `SCOPE_audit.read` |
| `usermanagement` | `/api/v1/auth/**`, `/api/v1/tenants/**` | `usermanagement:9005` | login dan registrasi tenant public; operasi tenant memakai scope resource/action |

Path dan query string diteruskan tanpa rewrite. URI upstream berasal dari environment variables
`ALERT_SERVICE_URI`, `ALERT_WEBSOCKET_URI`, `SCHEDULER_SERVICE_URI`, `AUDIT_SERVICE_URI`, dan
`USERMANAGEMENT_SERVICE_URI`.

## Load balancing dan canary

Mode production menggunakan Kubernetes-native load balancing:

```text
client -> ingress/LB -> api-gateway replicas -> Kubernetes Service/VIP -> service pods
```

Gateway tidak memakai discovery client atau `lb://`, sehingga tidak ada dua lapis load balancing.
Readiness probe pada downstream menentukan pod yang boleh menerima traffic.

Weighted canary tersedia untuk alert:

```bash
ALERT_CANARY_ENABLED=true
ALERT_CANARY_SERVICE_URI=http://centralized-alert-canary:9003
ALERT_CANARY_WEIGHT=5
```

Konfigurasi ini membagi traffic alert 95% stable dan 5% canary. Sticky session tidak digunakan.

## Request processing

Setiap route memakai:

- Request body maksimum 5 MB (configurable).
- Redis token-bucket rate limiter per route dan authenticated user/client ID.
- Circuit breaker per upstream.
- Retry maksimal dua kali hanya untuk `GET` dan `HEAD` pada 502/503/504.
- Exponential backoff 100 ms sampai 1 detik.
- Secure response headers.
- Connect timeout 2 detik dan response timeout 10 detik.
- Fixed connection pool dengan maksimum 500 connections.

Redis menggunakan kebijakan **fail-closed**: kegagalan Redis membuat request yang membutuhkan rate
limit gagal sebagai service unavailable; request tidak dilewatkan tanpa quota validation.

## Security

Production default mengaktifkan JWT validation untuk signature, issuer, time claims, dan audience.
Policy authorization adalah deny-by-default. Incoming forwarding dan authenticated identity
headers dibuang, lalu gateway membuat forwarding/identity headers dari connection dan JWT yang
sudah tervalidasi.

Issuer utama adalah `usermanagement`. Gateway mengambil public RSA key melalui discovery/JWKS,
memvalidasi audience `api-gateway`, dan memakai claim `scope` yang dibentuk dari permission
`resource:action` menjadi `SCOPE_resource.action`.

`POST /api/v1/auth/login` dan `POST /api/v1/tenants` dapat melewati gateway tanpa bearer token.
Kebijakan apakah registrasi tenant diizinkan tetap diputuskan oleh property
`usermanagement.registration.enabled` pada downstream. Endpoint tenant lainnya membutuhkan scope
yang sama dengan permission method-security usermanagement, misalnya `user:view` menjadi
`SCOPE_user.view` pada gateway dan `PERM_user:view` pada downstream.

Path `/internal/**` tidak memerlukan JWT, tetapi gateway tidak mendaftarkan downstream route untuk
path tersebut. Jika endpoint internal gateway ditambahkan, aksesnya wajib dibatasi pada internal
Ingress/Service dan network policy.

Handshake `/ws/alerts` dilewatkan tanpa JWT HTTP karena WebSocket API browser tidak dapat mengatur
header `Authorization` pada upgrade request. Bearer token dikirim sebagai STOMP `CONNECT` header
dan divalidasi kembali oleh `centralized_alert`; token tidak boleh dikirim melalui query URL.

CORS menggunakan exact-origin allowlist. Wildcard origin ditolak saat credentials aktif.
Management endpoint berjalan pada port terpisah `9090` dan Kubernetes manifest mengeksposnya lewat
ClusterIP terpisah, bukan public service.

Profile `local` menonaktifkan security agar routing mudah diuji. Jangan gunakan profile ini pada
production.

## Correlation dan observability

Client dapat mengirim `X-Correlation-Id` berisi maksimal 64 karakter aman. Nilai invalid diganti
UUID. Header diteruskan ke downstream dan dikembalikan pada response.

Access log menggunakan structured fields, termasuk route ID, upstream host, method, status,
duration, request/response size, trace ID, dan outcome. Raw path tidak dijadikan log/metric field
untuk mencegah kebocoran identifier dan high cardinality. Log console memakai ECS JSON.

Gateway juga menerbitkan satu audit event untuk setiap request API ke topic
`centralized-audit.requested`. Event mencatat username dari JWT sebagai `actorId` dan `actorName`,
beserta tenant, route, method, status, outcome, dan path yang sudah dinormalisasi. UUID dan
segmen angka pada path diganti `{id}`; bearer token, body, query string, dan header rahasia tidak
pernah dimasukkan ke audit metadata. Response 401/403 dari request yang sudah memasuki route
dicatat sebagai `DENIED`.

Gateway menerbitkan request/response observability event terpisah ke topic
`centralized-log.requested`. Event berisi method, path tanpa query string, route, status, durasi,
actor username, tenant, header allowlist, serta body JSON yang dibatasi ukurannya. Authorization,
cookie, password, token, secret, credential, dan data kartu disaring; body non-JSON tidak disimpan.
Publishing dilakukan asynchronous dan kegagalannya tidak menggagalkan request pengguna.

Kedua integrasi dapat dikendalikan secara independen melalui environment variable:

```dotenv
# Audit aktivitas pengguna ke audit_log
GATEWAY_AUDIT_ENABLED=true

# Request/response API ke centralized_log
GATEWAY_CENTRALIZED_LOG_ENABLED=true
```

Gunakan `false` untuk menonaktifkan integrasi terkait. Saat dinonaktifkan, filter langsung
meneruskan request tanpa membentuk event, membaca body untuk logging, atau mengirim pesan Kafka.
Perubahan environment variable membutuhkan restart API Gateway.

Endpoint management:

- `/actuator/health/liveness`
- `/actuator/health/readiness`
- `/actuator/prometheus`

## Menjalankan lokal

Prasyarat: JDK 21, Redis, serta service downstream pada port 9003-9004 dan 9005.

```bash
export GATEWAY_SECURITY_ENABLED=false
export REDIS_HOST=localhost
mvn spring-boot:run
```

Gateway tersedia di `http://localhost:9001`; management di `http://localhost:9090`.

Contoh:

```bash
curl -i 'http://localhost:9001/api/v1/audit-logs?limit=20' \
  -H 'X-Correlation-Id: audit-query-001' \
  -H 'X-Client-Id: operations-ui'
```

Referensi seluruh environment variable ada di `.env.example`; contoh kontrak tersedia di
`src/main/resources/json/index.json`.

Runtime, log timestamp, dan serialisasi waktu menggunakan UTC melalui `APP_TIMEZONE=UTC`.

## Build dan test

```bash
mvn clean verify
```

JaCoCo menggagalkan build jika line coverage custom production logic kurang dari 90%. Network
integration test dengan MockWebServer bersifat opt-in karena memerlukan local socket:

```bash
RUN_NETWORK_INTEGRATION_TESTS=true mvn -Dtest=GatewayRoutingIntegrationTest test
```

## Deployment

`manifests/kubernetes.yaml` menyediakan contoh Deployment dua replica, rolling update tanpa
unavailable replica, topology spread, probes, non-root/read-only security context, PodDisruptionBudget,
dua Service terpisah, serta HPA CPU dan memory. Nilai resource dan autoscaling wajib dikalibrasi
dengan load test sebelum production.

Build image:

```bash
mvn clean package
docker build -t example/api-gateway:1.0.0 .
docker run --rm --env-file .env -p 9001:9001 -p 9090:9090 example/api-gateway:1.0.0
```

Image memakai Java 21, UTC, dan user non-root. Isi `.env` dari `.env.example`, lalu gunakan hostname
service Docker/Kubernetes untuk Redis dan seluruh downstream; `localhost` di dalam container
menunjuk ke container gateway itu sendiri.

## Batasan yang disengaja

- Dynamic route repository belum diaktifkan; Java route configuration adalah last-known-good
  baseline yang tervalidasi saat startup.
- mTLS downstream memerlukan certificate/SSL bundle environment-specific dan belum diberi dummy
  certificate di repository.
- HPA custom metrics seperti active connections/request rate memerlukan Prometheus Adapter atau
  autoscaling provider milik platform.
- SLO throughput dan p99 harus ditentukan pemilik sistem melalui load test; tidak dibuat angka
  fiktif pada boilerplate.
