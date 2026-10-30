import { describe, expect, it } from 'vitest';

import { parseReadiness, readinessUrl } from './readiness';

describe('readinessUrl', () => {
  it('appends the Actuator path', () => {
    expect(readinessUrl('http://api:8080')).toBe('http://api:8080/actuator/health/readiness');
  });

  it('does not double the slash when the base URL ends in one', () => {
    expect(readinessUrl('http://api:8080/')).toBe('http://api:8080/actuator/health/readiness');
    expect(readinessUrl('http://api:8080//')).toBe('http://api:8080/actuator/health/readiness');
  });
});

describe('parseReadiness', () => {
  it('reads an up document and its components', () => {
    const report = parseReadiness({
      status: 'UP',
      components: {
        db: { status: 'UP' },
        readinessState: { status: 'UP' },
      },
    });

    expect(report.state).toBe('up');
    expect(report.status).toBe('UP');
    expect(report.components).toEqual([
      { name: 'db', status: 'UP' },
      { name: 'readinessState', status: 'UP' },
    ]);
  });

  it('treats OUT_OF_SERVICE as down even though the request succeeded', () => {
    const report = parseReadiness({ status: 'OUT_OF_SERVICE', components: { db: { status: 'UP' } } });

    expect(report.state).toBe('down');
    expect(report.status).toBe('OUT_OF_SERVICE');
  });

  it('treats a missing status as down rather than assuming health', () => {
    expect(parseReadiness({ components: {} }).state).toBe('down');
  });

  it('survives a body that is not a health document', () => {
    expect(parseReadiness('<html>Bad Gateway</html>')).toEqual({ state: 'down', status: null, components: [] });
    expect(parseReadiness(null)).toEqual({ state: 'down', status: null, components: [] });
  });

  it('skips components whose status is not a string', () => {
    const report = parseReadiness({ status: 'DOWN', components: { db: { status: 42 }, cache: 'UP' } });

    expect(report.components).toEqual([]);
  });

  it('is case-insensitive about status values', () => {
    expect(parseReadiness({ status: 'up' }).state).toBe('up');
  });
});
