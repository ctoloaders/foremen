import { resolve, dirname } from "path";
import { fileURLToPath } from "url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const BITRIX_URL = "https://foremen.bitrix24.pl/rest/747/jqsr7yxpg9yqy1nw";

const DRIVE_FIELD = "UF_CRM_639869B45A2A9";
const SHEETS_FIELD = "UF_CRM_1771587767";
const FOREMAN_FIELD = "UF_CRM_1692271880";
const ESTIMATOR_FIELD = "UF_CRM_1724145235";
const SALES_FIELD = "UF_CRM_1758702568";

// Excluded stages
const EXCLUDED_STAGES = ["UC_1OQS5K", "UC_7Y686T", "WON", "LOSE", "APOLOGY"];

async function bitrixCall(method: string, params: any = {}): Promise<any> {
  const res = await fetch(`${BITRIX_URL}/${method}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(params),
  });
  return res.json();
}

async function bitrixGetAll(method: string, params: any = {}): Promise<any[]> {
  let all: any[] = [];
  let start = 0;
  while (true) {
    const res = await bitrixCall(method, { ...params, start });
    if (res.result) all = all.concat(res.result);
    if (!res.next) break;
    start = res.next;
  }
  return all;
}

async function main() {
  console.log("Fetching all deals (excluding closed stages)...");
  
  const deals = await bitrixGetAll("crm.deal.list", {
    filter: {
      "!STAGE_ID": EXCLUDED_STAGES,
    },
    select: ["ID", "TITLE", "STAGE_ID", DRIVE_FIELD, SHEETS_FIELD, FOREMAN_FIELD, ESTIMATOR_FIELD, SALES_FIELD],
  });

  console.log(`Total deals (not in excluded stages): ${deals.length}`);

  // Filter: both Drive and Sheets fields filled
  const eligible = deals.filter(d => d[DRIVE_FIELD] && d[SHEETS_FIELD]);
  const skipped = deals.filter(d => !d[DRIVE_FIELD] || !d[SHEETS_FIELD]);

  console.log(`\nEligible (both Drive + Sheets filled): ${eligible.length}`);
  console.log(`Skipped (missing at least 1 field): ${skipped.length}`);

  // Show first 10 eligible
  console.log("\n--- First 10 eligible ---");
  for (const d of eligible.slice(0, 10)) {
    const hasForeman = d[FOREMAN_FIELD] ? "✓" : "✗";
    const hasEstimator = d[ESTIMATOR_FIELD] ? "✓" : "✗";
    const hasSales = d[SALES_FIELD] ? "✓" : "✗";
    console.log(`  #${d.ID} "${d.TITLE}" [${d.STAGE_ID}] foreman:${hasForeman} estimator:${hasEstimator} sales:${hasSales}`);
  }

  // Stats by stage
  console.log("\n--- Eligible by stage ---");
  const byStage: Record<string, number> = {};
  for (const d of eligible) {
    byStage[d.STAGE_ID] = (byStage[d.STAGE_ID] || 0) + 1;
  }
  for (const [stage, count] of Object.entries(byStage)) {
    console.log(`  ${stage}: ${count}`);
  }
}

main().catch(e => console.error("Error:", e.message));
