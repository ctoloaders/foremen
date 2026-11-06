# Тест-кейсы: FOR-05-02-rooms-autoimport

AI-автоимпорт комнат (текст / изображения / PDF / офисные документы), валидация, массовое создание, медиа комнат, SVG-план стен.

Спека содержит **API-часть** (против работающего Docker-стека) и **UI-часть** (браузерный движок). Результат прогона — MD-репорт с таблицами (шаблон в конце файла), скриншоты не требуются.

## Окружение и повторяемость

- Стек: `docker compose up` в корне (`postgres` :5432, `liquibase`, `backend` :8080, `frontend` :3000). Базовый URL API: `http://localhost:8080`, UI: `http://localhost:3000`.
- Первый ADMIN: `FOREMEN_ADMIN_CREATE=true`, `FOREMEN_ADMIN_EMAIL`, `FOREMEN_ADMIN_PASSWORD`. Логин: `POST /api/auth/login`.
- **Детерминизм LLM:** для всех блокирующих кейсов стек запускается с `FOREMEN_ROOMS_IMPORT_ENABLED=true` и stub-клиентом (`FOREMEN_ROOMS_IMPORT_LLM_STUB=true`). Stub выбирает заготовленный ответ по маркеру в тексте prompt (или в имени файла):
  - `STUB:THREE_ROOMS` (также **по умолчанию**, если маркера нет) — 3 комнаты: «Sypialnia» (спальня 3.2×4.1, потолок 2.6, без проёмов), «Łazienka» (ванная 1.8×2.4, дверь 0.9×2.0 на стене 0 и окно 0.6×1.0 на стене 1), «Kuchnia» (кухня Г-образная, 6 вершин, 1 внутренний угол, дверь на стене 0); заявленные площади/периметры совпадают с расчётными — предупреждений валидации нет;
  - `STUB:AREA_MISMATCH` — одна комната «Salon» 4×5 (рассчитано 20 м²), заявлено 22 м² (+10 %) → `AREA_MISMATCH`;
  - `STUB:OPENINGS_TOO_BIG` — «Pokój z oknami» 3×3, потолок 2.5, окно шириной 3.5 м (×4) на стене 0 → `OPENING_TOO_WIDE` / `OPENINGS_EXCEED_WALL`;
  - `STUB:UNKNOWN_TYPE` — «Pomieszczenie X», подсказка типа `zzz-unknown` не из каталога → `ROOM_TYPE_UNRESOLVED`;
  - `STUB:TEXT_ONLY_SCALARS` — «Pokój tekstowy» без геометрии, только `floorArea=12`, `ceilingHeight=2.6`;
  - `STUB:IMAGE_CROPS` — «Pokój A» (3×4, bbox `[100,100,500,450]`) и «Pokój B» (3×3.5, bbox `[100,500,500,900]`); кропы формируются реально из загруженной картинки;
  - `STUB:ROOM_COUNT_MISMATCH` — те же 3 комнаты, что в `THREE_ROOMS`, но «заявлено» 4 → `ROOM_COUNT_MISMATCH` (`stated=4`, `detected=3`);
  - `STUB:LLM_DOWN` — ошибка шага детекции (LLM недоступен);
  - `STUB:SLOW=<сек>` — искусственная задержка **каждого** вызова измерения комнаты (стадия MEASURING; по умолчанию 20 с, если число не указано); комбинируется со сценарным маркером, например `STUB:THREE_ROOMS STUB:SLOW=30`. Для 3 комнат общая задержка ≈ 3×N с — учитывать в таймаутах поллинга.
  - Маркер ищется в порядке: prompt → имена файлов → извлечённый текст; берётся первый сценарный маркер.
  - **Не достижимы через stub** (заготовок нет): потолок вне диапазона, `INTERNAL_CORNERS_MISMATCH`, `LOW_CONFIDENCE`, `GEOMETRY_INVALID`. Кейсы TC-API-32 (часть про диапазон), 33, 35, 36 в API-прогоне помечаются «пропущено (покрыто юнит/property-тестами правил `DraftValidator`)». `DIMENSION_MISSING` и `POSSIBLE_DUPLICATE_OF_EXISTING` достижимы.
- **Live-Gemini smoke** (TC-API-90, не блокирующий): стек с реальными ключами `FOREMEN_ROOMS_IMPORT_LLM_*`.
- **Переключение stub ↔ live** (`docker-compose.yml` / `.env`, секреты в репозиторий не коммитить):
  - *Stub (по умолчанию для локального стека и CI):* `FOREMEN_ROOMS_IMPORT_ENABLED=true`, `FOREMEN_ROOMS_IMPORT_LLM_STUB=true` — значения по умолчанию compose, `.env` можно не трогать. Креды не нужны.
  - *Live:* в `.env` задать `FOREMEN_ROOMS_IMPORT_LLM_STUB=false` и либо `FOREMEN_ROOMS_IMPORT_LLM_API_KEY` (Gemini Developer API), либо `FOREMEN_ROOMS_IMPORT_LLM_PROJECT` + `FOREMEN_ROOMS_IMPORT_LLM_LOCATION` (+ `..._CREDENTIALS_PATH`/`..._CREDENTIALS_LOCATION`) для Vertex; модель — `FOREMEN_ROOMS_IMPORT_LLM_MODEL`. Незаданные `LLM_*` наследуются от `FOREMEN_OCR_LLM_*`. Не оставлять `LLM_*=` с пустым значением в `.env` (пустая строка перекроет наследование). Затем `docker compose up -d backend`.
  - *Сервис выключен (TC-API-06):* `FOREMEN_ROOMS_IMPORT_ENABLED=false`, `docker compose up -d backend`.
- **Повторяемость по группам:** A, B, C, D, E, K, L (API) — генератор (`run-id` в именах проектов/пользователей/комнат) + teardown (`DELETE` job, удаление созданных комнат); F (медиа) — генератор + удаление комнаты (каскад) в конце; G — отдельный прогон со специальной конфигурацией backend, teardown = возврат дефолтных TTL и `DELETE` job; H (live) — генератор + `DELETE` job; I, J (UI) — генератор (свежий проект `AI-Import-{run-id}-ui` на каждый кейс или набор) + teardown через UI/API (Discard all, удаление комнат); Регрессия — генератор + teardown. Состояние браузера (localStorage pending-change-set) сбрасывается между кейсами очисткой хранилища.
- **Стратегия повторяемости:** **генератор + teardown**. `run-id = <timestamp>-<rand4>`. Все проекты создаются как `AI-Import-{run-id}`; пользователи `rooms-import+{run-id}@example.com`. В конце каждой группы — teardown: `DELETE /api/rooms/autoimport/{jobId}` для всех job, удаление созданных комнат и проекта. Кейсы можно запускать повторно без ручной чистки БД.
- Фикстуры файлов: `fixtures/rooms-import/` (`plan-3-rooms.png`, `plan-3-rooms.heic`, `plan-text.pdf`, `plan-scan.pdf`, `rooms.docx`, `rooms.xlsx`, `rooms.txt`, `broken.pdf`, `big-21mb.png`, `malware.exe`).

## Формат ошибок и права (как реализовано)

- Тело ошибки — `ErrorResponse`: `{ timestamp, status, error, message, path, fieldErrors?, params? }`. Поля `code` **нет**: `message` — локализованный текст (по `Accept-Language`), структурированные детали — в `params`. Проверки пишутся как **статус + `params` + локализованный текст** (для `Accept-Language: pl` и `ru`), а не по коду. Ключи `error.roomsimport.*` / `error.room.media.*` в таблицах ниже — идентификаторы сообщений (для сверки с `messages_*.properties`), а не поле ответа.
- Исключение: `failureReason` у FAILED-job — это именно ключ `error.roomsimport.failed.*`, он возвращается в DTO job как есть.
- ABAC (ресурс `ROOMS`): старт / cancel / discard — `CREATE` (MANAGER, ADMIN); поллинг (`current`, `GET {jobId}`) и draft-медиа — `READ`; медиа комнаты: list/content — `READ`, upload/delete — `UPDATE` (FOREMEN разрешён). WORKER и CLIENT — `403` на все новые эндпоинты. Проект вне доступа пользователя — `404`.
- Эндпоинты: `POST /api/rooms/project/{id}/autoimport` (multipart `prompt`, `files`) → `202 {jobId,status}`; `GET .../autoimport/current` → `200`/`204`; `GET /api/rooms/autoimport/{jobId}`; `POST .../{jobId}/cancel` → `204`/`409`/`404`; `DELETE .../{jobId}` → `204` (идемпотентно, отменяет активный); `GET .../{jobId}/media/{mediaId}` (draft-кроп, `Cache-Control: no-store`); `/api/rooms/{roomId}/media` (GET список, POST multipart `files` → `200` со списком, `GET /{mediaId}/content`, `DELETE /{mediaId}` → `204`).

## Предусловия общего Setup

1. Получить токен ADMIN (`POST /api/auth/login`).
2. Создать проект `AI-Import-{run-id}` (черновик сметы), сохранить `projectId`.
3. Создать пользователя без прав на ROOMS (`NOROOMS`) и пользователя MANAGER, назначенного на проект; получить токены.
4. Сохранить `roomTypeIds` из `GET /api/room-types`.

---

## Группа A. Запуск задачи (API)

### TC-API-01. Запуск по тексту → 202 + jobId
**Предусловия:** Setup.
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | `POST /api/rooms/project/{projectId}/autoimport` multipart, `prompt="STUB:THREE_ROOMS 3 pokoje"`, без файлов | `202`, тело `{ jobId, status:"QUEUED" }` |
| 2 | `GET /api/rooms/project/{projectId}/autoimport/current` | `200`, тот же `jobId` (в проекте один job) |
**Teardown:** `DELETE` job → `204`. Примечание: в проекте может быть только один job; кейсы, стартующие несколько job подряд, обязаны дожидаться завершения или делать `DELETE` между стартами (завершённый job заменяется новым автоматически).

### TC-API-02. Пустой ввод
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | multipart без `prompt` и без файлов | `400`, локализованное сообщение «input.empty» (PL/RU), без `params` |
| 2 | `prompt="   "` (пробелы) | `400`, то же сообщение |

### TC-API-03. Неподдерживаемый тип файла
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | Файл `malware.exe` | `415`, `params.fileName="malware.exe"`, сообщение `file.type` содержит имя файла |
| 2 | Файл `plan.png` с расширением, подменённым на `.txt` (содержимое PNG) | распознан по содержимому как изображение → `202` |

### TC-API-04. Лимиты
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | Файл `big-21mb.png` | `413`, `params.fileName="big-21mb.png"`, `params.limitBytes=20971520` |
| 2 | 11 файлов | `400`, `params.limit=10`, `params.count=11` |
| 3 | PDF на 51 страницу | `400`, `params.limit=50`, `params.pages=51` |
| 4 | После каждого отказа: `GET .../autoimport/current` | `204` (job не создан, файлы не сохранены) |

### TC-API-05. Права и изоляция
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | Запуск без токена | `401` |
| 2 | Запуск токеном `NOROOMS` / WORKER / CLIENT | `403` |
| 3 | Запуск MANAGER-ом чужого проекта (не назначен) | `404` (фиксируется фактический код; `202` недопустим) |
| 3a | Запуск FOREMEN (только ROOMS READ/UPDATE, без CREATE) | `403` |
| 4 | Запуск ADMIN-ом, затем `GET /api/rooms/autoimport/{jobId}` токеном MANAGER чужого проекта | `404` |

### TC-API-06. Сервис отключён
**Предусловия:** стек с `FOREMEN_ROOMS_IMPORT_ENABLED=false`.
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | Запуск с `prompt="x"` | `503`, локализованное сообщение «unavailable» |
| 2 | Проверить в БД количество строк `rooms_import_jobs` для проекта | `0` |

---

## Группа B. Поллинг и результат (API)

### TC-API-10. Прогресс стадий и результат
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | Старт с `STUB:THREE_ROOMS` | `202` |
| 2 | Поллинг `GET /api/rooms/autoimport/{jobId}` каждые 1 с до 60 с | `stage` проходит `QUEUED→EXTRACTING→DETECTING_ROOMS→CLASSIFYING→MEASURING→VALIDATING→COMPLETED`, без отката назад; пока не терминальный — поле `result` отсутствует |
| 3 | Итог | `status="COMPLETED"`, `result.rooms.length=3` |
| 4 | Проверить каждую `rooms[i].room` | есть `projectId`, `roomTypeId` (существующий), `geometry.vertices≥3`, `geometry.walls.length = vertices.length`, `ceilingHeight` |
| 5 | Сверить `rooms[i].calculated` с независимым расчётом (формула Гаусса для площади и периметр, считаются в самом тесте) | `floorArea`, `perimeter`, `wallArea`, `doorArea`, `windowArea` совпадают до 0.01 |
| 6 | `result.rooms[*].warnings` | нет предупреждений уровня WARNING (`AREA_MISMATCH`, `PERIMETER_MISMATCH`, `OPENING*`, `INTERNAL_CORNERS_MISMATCH`, `GEOMETRY_INVALID`); допустимы только INFO |
| 7 | Названия комнат | `Sypialnia`, `Łazienka`, `Kuchnia` (в таком порядке); типы bedroom/bathroom/kitchen разрешены в `roomTypeId` |
| 8 | Ванная: `calculated.doorArea` / `windowArea` | `1.8` / `0.6` (0.9×2.0, 0.6×1.0); кухня: `internalCorners=1`, периметр `15`, площадь `11` |

### TC-API-11. Текстовые скаляры без геометрии
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | Старт `STUB:TEXT_ONLY_SCALARS` | `202` |
| 2 | Результат | комната «Pokój tekstowy» без `geometry`, `floorArea=12`, `ceilingHeight=2.6`; INFO `DIMENSION_MISSING` (`params.field`) для недостающих: `wallArea`, `perimeter`, `doorArea`, `windowArea`, `doorCount`, `windowCount`, размеры дверей/окон, `internalCorners`; значения не выдуманы (`null`); для `floorArea`/`ceilingHeight` предупреждения нет |

### TC-API-12. Несоответствие числа комнат
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | Старт `STUB:ROOM_COUNT_MISMATCH` | `202` |
| 2 | Результат | на уровне `result.warnings`: `ROOM_COUNT_MISMATCH`, `params.stated=4`, `params.detected=3`; задача `COMPLETED` |

### TC-API-13. Задача не найдена
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | `GET /api/rooms/autoimport/999999999` | `404`, локализованное «не найдено» (ключ `error.entity.not.found`) |

### TC-API-14. Discard идемпотентен
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | `DELETE /api/rooms/autoimport/{jobId}` | `204` |
| 2 | Повторный `DELETE` | `204` |
| 3 | `GET` того же job | `404` |
| 4 | `GET .../media/{mediaId}` удалённого job | `404` |

### TC-API-15. Сбой LLM на детекции
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | Старт `STUB:LLM_DOWN` | `202` |
| 2 | Поллинг | `status="FAILED"`, `failureReason="error.roomsimport.failed.llm_unavailable"`, `result` отсутствует |

---

## Группа C. Разбор входных форматов (API)

### TC-API-20. Текст / TXT / CSV
Старт с файлом `rooms.txt` (+ маркер в содержимом `STUB:THREE_ROOMS`) → `COMPLETED`, `result.inputs[0].kind="TEXT"`, `status="OK"`.

### TC-API-21. DOCX и XLSX
Файлы `rooms.docx`, `rooms.xlsx` по отдельности → `COMPLETED`, текст извлечён (в `inputs[*].status="OK"`), комнаты обнаружены; для XLSX текст содержит имена листов.

### TC-API-22. PDF с текстовым слоем
`plan-text.pdf` → `inputs[0].kind="PDF"`, `pages` равно числу страниц, растеризация не выполнялась (проверка по `inputs[0].rasterizedPages=0`).

### TC-API-23. PDF-скан
`plan-scan.pdf` → страницы с малым текстом растеризованы (`rasterizedPages>0`), `COMPLETED`.

### TC-API-24. Изображения разных форматов
Для `plan-3-rooms.png`, `.jpg`, `.webp`, `.gif`, `.bmp`, `.tiff` → `COMPLETED`; для `.heic` → `COMPLETED`, но для комнат присутствует `CROP_UNAVAILABLE`, в `media` — целое исходное изображение.

### TC-API-25. Повреждённый файл не валит задачу
Старт: `broken.pdf` + `rooms.txt` → `COMPLETED`, `result.warnings` содержит `FILE_UNREADABLE` (`params.fileName="broken.pdf"`), комнаты из `rooms.txt` присутствуют.

### TC-API-26. Только нечитаемые файлы
Старт: только `broken.pdf`, без prompt → `FAILED`, `failureReason="error.roomsimport.failed.no_readable_input"`.

---

## Группа D. Валидация (API)

### TC-API-30. Расхождение площади
`STUB:AREA_MISMATCH` → комната «Salon» (4×5): warning `AREA_MISMATCH`, `field="floorArea"`, `params.stated=22`, `params.calculated=20`, `suggestedFix.floorArea=20`; `room.floorArea` в черновике **не изменён** (остаётся заявленным значением); `calculated.floorArea=20`; задача `COMPLETED`.

### TC-API-31. Проёмы больше стены
`STUB:OPENINGS_TOO_BIG` → «Pokój z oknami»: `OPENING_TOO_WIDE` (`params.wallIndex=0`, `wallLength=3`, `width=3.5`) и `OPENINGS_EXCEED_WALL` (площадь проёмов 4×3.5×2.4 > площадь стен 30 м²); комната остаётся в `rooms[]`.

### TC-API-32. Высота потолка
**Статус в API-прогоне: пропущено (нет stub-заготовки; покрыто юнит/property-тестами `CeilingHeightRule`).** Ожидание на unit-уровне: потолок 9.0 → `CEILING_HEIGHT_OUT_OF_RANGE`; отсутствующий потолок → `DIMENSION_MISSING` (`field=ceilingHeight`). Косвенная API-проверка: комнаты stub-сценариев имеют потолок в допустимом диапазоне — `CEILING_HEIGHT_OUT_OF_RANGE` отсутствует.

### TC-API-33. Внутренние углы
**Статус в API-прогоне: пропущено (нет stub-заготовки с рассогласованием; покрыто юнит-тестами `InternalCornersRule`).** Ожидание на unit-уровне: Г-образная комната (6 вершин) с `internalCorners=0` → `INTERNAL_CORNERS_MISMATCH`, `suggestedFix.internalCorners=1`. Косвенная API-проверка: «Kuchnia» из `STUB:THREE_ROOMS` (1 reflex-вершина, заявлено 1) → предупреждения нет.

### TC-API-34. Нераспознанный тип
`STUB:UNKNOWN_TYPE` → «Pomieszczenie X»: `room.roomTypeId=null`, `ROOM_TYPE_UNRESOLVED` (`field="roomTypeId"`), `suggestedFix.candidates` ≤ 3.

### TC-API-35. Низкая уверенность
**Статус в API-прогоне: пропущено (stub выдаёт confidence 0.9/0.7; покрыто юнит-тестами `ConfidenceRule`).** Unit-ожидание: низкая `confidence` → `LOW_CONFIDENCE` (`params.confidence`). Косвенно: у `STUB:TEXT_ONLY_SCALARS` (0.7) см. фактический результат в репорте — фиксируется как есть.

### TC-API-36. Невалидная геометрия
**Статус в API-прогоне: пропущено (нет stub-заготовки; покрыто юнит/property-тестами `GeometryValidityRule`).** Unit-ожидание: самопересекающийся полигон / <3 вершин / нулевая площадь → `GEOMETRY_INVALID`; задача `COMPLETED`, комната возвращена.

---

## Группа E. Кропы и массовое создание (API)

### TC-API-40. Кропы комнат из изображения
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | Старт: файл `plan-3-rooms.png`, prompt `STUB:IMAGE_CROPS` | `202` |
| 2 | Результат | 2 комнаты («Pokój A», «Pokój B»), у каждой `media[0]` с `kind="SOURCE_CROP"`, `contentType="image/png"`, `previewUrl` |
| 3 | `GET previewUrl` с токеном | `200`, `image/png`, размеры меньше исходного изображения |
| 4 | `GET previewUrl` без токена | `401` |
| 5 | `GET previewUrl` токеном пользователя чужого проекта | `404` |

### TC-API-41. Массовое создание из черновика (сквозной путь)
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | Взять `rooms[*].room` из `STUB:IMAGE_CROPS`, добавить `importJobId` и `importMedia:[{jobId, mediaId}]` (из `media[0]` комнаты) | — |
| 2 | `POST /api/rooms/project/{projectId}/save` с `createList` | `200`, `created.length=2`, `skippedMedia=[]`, `skippedImportRefs=[]`; созданные комнаты `aiImported=true` |
| 3 | `GET /api/rooms/{id}` по каждой созданной | геометрия сохранена, 5 расчётных метрик `source="CALCULATED"`, значения равны `calculated` черновика |
| 4 | `GET /api/rooms/{id}/media` | 1 элемент `kind="SOURCE_CROP"` |
| 5 | `GET /api/rooms/{id}/media/{mediaId}/content` | `200`, `image/png` |
| 6 | `GET /api/rooms/{id}` | `mediaCount=1` |
**Teardown:** удалить комнаты (`deleteIds`), `DELETE` job.

### TC-API-42. Предупреждения не блокируют создание
Взять комнату из `STUB:AREA_MISMATCH` (с warning) и сохранить как есть → `200`, комната создана; значение `floorArea` — рассчитанное по геометрии (`CALCULATED`).

### TC-API-43. Скаляры без геометрии → MANUAL
Комната из `STUB:TEXT_ONLY_SCALARS` → после сохранения `floorArea.source="MANUAL"`.

### TC-API-44. Чужое/протухшее медиа не блокирует создание
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | Сохранить комнату с `importMedia` на job **другого проекта** | `200`, комната создана, `skippedMedia` содержит ссылку, `mediaCount=0` |
| 2 | Сохранить комнату с `importMedia` на уже удалённый job | `200`, `skippedMedia` содержит ссылку |

### TC-API-45. Обратная совместимость
Запрос `createList` без `importMedia` и геометрия без `offset` → поведение как до изменения (регресс FOR-05-02).

### TC-API-46. Конфликт пересчёта сметы
Для проекта со сметой с назначенными работами сохранить импортированную комнату того же типа без `force` → `409 error.estimate.recalc.conflict`; повтор с `force=true` → `200`. (Проверка, что импорт не обходит существующий gate.)

---

## Группа F. Медиа комнаты (API)

### TC-API-50. Загрузка, список, удаление
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | `POST /api/rooms/{roomId}/media` multipart (`files`), 2 JPEG | `200`, список из 2 записей `kind="UPLOAD"` |
| 2 | `GET /api/rooms/{roomId}/media` | 2 записи, `fileName`, `sizeBytes`, `contentType` |
| 3 | `DELETE /api/rooms/{roomId}/media/{id}` | `204`; список — 1 запись |

### TC-API-51. Ограничения медиа
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | Файл `.exe` | `400`, локализованное сообщение `error.room.media.type` (содержит заявленный тип) |
| 2 | Файл `.png` с заявленным типом `image/png`, но другим содержимым (magic bytes не совпадают) | `400`, `error.room.media.type` |
| 3 | Файл 21 МБ | `400`, `error.room.media.size` (лимит 20 МБ) |
| 4 | 21-й файл в комнате (суммарно >20) | `400`, `error.room.media.count` (лимит 20) |
| 5 | Допустимые типы: JPEG, PNG, WEBP, PDF | `200` |

### TC-API-52. Каскад при удалении комнаты
Удалить комнату с медиа → записи `room_media` отсутствуют (проверка через `GET` → `404`, и в БД `COUNT=0`).

### TC-API-53. Права
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | Токен с ROOMS READ без UPDATE: `POST`/`DELETE` медиа | `403` |
| 2 | Тот же токен: `GET` список и `GET .../content` | `200` |
| 3 | FOREMEN (ROOMS UPDATE): `POST` и `DELETE` медиа | `200` / `204` |
| 4 | WORKER / CLIENT / `NOROOMS`: любой метод | `403` |
| 5 | Любой метод без токена | `401` |
| 6 | `GET .../{mediaId}/content` чужой комнаты по id медиа этой комнаты | `404` |

---

## Группа G. Ограничения жизненного цикла (API)

### TC-API-60. Зависшая задача
**Предусловия:** отдельный запуск backend с `FOREMEN_ROOMS_IMPORT_TTL_STUCK_TIMEOUT=10s` (relaxed-binding `foremen.rooms-import.ttl.stuck-timeout`; в compose по умолчанию не пробрасывается — задать через `docker-compose.override.yml`/`environment`). Старт `STUB:THREE_ROOMS STUB:SLOW=60` → после >10 с свип переводит job в `status="FAILED"`, `failureReason="error.roomsimport.failed.timeout"`; новый старт `202`. Значения по умолчанию после прогона вернуть (stuck 15 мин).

### TC-API-61. TTL
**Предусловия:** запуск backend с `FOREMEN_ROOMS_IMPORT_TTL_RESULT=5s` (override compose, как в TC-API-60). После `COMPLETED` и ожидания свипа результат и черновые медиа удаляются; `GET /api/rooms/autoimport/{jobId}` → `404`, `GET .../current` → `204`, draft-медиа → `404`. Значение по умолчанию (72 ч) вернуть после прогона.

---

## Группа H. Live Gemini (API, не блокирующий)

### TC-API-90. Smoke с реальным планом
**Предусловия:** стек с реальными ключами. Старт: `plan-3-rooms.png` + prompt «3 pokoje, wysokość 2,6 m». Ожидание: `COMPLETED`, ≥ 1 комната, типы разрешены, `calculated` совпадает с расчётом движка; результат кладётся в репорт для ручной оценки качества. Расхождения с эталоном **не** считаются падением теста.

---

## Группа I. UI — кнопка «Generate rooms» (браузер)

Все UI-кейсы выполняются при стеке со stub-клиентом. Логин ADMIN, переход на `/projects/{projectId}/rooms`.

### TC-UI-01. Видимость и права
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | Открыть Rooms-вкладку ADMIN-ом | в тулбаре есть кнопка «Generate rooms» (локаль PL: проверить перевод; RU: проверить перевод) |
| 2 | Войти пользователем без ROOMS CREATE | кнопки нет |
| 3 | Проект с залоченной сметой | кнопка недоступна так же, как «Add room» |

### TC-UI-02. Валидация диалога
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | Открыть диалог | поле prompt, зона файлов с подсказкой по типам/лимитам, кнопка Start неактивна |
| 2 | Выбрать `malware.exe` | локализованная ошибка о типе, файл не добавлен |
| 3 | Выбрать 11 файлов | ошибка о лимите 10 |
| 4 | Ввести `STUB:THREE_ROOMS` | Start активна |

### TC-UI-03. Генерация по тексту: колонки в матрице, ничего не сохранено
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | Prompt `STUB:THREE_ROOMS`, Start | индикатор прогресса показывает локализованные стадии |
| 2 | Дождаться завершения | диалог закрывается; в матрице 3 новые колонки (помечены как новые/несохранённые) |
| 3 | Проверить значения 15 измерений | заполнены; 5 расчётных ячеек с бейджем «Calculated» |
| 4 | Перезагрузить страницу | колонки сохранены в браузере (pending change set в localStorage) |
| 5 | Проверить в БД/API `GET /api/rooms?projectId=` | новых комнат **нет** (ничего не сохранено) |
| 6 | Нажать Save | `POST .../save` выполнен, комнаты созданы, колонки превращаются в сохранённые |

### TC-UI-04. Предупреждения
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | Генерация `STUB:AREA_MISMATCH` | на колонке бейдж с числом предупреждений |
| 2 | Открыть поповер («Показать предупреждения комнаты») | заголовок «Предупреждения комнаты (N)», сообщение «Указанная площадь (22 м²) отличается от рассчитанной (20 м²).», уровень (Предупреждение/Информация), кнопка «Применить предложение» (RU) / «Apply suggestion» |
| 3 | Нажать «Apply suggestion» | значение в ячейке изменено, ячейка помечена ручной правкой; Undo возвращает |
| 4 | Нажать Save «как есть» с другой колонкой, где warnings остались | сохранение проходит |
| 5 | Проектный баннер (`STUB:ROOM_COUNT_MISMATCH`) | виден, закрывается |

### TC-UI-05. Нераспознанный тип
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | Генерация `STUB:UNKNOWN_TYPE` | колонка отмечена как требующая выбора типа |
| 2 | Нажать Save | блокируется до выбора типа; у колонки маркер «Выберите тип комнаты перед сохранением» (RU) |
| 3 | Выбрать тип | Save доступна |

### TC-UI-06. Изображение → кропы переезжают в комнаты
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | Диалог: файл `plan-3-rooms.png` + `STUB:IMAGE_CROPS`, Start | 2 колонки |
| 2 | Save | комнаты созданы |
| 3 | Открыть редактор созданной комнаты | в секции «Media» миниатюра с бейджем «Cropped from source» |

### TC-UI-07. Отмена и ошибки
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | Старт и сразу Cancel | polling остановлен, `DELETE` отправлен, новых колонок нет |
| 2 | `STUB:LLM_DOWN` | локализованная ошибка («Автоматический импорт комнат через ИИ не удался», причина), кнопка «Повторить» (RU) / «Spróbuj ponownie» (PL — сверить с `pl.json`) |
| 3 | Стек с отключённой функцией | локализованное сообщение «функция недоступна» |

### TC-UI-08. Discard и Undo
После генерации нажать «Discard all» → колонки исчезли, localStorage очищен; Undo (если применимо) восстанавливает пачку как одно действие.

### TC-UI-10. Долгий импорт: спиннер и свобода навигации
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | Диалог: `STUB:THREE_ROOMS STUB:SLOW=30`, Start, закрыть диалог | диалог закрыт |
| 2 | Область таблицы комнат | спиннер с текстом «Auto AI import is in progress» (PL/RU — локализованный) и текущей стадией; существующие комнаты видимы и редактируются |
| 3 | Кнопка «Generate rooms» | недоступна, тултип с пояснением |
| 4 | Метка вкладки «Rooms» | маленький спиннер |
| 5 | Перейти на другую вкладку проекта, затем на другой проект, затем вернуться на `/projects/{id}/rooms` | спиннер всё ещё показан (job на сервере продолжает работу) |
| 6 | Перезагрузить страницу во время импорта | спиннер показан сразу после загрузки |
| 7 | Дождаться завершения (не покидая страницу) | спиннер исчезает, появляются 3 новые колонки + баннер «generated by AI — review before saving», на колонках бейдж AI |

### TC-UI-11. Результат ждёт пользователя
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | Старт `STUB:THREE_ROOMS STUB:SLOW=15`, сразу уйти на другую страницу, подождать 20 с | — |
| 2 | Вернуться на Rooms | 3 колонки добавлены один раз, баннер с подсказкой об AI |
| 3 | Перезагрузить страницу | колонки не дублируются (6 не становится) |
| 4 | Закрыть баннер | баннер скрыт, колонки остаются; job на сервере остаётся (`GET .../current` → `200`) |
| 5 | «Discard all» | колонки исчезли, `GET .../current` → `204` |

### TC-UI-12. Повторный старт при активной задаче (429)
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | В двух вкладках браузера открыть Rooms одного проекта | — |
| 2 | Во вкладке 1 запустить импорт (`STUB:SLOW=30`) | спиннер |
| 3 | Во вкладке 2 (кэш ещё без job) открыть диалог и нажать Start | локализованное сообщение «импорт уже выполняется» со стадией, диалог закрывается или показывает состояние, вкладка 2 переходит к спиннеру; второй job не создан |

### TC-UI-13. Отмена
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | Старт `STUB:SLOW=30`, закрыть диалог | в таблице спиннер и кнопка «Cancel import» |
| 2 | Нажать «Cancel import» | диалог подтверждения «Отменить импорт?» с текстом «Отменить выполняющийся ИИ-импорт? Прогресс будет потерян.» (RU; EN-ключ — «Cancel the running AI import? Progress will be lost.»); кнопка «Продолжить импорт» — ничего не меняется, импорт идёт |
| 3 | Подтвердить | `POST …/cancel` → `204`; спиннер исчезает сразу, тост «Import cancelled», «Generate rooms» снова активна, вкладка Rooms без спиннера |
| 4 | `GET .../current` | `204` |
| 5 | Колокольчик | новых уведомлений нет |
| 6 | Открыть диалог во время импорта и нажать «Cancel import» внутри диалога | то же поведение |
| 7 | Гонка: нажать «Cancel» в момент завершения (job уже `COMPLETED`) | UI показывает результат (колонки + баннер), а не ошибку |
| 8 | Проверить PL и RU | тексты кнопки, подтверждения и тоста переведены |
| 9 | Сразу после отмены нажать «Generate rooms» и стартовать новый импорт | `202`, новый спиннер |

### TC-UI-14. Проваленный импорт
`STUB:LLM_DOWN` → после закрытия диалога на странице баннер ошибки с «Try again»; закрытие баннера удаляет job (`current` → `204`); «Try again» открывает диалог. Если пользователь ушёл со страницы: в колокольчике уведомление «Automatyczny import pokoi przez AI nie powiódł się» (RU: «Автоматический импорт комнат через ИИ не удался»), клик ведёт на `/projects/{projectId}/rooms`, где показан баннер ошибки с «Try again».

### TC-UI-15. Добавление к существующему плану
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | В проекте уже есть комнаты `E1`, `E2` | — |
| 2 | Импорт `STUB:THREE_ROOMS` | 3 новых колонки **справа** от `E1`, `E2`; существующие колонки не изменились |
| 3 | Save | `E1`, `E2` без изменений, созданы 3 комнаты |
| 4 | Проверить заголовки колонок | у новых — бейдж AI (тултип «Created by AI import»), у `E1`, `E2` — нет |
| 5 | Перезагрузить страницу | бейдж AI у 3 сохранённых комнат остаётся; повторно баннер не показывается и колонки не добавляются (job `applied`) |
| 6 | Открыть редактор AI-комнаты | бейдж AI возле заголовка; правка и сохранение не снимают бейдж |

### TC-UI-16. Уведомление «импорт завершён» и переход на экран комнат
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | Запустить импорт `STUB:THREE_ROOMS STUB:SLOW=15` и сразу перейти на другую страницу проекта (например, Estimate) | — |
| 2 | Подождать завершения (~20 с), не возвращаясь на Rooms | в колокольчике +1 непрочитанное, текст на PL: «Automatyczny import pokoi przez AI zakończony» (на RU: «Автоматический импорт комнат через ИИ завершён»), уведомление кликабельно |
| 3 | Клик по уведомлению | переход на `/projects/{projectId}/rooms`, уведомление отмечено прочитанным (по поведению колокольчика) |
| 4 | Rooms-вкладка | 3 новые колонки + баннер «generated by AI», бейдж AI на колонках |
| 5 | Повторный клик по тому же уведомлению / перезагрузка | колонки не дублируются |

### TC-UI-09. Gate пересчёта сметы
Для проекта со сметой сохранить импортированные колонки → открывается существующий `EstimateRecalcDialog` (если есть изменения), подтверждение завершает сохранение.

---

## Группа J. UI — редактор комнаты: медиа и план стен (браузер)

### TC-UI-20. План стен с размерами и проёмами
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | Открыть редактор комнаты из `STUB:THREE_ROOMS` (ванная с дверью и окном) | отображается SVG-план |
| 2 | Проверить подписи | у каждой стены длина в метрах (2 знака), площадь пола в центре |
| 3 | Проверить проёмы | дверь — разрыв + дуга, окно — тройная линия, расположены по `offset`; легенда «Door/Window» (локализована) |
| 4 | Изменить ширину окна в `RoomOpeningsEditor` | план обновляется сразу |
| 5 | Доступность | у SVG `role="img"` и локализованный `aria-label`; у проёмов `<title>` |

### TC-UI-21. Комната без геометрии
Открыть комнату из `STUB:TEXT_ONLY_SCALARS` → вместо плана локализованное пустое состояние.

### TC-UI-22. Секция «Media»
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | Открыть редактор, секция «Media» пуста | пустое состояние |
| 2 | Загрузить 2 JPEG | миниатюры с бейджем «Uploaded», имя и размер |
| 3 | Клик по миниатюре | лайтбокс с полноразмерным изображением, закрывается по Esc |
| 4 | Загрузить `.exe` | локализованная ошибка типа |
| 5 | Удалить файл | диалог подтверждения → файл исчезает |
| 6 | Вернуться в матрицу | у колонки бейдж количества медиа |

### TC-UI-23. Локализация PL/RU
Переключить язык на PL и RU и пройти TC-UI-01, 04, 20, 22 → все тексты переведены, ключей вида `rooms.autoimport.*` на экране нет.

---

## Группа K. Один job на проект, текущий job, отмена (API)

Для длительных сценариев stub поддерживает маркер `STUB:SLOW=<сек>` (искусственная задержка стадии MEASURING, по умолчанию 20 с), чтобы job можно было наблюдать в активном состоянии.

### TC-API-70. Current job: нет задачи
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | Новый проект без импорта: `GET /api/rooms/project/{projectId}/autoimport/current` | `204 No Content` |

### TC-API-71. Current job: активная задача
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | Старт `STUB:THREE_ROOMS STUB:SLOW=20` | `202`, `jobId=J1` |
| 2 | `GET .../autoimport/current` | `200`, `jobId=J1`, `status` ∈ активных, `stage` указан, `result` отсутствует |
| 3 | Дождаться завершения | `status="COMPLETED"`, `result.rooms.length=3`, `appliedAt=null` |

### TC-API-72. Второй старт при активной задаче → 429
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | Старт `STUB:SLOW=20` | `202`, `jobId=J1` |
| 2 | Сразу второй `POST .../autoimport` с другим prompt и файлом | `429`, локализованное `message` (PL/RU, со стадией), `params.jobId=J1`, `params.stage`, `params.startedAt`, заголовок `Retry-After: 30` |
| 3 | В БД: строк `rooms_import_jobs` для проекта | `1`; входные файлы второго запроса не сохранены |
| 4 | Первый job по-прежнему идёт и завершается `COMPLETED` | ✔ |

### TC-API-73. Гонка двух стартов
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | Одновременно (барьер) отправить 5 стартов в один проект | ровно один `202`, остальные `429`; никаких `500` |
| 2 | В БД: строк для проекта | `1` |

### TC-API-74. Завершённый job заменяется новым
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | Завершить job J1 (`COMPLETED`) | — |
| 2 | Новый старт | `202`, `jobId=J2 ≠ J1` |
| 3 | `GET /api/rooms/autoimport/J1` | `404` |
| 4 | `GET .../current` | `jobId=J2` |

### TC-API-75. Проваленный job заменяется новым
Довести job до `FAILED` (`STUB:LLM_DOWN`) → новый старт `202` (не `429`).

### TC-API-76. Отмена активной задачи через DELETE
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | Старт `STUB:SLOW=20` | `202` |
| 2 | `DELETE /api/rooms/autoimport/{jobId}` | `204` |
| 3 | `GET .../current` | `204` |
| 4 | Немедленный новый старт | `202` (проект свободен) |
| 5 | Подождать окончания отменённого воркера (25 с); в БД строки старого job нет, результата и черновых медиа старого job нет | ✔ |

### TC-API-77. Разные проекты работают параллельно
Старт `STUB:SLOW=20` в проектах A и B → оба `202`.

### TC-API-78. Права и изоляция current
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | `GET .../current` без токена | `401` |
| 2 | Токеном `NOROOMS` | `403` |
| 3 | Токеном пользователя, не имеющего доступа к проекту | `404` |

### TC-API-79. Зависание освобождает проект
Конфигурация как в TC-API-60 (`STUCK_TIMEOUT=10s`, `STUB:SLOW=60`) → после таймаута `status="FAILED"`, `failureReason="error.roomsimport.failed.timeout"`; `GET .../current` показывает FAILED; новый старт `202` (не `429`).

---

### TC-API-84. Уведомление о завершении
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | Токеном MANAGER (пользователь U1, назначен на проект) запустить `STUB:THREE_ROOMS` | `202` |
| 2 | Дождаться `COMPLETED` | — |
| 3 | `GET /api/notifications` токеном U1 | ровно 1 новое уведомление: `type="notification.roomsImport.completed"`, `body=null`, `deepLink="/projects/{projectId}/rooms"`, `read=false` |
| 4 | `GET` уведомлений другим членом проекта (U2) и ADMIN | уведомления нет |
| 5 | Запустить `STUB:SLOW=20`, отменить `POST …/cancel` | новых уведомлений нет (ни completed, ни failed) |
| 6 | Запустить импорт, завершающийся с 0 комнат (prompt без помещений) | `COMPLETED` и уведомление completed есть |
(Уведомления читаются через `GET /api/notifications` и `GET /api/notifications/unread-count`; получатель — только пользователь, запустивший импорт. Текст типа резолвится на клиенте по ключу `notification.roomsImport.completed|failed`.)

### TC-API-85. Уведомление о сбое импорта
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | Токеном U1 запустить `STUB:LLM_DOWN` | `202` |
| 2 | Дождаться `FAILED` | `failureReason="error.roomsimport.failed.llm_unavailable"` |
| 3 | Уведомления U1 | ровно 1 новое: `type="notification.roomsImport.failed"`, `body="error.roomsimport.failed.llm_unavailable"`, `deepLink="/projects/{projectId}/rooms"` |
| 4 | Уведомления U2 и ADMIN | нет |
| 5 | Сбой по таймауту (воркер заблокирован, `stuck-timeout=10s`) | `FAILED` с `error.roomsimport.failed.timeout`; ровно 1 уведомление failed с этим кодом в теле |
| 6 | Гонка воркер/свип (воркер падает одновременно с таймаутом) | всё равно 1 уведомление на job |
| 7 | Job со всеми нечитаемыми файлами | `FAILED no_readable_input`, 1 уведомление failed |

### TC-API-86. Отмена выполняющегося импорта
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | Старт `STUB:THREE_ROOMS STUB:SLOW=20` | `202`, `jobId=J` |
| 2 | `POST /api/rooms/autoimport/J/cancel` (токен U1) | `204` |
| 3 | `GET .../current` | `204`; `GET /api/rooms/autoimport/J` → `404` |
| 4 | Немедленный новый старт | `202` |
| 5 | Подождать 25 с; проверить счётчик вызовов stub-LLM по J | после отмены новых вызовов нет; в БД нет результата и черновых медиа J |
| 6 | Повторный `POST …/cancel` J | `404` |
| 7 | Завершённый job: `POST …/cancel` | `409`, сообщение `not_cancellable`, job не удалён (`GET` → `200`) |
| 8 | Отмена job U1 пользователем U2 (ROOMS CREATE на проекте) | `204` |
| 9 | Отмена пользователем без ROOMS CREATE (FOREMEN/WORKER/CLIENT) / без доступа к проекту | `403` / `404` |

---

## Группа L. Append-only и маркер AI (API)

### TC-API-80. Существующие комнаты не затрагиваются
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | Создать в проекте 2 комнаты вручную (`E1`, `E2`), запомнить все поля и `sortOrder` | — |
| 2 | Импорт `STUB:THREE_ROOMS` → сохранить черновики через `createList` с `importJobId` | `created.length=3`, `updated=[]`, `deleted=[]` |
| 3 | `GET` комнат проекта | `E1`, `E2` без изменений; новые комнаты имеют `sortOrder` больше, чем у `E1`, `E2`, порядок как в черновике |

### TC-API-81. Предупреждение о дубликате существующей
Существует комната «Sypialnia» того же типа; импорт возвращает «Sypialnia» → в черновике `POSSIBLE_DUPLICATE_OF_EXISTING` (`params.existingRoomId`, `label`); комната присутствует в `rooms[]` (не отброшена).

### TC-API-82. Маркер aiImported
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | Сохранить комнату с валидным `importJobId` | комната создана, `GET /api/rooms/{id}` → `aiImported=true`; у job `appliedAt` заполнен |
| 2 | Создать комнату без `importJobId` | `aiImported=false` |
| 3 | `GET /api/rooms?projectId=..&aiImported=true` | только AI-комнаты |
| 4 | `PUT /api/rooms/{id}` на AI-комнате с любым изменением | `aiImported` остаётся `true` |
| 5 | `PUT` с полем `aiImported=false` в теле | поле игнорируется, значение прежнее |

### TC-API-83. Подделка маркера невозможна
| Шаг | Действие | Ожидание |
|---|---|---|
| 1 | `createList` с `aiImported=true`, без `importJobId` | комната создана с `aiImported=false` |
| 2 | `createList` с `importJobId` job-а другого проекта | `200`, комната создана, `aiImported=false`, id в `skippedImportRefs` |
| 3 | `createList` с `importJobId` удалённого/просроченного job | то же |

---

## Регрессия

### TC-REG-01. Ручное создание комнаты
Создать комнату вручную через матрицу и через `RoomFormSheet` → поведение без изменений (бейджи, расчёт, Save); у такой комнаты `aiImported=false`, бейджа AI нет.

### TC-REG-02. Геометрия без offset
Открыть ранее сохранённую комнату с геометрией без `offset` → редактор и план открываются, расчёт не меняется.

### TC-REG-03. Bulk save обратная совместимость
`POST /api/rooms/project/{id}/save` с `createList/updateItems/deleteIds` без новых полей → ответ содержит прежние поля, `skippedMedia` — пустой список.

### TC-REG-04. Лимиты других загрузок
Загрузка изображения производителя / документа закупки с превышением собственного лимита по-прежнему отклоняется с прежним кодом (проверка, что поднятые multipart-лимиты не ослабили существующие проверки).

### TC-REG-05. OCR закупок
Запуск OCR-пайплайна FOR-06-03 после рефакторинга `GeminiClientFactory` → стадии и результат прежние (на stub/отключённом режиме — прежнее поведение).

### TC-REG-06. Матрица прав
`GET /api/rooms` и существующие CRUD остаются доступны ролям как прежде; новые эндпоинты не открывают доступ роли без ROOMS.

---

## Шаблон MD-репорта прогона

**Run-id:** `<timestamp-rand4>` · **Стратегия повторяемости:** генератор + teardown · **Стек:** `<stub|live>` · **Дата:** `<YYYY-MM-DD>`

### API

| # | Тест-кейс | Шаг | Запрос/Действие | Ожидание | Факт | Статус |
|---|-----------|-----|-----------------|----------|------|--------|
| 1 | TC-API-01 | 1 | POST /api/rooms/project/{id}/autoimport | 202 + jobId | | |

### UI

| # | Тест-кейс | Шаг | Действие в браузере | Ожидание | Факт | Статус |
|---|-----------|-----|---------------------|----------|------|--------|
| 1 | TC-UI-03 | 2 | Дождаться завершения генерации | 3 новые колонки | | |

### Итог

Пройдено: X · Провалено: Y · Пропущено: Z. Блокирующие провалы: `<список>`. Live-smoke (TC-API-90): `<результат, заметки о качестве>`.

Teardown выполнен: `<да/нет>`; остаточные сущности: `<список>`.
