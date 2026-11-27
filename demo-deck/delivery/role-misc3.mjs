import { session } from './shot.mjs';
const s = await session('piotr@loaders.dev');
await s.go('/company/bank-transactions');
await s.page.locator('button:has(svg.lucide-bell)').first().click().catch(() => {});
await s.snap('fin-09-notifications', { wait: 1500 });
await s.go('/deliveries/external-orders');
await s.snap('fin-04-orders-queue', { wait: 1800 });
await s.close();
