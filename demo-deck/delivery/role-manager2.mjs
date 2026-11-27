import { session } from './shot.mjs';
const s = await session('cto@loaders.dev');
const { page, snap, go } = s;
// согласование (staff)
await go('/projects/2/positionApprovals');
await snap('mgr-06-approvals-staff', { wait: 2000 });
// проформы
await go('/deliveries/proformas');
await snap('mgr-07-proformas');
await go('/deliveries/proformas/638');
await snap('mgr-08-proforma-detail', { wait: 2000 });
await go('/deliveries/external-orders');
await snap('mgr-09-external-orders');
await go('/deliveries/shipments');
await snap('mgr-10-shipments');
await s.close();
