import { ok, login, day } from './lib.mjs';
import fs from 'node:fs';
const st = JSON.parse(fs.readFileSync(new URL('./state.json', import.meta.url)));
const buyer = await login('buyer@loaders.dev');
const s = await ok('POST', '/api/shipments', buyer, {
  projectId: 2, externalOrderId: st.eo2, deliveryMethod: 'CARRIER', carrierCode: 'GLS', trackingNumber: 'PL9021188740',
  etaDate: day(8), etaTimeFrom: '08:00:00', etaTimeTo: '12:00:00', note: 'Podłoga, 38 m² — 4 palety, potrzebny wózek paletowy',
  lines: [{ positionId: st.pos.floor, quantity: 38 }],
});
st.sFloor = s.id;
fs.writeFileSync(new URL('./state.json', import.meta.url), JSON.stringify(st, null, 2));
console.log('floor shipment', s.id);
