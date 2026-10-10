/** Privileged RPCs allowed from the authenticated Bilka Edge Function. */
export const ALLOWED_BILKA_RPCS: ReadonlySet<string> = new Set([
  "prej_store_key",
  "prej_read_key",
  "prej_forget_key",
  "prej_record_key_check",
  "prej_reserve_call",
  "prej_complete_call",
  // Single-use, atomic checkout; this is not the same as a direct table write.
  "bilka_record_purchase",
  "bilka_update_prices_only",
  "bilka_nightly_scheduler_ready",
]);
