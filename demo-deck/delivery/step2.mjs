// Шаг 2: согласование с клиентом (send -> accept клиентом)
import { ok, login, day } from './lib.mjs';
import fs from 'node:fs';
const st = JSON.parse(fs.readFileSync(new URL('./state.json', import.meta.url)));
const mgr = await login('cto@loaders.dev');
const client = await login('oleg@loaders.dev');
const { pos } = st;
const toSend = ['tiles', 'floor', 'wc', 'wall', 'bath', 'tapStart', 'basin', 'tapBath', 'logis'];
await ok('POST', '/api/position-approvals/staff/send', mgr, {
  positionIds: toSend.map((k) => pos[k]), decisionDeadline: day(3),
  managerNote: 'Prosimy o decyzję do piątku — musimy zdążyć z zamówieniem przed montażem.',
});
const accept = ['tiles', 'floor', 'wc', 'wall', 'bath', 'tapStart', 'basin'];
for (const k of accept) {
  const rounds = await ok('GET', `/api/position-approvals/staff/positions/${pos[k]}/rounds`, mgr);
  const r = rounds[0];
  const o = r.options[0];
  await ok('POST', `/api/position-approvals/client/rounds/${r.roundId}/accept`, client, {
    optionId: o.optionId, expectedUnitSalePrice: o.unitSalePrice, comment: k === 'floor' ? 'OK, potwierdzam.' : null,
  });
  console.log('accepted', k);
}
const rows = await ok('GET', `/api/purchase-positions/planner?projectId=${st.PID ?? 2}&size=50`, mgr);
console.log((rows.content ?? rows).map((r) => `${r.id}:${r.status}`).join(' '));
