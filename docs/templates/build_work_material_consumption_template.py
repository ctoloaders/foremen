import csv, os
from collections import Counter
import xlsxwriter
from xlsxwriter.utility import xl_range_abs

D = '/tmp/wmc/'
rd = lambda f: list(csv.DictReader(open(D + f, encoding='utf-8')))
cats = [c for c in rd('cats.csv') if c['active'] == 't']
works = [w for w in rd('works.csv') if w['active'] == 't']
ct = [x for x in rd('ct.csv') if x['active'] == 't']
ft = [x for x in rd('ft.csv') if x['active'] == 't']
catd = {c['code']: c for c in cats}
one = lambda s: ' '.join(s.split())

OUT = '/Users/alexander.borohov/projects/loaders/foremen/docs/templates/work-material-consumption-template.xlsx'
os.makedirs(os.path.dirname(OUT), exist_ok=True)
wb = xlsxwriter.Workbook(OUT)
S = 'Нормы расхода'; L = 'Справочники'; H = 'Инструкция'
ws_i = wb.add_worksheet(H); ws = wb.add_worksheet(S); ls = wb.add_worksheet(L)
ws.activate(); ws.set_first_sheet()


def F(**k):
    return wb.add_format({'font_name': 'Arial', 'font_size': 10, 'valign': 'vcenter', **k})


hdr = F(bold=True, bg_color='#1F4E78', font_color='white', border=1, text_wrap=True, align='center')
hdr_in = F(bold=True, bg_color='#C55A11', font_color='white', border=1, text_wrap=True, align='center')
hdr_t = F(bold=True, bg_color='#7F7F7F', font_color='white', border=1, text_wrap=True, align='center')
act = F(font_color='#0563C1', border=1, align='center')
bgs = ['#DDEBF7', '#FFFFFF']
inp = F(bg_color='#FFF2CC', border=1, text_wrap=True)
inpn = F(bg_color='#FFF2CC', border=1, num_format='0.0000')
inpw = F(bg_color='#FFF2CC', border=1, num_format='0.##', align='center')
tech = F(bg_color='#EDEDED', border=1, font_color='#595959')

# ---- Справочники
lh = F(bold=True, bg_color='#1F4E78', font_color='white', border=1, text_wrap=True)
cell = F(border=1)
basis = [('На единицу работы', 'PER_UNIT'), ('На помещение (1 раз)', 'PER_ROOM')]
units = [('м²', 'm2'), ('м³', 'm3'), ('пог. м', 'mb'), ('м', 'm'), ('шт.', 'szt'), ('компл.', 'kpl'),
         ('килограмм', 'kg'), ('литр', 'l')]
waste = [0, 2, 3, 5, 7, 10, 12, 15, 20]
COPY = '⧉ Копировать'


def mk(items, prefix, branch):
    cnt = Counter(one(x['name_ru']) for x in items)
    return sorted((prefix + one(x['name_ru']) + (' [%s]' % x['code'] if cnt[one(x['name_ru'])] > 1 else ''),
                   x['code'], branch) for x in items)


types = mk(ct, '[Строит.] ', 'construction') + mk(ft, '[Отделка] ', 'finishing')
blocks = [
    (0, ['Основа расчёта', 'Код основы'], basis, ['Basis_List', 'Basis_Codes']),
    (3, ['Ед. изм. материала', 'Код ед.'], units, ['Units_List', 'Unit_Codes']),
    (6, ['Процент отходов, %'], [(w,) for w in waste], ['Waste_List']),
    (8, ['Тип материала', 'Код типа', 'Код ветки'], types, ['MatTypes_All', 'MatTypes_All_Codes', 'MatTypes_All_Branch']),
    (12, ['Действие'], [(COPY,)], ['Copy_Action']),
]
REF = {}
ls.freeze_panes(1, 0)
for col, titles, rows, names in blocks:
    for j, t in enumerate(titles):
        ls.write(0, col + j, t, lh)
        ls.set_column(col + j, col + j, 40 if j == 0 else 22)
        a_ = xl_range_abs(1, col + j, len(rows), col + j)
        REF[names[j]] = "'%s'!%s" % (L, a_)
        wb.define_name(names[j], "=" + REF[names[j]])
    for i, r in enumerate(rows, 1):
        for j, v in enumerate(r):
            ls.write(i, col + j, v, cell)
for c in (2, 5, 7, 11):
    ls.set_column(c, c, 3)

# ---- Основной лист
cols = [('Копировать\nряд', 14, hdr), ('Код группы', 18, hdr), ('Группа работ', 30, hdr), ('Код работы', 9, hdr),
        ('Вид работы', 52, hdr), ('Ед. изм. работы', 9, hdr),
        ('Тип материала\n(выбор)', 38, hdr_in),
        ('Ед. изм. материала\n(выбор)', 13, hdr_in), ('Норма расхода\n(на 1 ед. работы)', 14, hdr_in),
        ('Процент отходов, %\n(выбор)', 12, hdr_in), ('Основа расчёта\n(выбор)', 20, hdr_in),
        ('Обоснование / комментарий', 30, hdr_in),
        ('branch', 13, hdr_t), ('material_type_code', 24, hdr_t), ('material_unit_code', 12, hdr_t),
        ('consumption_basis', 16, hdr_t), ('source_type', 11, hdr_t), ('source_doc', 22, hdr_t),
        ('source_ref', 11, hdr_t)]
ws.set_row(0, 42)
for i, (t, w, f) in enumerate(cols):
    ws.write(0, i, t, f)
    ws.set_column(i, i, w)
ws.freeze_panes(1, 5)
first = 1
for n, w in enumerate(works):
    r = first + n
    xr = r + 1
    c = catd[w['cat_code']]
    bg = bgs[int(c['id']) % 2]
    ro = F(bg_color=bg, border=1, text_wrap=True)
    roc = F(bg_color=bg, border=1, text_wrap=True, align='center')
    ws.write_blank(r, 0, None, act)
    ws.write(r, 1, c['code'], ro)
    ws.write(r, 2, one(c['name_ru']), ro)
    ws.write_string(r, 3, w['code'], roc)
    ws.write(r, 4, one(w['name_ru']), ro)
    ws.write(r, 5, w['unit_ru'], roc)
    for j in (6, 7, 10, 11):
        ws.write_blank(r, j, None, inp)
    ws.write_blank(r, 8, None, inpn)
    ws.write_blank(r, 9, None, inpw)
    ws.write_formula(r, 12, '=IFERROR(INDEX(%s,MATCH($G%d,%s,0)),"")' % (REF['MatTypes_All_Branch'], xr, REF['MatTypes_All']), tech)
    ws.write_formula(r, 13, '=IFERROR(INDEX(%s,MATCH($G%d,%s,0)),"")' % (REF['MatTypes_All_Codes'], xr, REF['MatTypes_All']), tech)
    ws.write_formula(r, 14, '=IFERROR(INDEX(%s,MATCH($H%d,%s,0)),"")' % (REF['Unit_Codes'], xr, REF['Units_List']), tech)
    ws.write_formula(r, 15, '=IF($G{x}="","",IFERROR(INDEX({c},MATCH($K{x},{l},0)),"PER_UNIT"))'.format(
        x=xr, c=REF['Basis_Codes'], l=REF['Basis_List']), tech)
    ws.write_formula(r, 16, '=IF($G%d="","","client")' % xr, tech)
    ws.write_formula(r, 17, '=IF($G%d="","","Client norms template")' % xr, tech)
    ws.write_formula(r, 18, '=IF($G{x}="","",$D{x})'.format(x=xr), tech)
last = first + len(works) - 1
rng = lambda c: '%s%d:%s%d' % (c, first + 1, c, last + 1)
ws.data_validation(rng('A'), {'validate': 'list', 'source': '=' + REF['Copy_Action'], 'error_type': 'warning'})
ws.data_validation(rng('G'), {'validate': 'list', 'source': '=' + REF['MatTypes_All'], 'error_message': 'Выберите тип материала из списка.'})
ws.data_validation(rng('H'), {'validate': 'list', 'source': '=' + REF['Units_List'], 'error_message': 'Выберите единицу из списка.'})
ws.data_validation(rng('I'), {'validate': 'decimal', 'criteria': 'between', 'minimum': 0, 'maximum': 99999999.9999,
                              'error_title': 'Норма расхода', 'error_message': 'Число от 0 до 99999999.9999.'})
ws.data_validation(rng('J'), {'validate': 'list', 'source': '=' + REF['Waste_List'], 'error_type': 'warning',
                              'error_message': 'Значения нет в списке. Допустимо число 0-999.99. Оставить?'})
ws.data_validation(rng('K'), {'validate': 'list', 'source': '=' + REF['Basis_List'], 'error_message': 'Выберите значение из списка.'})
ws.autofilter(0, 0, last, len(cols) - 1)

# ---- Инструкция
ws_i.set_column(0, 0, 120)
t = wb.add_format({'bold': True, 'font_size': 14, 'font_name': 'Arial'})
p = F(text_wrap=True)
lines = [
    ('Шаблон норм расхода материалов по видам работ', t),
    ('Строки предзаполнены: группа работ, код и название вида работ, единица работы (из справочника системы). Колонки A-F не меняйте.', p),
    ('Заполняйте жёлтые ячейки в колонках G-L: тип материала → ед. изм. → норма на 1 единицу работы → процент отходов.', p),
    ('• Тип материала: единый список, префикс показывает ветку: [Строит.] строительные, [Отделка] отделочные. Ветка определяется автоматически.', p),
    ('• Норма расхода: количество материала (в выбранной единице) на 1 единицу работы из колонки F. Число ≥ 0, до 4 знаков после запятой.', p),
    ('• Процент отходов: 5 означает 5 %. Пусто = 0.', p),
    ('• Основа расчёта: «На единицу работы» (норма × объём) или «На помещение (1 раз)». Пусто = «На единицу работы».', p),
    ('• Если работа расходует несколько материалов, выберите «⧉ Копировать» в колонке A её строки: ниже появится копия с той же работой, остальные поля пустые (нужен скрипт google-sheets-copy-row.gs).', p),
    ('• Если материалы для работы не нужны, оставьте строку пустой.', p),
    ('Серые колонки M-S (формулы) содержат технические коды для импорта. Не редактируйте их. Лист «Справочники» содержит списки и именованные диапазоны.', p),
]
for i, (s, f) in enumerate(lines):
    ws_i.write(i, 0, s, f)
    ws_i.set_row(i, 32 if i else 24)
wb.close()
print(len(works), 'rows', len(types), 'types')
