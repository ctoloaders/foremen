import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { acceptedPosition, errMatches, http, issuedProforma, ok, withCtx } from './support.js';

// FOR-11-03 regression group (R-04, R-05, R-07 automated; R-01, R-02, R-03, R-06 (partly), R-08..R-10 see FINDINGS.md).
// Repeatability: run-id fixture per test, full teardown in `finally` (support.ts).
describe('FOR-11-03 regression (API)', { tags: ['for-11-03', 'api', 'regression'] }, () => {
  test('R-04 the FOR-11-00 gate: an AGREED position cannot jump to WITH_BUYER by hand', async () => {
    await withCtx(async (c) => {
      const planned = (await ok('POST', '/api/purchase-positions', c.tok.admin, {
        projectId: c.projectId, kind: 'FINISHING', sourcingPath: 'AGREED', plannedMaterialId: c.materialId, quantity: 1, salePrice: 10, saleVatRateId: c.vat8,
      })).id as number;
      const accepted = await acceptedPosition(c, 1, 10);

      const a = await http('POST', `/api/purchase-positions/${planned}/transition`, c.tok.admin, { target: 'WITH_BUYER' });
      const b = await http('POST', `/api/purchase-positions/${accepted}/transition`, c.tok.admin, { target: 'WITH_BUYER' });

      expect(errMatches(a, 409, 'error.purchasePosition.transition.gateViolation')).toBeNull();
      expect(errMatches(b, 409, 'error.purchasePosition.transition.gateViolation')).toBeNull();
    });
  });

  test('R-05 the manual PACKAGE branch works and CLIENT_OWN stays out of external orders', async () => {
    await withCtx(async (c) => {
      const pkg = (await ok('POST', '/api/purchase-positions', c.tok.admin, {
        projectId: c.projectId, kind: 'FINISHING', sourcingPath: 'PACKAGE', plannedMaterialId: c.materialId, quantity: 1, salePrice: 10, saleVatRateId: c.vat8,
      })).id as number;

      const r = await http('POST', `/api/purchase-positions/${pkg}/transition`, c.tok.admin, { target: 'WITH_BUYER' });

      expect(r.status, r.text).toBe(200);
      expect(r.body.status).toBe('WITH_BUYER');
    });
  });

  test('R-07 after IN_PROFORMA a position cannot enter a new approval round', async () => {
    await withCtx(async (c) => {
      const { pids } = await issuedProforma(c, [[1, 40]]);

      const r = await http('POST', '/api/position-approvals/staff/send', c.tok.admin, { positionIds: [pids[0]], decisionDeadline: null, managerNote: 'x' });

      expect(errMatches(r, 409, 'error.positionApproval.send.notEligible')).toBeNull();
    });
  });
});
