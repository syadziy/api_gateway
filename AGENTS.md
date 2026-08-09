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

- Production security deny-by-default. Verifikasi signature, issuer, audience, expiry, dan nbf JWT.
- Sanitasi trusted/internal headers sebelum meneruskan request.
- Jangan log token, cookie, API key, raw path dengan identifier, atau request/response body.
- Gunakan ECS structured fields, route ID, normalized contract, trace ID, outcome, dan duration.
- Management port tidak boleh diekspos melalui public Service/Ingress.

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
