import { fetchReadiness } from '@/lib/readiness';

// Rendered per request, on the server. Prerendering would freeze the API verdict
// at build time, and a build has no API to ask — worse, a failed build-time fetch
// would fail `next build` for a reason that has nothing to do with this code.
export const dynamic = 'force-dynamic';

export default async function Home() {
  const readiness = await fetchReadiness();
  const apiUp = readiness.state === 'up';

  return (
    <main className="page">
      <header className="masthead">
        <p className="eyebrow">Pilot build</p>
        <h1>Saathi</h1>
        <p className="lede">
          Local delivery, ordered from the shop down the road. This is the storefront shell: it exists to
          prove the browser, the web server and the API agree with each other before any catalogue is
          built on top of them.
        </p>
      </header>

      <section className="card" aria-labelledby="status-heading">
        <div className="card-head">
          <h2 id="status-heading">System status</h2>
          <span className={apiUp ? 'badge badge-up' : 'badge badge-down'} role="status">
            {apiUp ? 'API ready' : 'API not ready'}
          </span>
        </div>

        <dl className="facts">
          <div>
            <dt>Readiness</dt>
            <dd>{apiUp ? 'Accepting traffic' : 'Not accepting traffic'}</dd>
          </div>
          <div>
            <dt>Checked</dt>
            <dd>
              <time dateTime={readiness.checkedAt}>{new Date(readiness.checkedAt).toLocaleString('en-IN', { timeZone: 'Asia/Kolkata' })}</time> IST
            </dd>
          </div>
          {readiness.reason ? (
            <div>
              <dt>Why</dt>
              <dd>{readiness.reason}</dd>
            </div>
          ) : null}
        </dl>

        {readiness.components.length > 0 ? (
          <ul className="components" aria-label="Readiness components">
            {readiness.components.map((component) => (
              <li key={component.name}>
                <span>{component.name}</span>
                <span className={component.status === 'UP' ? 'dot dot-up' : 'dot dot-down'} aria-label={component.status} />
              </li>
            ))}
          </ul>
        ) : null}

        {apiUp ? null : (
          <p className="note">
            The API is checked from the web server, not the browser, so this page still loads when the
            backend is down. Nothing on it is a placeholder for real data: there is no catalogue until the
            product decisions behind it are signed off.
          </p>
        )}
      </section>
    </main>
  );
}
