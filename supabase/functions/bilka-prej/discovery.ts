import type { PrejProduct } from "./prej.ts";

export type IngredientNeed = { id: string; name: string };
export type SearchGroup = { query: string; ingredients: IngredientNeed[] };
export type RankedCandidate = { product: PrejProduct; score: number };

/** Normalize the user's generic Danish ingredient names and provider product names. */
export function normalizeIngredient(value: string): string {
  return value
    .toLowerCase()
    .replaceAll("æ", "ae").replaceAll("ø", "oe").replaceAll("å", "aa")
    .normalize("NFD").replace(/[\u0300-\u036f]/g, "")
    .replace(/\([^)]*\)/g, " ")
    .replace(/[^\p{L}\p{N}]+/gu, " ")
    .trim().replace(/\s+/g, " ");
}

const clusters: Array<{ search: string; terms: RegExp }> = [
  { search: "mel", terms: /(?:^| )(?:mel|pizzamel|hvedemel|grahamsmel)(?: |$)/ },
  { search: "tomat", terms: /(?:^| )(?:tomat|tomater|cherrytomater|tomatpure|tomatpuree|hakkede tomater)(?: |$)/ },
  { search: "ris", terms: /(?:^| )(?:ris|groedris|risottoris|jasmin|basmatiris)(?: |$)/ },
  { search: "loeg", terms: /(?:^| )(?:loeg|roedloeg|hvidloeg)(?: |$)/ },
  { search: "ost", terms: /(?:^| )(?:cheddar|feta|revet ost|grana padano|american cheese)(?: |$)/ },
  { search: "olie", terms: /(?:^| )(?:olie|olivenolie|sesam olie)(?: |$)/ },
  { search: "sauce", terms: /(?:^| )(?:bbq sauce|fiskesauce|ketchup|mayonnaise|soya|salsa|worcestershire sauce)(?: |$)/ },
  { search: "nudler", terms: /(?:^| )(?:nudler|stir fry nudler)(?: |$)/ },
  { search: "kartofler", terms: /(?:^| )(?:kartofler|pommes fritter)(?: |$)/ },
  { search: "kylling", terms: /(?:^| )(?:kyllingebryst|kylling)(?: |$)/ },
  { search: "boenner", terms: /(?:^| )(?:kidneyboenner|sorte boenner)(?: |$)/ },
];

export function searchTerm(ingredientName: string): string {
  const clean = normalizeIngredient(ingredientName);
  const group = clusters.find(({ terms }) => terms.test(clean));
  const danishSearch = { loeg: "løg", boenner: "bønner" } as Record<string, string>;
  return group ? (danishSearch[group.search] ?? group.search) :
    cleanedExactQuery(ingredientName).toLowerCase();
}

/** Reuse one Prej search for multiple household ingredients whenever safe. */
export function groupSearches(ingredients: IngredientNeed[]): SearchGroup[] {
  const grouped = new Map<string, IngredientNeed[]>();
  for (const ingredient of ingredients) {
    const query = searchTerm(ingredient.name);
    if (!query) continue;
    grouped.set(query, [...(grouped.get(query) ?? []), ingredient]);
  }
  return [...grouped.entries()].map(([query, members]) => ({
    query,
    ingredients: members,
  })).sort((a, b) =>
    b.ingredients.length - a.ingredients.length ||
    a.query.localeCompare(b.query, "da")
  );
}

/** Search the saved ingredient name first. Synonyms precede the broad
 * grouped term; neither changes the ingredient's name or chosen SKU.
 * For each ingredient, duplicate search phrases are removed.
 */
export function searchQueries(ingredientName: string): string[] {
  const exact = cleanedExactQuery(ingredientName).toLowerCase();
  const alternatives: Record<string, string[]> = {
    pizzamel: ["tipo 00"],
    risottoris: ["risotto ris"],
    groedris: ["grødris"],
    championer: ["champignon"],
    kyllingebryst: ["kyllingefilet"],
    "tomat pure": ["tomatpuré"],
    "jasmin basmatiris": ["basmatiris"],
  };
  const synonyms = alternatives[normalizeIngredient(ingredientName)] ?? [];
  const terms = [exact, ...synonyms, searchTerm(ingredientName)];
  const seen = new Set<string>();
  return terms.map((t) => t.toLowerCase().trim()).filter((t) => {
    if (t.length < 2 || seen.has(t)) return false;
    seen.add(t);
    return true;
  });
}

/** Deduplicate queries at a given precision level across household ingredients. */
export function groupSearchesAtStage(ingredients: IngredientNeed[], stage: number): SearchGroup[] {
  const grouped = new Map<string, IngredientNeed[]>();
  for (const ingredient of ingredients) {
    const query = searchQueries(ingredient.name)[stage];
    if (!query) continue;
    grouped.set(query, [...(grouped.get(query) ?? []), ingredient]);
  }
  return [...grouped.entries()].map(([query, members]) => ({
    query, ingredients: members,
  })).sort((a, b) =>
    b.ingredients.length - a.ingredients.length ||
    a.query.localeCompare(b.query, "da")
  );
}

function matchesToken(haystack: string, needle: string): boolean {
  if (haystack.split(" ").includes(needle)) return true;
  // A long ingredient token may occur in a compound: "pizzamel" / "hvedemel".
  if (needle.length >= 4 && haystack.split(" ").some((t) => t.endsWith(needle))) return true;
  // Danish plural/suffix matching, but require meaningful nontrivial stems.
  const stem = needle.replace(/(erne|ende|ere|er|ene|en|et|e)$/g, "");
  return stem.length >= 4 &&
    haystack.split(" ").some((t) => t === stem || t.startsWith(stem));
}

const aliases: Record<string, string[]> = {
  "mel": ["hvedemel", "mel"],
  "pizzamel": ["pizzamel", "tipo 00", "tipo 0"],
  "loeg": ["loeg", "zittauer"],
  "roedloeg": ["roedloeg"],
  "hvidloeg": ["hvidloeg"],
  "tomat": ["tomat"],
  "tomat pure": ["tomatpure", "tomat pure"],
  "kartofler": ["kartofler", "kartoffel"],
  "groedris": ["groedris"],
  "risottoris": ["risotto", "arborio", "carnaroli"],
  "jasmin basmatiris": ["jasminris", "basmatiris"],
  "brun farin": ["brun farin"],
  "championer": ["champignon"],
  "kyllingebryst": ["kyllingebryst", "kylling filet"],
  "revet ost": ["revet ost"],
  "gaer": ["gaer"],
  "flode": ["floede"],
};

/**
 * Conservative suggestions: similarity is for ordering only.
 * Never commit a matched product as the household's chosen default.
 */
export function scoreCandidate(ingredientName: string, product: PrejProduct): number {
  const wanted = normalizeIngredient(ingredientName);
  const name = normalizeIngredient(product.name ?? "");
  if (!wanted || !name) return 0;

  const variations = aliases[wanted] ?? [wanted];
  if (variations.some((a) => name === a)) return 1;
  if (variations.some((a) => a.length >= 3 && matchesToken(name, a))) return 0.88;

  const parts = wanted.split(" ").filter((p) => p.length > 2);
  if (parts.length > 1 && parts.every((p) => matchesToken(name, p))) return 0.83;
  if (parts.length === 1 && matchesToken(name, parts[0])) return 0.78;
  return 0;
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

export function cleanedExactQuery(name: string): string {
  // Do not send a broad grouped name when fallback requires a specific match.
  return name.replace(/\([^)]*\)/g, "").replace(/[-/]+/g, " ").trim().slice(0, 200);
}
