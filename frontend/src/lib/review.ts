import type { EntityDto } from "./types";

/**
 * After a decision, move to the next pending entity (wrapping around). If nothing is pending,
 * stay on the current one so the reviewer sees the result of their last action.
 */
export function nextPendingIndex(entities: Pick<EntityDto, "reviewStatus">[], current: number): number {
  const n = entities.length;
  for (let step = 1; step <= n; step++) {
    const i = (current + step) % n;
    if (entities[i].reviewStatus === "PENDING") return i;
  }
  return current;
}
