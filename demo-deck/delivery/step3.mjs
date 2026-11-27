// Шаг 3: проформы (реальная генерация PDF). P1 выставлена и оплачена, P2 выставлена, платёж пришёл в банк, но не сопоставлен.
import { ok, http, login, psql, day } from './lib.mjs';
import fs from 'node:fs';
const st = JSON.parse(fs.readFileSync(new URL('./state.json', import.meta.url)));
const mgr = await login('cto@loaders.dev');
const fin = await login('piotr@loaders.dev');
const { pos } = st;

async function issue(ids, note) {
  const d = await ok('POST', '/api/client-proformas', mgr, { projectId: 2, positionIds: ids, dueDate: day(5), note });
  const r = await http('POST', `/api/client-proformas/${d.id}/issue`, mgr, { language: 'pl' });
  if (r.status !== 200) throw new Error(`issue ${d.id} -> ${r.status} ${r.text.slice(0, 200)}`);
  return ok('GET', `/api/client-proformas/${d.id}`, mgr);
}

const p1 = await issue([pos.tiles, pos.floor, pos.wc, pos.wall], 'Materiały wykończeniowe — łazienka i korytarz, etap 1');
const p2 = await issue([pos.bath, pos.tapStart], 'Armatura i wanna, etap 2');
console.log('P1', p1.id, p1.number, p1.status, p1.totalGross, 'hasPdf', p1.hasPdf);
console.log('P2', p2.id, p2.number, p2.status, p2.totalGross, 'hasPdf', p2.hasPdf);

const accId = Number(psql(`SELECT id FROM bank_accounts WHERE external_id='demo-mbank-pln'`));
let seq = 0;
const txn = (amount, title, cp) => Number(psql(
  `INSERT INTO bank_transactions (account_id, channel, origin, status, amount, currency, operation_date, booking_date, title, external_id, counterparty_name, created_by)
   VALUES (${accId}, 'BANK', 'BANK_FEED', 'POSTED', ${amount}, 'PLN', CURRENT_DATE, CURRENT_DATE, :'t', :'e', :'c', 'demo') RETURNING id;`,
  { t: title, e: `demo-${Date.now()}-${++seq}`, c: cp }).split('\n')[0]);

const t1 = txn(p1.totalGross, `Zapłata za proformę ${p1.number} — Goncharenko`, 'OLEG GONCHARENKO');
const l = await http('POST', `/api/bank/transactions/${t1}/links`, fin, { kind: 'PROFORMA_PAYMENT', amount: p1.totalGross, clientProformaId: p1.id });
console.log('pay link', l.status);
const t2 = txn(p2.totalGross, `Przelew za materiały wykończeniowe — Goncharenko`, 'OLEG GONCHARENKO');
const d = await http('POST', '/api/bank/transactions/detect', fin, { ids: [t2] });
console.log('detect', d.status);
Object.assign(st, { p1, p2, accId, t2 });
fs.writeFileSync(new URL('./state.json', import.meta.url), JSON.stringify(st, null, 2));
console.log(psql(`select id,status from purchase_positions where project_id=2 order by id`));
