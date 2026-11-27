// Шаг 4: внешние заказы EO1 (оплачен) и EO2 (ждёт оплаты, оплатит финансист в UI); отправки EO1; приёмка "wall"; CLIENT_OWN в доставку
import { ok, http, login, psql, day } from './lib.mjs';
import { randomUUID } from 'node:crypto';
import fs from 'node:fs';
const st = JSON.parse(fs.readFileSync(new URL('./state.json', import.meta.url)));
const buyer = await login('buyer@loaders.dev');
const fin = await login('piotr@loaders.dev');
const fore = await login('tomasz@loaders.dev');
const mgr = await login('cto@loaders.dev');
const { pos, sel, vat23 } = st;

const line = (key, qty, price) => ({ positionId: pos[key], quantity: qty, unitPurchaseNet: price, purchaseVatRateId: vat23, aboveCeilingAcknowledged: false });
const eo1 = await ok('POST', '/api/external-orders', buyer, {
  projectId: 2, sellerId: sel.leroy_merlin,
  header: { referenceNumber: 'LM-2026-118437', orderLink: 'https://www.leroymerlin.pl/zamowienie/LM-2026-118437', deliveryCostNet: 180, deliveryVatRateId: vat23, expectedShipDate: day(-5), expectedDeliveryDate: day(0), note: 'Płytki + WC + płytki ścienne, dostawa na obiekt' },
  lines: [line('tiles', 28, 139), line('wc', 2, 612), line('wall', 16, 98)],
});
const eo2 = await ok('POST', '/api/external-orders', buyer, {
  projectId: 2, sellerId: sel.castorama,
  header: { referenceNumber: 'CAS-W-77120', orderLink: 'https://www.castorama.pl/zamowienie/CAS-W-77120', deliveryCostNet: 120, deliveryVatRateId: vat23, expectedShipDate: day(3), expectedDeliveryDate: day(8), note: 'Podłoga Dąb Bright 14 mm' },
  lines: [line('floor', 38, 118)],
});
for (const e of [eo1, eo2]) console.log('send', e.id, (await http('POST', `/api/external-orders/${e.id}/send`, buyer)).status);
console.log('mark-paid EO1', (await http('POST', `/api/external-orders/${eo1.id}/mark-paid`, fin, { paidDate: day(-4), note: 'Przelew mBank, potwierdzenie w załączniku' })).status);

const ship = (body) => ok('POST', '/api/shipments', buyer, { projectId: 2, deliveryMethod: 'CARRIER', carrierCode: 'DPD', ...body });
const sWall = await ship({ externalOrderId: eo1.id, trackingNumber: '1000345190721', etaDate: day(-2), lines: [{ positionId: pos.wall, quantity: 16 }] });
const sTiles = await ship({ externalOrderId: eo1.id, trackingNumber: '1000345190733', etaDate: day(0), etaTimeFrom: '09:00:00', etaTimeTo: '13:00:00', lines: [{ positionId: pos.tiles, quantity: 28 }] });
const sWc = await ship({ externalOrderId: eo1.id, deliveryMethod: 'SHOP_DELIVERY', carrierCode: null, shopOrderLink: 'https://www.leroymerlin.pl/zamowienie/LM-2026-118437', driverName: 'Pan Andrzej (Leroy Merlin Targówek)', driverPhone: '+48 502 118 604', etaDate: day(0), etaTimeFrom: '14:00:00', etaTimeTo: '17:00:00', lines: [{ positionId: pos.wc, quantity: 2 }] });
const rc = await http('POST', '/api/goods-receipts', fore, { events: [{ positionId: pos.wall, shipmentId: sWall.id, kind: 'ACCEPTED', quantity: 16, comment: 'Wszystko OK, 16 szt. w kartonach', clientRequestId: randomUUID() }] });
console.log('receipt wall', rc.status);
console.log('own -> IN_DELIVERY', (await http('POST', `/api/purchase-positions/${pos.own}/transition`, mgr, { target: 'IN_DELIVERY' })).status);
Object.assign(st, { eo1: eo1.id, eo2: eo2.id, sWall: sWall.id, sTiles: sTiles.id, sWc: sWc.id });
fs.writeFileSync(new URL('./state.json', import.meta.url), JSON.stringify(st, null, 2));
console.log(psql(`select id,status from purchase_positions where project_id=2 order by id`));
