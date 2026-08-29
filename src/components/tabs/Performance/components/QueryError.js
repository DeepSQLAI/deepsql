import { AlertTriangle } from "lucide-react";

/**
 * The error state for an async panel in the Performance tab.
 *
 * <p>Exists because every panel here rendered a failed fetch and an empty result
 * identically: the branch was `!isLoading && rows.length === 0`, and `data ?? []` turns
 * any error into an empty array. Clicking a customer whose row said "1 slow query · 12
 * executions" produced "No queries rolled up yet for this customer" when the request had
 * actually 404'd — the data existed and the UI reported absence.
 *
 * <p>That matters most for 403s: with connection authorization now enforced, a user
 * without access to a connection gets a denial, and rendering it as "nothing captured
 * yet" would send them to re-run an ingestion they cannot fix.
 */
export function QueryError({ error, what = "data", className }) {
  // `error.status`, not `error.response.status`: the axios response interceptor in
  // api/client.js rethrows a plain Error with the status copied onto it. Reading the
  // axios-shaped field silently never matches, so every denial fell through to the
  // generic branch. `responseData` is where the interceptor puts the parsed body.
  const status = error?.status ?? error?.response?.status;

  let message;
  if (status === 403) {
    message = `You don't have access to this connection's ${what}.`;
  } else if (status === 404) {
    message = `Not found — the ${what} may have been removed, or belong to another connection.`;
  } else if (status === 412) {
    message = `Not configured for this connection yet.`;
  } else {
    // Prefer the server's own wording; the axios interceptor puts it on `message`.
    message = error?.responseData?.message
      || error?.response?.data?.message
      || error?.message
      || `Could not load ${what}.`;
  }

  return (
    <div className={className} role="alert">
      <AlertTriangle size={13} aria-hidden="true" />{" "}
      <span>{message}</span>
      {status ? <span> (HTTP {status})</span> : null}
    </div>
  );
}

export default QueryError;
