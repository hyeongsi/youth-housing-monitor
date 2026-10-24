import test from 'node:test';
import assert from 'node:assert/strict';
import { absoluteUrl, stripHtml } from '../src/shared.mjs';

test('stripHtml removes markup and decodes common entities', () => {
  assert.equal(stripHtml('<p>청년&nbsp;<strong>임대</strong>&amp;지원</p>'), '청년 임대 &지원');
});

test('absoluteUrl resolves a relative official notice link', () => {
  assert.equal(absoluteUrl('/notice/1', 'https://housing.seoul.go.kr/list'), 'https://housing.seoul.go.kr/notice/1');
});
