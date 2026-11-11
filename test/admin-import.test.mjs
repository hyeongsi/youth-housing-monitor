import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import {addAtPath,patchPayload,removeAtPath,setAtPath} from '../public/admin-import-model.js';

const root=new URL('../',import.meta.url);

test('관리자 PDF 검토 화면은 업로드·근거·검증·확정 흐름을 제공한다',async()=>{
  const [html,js,css]=await Promise.all([
    readFile(new URL('public/admin-import.html',root),'utf8'),
    readFile(new URL('public/admin-import.js',root),'utf8'),
    readFile(new URL('public/admin-import.css',root),'utf8'),
  ]);
  for(const id of ['import-form','announcement-query','announcement-search-status','announcement-id','pdf-file','selected-file-name','analysis-status','pdf-frame','review','completeness-summary','active-evidence','evidence-list','sites-form','events-form','eligibility-form','save-draft','validate-draft','commit-draft','revision-history','revision-history-list']) assert.match(html,new RegExp(`id="${id}"`));
  assert.match(html,/name="file"[^>]+type="file"/);
  for(const endpoint of ['/api/admin/announcements','/api/admin/document-imports']) assert.ok(js.includes(endpoint));
  assert.ok(js.includes("new URLSearchParams(location.search).get('id')"));
  assert.match(html,/aria-live=/);
  assert.match(css,/min-height:\s*44px/);
  assert.match(css,/\.evidence-list button\s*\{[^}]*min-height:\s*44px/s);
  assert.match(html,/data-review-panel="housing"/);
  assert.match(html,/data-review-panel="events"/);
  assert.match(html,/data-review-panel="rules"/);
  assert.ok(js.includes('renderCompleteness'));
  assert.ok(js.includes('renderActiveEvidence'));
  assert.ok(js.includes('selected-file-name'));
  assert.ok(js.includes('announcement-search-status'));
  assert.ok(js.includes('detail-revisions'));
  assert.ok(js.includes('renderRevisionHistory'));
  assert.ok(js.includes('renderRevisionDiff'));
  assert.ok(js.includes('/diff'));
  for(const label of ['원문 일치','검토 필요','확인 완료','수동 확인 완료','시행사','임대사업자','시공사','공고','청약 신청','당첨자 발표','연령','무주택','소득','이하','이상','같음','범위','본문 추출','표 추출','직접 입력','검토 초안']) assert.ok(js.includes(label));
  assert.match(css,/\.review-layout\s*\{[^}]*grid-template-columns/s);
  assert.match(css,/@media\s*\(max-width:\s*\d+px\)/);
});

test('반복 공고 상세와 신청조건 전체 필드가 PATCH payload에 보존된다',()=>{
  const current={draft:{sites:[{name:'첫 단지',organizations:[],units:[{unitType:'A',rooms:[{roomText:'101호'}],rentalOptions:[{depositWon:1}]}]}],events:[],eligibilityRules:[]},evidence:[]};
  addAtPath(current.draft,'sites',{name:'둘째 단지',organizations:[],units:[]});
  addAtPath(current.draft,'sites[0].units[0].rooms',{roomText:'102호'});
  addAtPath(current.draft,'sites[0].units[0].rentalOptions',{depositWon:2,monthlyRentWon:3});
  addAtPath(current.draft,'eligibilityRules',{category:'INCOME',targetGroup:'신혼부부',rank:2,householdSize:4,operator:'LTE',thresholdValue:1234567,thresholdUnit:'원',description:'기준'});
  setAtPath(current.draft,'sites[1].name','수정한 둘째 단지');
  removeAtPath(current.draft,'sites[0].units[0].rooms',0);
  const payload=patchPayload(current);
  assert.equal(payload.draft.sites.length,2);
  assert.equal(payload.draft.sites[1].name,'수정한 둘째 단지');
  assert.deepEqual(payload.draft.sites[0].units[0].rooms,[{roomText:'102호'}]);
  assert.equal(payload.draft.sites[0].units[0].rentalOptions.length,2);
  assert.deepEqual(payload.draft.eligibilityRules[0],{category:'INCOME',targetGroup:'신혼부부',rank:2,householdSize:4,operator:'LTE',thresholdValue:1234567,thresholdUnit:'원',description:'기준'});
});
