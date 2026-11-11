import test from 'node:test';
import assert from 'node:assert/strict';
import { buildStatus } from '../src/status.mjs';

test('status recognizes the database compatibility export as a completed full collection', () => {
  const exportedCatalog = { schemaVersion: 1, checkedAt: '2026-11-06T12:00:00', mode: 'full', itemCount: 2400, items: [] };
  const status = buildStatus({ allAnnouncements: exportedCatalog });
  assert.equal(status.records.allAnnouncements, 2400);
  assert.equal(status.records.allAnnouncementsMode, 'full');
});
