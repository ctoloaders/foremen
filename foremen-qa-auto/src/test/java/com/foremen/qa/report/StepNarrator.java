package com.foremen.qa.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a raw Gherkin step ({@code keyword} + {@code text}) into human-readable Russian narration for
 * the client-facing demo report (Requirement 10): a plain-language <em>description</em> of what the
 * step does and a <em>concrete</em> expected result. Pure and stateless — safe to call from the
 * Cucumber plugin without any DI.
 *
 * <h2>How steps are matched</h2>
 * The step text is normalized before lookup so parameterized steps collapse onto a single table
 * entry:
 * <ol>
 *   <li>the leading keyword is not part of the text (Cucumber already splits it off), but we trim;</li>
 *   <li>every quoted {@code "..."} argument is replaced with a positional placeholder
 *       ({@code {arg}}, {@code {arg2}}, …) so {@code I open the dictionary page "/currencies"} and
 *       {@code I open the dictionary page "/vat-rates"} both normalize to
 *       {@code i open the dictionary page {arg}};</li>
 *   <li>whitespace is collapsed and the text is lower-cased.</li>
 * </ol>
 * The extracted quoted arguments are then substituted back into the mapped sentence so the narration
 * reflects the concrete run (e.g. the real route). Unmapped steps get a sensible fallback keyed by
 * the keyword.
 */
public final class StepNarrator {

    /** Matches a double-quoted argument (non-greedy, no embedded quotes). */
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");

    /** Placeholder-token pattern used when substituting args back into a sentence. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{arg(\\d*)}");

    /** Description (что делаем) table, keyed by normalized step text. */
    private static final Map<String, String> DESCRIPTIONS = new LinkedHashMap<>();

    /** Expected-result (ожидаемый результат) table, keyed by normalized step text. */
    private static final Map<String, String> EXPECTED = new LinkedHashMap<>();

    static {
        map("the application stack is ready",
                "Проверяем готовность стенда (бэкенд :8080 и фронтенд :3000 доступны).",
                "Стенд готов: бэкенд и фронтенд отвечают, можно начинать сценарий.");
        map("I am logged in as the seeded admin",
                "Входим под сидовым администратором.",
                "Администратор аутентифицирован, открыта защищённая оболочка приложения.");
        map("I am on the login page",
                "Открываем страницу входа /login.",
                "Отрисована форма входа (поля E-mail, Пароль, кнопка входа).");
        map("I log in with the seeded admin credentials",
                "Вводим учётные данные администратора и отправляем форму.",
                "Форма принята, выполняется переход с /login.");
        map("I land on the home page",
                "Дожидаемся перехода на главную.",
                "URL сменился на /, отрисована оболочка приложения.");
        map("access and refresh tokens are stored",
                "Проверяем сохранение токенов сессии.",
                "В localStorage присутствуют access- и refresh-токены.");
        map("I submit the login form without filling any field",
                "Отправляем форму входа с пустыми полями.",
                "Клиентская валидация блокирует отправку, запрос на сервер не уходит.");
        map("submission is blocked by client validation",
                "Проверяем срабатывание клиентской валидации.",
                "Показаны сообщения о обязательных полях, форма не отправлена.");
        map("no login request is sent",
                "Проверяем сетевую активность формы.",
                "Запрос POST /api/auth/login не отправлялся.");
        map("I stay on the login page",
                "Проверяем, что остаёмся на странице входа.",
                "URL по-прежнему /login.");
        map("I log in with invalid credentials",
                "Вводим неверные учётные данные и отправляем форму.",
                "Сервер отвечает 401, форма показывает ошибку.");
        map("a localized login error message is shown",
                "Проверяем сообщение об ошибке входа.",
                "На форме отображается локализованное сообщение о неверных учётных данных.");
        map("no tokens are stored",
                "Проверяем отсутствие токенов.",
                "В localStorage нет access/refresh токенов.");
        map("I open the protected deep-link \"x\" while unauthenticated",
                "Без сессии открываем защищённый диплинк {arg}.",
                "Гард перенаправляет на /login, исходный маршрут {arg} запомнен.");
        map("I am redirected to the login page",
                "Проверяем редирект на вход.",
                "Произошёл переход на /login.");
        map("I am returned to the deep-link \"x\"",
                "После входа проверяем возврат на исходный маршрут.",
                "Приложение вернулось на {arg}.");
        map("I log out",
                "Выполняем выход из приложения.",
                "Сессия завершена.");
        map("I open the Users page",
                "Открываем страницу «Пользователи».",
                "Загрузилась страница управления пользователями.");
        map("the users list renders",
                "Проверяем отрисовку списка пользователей.",
                "Таблица пользователей отображается с колонками (Имя, E-mail, Роль, Статус).");
        map("I create a user with a generated email",
                "Создаём пользователя с уникальным сгенерированным e-mail (имя, язык, роль).",
                "Пользователь создан, лист-панель закрылась без ошибок.");
        map("the created user appears in the users list",
                "Ищем созданного пользователя в списке.",
                "Строка с созданным пользователем присутствует в таблице.");
        map("I open the Roles page",
                "Открываем страницу «Роли».",
                "Загрузилась страница ролей.");
        map("the roles list renders",
                "Проверяем отрисовку списка ролей.",
                "Таблица ролей отображается с заголовками колонок.");
        map("I open the access matrix",
                "Открываем матрицу доступов роли.",
                "Открыта матрица (ресурс × операция).");
        map("the access matrix is shown",
                "Проверяем отрисовку матрицы доступов.",
                "Матрица доступов видна: есть колонки ресурсов и строки ролей.");
        map("the application shell renders",
                "Проверяем отрисовку оболочки приложения.",
                "Отрисованы сайдбар и топбар на десктопе.");
        map("the primary navigation is usable",
                "Проверяем работоспособность основной навигации.",
                "Переходы по основным пунктам меню работают.");
        map("I open the Appearance settings page",
                "Открываем страницу оформления (тема).",
                "Загрузилась страница «Оформление» с выбором темы.");
        map("I toggle the theme mode",
                "Переключаем режим темы (светлая/тёмная) и сохраняем.",
                "Выбран противоположный режим темы и сохранён.");
        map("the theme selection persists across a page reload",
                "Перезагружаем страницу и проверяем сохранение темы.",
                "После перезагрузки выбранный режим темы сохранился (класс dark и localStorage совпадают).");
        map("the primary navigation groups are visible",
                "Проверяем видимость групп навигации для администратора.",
                "Основные группы навигации видны.");
        map("the current role name is shown in the shell",
                "Проверяем отображение текущей роли в оболочке.",
                "В оболочке показано название текущей роли.");
        map("a role granting only \"x\" \"x\" exists",
                "Через API создаём роль с единственным грантом {arg}:{arg2}.",
                "Создана ограниченная роль с правом {arg}:{arg2}.");
        map("a test user in the restricted role exists",
                "Создаём активного тест-пользователя в ограниченной роли (прямая вставка в БД).",
                "Пользователь создан и готов к UI-входу.");
        map("I log in through the UI as the test user",
                "Входим через UI под тест-пользователем.",
                "Тест-пользователь аутентифицирован, оболочка под его ролью.");
        map("the navigation item for route \"x\" is visible",
                "Проверяем видимость пункта меню для {arg}.",
                "Пункт навигации на {arg} виден.");
        map("the navigation item for route \"x\" is hidden",
                "Проверяем скрытие пункта меню для {arg}.",
                "Пункт навигации на {arg} скрыт (нет прав на ресурс).");
        map("I open the deep-link \"x\"",
                "Открываем диплинк на запрещённый маршрут {arg}.",
                "Выполняется переход/редирект по маршруту {arg}.");
        map("I am shown the forbidden page",
                "Проверяем страницу запрета доступа.",
                "Открыта страница /403 с заголовком «Нет доступа/Brak dostępu».");
        map("I click Go Back on the forbidden page",
                "Нажимаем «Вернуться назад» на странице /403.",
                "Клик по кнопке возврата выполнен.");
        map("I am taken away from the forbidden page",
                "Проверяем уход со страницы /403.",
                "Пользователь покинул маршрут /403.");
        map("I open the dictionary page \"x\"",
                "Открываем страницу справочника {arg}.",
                "Страница справочника {arg} загрузилась.");
        map("the dictionary page loads and its table renders with columns",
                "Проверяем загрузку справочника и таблицы.",
                "Страница загружена, таблица отрисована с ожидаемыми колонками.");
        map("I open the Work Catalog page",
                "Открываем «Каталог работ».",
                "Загрузилась страница каталога работ.");
        map("the work catalog list renders",
                "Проверяем отрисовку каталога работ.",
                "Список позиций каталога работ отображается.");
        map("I create a work item selecting a category and a unit",
                "Создаём позицию каталога, выбирая категорию и единицу через reference-селекты.",
                "Позиция создана с выбранными связями.");
        map("the created work item appears in the work catalog list",
                "Ищем созданную позицию в каталоге.",
                "Созданная позиция присутствует в списке.");
        map("two work items in two different categories exist",
                "Готовим две позиции в разных категориях.",
                "Созданы две позиции в двух разных категориях.");
        map("applying a single-value category filter narrows the list",
                "Применяем фильтр по одной категории.",
                "Список сузился до позиций выбранной категории.");
        map("a current work price exists",
                "Готовим текущую цену работы (validTo = null).",
                "Существует актуальная цена работы.");
        map("I open the Work Prices page",
                "Открываем «Цены работ».",
                "Загрузилась страница цен работ.");
        map("the work prices list renders and a current price is shown",
                "Проверяем список цен и наличие текущей цены.",
                "Список цен отображается, показана текущая цена.");
        map("I open the Projects page",
                "Открываем «Проекты».",
                "Загрузилась страница проектов.");
        map("the projects list renders",
                "Проверяем отрисовку списка проектов.",
                "Список проектов отображается.");
        map("I create a project via the happy path",
                "Создаём проект по основному сценарию.",
                "Проект создан.");
        map("the created project appears in the projects list",
                "Ищем созданный проект в списке.",
                "Созданный проект присутствует в списке.");
        map("the Catalog navigation group items route correctly",
                "Проверяем маршрутизацию пунктов группы «Каталог».",
                "Пункты группы «Каталог» ведут на корректные страницы.");
        map("the Dictionaries navigation group items route correctly",
                "Проверяем маршрутизацию пунктов группы «Справочники».",
                "Пункты группы «Справочники» ведут на корректные страницы.");
    }

    private StepNarrator() {
    }

    /**
     * Register a description + expected pair. The {@code rawKey} may itself contain quoted arguments
     * (any placeholder value works, e.g. {@code "x"}); it is normalized the same way runtime step
     * text is, so the table key ends up as {@code ... {arg} ...}.
     */
    private static void map(String rawKey, String description, String expected) {
        String key = normalize(rawKey);
        DESCRIPTIONS.put(key, description);
        EXPECTED.put(key, expected);
    }

    /** A plain-language Russian description of what the step does, argument-aware. */
    public static String describe(String keyword, String text) {
        String key = normalize(text);
        List<String> args = extractArgs(text);
        String mapped = DESCRIPTIONS.get(key);
        if (mapped != null) {
            return substitute(mapped, args);
        }
        return fallbackDescription(text);
    }

    /** A concrete Russian expected result for the step, argument-aware. */
    public static String expected(String keyword, String text) {
        String key = normalize(text);
        List<String> args = extractArgs(text);
        String mapped = EXPECTED.get(key);
        if (mapped != null) {
            return substitute(mapped, args);
        }
        return fallbackExpected(keyword);
    }

    // ---- normalization / substitution ----

    /**
     * Normalize step text for table lookup: trim, replace each quoted argument with a positional
     * placeholder ({@code {arg}}, {@code {arg2}}, …), collapse whitespace, and lower-case.
     */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String replaced = replaceArgsWithPlaceholders(text.trim());
        return replaced.replaceAll("\\s+", " ").trim().toLowerCase();
    }

    /** Replace quoted arguments with {@code {arg}}/{@code {arg2}}/… placeholders (order preserved). */
    private static String replaceArgsWithPlaceholders(String text) {
        Matcher m = QUOTED.matcher(text);
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        while (m.find()) {
            idx++;
            String token = idx == 1 ? "{arg}" : "{arg" + idx + "}";
            m.appendReplacement(sb, Matcher.quoteReplacement(token));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Extract the quoted arguments from the raw step text, in order. */
    private static List<String> extractArgs(String text) {
        List<String> args = new ArrayList<>();
        if (text == null) {
            return args;
        }
        Matcher m = QUOTED.matcher(text);
        while (m.find()) {
            args.add(m.group(1));
        }
        return args;
    }

    /**
     * Substitute extracted arguments back into a mapped sentence. {@code {arg}} maps to the first
     * argument, {@code {arg2}} to the second, etc. Missing arguments leave the placeholder untouched.
     */
    private static String substitute(String sentence, List<String> args) {
        if (args.isEmpty() || !sentence.contains("{arg")) {
            return sentence;
        }
        Matcher m = PLACEHOLDER.matcher(sentence);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String digits = m.group(1);
            int index = digits.isEmpty() ? 0 : Integer.parseInt(digits) - 1;
            String replacement = index >= 0 && index < args.size() ? args.get(index) : m.group();
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ---- fallbacks ----

    /** Humanize raw step text: drop a leading "I ", capitalize the first letter. */
    private static String fallbackDescription(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String t = text.trim();
        if (t.startsWith("I ")) {
            t = t.substring(2).trim();
        }
        if (t.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    /** Keyword-based generic expected result when a step is not in the table. */
    private static String fallbackExpected(String keyword) {
        String k = keyword == null ? "" : keyword.trim().toLowerCase();
        return switch (k) {
            case "then", "and", "but" -> "Условие выполнено.";
            case "when" -> "Действие выполнено успешно.";
            case "given" -> "Предусловие установлено.";
            default -> "Шаг выполнен без ошибок.";
        };
    }
}
