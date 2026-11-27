// Шаг 1: окружение (профиль компании, банк, продавцы, проект) + позиции закупок проекта "Jana Kazimierza 64a"
import { psql, ok, http, login, day } from './lib.mjs';
import fs from 'node:fs';

const admin = await login('cto+1@loaders.dev');
const mgr = await login('cto@loaders.dev');
const client = await login('oleg@loaders.dev');
const PID = 2;

// проект: активный, старт в прошлом
psql(`UPDATE projects SET status='ACTIVE', start_date = CURRENT_DATE - 21 WHERE id=${PID};`);

// профиль компании (нужен для PDF проформы)
await ok('PUT', '/api/company-profile', admin, {
  fullName: 'Foremen Remonty Sp. z o.o.',
  registeredAddress: 'ul. Puławska 182, 02-670 Warszawa',
  nip: '5252248481', regon: '146254897', representativeName: 'Robert Jankowski', representativeRole: 'Prezes Zarządu',
  email: 'biuro@foremen.eu', bankAccount: 'PL61 1090 1014 0000 0712 1981 2874',
});

// продавцы: реальные названия + NIP
const sellers = {
  leroy: ['Leroy Merlin Polska Sp. z o.o.', '5260210219'],
  castorama: ['Castorama Polska Sp. z o.o.', '5272223611'],
  tubadzin: ['Tubądzin Showroom Warszawa', '6340134203'],
};
psql(`UPDATE material_sellers SET name_pl='Leroy Merlin Polska Sp. z o.o.', name_ru='Leroy Merlin Polska Sp. z o.o.', nip='5260210219' WHERE code='leroy_merlin';
      UPDATE material_sellers SET name_pl='Castorama Polska Sp. z o.o.', name_ru='Castorama Polska Sp. z o.o.', nip='5272223611' WHERE code='castorama';`);
const sel = JSON.parse(psql(`select json_object_agg(code,id) from material_sellers where code in ('leroy_merlin','castorama')`));

// НДС
const vats = (await ok('GET', '/api/vat-rates?size=50', admin)).content;
const vat8 = vats.find((v) => Number(v.rate) === 8).id;
const vat23 = vats.find((v) => Number(v.rate) === 23).id;

// позиции: [ключ, materialId, qty, закупка нетто, продажа нетто, needBy, путь]
const specs = [
  ['tiles',   548, 28, 139.0, 168.0, 9],
  ['floor',   20,  38, 118.0, 149.0, 14],
  ['wc',      139, 2,  612.0, 790.0, 12],
  ['wall',    487, 16, 98.0,  129.0, -3],
  ['bath',    64,  1,  1480.0, 1890.0, 20],
  ['tapStart',191, 2,  215.0, 289.0, 20],
  ['basin',   253, 2,  389.0, 510.0, 25],
  ['tapBath', 152, 1,  540.0, 699.0, 25],
  ['logis',   162, 1,  1090.0, 1490.0, 28],
  ['door',    296, 4,  890.0, 1150.0, 35],
];
const pos = {};
for (const [key, mat, qty, buy, sale, need] of specs) {
  const p = await ok('POST', '/api/purchase-positions', mgr, {
    projectId: PID, kind: 'FINISHING', sourcingPath: 'AGREED', plannedMaterialId: mat, quantity: qty,
    plannedPurchasePrice: buy, salePrice: sale, saleVatRateId: vat8, purchaseVatRateId: vat23,
    needByDate: day(need), orderByDate: day(need - 10), notes: null,
  });
  pos[key] = p.id;
  console.log('position', key, p.id, p.status);
}
// клиент покупает сам
const own = await ok('POST', '/api/purchase-positions', mgr, {
  projectId: PID, kind: 'FINISHING', sourcingPath: 'CLIENT_OWN', plannedMaterialId: 298, quantity: 4,
  needByDate: day(7), notes: 'Klient kupuje sam — dostawa bezpośrednio na budowę',
});
pos.own = own.id;
console.log('own', own.id, own.status);
psql(`UPDATE purchase_positions SET unit_id=3 WHERE project_id=2 AND planned_material_id IN (139,191,152,253,64,162,296,298)`);
fs.writeFileSync(new URL('./state.json', import.meta.url), JSON.stringify({ pos, sel, vat8, vat23 }, null, 2));
