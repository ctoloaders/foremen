/** Audit log record returned by GET /api/audit */
export interface AuditRecord {
  id: number
  entityClass: string
  entityId: number
  operation: string
  performedBy: string
  performedAt: string // ISO datetime string
  snapshotBefore: Record<string, unknown> | null
  snapshotAfter: Record<string, unknown> | null
}
