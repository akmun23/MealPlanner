import { toBilkaCacheRow, type PrejProduct } from "./prej.ts";

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
  const allowed = new Set(expectedIds.filter((id) => /^[0-9]+$/.test(id)));
  const rows = new Map<string, PriceOnlyUpdate>();
  for (const product of products) {
    const item = toBilkaCacheRow(product);
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
  return [...rows.values()];
}
