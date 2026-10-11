/** Pure, dependency-free Prej normalization. Keep Bilka separate from BilkaToGo. */
export interface PrejPrice {
  chain_slug?: string;
  price?: number | null;
  unit_price?: number | null;
  unit_price_label?: string | null;
  quantity?: number | null;
  unit?: string | null;
  is_offer?: boolean | null;
  last_seen_date?: string | null;
}
export interface PrejProduct {
  id?: number;
  name?: string | null;
  brand?: string | null;
  image_url?: string | null;
  quantity?: number | null;
  unit?: string | null;
  prices?: PrejPrice[];
  category_name?: string | null;
  category_slug?: string | null;
}

/** Provider grocery=1 still returns pet products, cosmetics, toys and household
 * goods. Only cache food-category products as potential ingredient matches.
 * Unknown/missing categories are not safe to auto-suggest. A previously selected
 * SKU is preserved separately and its price updates never depend on this filter.
 */
export function isFoodSearchResult(
  product: PrejProduct,
  allowUncategorised = false,
): boolean {
  const category = (product.category_name ?? product.category_slug ?? "")
    .toLowerCase()
    .replaceAll("æ", "ae").replaceAll("ø", "oe").replaceAll("å", "aa")
    .normalize("NFD").replace(/[\u0300-\u036f]/g, "")
    .replace(/[^a-z0-9]+/g, " ").trim();
  // ToGo is already a grocery-oriented catalog. Never discard an otherwise
  // relevant product just because Prej has not categorised it yet.
  if (!category) return allowUncategorised;
  const blocked = [
    /^personlig pleje(?: |$)/, /^dyremad(?: |$)/, /^dyr(?: |$)/,
    /^husholdning(?: |$)/, /^oevrigt(?: |$)/, /^baby boern(?: |$)/,
    /^legetoej(?: |$)/, /^elektronik(?: |$)/, /^bolig(?: |$)/,
    /^have(?: |$)/, /^sport(?: |$)/, /^fritid(?: |$)/,
    /^toej(?: |$)/, /^mode(?: |$)/, /^boeger(?: |$)/,
    /^rengoering(?: |$)/, /^skoenhed(?: |$)/,
  ];
  return !blocked.some((rule) => rule.test(category));
}

export type BilkaCacheRow = {
  id: string;
  chain_slug: GroceryChain;
  name: string;
  brand: string | null;
  package_quantity: number | null;
  package_unit: string | null;
  package_base_amount: number | null;
  quantity_kind: "MASS" | "VOLUME" | "COUNT" | "PACKAGE" | null;
  image_url: string | null;
  price_ore: number;
  unit_price_ore: number | null;
  unit_price_basis: "KG" | "L" | "PIECE" | "PACK" | null;
  is_offer: boolean | null;
  is_available: boolean;
  price_updated_at: string | null;
};

/** Prej IDs are global, but our saved SKUs must be per chain. */
export type GroceryChain = "bilka" | "bilkatogo";
export function storedProductId(id: number | string, chain: GroceryChain): string {
  const raw = String(id);
  if (!/^\\d+$/.test(raw)) throw new Error("invalid_prej_id");
  return chain === "bilkatogo" ? "bilkatogo:" + raw : raw;
}
export function providerProductId(id: string): string {
  const sourceId = id.startsWith("bilkatogo:") ? id.slice("bilkatogo:".length) : id;
  if (!/^\\d+$/.test(sourceId)) throw new Error("invalid_prej_id");
  return sourceId;
}
export function isStoredProductId(id: string): boolean {
  return /^\\d+$/.test(id) || /^bilkatogo:\\d+$/.test(id);
}

export function batchIds(ids: string[], size = 500): string[][] {
  if (size < 1 || size > 500) throw new Error("Batch size must be 1..500");
  const unique = [...new Set(ids.filter((id) => /^[0-9]+$/.test(id)))];
  return Array.from({ length: Math.ceil(unique.length / size) }, (_, i) =>
    unique.slice(i * size, (i + 1) * size)
  );
}

function packageSize(quantity: number | null, unit: string | null): {
  base: number | null;
  kind: BilkaCacheRow["quantity_kind"];
} {
  if (quantity == null || !Number.isFinite(quantity) || quantity <= 0) return { base: null, kind: null };
  switch ((unit ?? "").trim().toLowerCase()) {
    case "g": return { base: quantity, kind: "MASS" };
    case "kg": return { base: quantity * 1000, kind: "MASS" };
    case "ml": return { base: quantity, kind: "VOLUME" };
    case "cl": return { base: quantity * 10, kind: "VOLUME" };
    case "l":
    case "liter": return { base: quantity * 1000, kind: "VOLUME" };
    case "stk":
    case "styk": return { base: quantity, kind: "COUNT" };
    case "pakke":
    case "pk": return { base: quantity, kind: "PACKAGE" };
    default: return { base: null, kind: null };
  }
}

function priceBasis(label: string | null): BilkaCacheRow["unit_price_basis"] {
  if (!label) return null;
  if (/kr\s*\/\s*kg/i.test(label)) return "KG";
  if (/kr\s*\/\s*l\b/i.test(label)) return "L";
  if (/kr\s*\/\s*stk/i.test(label)) return "PIECE";
  if (/kr\s*\/\s*pk/i.test(label)) return "PACK";
  return null;
}

/** A product-level minimum price is NEVER safe: only the bilka price row is used. */
export function toBilkaCacheRow(
  product: PrejProduct,
  chain: GroceryChain = "bilka",
): BilkaCacheRow | null {
  if (typeof product.id !== "number" || !Number.isSafeInteger(product.id) || product.id <= 0) return null;
  const name = product.name?.trim();
  const price = product.prices?.find((p) => p.chain_slug === chain);
  if (!name || !price || typeof price.price !== "number" || !Number.isSafeInteger(price.price) || price.price < 0) return null;

  // Chain-specific package size wins: aggregate product quantities can differ by chain.
  const quantity = price.quantity ?? product.quantity ?? null;
  const unit = price.unit ?? product.unit ?? null;
  const measure = packageSize(quantity, unit);
  const date = price.last_seen_date;
  const updated = date && /^\d{4}-\d{2}-\d{2}$/.test(date) ? date + "T12:00:00Z" : null;
  const img = product.image_url;
  return {
    id: storedProductId(product.id, chain),
    chain_slug: chain,
    name,
    brand: product.brand?.trim() || null,
    package_quantity: quantity,
    package_unit: unit,
    package_base_amount: measure.base,
    quantity_kind: measure.kind,
    image_url: img && /^https?:\/\//i.test(img) ? img : null,
    price_ore: Number(price.price),
    unit_price_ore: typeof price.unit_price === "number" && Number.isSafeInteger(price.unit_price) && price.unit_price >= 0
      ? Number(price.unit_price) : null,
    unit_price_basis: priceBasis(price.unit_price_label ?? null),
    is_offer: price.is_offer ?? null,
    is_available: true,
    price_updated_at: updated,
  };
}

export function dailyRemaining(headers: Headers): number | null {
  const raw = headers.get("X-RateLimit-Remaining-Day");
  if (raw == null || !/^\d+$/.test(raw)) return null;
  const n = Number(raw);
  return Number.isSafeInteger(n) ? n : null;
}

export function validPrejKey(raw: unknown): raw is string {
  return typeof raw === "string" &&
    /^prej_(dev|biz|pro)_[a-zA-Z0-9_-]{12,256}$/.test(raw);
}
