/**
 * Копирование ряда в таблице норм расхода (Google Таблицы).
 * Установка: Расширения → Apps Script → вставить код → Сохранить. Авторизация не требуется (простой триггер onEdit).
 * Использование: в колонке A выбрать «⧉ Копировать». Под строкой появится копия
 * (заполнены только колонки работы A-F), нижние строки сдвигаются вниз.
 */
function onEdit(e) {
  var SHEET = 'Нормы расхода';
  var ACTION = '⧉ Копировать';
  var FIRST_INPUT_COL = 7;  // G
  var LAST_INPUT_COL = 12;  // L
  var range = e.range;
  var sheet = range.getSheet();
  if (sheet.getName() !== SHEET || range.getColumn() !== 1 || range.getNum  if (sheet.getName() !== SHEET || range.getColumn() !== 1 || range.getNum  if (sheet.getName() !== SHEET || range.getColumn() !== 1 || raastCol  if (sheet.getName() !== SHEET || range.getColumn() !== 1 || range.getNum  if (sheet.getName() !==  1  if (sheet.getName() !== SHEET || range.getColumn() !== 1 || range.getNum  if (sheet.getName() !== SHEET || range.getColumn() !== 1 || range.getNum  if (sheet.getName() !== SHEET || range.getColuheet.setCurrentCell(sheet.getRange(r + 1, FIRST_INPUT_COL));
}
