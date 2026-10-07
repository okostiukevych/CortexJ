# CortexJ — повторный аудит надёжности, безопасности, масштабируемости и отказоустойчивости

**Дата:** 2026-10-05
**Скоуп:** весь реактор — core, planner, cache, api, streaming (+flow), server, repositories (local/hf/http), runtimes (onnx/llamacpp/djl/remote-openai/isolated), worker, converters, declarative, observability, platform (java8–25), tck, тесты, CI и документация.
**Метод:** пофайловая верификация фиксов аудита от 2026-09-27 по текущему коду (не по докам/коммитам) + поиск новых дефектов; 5 параллельных проверок (конкурентность, безопасность, масштабируемость, отказоустойчивость, тесты/CI); ключевые утверждения проверены вручную по исходникам.

---

## Вердикт

| Вопрос | Ответ |
|---|---|
| Фиксы аудита 2026-09-27 реальны? | **Да** — из ~40 позиций 35 закрыты полностью, 4 частично (P0-3 sync-only, H-5 error-ветка, H-9 HTTP-путь честно unverified, M-12 без FileLock/TTL), 1 воспроизведена заново в новом коде (H-8 → server-модуль) |
| Thread-safe? | **Почти** — 1 CRITICAL (async-путь вне close-барьера), 5 MEDIUM-гонок |
| Без утечек? | **Почти** — OTel span-утечки, temp-каталог worker, Error-окна в load-пути |
| Масштабируема? | **Да для 21/25** (virtual threads, bounded-пулы, admission); 8–17 — приемлемо (max(8, 2×cores), очередь 256, AbortPolicy); worker сериализует ops; сервер ограничен admission-контролем |
| Безопасна? | **В основном** — bind+auth+constant-time, sha256 (HF) до коммита, path traversal/symlink containment, hardening парсеров; 1 HIGH (DNS-rebinding обход guard), apiKey в worker-пайп, fail-open политики |
| CI подкрепляет заявления? | **Нет** — все 10 джобов `if: false`; «457 green» гарантируется только локальной дисциплиной |

Снижение тяжести по сравнению с 2026-09-27: **7 CRITICAL → 1** (остаток неполного фикса), **14 HIGH → 1 кодовый** (DNS rebinding; остальные HIGH — процессные: CI, README). Количество находок сопоставимо, потому что: (а) новый код M10-волны (isolated/worker, серверные guards, OTel) принёс новые дефекты, (б) фиксы местами неполные, (в) этот аудит глубже первого (refcounts, семантика отмены, span-жизненный цикл проверялись впервые).

---

## Статус фиксов аудита 2026-09-27 (верификация по коду)

| Старая | Статус | Комментарий |
|---|---|---|
| P0-1 double-close | ✅ | CAS `beginClose` + close под sessionLock; CAS и в самих моделях; `ModelHandleCloseRaceTest` |
| P0-2 параллельная загрузка | ✅ | `oncePerKey` + уникальный `.part` + sha256 из HF blobs; `ConcurrentLoadDedupTest` |
| P0-3 close во время load | ⚠️ | sync-путь закрыт (fair RW-барьер + `PlatformCloseBarrierTest`); **async-путь нет → C-1** |
| P0-4 однопоточный сервер | ✅ | bounded pool + semaphore admission + настраиваемый await |
| P0-5 temp конверсий | ✅ | finally-удаление; `ConversionTempCleanupTest` |
| P0-6 процесс на каждый load | ✅ | `SharedEndpointRegistry` refcount; LOW-остаток L-1 |
| P0-7 2–4 потока | ✅ | max(8, 2×cores), очередь 256, daemon, AbortPolicy; 21+ — virtual threads |
| H-1..H-4, H-7 | ✅ | requireActive + RW-лок; буферизация PushTokenStream; реальная отмена в обоих рантаймах; destroyForcibly-лестница |
| H-5 Flow-контракт | ⚠️ | onNext/onComplete сериализованы; **onError обходит guard → M-1** |
| H-6 non-daemon | ✅ | daemon-фабрики везде; `DaemonThreadPolicyTest` |
| H-8 Ctrl+C сервера | ⚠️ | CLI-путь исправлен; **CortexJServerMain повторяет паттерн → M-13** |
| H-9 sha256 | ⚠️ | HF — реальный sha256; HTTP/локальные >256MB — честно помечены unverified (осознанный остаток) |
| H-10/H-11 сервер | ✅ | loopback+auth+constant-time; лимит тела 8MB внутри цикла; **но новый guard уязвим → H-1** |
| H-12/H-13/H-14 | ✅ | чистый FNV-1a; verify-once-then-trust; TTL-кэши метаданных |
| M-1..M-9, M-13..M-19 | ✅ | frozen builder; onClose-callback; probe TTL; изоляция EventBus; per-key locks; дедуп конверсий; waitForReady; disconnect в close; temp в list/clean; ModelBundler finally; конвертеры finally; overflow-типизация; ONNX fail-close; wiring ArtifactInspector; амортизированная эвикция; bulk-конвертер; индексированные токенизаторы |
| M-12 resume .part | ⚠️ | in-process защита есть; **FileLock и TTL отсутствуют → M-14** |
| M-18 редиректы | ✅ | ручное следование + credential stripping + scheme-pin |
| P3 (sessionLock, TOCTOU) | ✅ | SESSION_SCOPED через sessionLock; emit под lock |

Тестовая база волны фиксов реальна: 20 `@Tag("concurrency")`-сьютов с барьерами и многораундовыми гонками, leak-ассерты (процесс вышел, temp удалён, handle закрыт), TCK 7+4 контрактов, 457 `@Test`/126 файлов (подсчёт подтверждён).

---

## Новые находки

### CRITICAL

**C-1. `loadAsync` выполняется вне close-барьера → осиротевший handle/процесс и зависший Future (реинкарнация P0-3).**
`core/.../DefaultModels.java:153-206` — readLock держится только на время подачи задачи в downloadExecutor; тело (`loadWithFallbacks`) исполняется без блокировки и никогда не перечитывает `closed`. `closeAll()` ставит `closed=true` и итерирует снапшот; задача, завершившаяся после снапшота, делает `openHandles.add(handle)` (`:403`) — этот handle никто не закроет никогда (llama-server с полной RAM переживает JVM). Задача, выброшенная `shutdownNow()`, оставляет Future клиента незавершённым навсегда.
**Фикс:** read-lock внутри runnable + перепроверка `closed`; в `closeAll` — фейлить/дренировать in-flight async-loads до clear; shutdownExecutor(downloadExecutor) после closeAll.

### HIGH

**H-1. DNS-rebinding-обход loopback-защиты no-auth сервера.**
`server/.../CortexJServer.java:256-287` — `isLoopbackHost` резолвит attacker-контролируемое имя из Host-заголовка (`InetAddress.getByName(host).isLoopbackAddress()`). Домен с A-записью → 127.0.0.1 проходит guard без auth; SOP не мешает читать ответы (классический rebinding). Guard, добавленный фиксом #82, отменяет сам себя.
**Фикс:** только литеральные `localhost`/`127.0.0.0-8`/`::1` без DNS-резолва.

**H-2. CI и CodeQL полностью выключены (`if: false` ×10), README утверждает обратное.**
`.github/workflows/ci.yml:14,49,74,103,138`, `codeql.yml:21`. Ни один тест/скан/coverage не исполняется автоматически; все «✅ verified» подкреплены только локальными прогонами. Регрессии невидимы до следующего ручного аудита.
**Фикс:** включить build+quality-джобы (минимум ubuntu JDK-матрица + SpotBugs + JaCoCo), синхронизировать README.

**H-3. README:157,240 — рудиментарные «87 tests» против «~457» (строка 193 того же файла).**
Коммит db21783 починил только третий очаг. Претензия из аудита 2026-09-27 осталась в двух местах.

### MEDIUM

| # | Проблема | Где |
|---|---|---|
| M-1 | `TokenFlowAdapter`: upstream-`onError` идёт в обход drain-guard → параллельные onNext/onError (остаток H-5) | `streaming/flow/.../TokenFlowAdapter.java:149-156` |
| M-2 | Гонка pin-refcount: `remove(identity, count)` удаляет счётчик живого handle → unpin живого артефакта; `pin()` TOCTOU — `FileAlreadyExistsException` глотается, handle работает без пина | `core/.../DefaultModels.java:96-125`; `cache/.../CacheStorage.java:416-419` |
| M-3 | Терминал проглатывается при close/отмене mid-stream: SSE-воркер видит `closed/cancelled` → тихий return, ни onComplete, ни onError → подписчик/сервер ждут до потолка | `runtimes/llamacpp/.../LlamaServerModel.java:267-275`; `runtimes/remote-openai/.../RemoteOpenAiRuntime.java:214-225,340-343` |
| M-4 | Worker: ops обрабатываются однопоточно и блокирующе — `cancelStream`/`shutdown` застревают за длинным generate; ложные 300-с таймауты при очереди; parent close может блокироваться ~600 с | `worker/.../WorkerHost.java:146-159,282-289`; `runtimes/isolated/.../WorkerChannel.java:286-321` |
| M-5 | Чужая отмена отравляет ждущих `oncePerKey`: CancellationException-cause перевыбрасывается ждущему без retry; ждущий игнорирует свой cancelFlag | `core/.../DefaultModels.java:520-541` |
| M-6 | remote.apiKey уезжает в worker-child по pipe (вся platformConfiguration в op `open`), env-allowlist при этом секреты не пробрасывает — граница доверия несогласована | `runtimes/isolated/.../IsolatedRuntimeProvider.java:172` |
| M-7 | Неограниченное чтение на границах доверия: `readLine()` без лимита (worker stdio/stderr), `HttpJsonClient.readAll`, `LlamaServerModel` response body → OOM родителя от злонамеренного/сломанного child или endpoint | `runtimes/isolated/.../WorkerChannel.java:188,221,497`; `runtimes/remote-openai/.../HttpJsonClient.java:124-139` |
| M-8 | Enterprise policy fail-open: любой IOException при чтении → «NOT applied» + WARNING; chmod 000/битый файл молча отключают все запреты | `core/.../DefaultCortexJ.java:357-377` |
| M-9 | OTel span-утечки: `MODEL_RESOLUTION_STARTED`/`MODEL_LOADED` публикуются без атрибутов → все span-ы с ключом `":"`; успешный load не завершает resolution-span (unbounded рост); `startSpan` стартует span до putIfAbsent — конкурентные load теряют не-ended span-ы | `core/.../DefaultModels.java:329-330,400-401`; `observability/.../OtelEventListener.java:118-122` |
| M-10 | `Error` между `provider.open()` и завершением load: catch только RuntimeException → OOM при загрузке течёт llama-endpoint с refcount (живой процесс) | `core/.../DefaultModels.java:374-409` |
| M-11 | remote SSE: poll-таймаут 2 с применяется и к чтению заголовков (`getResponseCode`) → endpoint с очередью >2 с даёт ложный срыв стрима | `runtimes/remote-openai/.../RemoteOpenAiRuntime.java:260,275` |
| M-12 | WorkerChannel никогда не удаляет temp-каталог child-JVM (класс старого P0-5 в новом коде) | `runtimes/isolated/.../WorkerChannel.java:106` |
| M-13 | `CortexJServerMain` регистрирует shutdown-hook ПОСЛЕ загрузки модели — Ctrl+C в окне загрузки осиротевает llama-server (паттерн H-8 воспроизведён заново) | `server/.../CortexJServerMain.java:28-36` |
| M-14 | Resume `.part`: FileLock по-прежнему нет → два процесса на общем `CORTEXJ_HOME` портят один partial; TTL протухших partial-ов нет (остаток M-12) | `core/.../download/DownloadManager.java` |
| M-15 | BOM не покрывает ~12 модулей из README-таблицы (isolated, worker, server, declarative, converters, djl, http-repo, observability, flow, maven-plugin, cli) | `bom/pom.xml:17-48` |
| M-16 | compat-профиль: `cortexj.api.baseline`/`cortexj.compat.skip` нигде не определены — падает вместо документированного skip | `pom.xml:199,213` |
| M-17 | SpotBugs `EI_EXPOSE_REP/EI_EXPOSE_REP2` заглушены глобально (все классы), а не только для API value-типов | `spotbugs-exclude.xml:15-20` |
| M-18 | Нет позитивного round-trip с реальным llama-server (гейт-тест проверяет только провал старта) | `runtimes/llamacpp/.../RealLlamaServerGatedTest.java` |
| M-19 | Child-JVM переживает `platform.close()` (закрытие провайдера opt-in, платформенного хука нет) | `runtimes/isolated/.../IsolatedRuntimeProvider.java:180-196` |
| M-20 | Нет деградации при смерти llama-server mid-generation: handle остаётся READY, все последующие вызовы падают connection-refused вечно (re-probe/respawn нет) | `runtimes/llamacpp/.../SharedEndpointRegistry.java` |
| M-21 | ModelReference = SSRF + чтение локальных файлов, если приложение передаёт user input в `load()/plan()`; нигде не задокументировано как trusted-config-input | `repositories/http`, `repositories/local` |

### LOW

- **L-1** `SharedEndpointRegistry`: окно двойного запуска при смене keyLock (release→0 в момент двух acquire) — транзиентное 2× RAM
- **L-2** `asyncExecutor` (DefaultCortexJ:108-110) — unbounded-очередь; ветка RejectedExecutionException в executeAsync мертва; для не-SESSION_SCOPED сырой REE наружу
- **L-3** shutdown-hook платформы регистрируется до конца конструктора → NPE в hook при JVM-exit во время построения
- **L-4** эвикция не берёт keyLock во время полного хеша в `get()` → типированный провал «Cannot validate»
- **L-5** `invalidateProbeCache()` сносит in-flight claims → дублирующиеся физические probe
- **L-6** `close()` второго потока не ждёт фактического освобождения → быстрый close→load кратко видит 2 процесса
- **L-7** sync-EventBus слушатель может reentrant-но закрыть handle из inference-коллбэка
- **L-8** сервер: SSE-таймаут пишет фрейм без синхронизации с runtime-воркером → интерливинг байтов
- **L-9** `JsonPromptDecoder`: первый несбалансированный `{` отменяет поиск навсегда; IllegalArgumentException вместо типизированной ошибки
- **L-10** `ModelBundler` temp-имя: коллизионный nanoTime-паттерн (36fced8 починил его в CacheStorage, но не здесь)
- **L-11** отмена loadAsync не действует во время конверсии (multi-GB работа вхолостую)
- **L-12** `DEGRADED` недостижим; CLOSING идёт мимо таблицы переходов
- **L-13** SSE poll 2 с может разорвать строку посреди доставки (компромисс фикса M-8)
- **L-14** disk-fill окно до 100 GB: кап кэша проверяется только в `put`, не на старте скачивания
- **L-15** «театр теста»: `apiKeyUpstreamOnlyAndNeverInErrors` не задаёт ключ и не проверяет его отсутствие (утверждение коду соответствует, тест фиктивен)
- **L-16** case-коллизии кэш-ключей (sanitize → lowercase): взаимное удаление артефактов через verify-delete
- **L-17** до 300 символов тела чужого endpoint отражаются клиентам сервера
- **L-18** `cortexj.remote.api.key` через system property читается jcmd/hs_err — предупреждения нет
- **L-19** slow-loris чтения тела (нет read-timeout; semaphore висит)
- **L-20** прочее: `@Tag("perf")` в дефолтном прогоне; нет Dependabot/OWASP; Jackson 2.17.1 (CVE-2024-43791); нет прямых isTerminated-ассертов; CLI plan/pull/run без прямых тестов; redirect-зазоры DownloadManager:274-289/ModelBundler:253-262; NPE HfTokenizer на скаляре; Host DNS-lookup без таймаута форсирует медленные запросы

---

## По свойствам

**Многопоточность.** Сила: CAS-lifecycle, fair RW-барьер, RW-лок инференс/закрытие ONNX/DJL, drain-guard + missed-work re-check, refcount-реестр, 20 concurrency-сьютов. Пробелы: C-1, M-1..M-5.

**Безопасность.** Сила: constant-time auth, bind+auth, sha256 (HF) до атомарного коммита, symlink containment, hardening парсеров, env-allowlist, нет polymorphic Jackson/ReDoS/shell-инвокаций. Пробелы: H-1, M-6..M-8, M-21, L-14..L-18.

**Масштабируемость.** Сила: bounded-пулы с AbortPolicy везде, virtual threads 21+, SharedEndpointRegistry (1 процесс на модель), verify-once-then-trust, TTL-кэши метаданных/probe, амортизированная эвикция, bulk-конвертер, индексированные токенизаторы, ArtifactInspector wired. Пробелы: L-2 (asyncExecutor unbounded), M-4 (worker сериализует), M-20 (нет re-probe), MemoryEstimator остаётся честной эвристикой (confidence MEDIUM/LOW).

**Отказоустойчивость.** Сила: типированные ошибки на всех проверенных сценариях деградации (disk full, network down, битый кэш, смерть llama-server mid-generation, нативные исключения), retry-матрица с бэк-оффом и Retry-After, resume с валидацией 206, kill-лестницы, finally-очистка темпов, TCK-контракты lifecycle/cancel/registration. Пробелы: C-1, M-3, M-10..M-14, M-19, M-20, L-9..L-13.

---

## Качество тестов и CI

- **457 @Test / 126 файлов подтверждены**; concurrency-набор реален (барьеры, 200-раундовые гонки, liveness-ассерты); leak-ассерты по всем категориям; TCK 7+4 контрактов; скипы громкие; `|| true` отсутствует.
- **CI полностью выключен** (H-2): внутри выключенных лейнов всё честно (SpotBugs failOnError, CodeQL security-extended, JaCoCo, doctor валит лейн, GPU-гейты), но ни один не исполняется.
- Остаточные: нет позитивного round-trip с реальным llama-server (M-18); `@Tag("perf")` не отделён; BOM/compat/EI_EXPOSE_REP (M-15..M-17); README «87 tests» (H-3).

---

## Дорожная карта

1. **Немедленно (C-1, H-1, H-2, H-3):** read-lock в async-runnable + дренаж in-flight в closeAll; литеральный loopback-whitelist; включить CI; вычистить «87» из README.
2. **Первая волна MEDIUM (код):** M-1..M-5 (стримы/пины/отмена), M-9..M-13 (OTel, Error-окна, SSE-заголовки, worker-temp, server-hook).
3. **Вторая волна MEDIUM (границы доверия):** M-6, M-7, M-8 + advisory M-21 в security-доках.
4. **Сборка/процесс:** M-15..M-17, L-14, L-15.
5. **Обязательные тесты к фиксам:** close-during-loadAsync с leak-ассертом; терминал при close mid-stream; конкурентные pin/close × N; worker cancel за долгим op; containment-тесты apiKey; span-жизненный цикл.

## Резюме одним абзацем

Волна фиксов после 2026-09-27 добросовестна и проверяема: 35+ позиций реально закрыты, тестовая база выросла с нуля многопоточности до 20 честных race-сьютов, TCK и leak-ассертов. Система больше не имеет структурных дефектов happy-path и типированно деградирует на всех проверенных сценариях отказа. Оставшиеся проблемы концентрируются в трёх кластерах: незакрытый async-путь close-барьера (единственный CRITICAL — прямое эхо неполноты фикса P0-3), свежий код M10-волны (isolated worker: temp-утечка, однопоточный протокол, apiKey в пайпе; серверные guards: DNS-rebinding; OTel: span-утечки) и процессный слой (выключенный CI противоречит README). До production-заявления «verified by CI» нужно закрыть первые два кластера и включить третий.
