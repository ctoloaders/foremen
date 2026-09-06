/**
 * Pure filter-fragment emitter for reference (association) columns.
 *
 * Given a reference column's id filter path (e.g. `role.id`) and the set of
 * selected target-entity ids, produce the query fragment consumed by the
 * DataTable filter layer (composed with other fragments via the existing
 * ` AND ` joiner in {@link buildQueryString}).
 *
 * Operator symbols match the implemented backend query grammar
 * (`QueryOperator` / `QueryTokenizer`), which uses TILDE-WRAPPED operators —
 * NOT RSQL. See design.md "Operator-symbol correction":
 *   - single id    → `${idPath}==${id}`
 *   - multiple ids → `${idPath}~in~${ids.join(',')}` (comma-joined, no parens)
 *   - empty set    → no fragment (`null`, consistent with how the other
 *                     column-filter builders represent "no filter")
 *
 * The function is pure and order-stable: it does not sort or dedupe `ids`; the
 * emitted list preserves the caller-supplied order.
 *
 * @param idPath the reference field's id filter path (e.g. `role.id`)
 * @param ids the selected target-entity ids
 * @returns the query fragment, or `null` when no filter should be applied
 */
export function emitReferenceFragment(
  idPath: string,
  ids: readonly number[]
): string | null {
  if (!idPath || ids.length === 0) return null
  if (ids.length === 1) return `${idPath}==${ids[0]}`
  return `${idPath}~in~${ids.join(',')}`
}
