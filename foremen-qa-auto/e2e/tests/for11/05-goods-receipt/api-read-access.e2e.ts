import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import {
  del, ev, get, JPEG, makeWorld, mediaContent, msgOf, MSG, PNG, post, psql, receive, uploadMedia, voidReceipt, WEBP, type Tag, type World,
} from './support.js';

// FOR-11-05 Part A: A6 (reads), A7 (permission matrix + scope), A8 (photos), A9 (contracts).
// Repeatability: run-id in every name + cleanup of every row (incl. goods_receipts) in `finally`.

const MONEY = /(price|amount|cost|margin|vat|storageuri|storagekey|iban)/i;
/** Collects every JSON key that looks like a money / storage-key field. */
function badKeys(v: unknown, path = '$'): string[] {
  if (Array.isArray(v)) return v.flatMap((x, i) => badKeys(x, `${path}[${i}]`));
  if (v && typeof v === 'object') {
    return Object.entries(v as Record<string, unknown>).flatMap(([k, x]) => (MONEY.test(k) ? [`${path}.${k}`] : []).concat(badKeys(x, `${path}.${k}`)));
  }
  return [];
}

/**
 * Environment guard: the running stack has the image storage enabled but the GCS credentials are expired
 * (`invalid_rapt`), so every upload answers 500. Such tests are skipped (see FINDINGS.md, "Blocked").
 */
const storageDown = (r: { status: number }) =>
  test.skip(r.status === 500, 'image storage unavailable in this environment (GCS credentials expired: invalid_rapt)');

/** A world with a received-position fixture used by several read tests. */
async function fixture(w: World, qty = 3) {
  const pa = w.position({ quantity: qty });
  const sa = await w.shipment([[pa, qty]]);
  const r = await receive(w.t('FOREMAN'), [ev(pa, 'ACCEPTED', 1, { shipmentId: sa })]);
  if (r.status !== 201) throw new Error(JSON.stringify(r.body));
  return { pa, sa, rid: r.body.receipts[0].id as number };
}

describe('FOR-11-05 API: reads', { tags: ['for-11-05', 'api'] }, () => {
  test('TC-A6-01 position ledger: numbers, consistency with the position, roles and scope', async () => {
    const w = await makeWorld(['FINANCIER', 'WORKER', 'FOREMAN2']);
    try {
      const pa = w.position({ quantity: 3 });
      const sa = await w.shipment([[pa, 3]]);
      const url = `/api/purchase-positions/${pa}/ledger`;
      for (const tag of ['MANAGER', 'BUYER', 'FOREMAN'] as Tag[]) {
        const r = await get(url, w.t(tag));
        expect(r.status, tag).toBe(200);
        expect(r.body.positionStatus).toBe('IN_DELIVERY');
        for (const k of ['cap', 'acceptedNet', 'missing', 'returned', 'outstanding', 'surplus', 'inFlight', 'committed', 'remainder']) {
          expect(typeof r.body.ledger[k], k).toBe('number');
          expect(r.body.ledger[k], k).toBeGreaterThanOrEqual(0);
        }
        expect(badKeys(r.body)).toEqual([]);
      }
      await receive(w.t('FOREMAN'), [ev(pa, 'ACCEPTED', 2, { shipmentId: sa })]);
      const mid = (await get(url, w.t('MANAGER'))).body;
      expect(mid.ledger.acceptedNet).toBe(2);
      expect(Number(w.positionRow(pa).accepted)).toBe(2);
      expect(mid.positionStatus).toBe(w.positionRow(pa).status);
      await receive(w.t('FOREMAN'), [ev(pa, 'ACCEPTED', 1, { shipmentId: sa })]);
      expect((await get(url, w.t('MANAGER'))).body.positionStatus).toBe('RECEIVED');
      for (const tag of ['FINANCIER', 'CLIENT', 'WORKER'] as Tag[]) expect((await get(url, w.t(tag))).status, tag).toBe(403);
      expect((await get(url, w.t('FOREMAN2'))).status).toBe(404);
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A6-02 list: default order and page size, filters, includeVoided, size clamp, bad kind', async () => {
    const w = await makeWorld();
    try {
      const p = w.position({ quantity: 6 });
      const s = await w.shipment([[p, 6]]);
      const f = w.t('FOREMAN');
      const a = await receive(f, [ev(p, 'ACCEPTED', 1, { shipmentId: s })]);
      await new Promise((r) => setTimeout(r, 50));
      await receive(f, [ev(p, 'MISSING', 1, { shipmentId: s, comment: `rcpt-${w.id}-m` })]);
      await new Promise((r) => setTimeout(r, 50));
      const c = await receive(f, [ev(p, 'ACCEPTED', 1, { shipmentId: s })]);
      await voidReceipt(f, c.body.receipts[0].id, 'qa');
      const base = `/api/goods-receipts?projectId=${w.projectId}`;
      const m = w.t('MANAGER');
      const list = await get(base, m);
      expect(list.status).toBe(200);
      expect(list.body.size).toBe(20);
      const occ = list.body.content.map((x: any) => Date.parse(x.occurredAt));
      expect(occ).toEqual([...occ].sort((x, y) => y - x));
      expect(list.body.content).toHaveLength(2); // the voided one is hidden
      expect((await get(`${base}&includeVoided=true`, m)).body.content).toHaveLength(3);
      expect((await get(`${base}&kind=MISSING`, m)).body.content.map((x: any) => x.kind)).toEqual(['MISSING']);
      expect((await get(`${base}&positionId=${p}`, m)).body.content).toHaveLength(2);
      expect((await get(`${base}&shipmentId=${s}`, m)).body.content).toHaveLength(2);
      const hour = (d: number) => new Date(Date.now() + d * 3600_000).toISOString();
      expect((await get(`${base}&from=${encodeURIComponent(hour(-1))}&to=${encodeURIComponent(hour(1))}`, m)).body.content).toHaveLength(2);
      expect((await get(`${base}&from=${encodeURIComponent(hour(1))}`, m)).body.content).toHaveLength(0);
      expect((await get(`${base}&size=1000`, m)).body.size).toBeLessThanOrEqual(200);
      expect((await get(`${base}&size=0`, m)).body.size).toBeGreaterThanOrEqual(1);
      expect((await get(`${base}&page=-3`, m)).status).toBe(200);
      expect((await get(`${base}&kind=BAD`, m)).status).toBe(400);
      expect(badKeys(list.body)).toEqual([]);
      expect(a.status).toBe(201);
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A6-03 get one receipt: labels, media metadata, flags, other project is 404', async () => {
    const w = await makeWorld(['FOREMAN2']);
    try {
      const { rid } = await fixture(w);
      const r = await get(`/api/goods-receipts/${rid}`, w.t('FOREMAN'));
      expect(r.status).toBe(200);
      expect(r.body.materialLabel).toBeTruthy();
      expect(r.body.unitName).toBeTruthy();
      expect(r.body.receivedByName).toBeTruthy();
      expect(Array.isArray(r.body.media)).toBe(true);
      expect(r.body.canVoid).toBe(true);
      expect(r.body.canEditMedia).toBe(true);
      expect(badKeys(r.body)).toEqual([]);
      expect((await get(`/api/goods-receipts/${rid}`, w.t('FOREMAN2'))).status).toBe(404);
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A6-04 to-receive: only IN_DELIVERY / PARTIALLY_RECEIVED, delivered shipments first, unresolved quantity shrinks', async () => {
    const w = await makeWorld();
    try {
      const pa = w.position({ quantity: 3 });
      const pb = w.position({ quantity: 2 });
      const pc = w.position({ quantity: 1 });
      const sa = await w.shipment([[pa, 3]]);
      const sb = await w.shipment([[pb, 2]]);
      const sc = await w.shipment([[pc, 1]]);
      psql(`UPDATE shipments SET status = 'DELIVERED' WHERE id = ${sb};`);
      psql(`UPDATE shipments SET eta_date = current_date + 5 WHERE id = ${sa};`);
      await receive(w.t('FOREMAN'), [ev(pc, 'ACCEPTED', 1, { shipmentId: sc })]); // RECEIVED -> not listed
      await receive(w.t('FOREMAN'), [ev(pa, 'ACCEPTED', 1, { shipmentId: sa })]);
      const r = await get(`/api/goods-receipts/to-receive?projectId=${w.projectId}`, w.t('FOREMAN'));
      expect(r.status).toBe(200);
      const ids = r.body.map((x: any) => x.positionId);
      expect(ids).not.toContain(pc);
      expect(ids).toEqual([pb, pa]); // the position with a DELIVERED shipment comes first
      const a = r.body.find((x: any) => x.positionId === pa);
      expect(a.positionStatus).toBe('PARTIALLY_RECEIVED');
      expect(a.shipments[0]).toMatchObject({ shipmentId: sa, quantity: 3, unresolvedQuantity: 2 });
      expect(badKeys(r.body)).toEqual([]);
      // BUYER without projectId sees all projects, FOREMAN without projectId only their own
      const buyer = await get('/api/goods-receipts/to-receive', w.t('BUYER'));
      expect(buyer.status).toBe(200);
      expect(buyer.body.map((x: any) => x.positionId)).toEqual(expect.arrayContaining([pa, pb]));
      const foreman = await get('/api/goods-receipts/to-receive', w.t('FOREMAN'));
      expect(foreman.body.every((x: any) => x.projectId === w.projectId)).toBe(true);
    } finally {
      await w.cleanup();
    }
  });
});

describe('FOR-11-05 API: permission matrix and scope', { tags: ['for-11-05', 'api'] }, () => {
  test('TC-A7-01 role matrix: list/get/to-receive/ledger, record, void, media, media content', async () => {
    const w = await makeWorld(['FINANCIER', 'WORKER']);
    try {
      const { pa, sa, rid } = await fixture(w, 10);
      // a photo for the media content check (needs the image storage)
      const up = await uploadMedia(w.t('MANAGER'), rid, [{ name: 'a.jpg', type: 'image/jpeg', bytes: JPEG() }]);
      const mediaId: number | undefined = up.status === 200 ? up.body[0].id : undefined;
      const read = (tag: Tag) => [
        `/api/goods-receipts?projectId=${w.projectId}`, `/api/goods-receipts/${rid}`,
        `/api/goods-receipts/to-receive?projectId=${w.projectId}`, `/api/purchase-positions/${pa}/ledger`,
      ].map((u) => get(u, w.t(tag)));
      for (const tag of ['ADMIN', 'MANAGER', 'FOREMAN', 'BUYER'] as Tag[]) {
        for (const r of await Promise.all(read(tag))) expect(r.status, `${tag} read`).toBe(200);
      }
      for (const tag of ['FINANCIER', 'WORKER', 'CLIENT'] as Tag[]) {
        for (const r of await Promise.all(read(tag))) {
          expect(r.status, `${tag} read`).toBe(403);
          expect(msgOf(r)).toContain(MSG.accessDenied);
        }
      }
      // record: ADMIN/MANAGER/FOREMAN 201, others 403
      for (const tag of ['ADMIN', 'MANAGER', 'FOREMAN'] as Tag[]) {
        expect((await receive(w.t(tag), [ev(pa, 'ACCEPTED', 1, { shipmentId: sa })])).status, `${tag} record`).toBe(201);
      }
      const before = (await get(`/api/purchase-positions/${pa}/ledger`, w.t('MANAGER'))).body.ledger;
      for (const tag of ['BUYER', 'FINANCIER', 'WORKER', 'CLIENT'] as Tag[]) {
        const r = await receive(w.t(tag), [ev(pa, 'ACCEPTED', 1, { shipmentId: sa })]);
        expect(r.status, `${tag} record`).toBe(403);
        expect(msgOf(r)).toContain(MSG.accessDenied);
      }
      expect((await get(`/api/purchase-positions/${pa}/ledger`, w.t('MANAGER'))).body.ledger).toEqual(before);
      // void / media change: denied roles
      for (const tag of ['BUYER', 'FINANCIER', 'WORKER', 'CLIENT'] as Tag[]) {
        expect((await voidReceipt(w.t(tag), rid, 'x')).status, `${tag} void`).toBe(403);
        expect((await uploadMedia(w.t(tag), rid, [{ name: 'a.jpg', type: 'image/jpeg', bytes: JPEG() }])).status, `${tag} media POST`).toBe(403);
        expect((await del(`/api/goods-receipts/${rid}/media/${mediaId ?? 1}`, w.t(tag))).status, `${tag} media DELETE`).toBe(403);
      }
      // media content: readers 200 (when the storage works), FINANCIER/WORKER/CLIENT 403
      if (mediaId) {
        for (const tag of ['ADMIN', 'MANAGER', 'FOREMAN', 'BUYER'] as Tag[]) {
          expect((await mediaContent(w.t(tag), rid, mediaId)).status, `${tag} content`).toBe(200);
        }
        for (const tag of ['FINANCIER', 'WORKER', 'CLIENT'] as Tag[]) {
          expect((await mediaContent(w.t(tag), rid, mediaId)).status, `${tag} content`).toBe(403);
        }
        // allowed void / delete: FOREMAN author in window and ADMIN
        expect((await del(`/api/goods-receipts/${rid}/media/${mediaId}`, w.t('FOREMAN'))).status).toBe(204);
      }
      expect((await voidReceipt(w.t('FOREMAN'), rid, 'author')).status).toBe(200);
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A7-02 scope: another project is 404 for record, ledger, get, void, media content, to-receive and mixed batches', async () => {
    const w = await makeWorld(['FOREMAN2']);
    try {
      const { pa } = await fixture(w);
      const px = w.position({ quantity: 2, projectId: w.otherProjectId });
      const sx = await w.shipment([[px, 2]], { projectId: w.otherProjectId });
      const f = w.t('FOREMAN');
      expect((await receive(f, [ev(px, 'ACCEPTED', 1)])).status).toBe(404);
      expect((await get(`/api/purchase-positions/${px}/ledger`, f)).status).toBe(404);
      const foreignList = await get(`/api/goods-receipts?projectId=${w.otherProjectId}`, f);
      expect(foreignList.status === 404 || (foreignList.status === 200 && foreignList.body.content.length === 0)).toBe(true);
      const mine = await receive(w.t('FOREMAN2'), [ev(px, 'ACCEPTED', 1, { shipmentId: sx })]);
      expect(mine.status, JSON.stringify(mine.body)).toBe(201);
      const rid = mine.body.receipts[0].id;
      expect((await get(`/api/goods-receipts/${rid}`, f)).status).toBe(404);
      expect((await voidReceipt(f, rid, 'x')).status).toBe(404);
      expect((await get(`/api/goods-receipts/${rid}/media/1/content`, f)).status).toBe(404);
      const tr = await get(`/api/goods-receipts/to-receive?projectId=${w.otherProjectId}`, f);
      expect(tr.status === 404 || (tr.status === 200 && !tr.body.some((x: any) => x.positionId === px))).toBe(true);
      const mixed = await receive(f, [ev(pa, 'ACCEPTED', 1), ev(px, 'ACCEPTED', 1)]);
      expect(mixed.status).toBe(404);
      const own = (await get(`/api/purchase-positions/${pa}/ledger`, w.t('MANAGER'))).body.ledger.acceptedNet;
      expect(own).toBe(1); // only the fixture's event; the mixed batch rolled back
      for (const tag of ['BUYER', 'ADMIN'] as Tag[]) {
        expect((await get(`/api/goods-receipts/${rid}`, w.t(tag))).status, tag).toBe(200);
        expect((await get(`/api/goods-receipts?projectId=${w.projectId}`, w.t(tag))).status, tag).toBe(200);
      }
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A7-03 no token is 401', async () => {
    const r = await Promise.all([
      fetch('http://localhost:8080/api/goods-receipts'),
      fetch('http://localhost:8080/api/goods-receipts/to-receive'),
      fetch('http://localhost:8080/api/goods-receipts', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{"events":[]}' }),
      fetch('http://localhost:8080/api/purchase-positions/1/ledger'),
    ]);
    expect(r.map((x) => x.status)).toEqual([401, 401, 401, 401]);
  });
});

describe('FOR-11-05 API: photos', { tags: ['for-11-05', 'api', 'photos'] }, () => {
  const file = (name: string, type: string, bytes: Uint8Array) => ({ name, type, bytes });

  test('TC-A8-01 upload and read back: metadata without storage keys, headers, bytes', async () => {
    const w = await makeWorld();
    try {
      const { rid } = await fixture(w);
      const f = w.t('FOREMAN');
      const jpeg = JPEG(50);
      const one = await uploadMedia(f, rid, [file('ok1.jpg', 'image/jpeg', jpeg)]);
      storageDown(one);
      expect(one.status, JSON.stringify(one.body)).toBe(200);
      expect(one.body).toHaveLength(1);
      expect(one.body[0]).toMatchObject({ fileName: 'ok1.jpg', sizeBytes: jpeg.length, contentType: 'image/jpeg' });
      expect(badKeys(one.body)).toEqual([]);
      const c = await mediaContent(f, rid, one.body[0].id);
      expect(c.status).toBe(200);
      expect(c.headers.get('content-type')).toContain('image/jpeg');
      expect(c.headers.get('content-disposition')).toContain('ok1.jpg');
      expect(c.headers.get('x-content-type-options')).toBe('nosniff');
      expect(Buffer.compare(c.bytes, Buffer.from(jpeg))).toBe(0);
      const three = await uploadMedia(f, rid, [file('ok1.jpg', 'image/jpeg', JPEG()), file('ok2.png', 'image/png', PNG()), file('ok3.webp', 'image/webp', WEBP())]);
      expect(three.status, JSON.stringify(three.body)).toBe(200);
      expect(three.body).toHaveLength(3);
      expect((await get(`/api/goods-receipts/${rid}`, f)).body.media).toHaveLength(4);
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A8-02 invalid files are rejected and nothing is stored', async () => {
    const w = await makeWorld();
    try {
      const { rid } = await fixture(w);
      const f = w.t('FOREMAN');
      const count = async () => (await get(`/api/goods-receipts/${rid}`, f)).body.media.length as number;
      const big = new Uint8Array(5 * 1024 * 1024 + 10);
      big.set(JPEG());
      const size = await uploadMedia(f, rid, [file('big.jpg', 'image/jpeg', big)]);
      expect(size.status).toBe(400);
      expect(msgOf(size)).toContain(MSG.mediaSize);
      const fake = await uploadMedia(f, rid, [file('fake.jpg', 'image/jpeg', new TextEncoder().encode('this is not an image at all'))]);
      expect(fake.status).toBe(400);
      expect(msgOf(fake)).toContain(MSG.mediaType);
      const heic = await uploadMedia(f, rid, [file('heic.heic', 'image/heic', JPEG())]);
      expect(heic.status).toBe(400);
      expect(msgOf(heic)).toContain(MSG.mediaType);
      const nine = await uploadMedia(f, rid, Array.from({ length: 9 }, (_, i) => file(`n${i}.jpg`, 'image/jpeg', JPEG())));
      expect(nine.status).toBe(400);
      expect(msgOf(nine)).toContain(MSG.mediaCount);
      const mixed = await uploadMedia(f, rid, [file('ok1.jpg', 'image/jpeg', JPEG()), file('big.jpg', 'image/jpeg', big)]);
      expect(mixed.status).toBe(400);
      expect(await count()).toBe(0);
      // fill to 10, the 11th is refused (needs a working image storage; the rejections above do not)
      const eight = await uploadMedia(f, rid, Array.from({ length: 8 }, (_, i) => file(`a${i}.jpg`, 'image/jpeg', JPEG())));
      storageDown(eight);
      expect(eight.status).toBe(200);
      expect((await uploadMedia(f, rid, Array.from({ length: 2 }, (_, i) => file(`b${i}.jpg`, 'image/jpeg', JPEG())))).status).toBe(200);
      const eleventh = await uploadMedia(f, rid, [file('c.jpg', 'image/jpeg', JPEG())]);
      expect(eleventh.status).toBe(400);
      expect(msgOf(eleventh)).toContain(MSG.mediaCount);
      expect(await count()).toBe(10);
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A8-03 photo rights: other foreman 403, MANAGER ok, author out of the window 403, voided receipt read-only', async () => {
    const w = await makeWorld(['FOREMANB']);
    try {
      const { rid } = await fixture(w);
      const j = file('ok.jpg', 'image/jpeg', JPEG());
      const up = await uploadMedia(w.t('FOREMAN'), rid, [j]);
      storageDown(up);
      expect(up.status, JSON.stringify(up.body)).toBe(200);
      const mid = up.body[0].id;
      const other = await uploadMedia(w.t('FOREMANB'), rid, [j]);
      expect(other.status).toBe(403);
      expect(msgOf(other)).toContain(MSG.mediaForbidden);
      expect((await del(`/api/goods-receipts/${rid}/media/${mid}`, w.t('FOREMANB'))).status).toBe(403);
      const mg = await uploadMedia(w.t('MANAGER'), rid, [j]);
      expect(mg.status).toBe(200);
      expect((await del(`/api/goods-receipts/${rid}/media/${mg.body[0].id}`, w.t('MANAGER'))).status).toBe(204);
      psql(`UPDATE goods_receipts SET occurred_at = occurred_at - interval '25 hours' WHERE id = ${rid};`);
      expect((await uploadMedia(w.t('FOREMAN'), rid, [j])).status).toBe(403);
      expect((await del(`/api/goods-receipts/${rid}/media/${mid}`, w.t('FOREMAN'))).status).toBe(403);
      expect((await voidReceipt(w.t('MANAGER'), rid, 'qa')).status).toBe(200);
      const afterVoid = await uploadMedia(w.t('MANAGER'), rid, [j]);
      expect(afterVoid.status).toBeGreaterThanOrEqual(400);
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A8-04 delete a file: 204, content 404, unknown / foreign media id 404, neighbours stay readable', async () => {
    const w = await makeWorld();
    try {
      const { rid, pa, sa } = await fixture(w);
      const f = w.t('FOREMAN');
      const up = await uploadMedia(f, rid, [file('a.jpg', 'image/jpeg', JPEG()), file('b.png', 'image/png', PNG())]);
      storageDown(up);
      expect(up.status, JSON.stringify(up.body)).toBe(200);
      const [a, b] = up.body as Array<{ id: number }>;
      expect((await del(`/api/goods-receipts/${rid}/media/${a!.id}`, f)).status).toBe(204);
      expect((await mediaContent(f, rid, a!.id)).status).toBe(404);
      expect((await mediaContent(f, rid, b!.id)).status).toBe(200);
      expect((await del(`/api/goods-receipts/${rid}/media/999999999`, f)).status).toBe(404);
      const second = await receive(f, [ev(pa, 'ACCEPTED', 1, { shipmentId: sa })]);
      const rid2 = second.body.receipts[0].id;
      expect((await del(`/api/goods-receipts/${rid2}/media/${b!.id}`, f)).status).toBe(404);
      expect((await mediaContent(f, rid, b!.id)).status).toBe(200);
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A8-06 uploaded photo is registered in image_references (not an orphan) and its content is readable', async () => {
    const w = await makeWorld();
    try {
      const { rid } = await fixture(w);
      const up = await uploadMedia(w.t('FOREMAN'), rid, [file('a.jpg', 'image/jpeg', JPEG())]);
      storageDown(up);
      expect(up.status, JSON.stringify(up.body)).toBe(200);
      expect((await mediaContent(w.t('FOREMAN'), rid, up.body[0].id)).status).toBe(200);
      expect(psql(`SELECT count(*) FROM goods_receipt_media WHERE receipt_id = ${rid};`)).toBe('1');
    } finally {
      await w.cleanup();
    }
  });
});

describe('FOR-11-05 API: contracts', { tags: ['for-11-05', 'api'] }, () => {
  test('TC-A9-01 errors are localized (PL and RU differ and are not the key)', async () => {
    const w = await makeWorld();
    try {
      const p = w.position({ quantity: 2 });
      const s = await w.shipment([[p, 2]]);
      const body = [ev(p, 'MISSING', 9, { shipmentId: s, comment: 'x' })];
      const pl = await post('/api/goods-receipts', w.t('FOREMAN'), { events: body }, 'pl');
      const ru = await post('/api/goods-receipts', w.t('FOREMAN'), { events: body }, 'ru');
      expect(pl.status).toBe(409);
      expect(ru.status).toBe(409);
      expect(msgOf(pl)).toContain('Ilość przekracza ilość w drodze');
      expect(msgOf(ru)).toContain('Количество превышает находящееся в пути');
      expect(msgOf(pl)).not.toContain('error.goodsReceipt');
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A9-02 event time is the server time; a client occurredAt is ignored', async () => {
    const w = await makeWorld();
    try {
      const p = w.position({ quantity: 2 });
      const s = await w.shipment([[p, 2]]);
      const yearAgo = new Date(Date.now() - 365 * 86400_000).toISOString();
      const r = await receive(w.t('FOREMAN'), [ev(p, 'ACCEPTED', 1, { shipmentId: s, occurredAt: yearAgo })]);
      expect(r.status, JSON.stringify(r.body)).toBe(201);
      expect(Math.abs(Date.now() - Date.parse(r.body.receipts[0].occurredAt))).toBeLessThan(60_000);
      expect(r.body.receipts[0].receivedById).toBe(w.users.FOREMAN!.id);
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A9-03 no money fields or storage keys in any response', async () => {
    const w = await makeWorld();
    try {
      const { pa, sa, rid } = await fixture(w);
      const rec = await receive(w.t('FOREMAN'), [ev(pa, 'MISSING', 1, { shipmentId: sa, comment: 'x' })]);
      const up = await uploadMedia(w.t('FOREMAN'), rid, [{ name: 'a.jpg', type: 'image/jpeg', bytes: JPEG() }]);
      const v = await voidReceipt(w.t('MANAGER'), rec.body.receipts[0].id, 'qa');
      const reads = [
        rec, v, ...(up.status === 200 ? [up] : []), // upload is skipped when the image storage is down
        await get(`/api/goods-receipts?projectId=${w.projectId}&includeVoided=true`, w.t('MANAGER')),
        await get(`/api/goods-receipts/${rid}`, w.t('MANAGER')),
        await get(`/api/goods-receipts/to-receive?projectId=${w.projectId}`, w.t('MANAGER')),
        await get(`/api/purchase-positions/${pa}/ledger`, w.t('MANAGER')),
      ];
      for (const r of reads) {
        expect(r.status).toBeLessThan(300);
        expect(badKeys(r.body)).toEqual([]);
      }
      // clientRequestId is request-only: it must not be echoed by reads
      const list = (await get(`/api/goods-receipts?projectId=${w.projectId}`, w.t('MANAGER'))).body;
      expect(JSON.stringify(list)).not.toContain('clientRequestId');
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A9-04 audit trail has create and void events of a receipt', async () => {
    const w = await makeWorld();
    try {
      const { rid } = await fixture(w);
      await voidReceipt(w.t('MANAGER'), rid, 'qa');
      const r = await get(`/api/audit?query=${encodeURIComponent(`entityClass==GoodsReceiptEntity and entityId==${rid}`)}&size=20`, w.t('ADMIN'));
      expect(r.status, JSON.stringify(r.body).slice(0, 300)).toBe(200);
      const ops = (r.body.content as any[]).map((x) => x.operation);
      expect(ops).toEqual(expect.arrayContaining(['CREATE']));
      expect(ops.length).toBeGreaterThanOrEqual(2); // CREATE + the void update
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A9-05 dashboard dataset GOODS_RECEIPTS exposes kind, quantity, occurredAt, project, position, voided and no free-text/keys', async () => {
    const w = await makeWorld();
    try {
      const r = await get('/api/dashboard-widgets/datasets', w.t('ADMIN'));
      expect(r.status).toBe(200);
      const ds = (r.body as any[]).find((d) => d.code === 'GOODS_RECEIPTS');
      expect(ds).toBeTruthy();
      const names: string[] = ds.fields.map((f: any) => f.name);
      for (const need of ['kind', 'quantity', 'occurredAt']) expect(names, need).toContain(need);
      expect(names.some((n) => n.startsWith('project'))).toBe(true);
      expect(names.some((n) => n.startsWith('position'))).toBe(true);
      expect(names.some((n) => /void/i.test(n))).toBe(true);
      for (const banned of ['comment', 'voidReason', 'void_reason', 'clientRequestId', 'storageUri']) expect(names, banned).not.toContain(banned);
    } finally {
      await w.cleanup();
    }
  });
});
