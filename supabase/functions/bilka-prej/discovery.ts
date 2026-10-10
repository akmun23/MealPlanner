import type { PrejProduct } from "./prej.ts";

export type IngredientNeed = { id: string; name: string };
export type SearchGroup = { query: string; ingredients: IngredientNeed[] };
export type RankedCandidate = { product: PrejProduct; score: number };

/** Normalize spelling/case for lexical comparison, without a food vocabulary. */
export function normalizeIngredient(value: string): string {
  return value
    .toLowerCase()
    .replaceAll("æ", "ae").replaceAll("ø", "oe").replaceAll("å", "aa")
    .normalize("NFD").replace(/[\u0300-\u036f]/g, "")
    .replace(/\([^)]*\)/g, " ")
    .replace(/[^\p{L}\p{N}]+/gu, " ")
    .trim().replace(/\s+/g, " ");
}

/** Keep the exact food name chosen by the household; no inferred synonyms. */
export function cleanedExactQuery(name: string): string {
  return name.replace(/\([^)]*\)/g, "").replace(/[-/]+/g, " ")
    .trim().replace(/\s+/g, " ").slice(0, 200);
}

export function searchTerm(ingredientName: string): string {
  return cleanedExactQuery(ingredientName).toLowerCase();
}

export function searchQueries(ingredientName: string): string[] {
  const query = searchTerm(ingredientName);
  return query.length >= 2 ? [query] : [];
}

/** Only share a Prej search when ingredient names are actually identical. */
export function groupSearches(ingredients: IngredientNeed[]): SearchGroup[] {
  const groups = new Map<string, IngredientNeed[]>();
  for (const ingredient of ingredients) {
    const query = searchQueries(ingredient.name)[0];
    if (!query) continue;
    groups.set(query, [...(groups.get(query) ?? []), ingredient]);
  }
  return [...groups.entries()].map(([query, members]) => ({
    query, ingredients: members,
  })).sort((a, b) =>
    b.ingredients.length - a.ingredients.length ||
    a.query.localeCompare(b.query, "da")
  );
}

/** Kept for discovery's staged API; there is deliberately no fallback stage. */
export function groupSearchesAtStage(ingredients: IngredientNeed[], stage: number): SearchGroup[] {
  return stage === 0 ? groupSearches(ingredients) : [];
}

function matchesToken(haystack: string, needle: string): boolean {
  const tokens = haystack.split(" ");
  if (tokens.includes(needle)) return true;
  // Generic support for compounded words and Danish inflections, not a list
  // of assumed ingredient substitutions. Short tokens may have false hits,
  // so only 4+ letter tokens get flexible standalone matching.
  if (needle.length >= 4 && tokens.some((t) => t.startsWith(needle) || t.endsWith(needle))) {
    return true;
  }
  const stem = needle.replace(/(erne|ende|ere|er|ene|en|et|e)$/g, "");
  return stem.length >= 4 &&
    tokens.some((t) => t === stem || t.startsWith(stem));
}

/**
 * Suggestions require the original ingredient words. We never infer that
 * another food type is an equivalent substitute, and never save defaults.
 */
export function scoreCandidate(ingredientName: string, product: PrejProduct): number {
  const wanted = normalizeIngredient(ingredientName);
  const name = normalizeIngredient(product.name ?? "");
  if (!wanted || !name) return 0;
  if (name === wanted) return 1;
  // Word boundaries vary between provider products: "Basmati ris" and
  // "Basmatiris" should match without inventing a synonym.
  const compact = wanted.replaceAll(" ", "");
  if (compact.length >= 5 && name.replaceAll(" ", "").includes(compact)) return 0.88;
  const parts = wanted.split(" ").filter((p) => p.length > 2);
  if (!parts.length || !parts.every((p) => matchesToken(name, p))) return 0;
  return parts.length > 1 ? 0.83 : 0.78;
}

export function rankCandidates(
  ingredientName: string,
  products: PrejProduct[],
  max = 10,
): RankedCandidate[] {
  return products
    .map((product) => ({ product, score: scoreCandidate(ingredientName, product) }))
    .filter((item) => item.score >= 0.75)
    .sort((a, b) =>
      b.score - a.score ||
      ((a.product.prices?.find((p) => p.chain_slug === "bilka")?.unit_price ?? Infinity) -
       (b.product.prices?.find((p) => p.chain_slug === "bilka")?.unit_price ?? Infinity)) ||
      String(a.product.name).localeCompare(String(b.product.name), "da")
    )
    .slice(0, max);
}
