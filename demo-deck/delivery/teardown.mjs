// Сброс демо-данных закупок проекта 2 (только то, что создавали сценарии доставок). Банковский счёт и профиль компании остаются.
import { psql } from './lib.mjs';
const pid = 2;
const sql = `
DELETE FROM audit_log WHERE entity_class = 'GoodsReceiptEntity' AND entity_id IN (SELECT id FROM goods_receipts WHERE project_id = ${pid});
DELETE FROM audit_log WHERE (entity_class = 'ShipmentEntity' AND entity_id IN (SELECT id FROM shipments WHERE project_id = ${pid}))
   OR (entity_class = 'PurchasePositionEntity' AND entity_id IN (SELECT id FROM purchase_positions WHERE project_id = ${pid}));
DELETE FROM audit_log WHERE entity_class = 'PurchaseRequestEntity' AND entity_id IN (SELECT id FROM purchase_requests WHERE project_id = ${pid});
DELETE FROM bank_transaction_suggestions WHERE client_proforma_id IN (SELECT id FROM client_proformas WHERE project_id = ${pid})
   OR external_order_id IN (SELECT id FROM external_orders WHERE project_id = ${pid})
   OR transaction_id IN (SELECT id FROM bank_transactions WHERE created_by = 'demo');
DELETE FROM bank_transaction_links WHERE client_proforma_id IN (SELECT id FROM client_proformas WHERE project_id = ${pid})
   OR external_order_id IN (SELECT id FROM external_orders WHERE project_id = ${pid})
   OR transaction_id IN (SELECT id FROM bank_transactions WHERE created_by = 'demo');
DELETE FROM bank_transactions WHERE created_by = 'demo';
DELETE FROM goods_receipts WHERE project_id = ${pid};
DELETE FROM shipments WHERE project_id = ${pid};
DELETE FROM external_order_documents WHERE external_order_id IN (SELECT id FROM external_orders WHERE project_id = ${pid});
DELETE FROM external_order_lines WHERE external_order_id IN (SELECT id FROM external_orders WHERE project_id = ${pid});
DELETE FROM external_orders WHERE project_id = ${pid};
DELETE FROM client_proforma_lines WHERE proforma_id IN (SELECT id FROM client_proformas WHERE project_id = ${pid});
DELETE FROM client_proformas WHERE project_id = ${pid};
UPDATE position_approval_rounds SET chosen_option_id = NULL, source_suggestion_id = NULL WHERE position_id IN (SELECT id FROM purchase_positions WHERE project_id = ${pid});
DELETE FROM position_approval_options WHERE round_id IN (SELECT r.id FROM position_approval_rounds r JOIN purchase_positions p ON p.id = r.position_id WHERE p.project_id = ${pid});
DELETE FROM position_approval_rounds WHERE position_id IN (SELECT id FROM purchase_positions WHERE project_id = ${pid});
DELETE FROM purchase_position_variants WHERE position_id IN (SELECT id FROM purchase_positions WHERE project_id = ${pid});
DELETE FROM purchase_positions WHERE project_id = ${pid};
DELETE FROM purchase_requests WHERE project_id = ${pid};
DELETE FROM notifications WHERE created_date::date = CURRENT_DATE
   AND recipient_id IN (SELECT id FROM users WHERE email IN ('cto@loaders.dev','oleg@loaders.dev','piotr@loaders.dev','tomasz@loaders.dev','buyer@loaders.dev'))
   AND (type LIKE 'notification.position.%' OR type LIKE 'notification.proforma.%' OR type LIKE 'notification.order.%' OR type LIKE 'notification.externalOrder.%'
        OR type LIKE 'notification.shipment.%' OR type LIKE 'notification.invoice.%' OR type LIKE 'notification.goodsReceipt.%' OR type LIKE 'notification.receipt.%');
SELECT 'positions left: ' || count(*) FROM purchase_positions WHERE project_id = ${pid};
`;
console.log(psql(sql));
