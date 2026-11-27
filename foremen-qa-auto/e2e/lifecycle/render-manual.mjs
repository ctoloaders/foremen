// Строит инструкцию для пользователя на русском языке из результатов прогона.
// Запуск: node lifecycle/render-manual.mjs <папка_прогона>
// Пример: node lifecycle/render-manual.mjs lifecycle/reports/run-ru-01
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { basename, join, resolve } from 'node:path';
import { STEPS_RU, PHASES_RU, ROLES_RU, ROLE_ORDER, ROLE_PURPOSE_RU } from './manual-ru.mjs';

const runDir = resolve(process.argv[2] ?? '');
if (!existsSync(join(runDir, 'results.json'))) {
  console.error(`Не найден results.json в ${runDir}`);
  process.exit(1);
}
const steps = JSON.parse(readFileSync(join(runDir, 'results.json'), 'utf8'));
const runId = basename(runDir);

const STATUS = {
  PASS: '✅ Выполнено',
  WORKAROUND: '⚠️ Выполнено обходным путём',
  FAIL: '❌ Не выполнено',
  BLOCKED: '⏸ Не запущен',
  NOT_RUN: '⏸ Не запущен',
};
const count = (s) => steps.filter((r) => r.status === s).length;
const missing = steps.filter((r) => !STEPS_RU[r.id]).map((r) => r.id);
if (missing.length) console.warn(`Нет русского текста для шагов: ${missing.join(', ')}`);

const tr = (r) => STEPS_RU[r.id] ?? { t: r.title, h: r.what, e: r.expected };
const modeText = (m) => (m === 'UI' ? 'в браузере, на экранах системы' : 'запросом к системе от имени роли (API)');

const L = [];
const add = (...xs) => L.push(...xs, '');

add('# Инструкция: проверка жизненного цикла проекта в Foremen', '');
add(`Прогон: \`${runId}\`. Язык интерфейса: русский. Дата формирования: ${new Date().toLocaleDateString('ru-RU')}.`);

add('## 1. Что проверяет эта инструкция', '');
add(
  'Один проект проходит весь путь: от создания до завершения. По шагам видно, какие действия выполняет каждая роль,',
  'что должно получиться и что система сделала на самом деле. Шаги идут в порядке, в каком их нужно выполнять.',
  'Если шаг не выполнен, все шаги, которые на него опираются, не запускаются, и они отмечены как «не запущен».',
);

add('## 2. Итог прогона', '');
add('| Показатель | Количество |', '|---|---|');
add(`| Всего шагов | ${steps.length} |`);
add(`| Выполнено | ${count('PASS')} |`);
add(`| Выполнено обходным путём | ${count('WORKAROUND')} |`);
add(`| Не выполнено | ${count('FAIL')} |`);
add(`| Не запущено (зависит от невыполненного шага) | ${count('BLOCKED') + count('NOT_RUN')} |`, '');
const verdict =
  count('FAIL') === 0 && count('BLOCKED') + count('NOT_RUN') === 0
    ? 'Весь жизненный цикл проекта проходит без ошибок.'
    : `Есть невыполненные шаги: ${count('FAIL')}. Их описание — в разделе 6.`;
add(`**Вывод:** ${verdict}`, '');

add('## 3. Перед началом', '');
add(
  '- Запустите систему: в корне репозитория выполните `docker compose up`. Интерфейс откроется на `http://localhost:3000`.',
  '- Интерфейс должен быть на русском. Переключите язык кнопкой «RU» в верхней панели. Прогон делает это сам.',
  '- Тестовые пользователи (менеджер, сметчик, продавец, прораб, рабочий, финансист, закупщик, клиент) создаются автоматически. Пароли генерируются при каждом прогоне.',
  '- Клиент пароля не имеет. Он входит по коду, который приходит на почту. В тестовом стенде код читается из базы данных.',
  '- Шаги помечены как «в браузере» или «запросом к системе». Запросы выполняются от имени роли, которой принадлежит шаг.',
  '',
);

add('## 4. Кто что делает', '');
add('| Роль | Задача в проекте |', '|---|---|');
for (const role of ROLE_ORDER) {
  const n = steps.filter((r) => r.role === role).length;
  if (n) add(`| ${ROLES_RU[role]} | ${ROLE_PURPOSE_RU[role]} (шагов: ${n}) |`);
}
add('');

add('## 5. Шаги по порядку', '');
let n = 0;
let lastPhase = null;
for (const r of steps) {
  if (r.phase !== lastPhase) {
    lastPhase = r.phase;
    add(`### ${PHASES_RU[r.phase] ?? r.phase}`, '');
  }
  n += 1;
  const s = tr(r);
  add(`#### Шаг ${n}. ${s.t}`, '');
  add(`- **Кто выполняет:** ${ROLES_RU[r.role] ?? r.role} (код шага ${r.id})`);
  add(`- **Как:** ${modeText(r.mode)}`);
  add(`- **Что сделать:** ${s.h}`);
  add(`- **Что должно получиться:** ${s.e}`);
  add(`- **Результат прогона:** ${STATUS[r.status] ?? r.status}`);
  if (r.status === 'FAIL') {
    add(`- **Что пошло не так:** ${(r.error || r.actual || 'нет подробностей').replace(/\n/g, ' ').slice(0, 300)}`);
  }
  if (r.status === 'BLOCKED' || r.status === 'NOT_RUN') {
    add(`- **Почему не запущен:** нужен результат шага ${(r.blockedBy ?? []).join(', ') || '—'}`);
  }
  if (r.status === 'WORKAROUND') {
    add('- **Обходной путь:** шаг выполнен иначе, чем описано в спецификации. Подробности в отчёте об ошибках.');
  }
  for (const shot of r.screenshots ?? []) add(`- ![${s.t}](${shot})`);
  add('');
}

const failed = steps.filter((r) => r.status === 'FAIL' || r.status === 'BLOCKED' || r.status === 'NOT_RUN');
add('## 6. Если что-то не выполнено', '');
if (!failed.length) {
  add('Все шаги выполнены. Этот раздел пуст.', '');
} else {
  add('| Шаг | Что сделать | Статус | Что пошло не так |', '|---|---|---|---|');
  for (const r of failed) {
    const why = r.status === 'FAIL' ? (r.error || r.actual || '').replace(/\n/g, ' ').slice(0, 160) : `нужен шаг ${(r.blockedBy ?? []).join(', ')}`;
    add(`| ${r.id} | ${tr(r).t} | ${STATUS[r.status]} | ${why} |`);
  }
  add('');
}

add('## 7. Значение статусов', '');
add(
  '- ✅ **Выполнено** — система сделала то, что написано в «Что должно получиться».',
  '- ⚠️ **Выполнено обходным путём** — результат получен, но другим способом. Такого в этом прогоне быть не должно.',
  '- ❌ **Не выполнено** — система сделала не то. Проверьте раздел 6 и отчёт об ошибках.',
  '- ⏸ **Не запущен** — шаг не выполнялся, потому что предыдущий не выполнен.',
  '',
);

const out = join(runDir, 'MANUAL-RU.md');
writeFileSync(out, L.join('\n'));
console.log(`Инструкция записана: ${out} (шагов: ${steps.length}, выполнено: ${count('PASS')})`);
