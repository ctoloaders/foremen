import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { Env } from './support.js';

// FOR-11-02 Part A1 (ABAC and visibility). Source: .kiro/specs/FOR-11-Delivery-Management/FOR-11-02-position-approval/test-cases.md.
// Repeatability: generator + teardown. Each test builds its own Env (run-id users/project/materials/positions)
// and Env.cleanup() removes everything in `finally`.
const CL = '/api/position-approvals/client';
const ST = '/api/position-approvals/staff';

describe('FOR-11-02 A1 access and visibility', { tags: ['for-11-02', 'api'] }, () => {
  test('TC-A1-01 client sees no internal resources', async () => {
    const env = await Env.create();
    try {
      await env.position('1');
      for (const p of ['/api/purchase-positions', '/api/purchase-requests', '/api/procurement/projects']) {
        const r = await env.as('client', 'GET', p);
        expect(r.status, p).toBe(403);
      }
      const r = await env.as('client', 'GET', `${CL}/projects/${env.projectA}`);
      expect(r.status).toBe(200);
      expect(r.body).toEqual([]);
    } finally {
      await env.cleanup();
    }
  });

  test('TC-A1-02 an unsent position is invisible to the client', async () => {
    const env = await Env.create();
    try {
      const p1 = await env.position('1');
      const r = await env.as('client', 'GET', `${CL}/positions/${p1.id}`);
      expect(r.status).toBe(404);
    } finally {
      await env.cleanup();
    }
  });

  // BUG-1 (FINDINGS.md): staff list (StaffApprovalQueryService HAS_ROUND) omits positions that were never sent.
  test('TC-A1-02 step 2 the manager staff view lists the unsent position', async () => {
    const env = await Env.create();
    try {
      const p1 = await env.position('1');
      const staff = await env.as('manager', 'GET', `${ST}/projects/${env.projectA}`);
      expect(staff.status).toBe(200);
      expect(staff.body.map((p: any) => p.positionId)).toContain(p1.id);
    } finally {
      await env.cleanup();
    }
  });

  test('TC-A1-03 client cannot call staff endpoints', async () => {
    const env = await Env.create();
    try {
      const p1 = await env.position('1');
      expect((await env.send([p1.id])).status).toBe(200);
      const roundId = await env.roundId(p1.id);
      const body = { positionIds: [p1.id], decisionDeadline: '2099-01-01' };
      expect((await env.as('client', 'POST', `${ST}/send`, body)).status).toBe(403);
      expect(await env.err('client', 'GET', `${ST}/projects/${env.projectA}`, undefined, 403, 'error.positionApproval.staff.forbidden')).toBeNull();
      const dob = await env.as('client', 'POST', `${ST}/rounds/${roundId}/decide-on-behalf`, { decision: 'ACCEPT', optionId: await env.optionId(p1.id), expectedUnitSalePrice: 100, comment: 'x' });
      expect(dob.status).toBe(403);
    } finally {
      await env.cleanup();
    }
  });

  test('TC-A1-04 roles without access', async () => {
    const env = await Env.create();
    try {
      const p1 = await env.position('1');
      expect((await env.as('foreman', 'GET', `${CL}/projects/${env.projectA}`)).status).toBe(403);
      const send = await env.as('buyer', 'POST', `${ST}/send`, { positionIds: [p1.id], decisionDeadline: '2099-01-01' });
      expect(send.status).toBe(403);
      expect((await env.as('buyer', 'GET', `${ST}/projects/${env.projectA}`)).status).toBe(200);
    } finally {
      await env.cleanup();
    }
  });

  test('TC-A1-05 project isolation', async () => {
    const env = await Env.create({ second: true });
    try {
      const p1 = await env.position('1');
      const p7 = await env.position('7', { project: 'B', block: 1, sale: 10 });
      expect(await env.err('client2', 'GET', `${CL}/projects/${env.projectA}`, undefined, 403, 'error.access.denied')).toBeNull();
      expect(await env.err('client', 'GET', `${CL}/projects/${env.projectB}`, undefined, 403, 'error.access.denied')).toBeNull();
      expect((await env.send([p1.id])).status).toBe(200);
      expect((await env.as('client2', 'GET', `${CL}/positions/${p1.id}`)).status).toBe(404);
      expect((await env.as('manager', 'GET', `${ST}/projects/${env.projectB}`)).status).toBe(403);
      expect(p7.id).toBeGreaterThan(0);
    } finally {
      await env.cleanup();
    }
  });
});
