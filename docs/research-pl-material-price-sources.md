# Ресёрч: где брать прайс-листы на строй- и финишные материалы в Польше

Цель — наполнить каталог по тематике «ремонт квартир» (материалы + цены) структурированными данными.

Ниже: (1) топ-10 сетей/оптовиков, (2) реалистичные способы получить структурированные данные по каждому, (3) что удалось проверить технически, (4) готовые ссылки для ручной выгрузки там, где автоматика не проходит.

---

## Краткий вывод (TL;DR)

- **Прямой скрейп крупных DIY-сетей (Leroy Merlin, Castorama, OBI) практически закрыт**: сайты за антибот-защитой (запросы отдают timeout/403), а карточки товаров у части сетей явно запрещены в `robots.txt`. Массово и легально тянуть их каталог напрямую не выйдет.
- **Самый чистый структурированный путь — не «прайс сети», а продуктовые фиды и ценовые базы**:
  - **Google Merchant / Ceneo product feed (XML)** — стандарт, в котором магазины сами публикуют товары+цены; многие польские магазины отдают такой фид.
  - **Ceneo Partner API** — официальный агрегатор цен из тысяч польских магазинов (нужен API-ключ).
  - **Сметные ценовые базы (Sekocenbud / Intercenbud / INFOCENBUD-ORGBUD)** — нормативные цены материалов и работ, ровно под тематику ремонта; продаются поквартально в электронном виде.
  - **Marketplace-фиды (Leroy Merlin/Adeo, Allegro)** — через Mirakl/Allegro API, но это канал для продавцов, а не для скачивания чужого каталога.
- **Практичнее всего для CSV своими силами**: (а) взять product-feed конкретных средних онлайн-магазинов, (б) подписаться на одну сметную базу (Sekocenbud/Intercenbud) как «эталон цен».

> Замечание по комплаенсу: у ряда сетей карточки товаров запрещены в `robots.txt` (проверено для PSB Mrówka — ниже). Массовый скрейп таких страниц нарушает их правила и часто ToS. Ниже помечаю, где путь «легальный по дизайну» (фид/API/покупка базы), а где — «серая зона» (скрейп).

---

## Топ-10 сетей и оптовиков (широкий ассортимент, тематика «дом/ремонт/отделка»)

Рынок польского DIY по долям (данные 2016 г., для понимания состава игроков — [interempresas](https://www.interempresas.net/Ferreteria/221273-Castorama-Leroy-Merlin-OBI-son-cadenas-mayor-participacion-mercado-bricolaje-Polonia.html)): Kingfisher (Castorama, Brico Dépôt) ~34%, Adeo (Leroy Merlin, Bricoman) ~27%, OBI ~13%, Bricomarché ~10%. PSB — крупнейшая сеть складов/маркетов по числу точек ([grupapsb](https://mrowka.com.pl/)).

| # | Сеть / оптовик | Профиль | Онлайн-каталог с ценами |
|---|----------------|---------|--------------------------|
| 1 | **Leroy Merlin** (Adeo) | DIY-гипермаркет, отделка+ремонт | leroymerlin.pl |
| 2 | **Castorama** (Kingfisher) | DIY-гипермаркет | castorama.pl |
| 3 | **OBI** | DIY-гипермаркет | obi.pl |
| 4 | **Bricomarché** (Muszkieterowie) | DIY-маркет | bricomarche.pl |
| 5 | **PSB Mrówka / Grupa PSB** | Крупнейшая сеть складов + маркетов «дом и сад» | mrowka.com.pl, e-mrowka.pl |
| 6 | **Bricoman** (Adeo) | Формат для профи/подрядчиков, крупный опт | bricoman.pl |
| 7 | **Merkury Market** | Стройка + отделка + интерьер | merkurymarket.pl |
| 8 | **PSB Profi** | B2B-формат PSB для профи | (в составе Grupa PSB) |
| 9 | **Comfort / профильные сети отделки** (płytki, podłogi, drzwi) | Плитка, напольные покрытия, двери | напр. komfort.pl |
| 10 | **Онлайн-хёртовни отделки** (напр. Płytki-*, e-instal, hurtownie farb) | Плитка/сантехника/краски опт, чаще с product-feed | различные |

Дополнительно — **специализированные оптовики отделочных материалов** (плитка, сантехника, краски, гипс/ГКЛ, электрика). Именно у них чаще всего открыт product-feed (Ceneo/Google), а значит проще всего вытащить CSV.

---

## Как получить данные в структурированной форме (по каналам)

### Канал A. Product feed (XML) — Google Merchant / Ceneo format — рекомендуемый
Большинство польских онлайн-магазинов публикуют товарный фид для Ceneo/Google Shopping. Это XML со всеми полями: `name`, `price`, `category`, `EAN/GTIN`, `producer`, `availability`, `url`, `image`.
- Формат Ceneo/Google — стандартизирован ([wymagania feedu, edrone](https://help.edrone.me/pl/articles/8294829-wymagania-dotyczace-feedu-produktowego); [Google product file](https://support.google.com/merchants/answer/12631822)).
- Как найти фид магазина: смотреть `/robots.txt` и `/sitemap.xml`, искать ссылки вида `.../ceneo.xml`, `.../google.xml`, `.../feed.xml`, `/integracje/`, или спросить у магазина напрямую (часто дают по запросу партнёрам).
- XML → CSV конвертируется тривиально (напишем парсер под пример фида/ссылку).

### Канал B. Ceneo Partner API — легально, широкое покрытие
Официальный API агрегатора цен Польши (десятки млн товаров из тысяч магазинов):
- Документация: [partnerzyapi.ceneo.pl](http://partnerzyapi.ceneo.pl/) — `AuthorizationService` (токен по API-ключу) + `PartnerService` (товары/категории, фильтры по категории, названию, минимальной цене, производителю).
- Даёт: название, минимальную цену, число предложений, категорию, производителя.
- Нужен: партнёрский API-ключ Ceneo (запрашивается у Ceneo). Ключ кладём в локальный `.env` (см. `.env.example` → `CENEO_API_KEY`).

### Канал C. Сметные ценовые базы (эталон цен под ремонт) — легально, самый релевантный «нормативно»
Это польские базы кошторисовых цен материалов и работ — прямо под тематику ремонта квартир:
- **Sekocenbud** — самая распространённая база для смет ([butorg.pl](https://butorg.pl/)).
- **Intercenbud** — «база реальных цен для смет» ([intercenbud](https://intercenbud.pl.siteindices.com/)).
- **INFOCENBUD / ORGBUD-SERWIS** — ежеквартальные издания ICCP (цены материалов), BCB, ICRB; есть примеры PDF для ознакомления ([orgbud, bazy cen](https://orgbud.pl/do-pobrania/baza-cen/)).
- Формат: поквартальные издания, электронные версии интегрируются в кошторисные программы (Norma EXPERT и др.). Обычно платная подписка. Дают структурированные позиции: материал → ед. изм. → цена.

### Канал D. Marketplace-фиды (Leroy Merlin/Adeo, Allegro) — канал для продавцов
- **Leroy Merlin/Adeo** работает на платформе **Mirakl**; интеграция каталога/цен/стока идёт через Mirakl API (напр. описания [boostmyshop](https://www.boostmyshop.com/en/integrations/leroy-merlin), [channable](https://www.channable.com/integrations/leroy-merlin)). Это для тех, кто **продаёт** на площадке, а не для выгрузки чужих цен.
- **Allegro** — крупнейший маркетплейс PL, есть публичный каталог продуктов и API/массовые файлы, но данные о ценах — это офферы продавцов, доступ регулируется ([allegro help](https://help.allegro.com/pl/sell/d/produkty)).

### Канал E. Прямой скрейп сайтов сетей — серая зона / технически затруднён
- Крупные DIY (Leroy Merlin, Castorama, OBI) — за антибот-защитой; напрямую не отдаются (см. проверки ниже).
- Карточки товаров PSB Mrówka **запрещены** в `robots.txt`.
- Для средних магазинов скрейп возможен технически, но фид (Канал A) почти всегда есть и он чище/легальнее.

---

## Что проверено технически

| Источник | Проверка | Результат | Вывод |
|----------|----------|-----------|-------|
| leroymerlin.pl `/robots.txt` | HTTP fetch | **timeout 30s** | антибот, прямой доступ закрыт |
| castorama.pl `/robots.txt` | HTTP fetch | **timeout 30s** | антибот |
| obi.pl `/robots.txt`, `/sitemap.xml` | HTTP fetch | **timeout / 403** | антибот |
| **mrowka.com.pl** `/robots.txt` | HTTP fetch | **OK** — но `Disallow: /sklep-psb-mrowka/*/produkt*` | карточки товаров запрещены роботам |
| mrowka.com.pl главная | HTTP fetch | **OK** (только описание сети) | каталога цен на этом домене нет |
| e-mrowka.pl `/robots.txt` | HTTP fetch | **403 Forbidden** | антибот на онлайн-магазине |
| ceneo.pl / partnerzyapi.ceneo.pl | HTTP fetch | **timeout** (нужен ключ) | API существует, требует партнёрского ключа |
| orgbud.pl (INFOCENBUD) | HTTP fetch | **OK** | ценовая база доступна к заказу, примеры PDF |

Вывод по автоматической выгрузке: **готовый CSV из крупных сетей вытянуть не удалось** — они либо блокируют роботов, либо запрещают карточки. Реальный автоматический CSV достижим через **product feed конкретного магазина** или **Ceneo API с ключом**.

---

## Ссылки для самостоятельной загрузки (там, где автоматика не проходит)

Крупные сети — качать вручную из личного кабинета/каталога или запрашивать фид у отдела продаж:

- Leroy Merlin PL: https://www.leroymerlin.pl
- Castorama PL: https://www.castorama.pl
- OBI PL: https://www.obi.pl
- Bricomarché PL: https://www.bricomarche.pl
- Bricoman PL (профи/опт): https://www.bricoman.pl
- PSB Mrówka: https://mrowka.com.pl · онлайн: https://e-mrowka.pl · группа: https://grupapsb.com.pl
- Merkury Market: https://www.merkurymarket.pl

Ценовые базы (эталон цен материалов/работ для ремонта):

- Sekocenbud (через дистрибьютора): https://butorg.pl
- Intercenbud: https://intercenbud.pl
- INFOCENBUD / ORGBUD-SERWIS (примеры PDF + заказ): https://orgbud.pl/do-pobrania/baza-cen/

Агрегатор/стандарты фидов:

- Ceneo Partner API (документация): http://partnerzyapi.ceneo.pl
- Ceneo категория «Budowa i remont»: https://www.ceneo.pl/Budowa_i_remont
- Формат product feed (Ceneo/Google), требования: https://help.edrone.me/pl/articles/8294829-wymagania-dotyczace-feedu-produktowego

---

## Рекомендованный план действий (быстрый и легальный путь к CSV)

1. **Определить 3–5 «доноров»-магазинов** с широким ассортиментом отделки, у которых есть product feed (плитка, краски, ГКЛ/гипс, сантехника, электрика). Их каталог + цены = основа каталога.
2. **Достать у них XML-фид** (Ceneo/Google формат): проверить `/sitemap.xml`, `/robots.txt`, типовые пути фида, либо запросить у магазина. По ссылке на фид — конвертируем XML → CSV под структуру каталога.
3. **Подписаться на одну сметную базу** (Sekocenbud или Intercenbud) как нормативный эталон цен материалов/работ — это закрывает «ремонт квартир» по нормам.
4. **Ceneo Partner API** — если нужен широкий срез рыночных цен по категориям: получить ключ (→ `.env` `CENEO_API_KEY`), дальше выгрузка в CSV.
5. Крупные сети (Leroy/Castorama/OBI) — использовать как **сверку цен** через ручную выгрузку/личный кабинет, а не как источник массового скрейпа.

## Что нужно, чтобы вытащить CSV автоматически

- Ссылка на **product-feed** любого магазина-донора (XML), **или**
- **API-ключ Ceneo** (кладётся в локальный `.env` → `CENEO_API_KEY`), **или**
- Целевая структура CSV каталога (можно взять из `docs/Materiały pakiety - Lista.csv` / `docs/Matrix Foremen v3.0 (1).xlsx - Oferta.csv`) — под неё нормализуем данные из фидов.

---

_Источник данных: веб-поиск и проверки доступности от 2026-09-20. Цены/доли рынка в источниках могут устаревать — актуализировать при заборе данных._
