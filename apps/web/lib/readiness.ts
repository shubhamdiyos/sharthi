// Server-side readiness check.
//
// This module must never be imported from a client component: it reads
// API_BASE_URL, which is the address *inside* the Compose network and is not a
// browser-reachable URL. NEXT_PUBLIC_API_BASE_URL exists separately for the
// browser, and nothing here touches it.

/** Long enough for a cold Actuator check, short enough that a dead API does not hang the page. */
const TIMEOUT_MS = 3_000;

/** Where the API lives when nothing says otherwise: a developer running `mvn spring-boot:run`. */
const DEFAULT_BASE_URL = 'http://localhost:8080';

export interface ComponentStatus {
  name: string;
  status: string;
}

export interface ReadinessReport {
  state: 'up' | 'down';
  status: string | null;
  components: ComponentStatus[];
  /** Why the API is down, in a form safe to render and log. Null when up. */
  reason: string | null;
  checkedAt: string;
}

export function readinessUrl(baseUrl: string): string {
  return `${baseUrl.replace(/\/+$/, '')}/actuator/health/readiness`;
}

/**
 * Reads the Actuator payload without trusting it.
 *
 * A readiness document is `{"status":"UP","components":{...}}`, but any key can
 * be missing and the body can be an error page, so every field is checked rather
 * than cast. Only `status === "UP"` counts as up: `OUT_OF_SERVICE`, `UNKNOWN`,
 * and an absent status are all reported as down, because a page that says "fine"
 * while the API is not is worse than no page.
 */
export function parseReadiness(payload: unknown): Pick<ReadinessReport, 'state' | 'status' | 'components'> {
  if (typeof payload !== 'object' || payload === null) {
    return { state: 'down', status: null, components: [] };
  }

  const record = payload as Record<string, unknown>;
  const rawStatus = record.status;
  const status = typeof rawStatus === 'string' ? rawStatus.toUpperCase() : null;

  const components: ComponentStatus[] = [];
  const rawComponents = record.components;
  if (typeof rawComponents === 'object' && rawComponents !== null) {
    for (const [name, value] of Object.entries(rawComponents as Record<string, unknown>)) {
      if (typeof value === 'object' && value !== null) {
        const componentStatus = (value as Record<string, unknown>).status;
        if (typeof componentStatus === 'string') {
          components.push({ name, status: componentStatus.toUpperCase() });
        }
      }
    }
    components.sort((a, b) => a.name.localeCompare(b.name));
  }

  return { state: status === 'UP' ? 'up' : 'down', status, components };
}

function describeError(error: unknown): string {
  if (error instanceof Error) {
    if (error.name === 'TimeoutError') {
      return `no answer within ${TIMEOUT_MS} ms`;
    }
    // The cause carries the socket code (ECONNREFUSED, ENOTFOUND). The message
    // itself is not rendered: it can echo the internal hostname.
    const cause = (error as { cause?: { code?: unknown } }).cause;
    if (cause && typeof cause.code === 'string') {
      return `unreachable (${cause.code})`;
    }
    return `unreachable (${error.name})`;
  }
  return 'unreachable';
}

export async function fetchReadiness(
  baseUrl: string = process.env.API_BASE_URL ?? DEFAULT_BASE_URL,
): Promise<ReadinessReport> {
  const checkedAt = new Date().toISOString();

  try {
    const response = await fetch(readinessUrl(baseUrl), {
      // A health answer cached for even a few seconds is a status page that lies.
      cache: 'no-store',
      headers: { accept: 'application/json' },
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });

    // Actuator answers 503 with a JSON body when a component is down. That body
    // is the useful part, so it is parsed rather than discarded on !ok.
    const payload: unknown = await response.json();
    const parsed = parseReadiness(payload);

    if (parsed.state === 'up' && !response.ok) {
      return { ...parsed, state: 'down', reason: `HTTP ${response.status}`, checkedAt };
    }

    return {
      ...parsed,
      reason: parsed.state === 'down' ? `status ${parsed.status ?? 'unknown'}` : null,
      checkedAt,
    };
  } catch (error) {
    return { state: 'down', status: null, components: [], reason: describeError(error), checkedAt };
  }
}
