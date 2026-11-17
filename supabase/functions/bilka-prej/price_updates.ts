import {
  batchIds, isStoredProductId, providerProductId, toBilkaCacheRow,
  type GroceryChain, type PrejProduct,
} from "./prej.ts";

export type PriceOnlyUpdate = {
  id: string;
  price_ore: number;
  unit_price_ore: number | null;
  unit_price_basis: "KG" | "L" | "PIECE" | "PACK" | null;
  is_offer: boolean | null;
  price_updated_at: string | null;
};

/** Updating an existing selected SKU must never overwrite the user's
 *  chosen brand, package size, product name or photo with changed API metadata.
 */
export function priceOnlyUpdates(products: PrejProduct[], expectedIds: string[]): PriceOnlyUpdate[] {
  const allowed = new Set(expectedIds.filter(isStoredProductId));
  const rows = new Map<string, PriceOnlyUpdate>();
  for (const product of products) {
    for (const chain of ["bilka", "bilkatogo"] as GroceryChain[]) {
      const item = toBilkaCacheRow(product, chain);
      if (!item || !allowed.has(item.id)) continue;
      rows.set(item.id, {
        id: item.id,
        price_ore: item.price_ore,
        unit_price_ore: item.unit_price_ore,
        unit_price_basis: item.unit_price_basis,
        is_offer: item.is_offer,
        price_updated_at: item.price_updated_at,
      });
    }
  }
  return [...rows.values()];
}

/**
 * The next Prej batch for a household's chosen products: skip those refreshed
 * recently first, then take up to one batch of what is left. Filtering before
 * batching keeps a household with more than 500 products from re-checking the
 * same fresh first batch forever.
 */
export function refreshBatch(
  selected: string[], recentlyUpdated: ReadonlySet<string>, size = 500,
): { batch: string[]; skippedFresh: number; remaining: number } {
  const stale = selected.filter((id) => !recentlyUpdated.has(id));
  const providerIds = new Set(batchIds(stale.map(providerProductId), size)[0] ?? []);
  const batch = stale.filter((id) => providerIds.has(providerProductId(id)));
  return {
    batch,
    skippedFresh: selected.length - stale.length,
    remaining: stale.length - batch.length,
  };
}
