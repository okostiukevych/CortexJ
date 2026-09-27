# CortexJ — аудит надёжности, безопасности и масштабируемости

**Дата:** 2026-09-27
**Скоуп:** весь реактор — core, planner, cache, api, streaming (+flow), server, repositories (local/hf/http), runtimes (onnx/llamacpp/djl/remote-openai), converters, platform (java8–25), tck, тесты и CI.
**Метод:** пофайловый code review всех 233 main-классов + 45 тестовых файлов; ключевые security-утверждения проверены вручную по исходникам.

---

## Вердикт

| Вопрос | Ответ |
|---|---|
| Работает на happy path? | **Да** — 160 тестов зелёные, атомарный кэш, resume скачиваний |
| На 100% надёжна? | **Нет** — 7 CRITICAL, 14 HIGH, 19 MEDIUM, ~10 LOW |
| Thread-safe? | **Нет** — 3 критические гонки, все воспроизводимы обычными сценариями |
| Без утечек? | **Нет** — 5 подтверждённых (temp dirs, реестр handles, осиротевшие процессы, `.part`-файлы, unbounded map) |
| Масштабируема? | **Частично**: Java 21/25 (virtual threads) — приемлемо; Java 8–17 — 2–4 потока на всё; встроенный сервер — однопоточный |
| Безопасна? | **Частично**: path traversal закрыт, но sha256 на скачивании фактически не проверяется, сервер слушает 0.0.0.0 без auth |

Все гонки ниже **не ловятся текущими тестами** (в наборе ноль многопоточных тестов) — зелёный CI их не опровергает.

---

## P0 — CRITICAL

### P0-1. Гонка двойного `close()` → double-free нативной сессии
**Где:** `core/src/main/java/io/cortexj/core/DefaultModelHandle.java:317-342`, `api/.../model/ModelLifecycleState.java:34-37`
`close()` — check-then-act без блокировки (`if (state == CLOSED) return; transitionTo(CLOSING)`), а `canTransitionTo` разрешает `CLOSING→CLOSING`. Два потока (shutdown hook + обработчик запроса) → оба исполняют тело close → двойной `session.close()` в `OnnxRuntimeProvider.java:271-278`, `DjlProvider.java:299-305`, двойной `endpoint.stop()` в `LlamaServerModel.java:324-333`. Для ONNX это native double-free: исключение или краш JVM.
**Фикс:** CAS-переход через `AtomicReference<ModelLifecycleState>` + close под `sessionLock`; idempotency гарантирована CAS.

### P0-2. Гонка параллельной загрузки одной модели → порча артефакта + 2× процессов
**Где:** `core/.../DefaultModels.java:48-81,165-201`; `core/.../download/DownloadManager.java:99-122,147-216`
`load()` без дедупликации: два потока промахиваются по кэшу, оба качают, **оба пишут в один и тот же resume-файл** `repo~rev~id.part` (один с truncate, другой с append — файл калечится). Затем битый файл проходит проверку, потому что `Checksums.java:32-35` возвращает `true` при пустом checksum, а **оба провайдера передают checksum = null** (`HuggingFaceRepositoryProvider.java:141` — sha256 из `?blobs=true` запрашиваются и выбрасываются; `HttpRepositoryProvider.java:61`). Итого: битый GGUF в кэше → падение llama-server, плюс два процесса с полной RAM модели каждый.
**Фикс:** in-flight дедуп (`ConcurrentHashMap<String, CompletableFuture<Path>>`), уникальный `.part` на попытку или `FileLock`, реальный sha256 из HF blobs.

### P0-3. `platform.close()` во время `load()` → навсегда осиротевший llama-server
**Где:** `core/.../DefaultCortexJ.java:237-251`; `DefaultModels.java:141-144,378-386`
`closeAll()` итерирует снимок `CopyOnWriteArrayList`; handle, добавленный после итерации, никто не закроет никогда (executor уже погашен). llamacpp к этому моменту уже запустил процесс. Ребёнок не умирает с JVM → **гигабайтовый процесс живёт, пока его не убьют руками**.
**Фикс:** барьер закрытия (счётчик in-flight loads / общий lock между `load()` и `closeAll()`), доигрывающий новодобавленные handle.

### P0-4. Встроенный сервер однопоточный; один SSE-клиент замораживает всё до 10 минут
**Где:** `server/.../CortexJServer.java:48` (нет `setExecutor` → все хэндлеры на единственном HTTP-Dispatcher), `:204` (`finished.await(10, MINUTES)` **на dispatcher-потоке**)
Один зависший/медленный стриминговый клиент блокирует принятие любых соединений, `/v1/models`, embeddings — всё. Повторные брошенные стримы держат сервер мёртвым.
**Фикс:** `setExecutor(...)` (кэшированный пул / virtual threads), не блокировать dispatcher на `await`.

### P0-5. Конверсионные temp-каталоги никогда не удаляются
**Где:** `core/.../DefaultModels.java:250-297` (`createTempDirectory("cortexj-conversion")`, строка 252)
Каждая материализуемая конверсия оставляет **полную копию модели** в `$TMPDIR` — и на успехе, и на провале. 3 конверсии по 4 GB = 12 GB мусора → переполнение диска.
**Фикс:** try-finally рекурсивное удаление; на успехе — после `cache.put`.

### P0-6. Один llama-server-процесс на каждый `load()` одной модели
**Где:** `runtimes/llamacpp/.../LlamaCppProvider.java:221-245`, `ProcessLlamaServerLauncher.java:24-52`
Нет реестра/refcount: 2 handle одной модели = 2 процесса × полный RAM. Для «runtime orchestration platform» это обратная масштабируемость.
**Фикс:** реестр shared endpoints с refcount по (artifact, параметры).

### P0-7. Java 8–17: 2–4 потока на всю платформу (head-of-line blocking)
**Где:** `platform/java8|java11/...:23-26` (`max(2, cores)`), `java17` (`max(4, 2×cores)`), `api/.../spi/platform/PlatformModule.java:33-36`
Один executor на все SSE-чтения + `executeAsync`. Каждый стрим пиннит поток **на всю генерацию**. 100 параллельных стримов → ~96 даже не стартуют. (На 21/25 virtual threads — ок.)
**Фикс:** daemon `ThreadFactory`, отдельный увеличенный IO-пул от executeAsync, рассмотрение NIO/async HTTP.

---

## P1 — HIGH

| # | Проблема | Где | Сценарий |
|---|---|---|---|
| H-1 | `execute()/stream()` во время close: state не проверяется; `RemoteModel.closed` (`:110`) — мёртвое поле, никогда не читается | `DefaultModelHandle.java:278-315`; `OnnxRuntimeProvider.java:217-230`; `RemoteOpenAiRuntime.java:110` | `session.run()` на закрытой нативной сессии → OrtException/краш |
| H-2 | `PushTokenStream`: терминальный сигнал до `subscribe()` потерян навсегда; ранние токены дропаются | `streaming/.../PushTokenStream.java:26-64`; `RemoteOpenAiRuntime.java:165-187` (воркер стартует до возможного подписчика) | Быстрый ответ/ошибка → подписчик висит навсегда |
| H-3 | Отмена стрима — no-op (llama.cpp): флаг `cancelled` никем не читается, `close()` пустой, single-thread executor | `LlamaServerModel.java:52,135-147,150-216,218-222` | Один брошенный стрим занимает единственный SSE-поток на всю генерацию — все следующие стримы модели в очереди |
| H-4 | Отмена стрима — no-op (remote): `onCancel` не зарегистрирован; воркер читает до `[DONE]`/300 с; eager start без подписчиков; default пул 4 потока | `RemoteOpenAiRuntime.java:61,165-187,223-259` | Отменённые стримы биллятся и съедают пул `executeAsync` |
| H-5 | `TokenFlowAdapter`: параллельные и неупорядоченные `onNext` (нарушение Flow-контракта) — два drainer-потока одновременно | `streaming/flow/.../TokenFlowAdapter.java:95-126` | Склеенный/перепутанный текст токенов |
| H-6 | Non-daemon потоки на 8–17 → JVM не выходит без `close()` | платформенные модули; `RemoteOpenAiRuntime.java:61`; `LlamaServerModel.java:52` | Пропустили close в приложении → процесс висит навсегда |
| H-7 | Нет `destroyForcibly`-эскалации при нездоровом старте llama-server (есть только в `stop()`) | `ProcessLlamaServerLauncher.java:57-75` | Большая модель, SIGTERM игнорируется во время mmap → `ModelLoadException` + живой процесс с RAM и портом |
| H-8 | Ctrl+C на `cortexj server` → осиротевший llama-server: shutdown-hook делает только `httpServer.stop()`, `platform.close()` в `finally` недостижим (`await()` навсегда) | `CortexJServerMain.java:29-44`; `CortexJServer.java:39-42,74-78` | Каждый рестарт сервера оставляет процесс |
| H-9 | Скачивание фактически не верифицируется: HF/HTTP передают `checksum = null` | `HuggingFaceRepositoryProvider.java:141`; `HttpRepositoryProvider.java:60-62` | Повреждённый/подменённый файл попадает в кэш и в рантайм; противоречит `security-review.md:20` |
| H-10 | Сервер слушает **0.0.0.0** (`new InetSocketAddress(port)`) **без какой-либо auth** на `/v1/*` — проверено вручную | `CortexJServer.java:48` | Любой хост в сети гоняет инференс на вашей машине/GPU |
| H-11 | `readBody` читает тело без лимита — проверено вручную | `CortexJServer.java:284-295` | Один большой POST → OOM всего процесса |
| H-12 | Fallback-токенизатор: `synchronized` на hot path + unbounded `LinkedHashMap observed` | `model/.../HfTokenizer.java:41-60` | Монотонный рост heap + сериализация всех embed-запросов |
| H-13 | Cache-hit с checksum перечитывает и хеширует весь файл (10 GB → 10 GB чтения) | `CacheStorage.java:63-86`; `Checksums.java:32-50` | Каждый load дорогой; сегодня полудормантно из-за H-9 |
| H-14 | HF-метаданные тянутся по сети на каждый `plan()`/`load()` без кэша | `HuggingFaceRepositoryProvider.java:154-192`; `DefaultExecutionPlanner.java:582-609` | Сотни мс латентности на каждый load; нитка блокируется |

---

## P2 — MEDIUM

| # | Проблема | Где |
|---|---|---|
| M-1 | Живой mutable `CortexJBuilder` в `DefaultCortexJ`, `config()` перечитывается на каждый вызов; `trustPolicy` можно поменять после build() без барьеров памяти | `DefaultCortexJ.java:29,129-131`; `CortexJBuilder.java:18-36` |
| M-2 | `handleClosed()/unregister()` — мёртвый код: закрытые handles копятся в `openHandles` до конца жизни платформы | `DefaultModels.java:388-394` |
| M-3 | `probeCache`: неатомарный get→put; «недоступен» от сетевого сбоя кэшируется **навсегда** (нет TTL/инвалидации) | `DefaultExecutionPlanner.java:63-64,405-421` |
| M-4 | `EventBus.asListener()` не изолирует исключения слушателей (в отличие от `publish()`) — может убить download/cache.put | `EventBus.java:21-35 vs 49-58` |
| M-5 | `CacheStorage`: гонка verify-delete vs replace (можно удалить свежий артефакт); окно «нет в кэше» между delete и move → повторные скачивания; over-eviction при параллельных put | `CacheStorage.java:63-129,214-243` |
| M-6 | Параллельные load одной конверсии дублируют тяжёлую работу (та же форма, что P0-2) | `DefaultModels.java:250-298` |
| M-7 | `waitForReady()` не ждёт — no-op; состояния LOADING/DOWNLOADING/PREPARING никогда не используются | `DefaultModelHandle.java:52,272-276` |
| M-8 | `shutdownNow()` не прерывает socket reads → SSE-воркеры живут до 300 с после `close()` | `DefaultCortexJ.java:240-250` + SSE-циклы |
| M-9 | Кэш-темпы `.part-` невидимы для `list()/clean` (фильтр по имени) → вечный мусор при сбое evict/move | `CacheStorage.java:112-122,140` |
| M-10 | `ModelBundler`: temp не удаляется при HTTP-ошибке/обрезке | `ModelBundler.java:224-264` |
| M-11 | Конвертеры оставляют `.part` при сбое на середине | `GgufFloatConverter.java:183-188`; `SafetensorsToGgufConverter.java:148-153` |
| M-12 | Resume `.part`: нет срока жизни, нет FileLock; LRU-эвикция может удалить файл **активного** скачивания («фикс» из security-review покрыл только `.part-`, не `.part` DownloadManager) | `DownloadManager.java:99-161`; `CacheStorage.java:140` |
| M-13 | `TokenFlowAdapter`: буферизация без учёта demand (неограниченный буфер при лагающем подписчике) | `TokenFlowAdapter.java:95-100` |
| M-14 | ONNX: окно утечки `OrtSession` при `Error` между createSession и конструктором; `readAllBytes` удваивает память загрузки (1 GB модель ≈ 2 GB transient) | `OnnxRuntimeProvider.java:143-156` |
| M-15 | `ArtifactInspector = null` в продакшене: планировщик не видит реальные хедеры GGUF/safetensors (оценки памяти — эвристики) | `DefaultCortexJ.java:98-104` |
| M-16 | `evictToFit` обходит **всё дерево** кэша на каждый put | `CacheStorage.java:131-151,214-243` |
| M-17 | Конвертер: `byte[2]` на каждый элемент — миллиарды аллокаций на multi-GB конверсии | `GgufFloatConverter.java:209-220` |
| M-18 | `Authorization` пробрасывается на все redirect-хопы → утечка HF-токена на сторонние CDN | `DownloadManager.java:231-259` |
| M-19 | BPE `O(words×merges)`, unigram `O(n²)` substring — медленное кодирование на каждый запрос | `HfTokenizer.java:131-154,183-202` |

---

## P3 — LOW (списком)

- `PushTokenStream`: emit-after-complete TOCTOU; неиспользуемое поле `lock`
- Глотание исключений в `EventBus.publish` и close-путях (усугубляет P0-1, скрывая double-close)
- `sessionLock` не применяется к `capability()`/`stream()` при `SESSION_SCOPED`
- Загрузки из `load()` не отменяемы (`cancelFlag = null`)
- `PlannerInputs.immutable()` возвращает mutable-список
- HTTP-стримы закрываются только через `disconnect()` (хрупко при рефакторинге)
- probe-connection не disconnect()ится при исключении в `openFollowingRedirects`
- comparator планировщика строит candidate дважды на сравнение
- EventBus — синхронный dispatch на вызывающем потоке (медленный слушатель блокирует load/inference)

---

## Заявления vs реальность (документация врёт в 5 местах)

| Заявление | Реальность |
|---|---|
| README: «41 suites / 177 tests green on JDK 25» и «87 automated tests across 16 suites» | В дереве **160** `@Test` / 45 файлов / 41 suite. Оба числа README неверны и противоречат друг другу |
| `security-review.md:20`: «SHA-256 verification on download and on cache read ✅» | `checksum = null` на HF и HTTP путях → на основном пути скачивания sha256 **не проверяется** |
| `security-review.md:31`: гонка эвикции `.part-*` «fixed» | Фикс покрывает только суффикс `.part-`; resume-файлы DownloadManager (`.part`) эвицируются посреди активного скачивания |
| «llama-server managed process … graceful shutdown» | Верно только через штатный `stop()`; при провале старта (H-7), Ctrl+C сервера (H-8), `close()` во время load (P0-3) процесс осиротевает |
| `docs/writing-runtime-provider.md:123`: TCK «validates … registration» | Теста registration в TCK нет |

---

## Качество тестов и CI

**Тесты (160, зелёные по CI):**
- **Ноль** многопоточных/stress-тестов — ни одна гонка выше тестами не ловится
- **Ноль** leak-ассертов: никто не проверяет, что процесс завершился, executor isTerminated, что файлы удалены. `EndToEndLoadTest.FakeRuntimeModel.closed` декларируется, но **никогда не проверяется**
- llama.cpp тестируется против **stub-лаунчера**; `ProcessLlamaServerLauncher`, 180-секундный health-timeout, смерть процесса mid-stream, retry на 5xx/429 — не покрыты
- TCK тонкий: 3 runtime-теста + 4 repository; нет контрактов lifecycle/close/cancel/registration; TCK-тесты тихо пропускают assert'ы, если runtime не установлен
- `@Tag("perf")` не отделён от гейтинга

**CI (`.github/workflows/ci.yml`):** широкая JDK-матрица 8–25 × ubuntu/macos/windows — реально. Но: **ноль** security-скан (нет CodeQL/OWASP/Dependabot), **ноль** статанализа (SpotBugs/ErrorProne), **ноль** coverage (JaCoCo); лейн `doctor || true` **маскирует провалы**; GPU-lanes выключены (заявлены как готовые).

---

## Что сделано хорошо (баланс)

- Атомарная запись кэша: уникальный temp (nanoTime) + `ATOMIC_MOVE` + fallback; санитизация путей (`CacheStorage.sanitize` — патч traversal держит)
- Корректный shutdown-протокол executors: `shutdown → awaitTermination(5s) → shutdownNow`, interrupt восстанавливается
- Единственный правильный DCL-синглтон в кодовой базе (`OrtEnvironment`, volatile + synchronized)
- llama-server drain-поток — daemon + `redirectErrorStream` (нет pipe-deadlock); `stop()` — правильная лестница `destroy → waitFor(10s) → destroyForcibly → waitFor(5s)`
- CAS-одноразовость терминалов стрима; `CopyOnWriteArrayList` для слушателей; immutable value-типы с защитными копиями
- Resume скачиваний: `Range: bytes=N-`, 206-валидация, детекция обрезки; капы (retry 3, redirect 5, size-check **до** сети)
- Провал загрузки не течёт: `loadCandidate` закрывает runtime при исключении
- HF-токен не логируется (есть тест `apiKeyUpstreamOnlyAndNeverInErrors`) — подтверждено

---

## Дорожная карта починки

**P0 (~1.5–2 дня):** P0-1 (CAS close + guards), P0-2 (in-flight дедуп + sha256 из blobs), P0-3 (close-барьер), P0-4 (server: `setExecutor` + лимит readBody + bind localhost по умолчанию + auth-хук — закрывает и H-10/H-11), P0-5 (finally-удаление temp), P0-7 (daemon factories), P0-6 (shared-endpoint registry — можно второй волной).

**P1 (~3–5 дней):** cancel-цепочка в обоих рантаймах (H-3/H-4), shutdown-hook платформы (H-8), `destroyForcibly` при старте (H-7), буферизация терминала `PushTokenStream` (H-2), wiring `handleClosed` (M-2), TTL probeCache (M-3), `waitForReady` (M-7).

**P2 (~1 неделя пачками):** кэш-гонки (M-5), temp-файлы (M-9/M-10/M-11/M-12), ONNX Path-API + fail-close (M-14), wiring `ArtifactInspector` (M-15), Authorization на редиректах (M-18), perf (M-17/M-19).

**Обязательные новые тесты (иначе фиксы не держатся):** double-close стресс; concurrent load одной модели (1 скачивание, 1 процесс, целостность); close-during-load / close-during-stream; cancel → соединение закрыто за N сек; сервер: параллельные запросы + огромный Content-Length → 413; leak-ассерты (процесс вышел, executor terminated, файлы удалены). В CI: CodeQL/SpotBugs/JaCoCo, убрать `|| true`, синхронизировать README с реальностью.

---

## Резюме одним абзацем

Кодовая база показывает сильную дисциплину в value-типах, атомарности записи кэша и процессном менеджменте штатных путей — happy path честно работает и протестирован. Но система **не готова называться production-ready**: concurrency-модель жизненного цикла (load/close/use) не спроектирована, а прикручена — отсюда все 7 критических дефектов; cancellation стримов — декоративен на обоих рантаймах; сервер нельзя выставлять в сеть; а главное security-обещание (sha256 при скачивании) в коде не выполняется. Все критические дефекты имеют чёткие, локальные, проверяемые фиксы — при наличии тестов на гонки из дорожной карты платформа станет действительно надёжной.
