# AGENTS.md

## Project overview

`api_gateway` adalah reactive edge service Java 21 untuk routing, security, rate limiting,
resilience, dan observability. PostgreSQL/business state bukan tanggung jawab gateway.

## Architecture rules

- Gunakan WebFlux/Reactor Netty. Dilarang menambah `spring-boot-starter-web`, Spring MVC,
  Servlet filter, blocking database driver, atau `sdk-util` versi MVC.
- Jangan melakukan blocking call pada event-loop. Gunakan reactive client/library.
- Gateway harus stateless. Distributed quota menggunakan Redis.
- Mode load balancing default adalah Kubernetes Service/VIP. Jangan menambah client-side discovery
  tanpa menghapus platform load-balancing layer atau mendokumentasikan alasan.
- Route code hanya berisi cross-cutting policy; business orchestration tetap di downstream.
- Gunakan constructor injection dan type-safe properties di bawah `gateway.*`.
- Gunakan UTC untuk timezone JVM, log, dan timestamp yang diteruskan gateway. Konversi timezone
  hanya dilakukan oleh downstream pada boundary bisnis/presentation yang eksplisit.

## Security and logging

- The gateway is reactive and does not use `sdk_util`; maintain its public paths in the gateway
  `SecurityWebFilterChain`. Do not add the servlet property `sdk.security.permit-all-paths`.
- Production security deny-by-default. Verifikasi signature, issuer, audience, expiry, dan nbf JWT.
- Sanitasi trusted/internal headers sebelum meneruskan request.
- Jangan log token, cookie, API key, raw path dengan identifier, atau request/response body.
- Gunakan ECS structured fields, route ID, normalized contract, trace ID, outcome, dan duration.
- Management port tidak boleh diekspos melalui public Service/Ingress.

## Audit event contract

- `SecurityConfig` adalah source of truth endpoint-to-permission. Setiap penambahan atau perubahan
  `pathMatchers(...).hasAuthority("SCOPE_<permission>")` wajib diikuti mapping yang sama pada
  `AuditEventFilter.requiredPermission(...)` dan test untuk action auditnya.
- Untuk endpoint terproteksi, field `action` harus berasal dari permission yang diminta, bukan dari
  route ID dan HTTP method. Ubah permission menjadi uppercase snake case; contoh
  `alert.read-recipients` menjadi `ALERT_READ_RECIPIENTS`, `tenant.update` menjadi
  `TENANT_UPDATE`, dan `scheduler.manage` menjadi `SCHEDULER_MANAGE`.
- Pertahankan action endpoint publik yang spesifik: `POST /api/v1/auth/login` menggunakan
  `AUTH_LOGIN` dan `POST /api/v1/tenants` menggunakan `TENANT_REGISTER`. Endpoint tanpa mapping
  permission atau action khusus boleh menggunakan fallback `<ROUTE_ID>_<OPERATION>`.
- Metadata audit endpoint terproteksi wajib berisi `requiredPermission` tanpa prefix `SCOPE_`.
  Metadata juga mempertahankan `httpMethod`, normalized `httpPath`, `httpStatus`, `routeId`, dan
  `tenantId` jika tersedia. Jangan menghapus field ini tanpa migrasi kontrak producer/consumer/UI.
- `httpPath` harus membedakan endpoint yang benar-benar diakses, termasuk recipient configuration
  dan delivery history, tetapi UUID serta numeric identifier harus tetap dinormalisasi menjadi
  `{id}`.
- Actor request authenticated diambil dari JWT `username`, lalu principal/subject sebagai fallback.
  Actor login diambil dari field `username` request body yang sudah ditangkap dan disanitasi;
  jangan mencatat password atau menjadikan login valid sebagai `anonymous`/`unknown-user`.
- `resourceType` audit tetap merepresentasikan service/route ID sehingga consumer dan frontend dapat
  menampilkannya sebagai kolom Service. Jangan mengganti maknanya dengan permission atau endpoint.
- Audit record bersifat immutable. Perubahan kontrak hanya berlaku untuk event baru dan tidak boleh
  menulis ulang histori tanpa migrasi eksplisit.
- Perubahan resolver actor, permission, action, endpoint normalization, atau metadata wajib menambah
  atau memperbarui `AuditEventFilterTest` untuk skenario authenticated, anonymous/denied, login,
  dan endpoint yang memiliki permission serupa tetapi tujuan berbeda.

## Resilience

- Retry hanya method idempotent dan tetap berada dalam total request deadline.
- Perubahan timeout, retry, circuit breaker, bulkhead, pool, atau rate-limit harus disertai test.
- Redis rate limiter production bersifat fail-closed kecuali policy route secara eksplisit diubah
  dan diuji.
- Jangan menambahkan fallback sukses palsu.

## Testing

- Custom production logic wajib memiliki JaCoCo line coverage minimal 90%.
- Test header spoofing, trace normalization, auth/audience, route mapping, rate-limit keys, timeout,
  circuit breaker/error mapping, dan anonymous/authenticated behavior.
- Jalankan `mvn clean verify` sebelum handoff.
- Test yang membuka socket dijalankan dengan `RUN_NETWORK_INTEGRATION_TESTS=true`; CI yang sesuai
  harus menjalankannya, bukan menghapusnya.
