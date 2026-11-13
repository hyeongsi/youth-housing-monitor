import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import {addAtPath,patchPayload,removeAtPath,setAtPath} from '../public/admin-import-model.js';
import {announcementIdFromSearch,adminAnnouncementLookupUrl,publicAnnouncementLookupUrl,announcementSelectionState,selectionFailureMessage} from '../public/admin-navigation-model.js';

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
  assert.ok(js.includes("new URLSearchParams(location.search)"));
  assert.ok(js.includes("selectedAnnouncementId"));
  assert.ok(js.includes('/api/admin/document-parser-options'));
  assert.ok(js.includes('existingExtractionId'));
  assert.ok(js.includes('result.duplicate'));
  assert.ok(js.includes("new Option('청약 사이트 선택','')"));
  assert.ok(js.includes("new Option('문서 유형 선택','')"));
  assert.match(html,/id="site-code-value"[^>]+name="siteCode"/);
  assert.match(html,/id="document-type-value"[^>]+name="documentType"/);
  assert.match(html,/aria-live=/);
  assert.match(css,/min-height:\s*44px/);
  assert.match(css,/\.evidence-list button\s*\{[^}]*min-height:\s*44px/s);
  assert.match(html,/data-review-panel="housing"/);
  assert.match(html,/data-review-panel="events"/);
  assert.match(html,/data-review-panel="rules"/);
  assert.ok(js.includes('renderCompleteness'));
  assert.ok(js.includes("'단지·위치'"));
  assert.ok(js.includes("'주택형·면적'"));
  assert.ok(js.includes("'보증금·월세'"));
  assert.ok(js.includes('confirmEvidence'));
  assert.ok(js.includes("'원문과 같음'"));
  assert.ok(js.includes("'직접 수정함'"));
  assert.ok(!js.includes('data-evidence-status'));
  assert.ok(js.includes('evidenceGroup'));
  assert.ok(js.includes('확인 필요 ${pending}건'));
  assert.ok(js.includes('panelChosen'));
  assert.ok(js.includes('renderActiveEvidence'));
  assert.ok(js.includes('selected-file-name'));
  assert.ok(js.includes('announcement-search-status'));
  assert.ok(js.includes('detail-revisions'));
  assert.match(html,/id="post-commit-actions"/);
  assert.match(html,/운영 화면에 반영/);
  assert.ok(js.includes('renderRevisionHistory'));
  assert.ok(js.includes('renderRevisionDiff'));
  assert.ok(js.includes('/diff'));
  for(const label of ['원문 일치','검토 필요','확인 완료','수동 확인 완료','시행사','임대사업자','시공사','공고','청약 신청','당첨자 발표','연령','무주택','소득','이하','이상','같음','범위','본문 추출','표 추출','직접 입력','검토 초안']) assert.ok(js.includes(label));
  assert.match(css,/\.review-layout\s*\{[^}]*grid-template-columns/s);
  assert.match(css,/@media\s*\(max-width:\s*\d+px\)/);
});

test('작업함 대상 공고는 PDF 검토와 사용자 상세 화면으로 안전하게 연결된다',async()=>{
  const [adminData,app]=await Promise.all([
    readFile(new URL('public/admin-data.js',root),'utf8'),
    readFile(new URL('public/app.js',root),'utf8'),
  ]);
  assert.ok(adminData.includes('/admin-import.html?announcementId=${encodeURIComponent(item.id)}'));
  assert.ok(adminData.includes('/?announcementId=${encodeURIComponent(item.id)}#announcements'));
  assert.ok(app.includes('announcementIdFromSearch(location.search)'));
  assert.ok(app.includes('publicAnnouncementLookupUrl(announcementId)'));
  assert.match(app,/openAnnouncementDrawer\(result\.announcement,\s*result\.details \|\| null,\s*null\)/);
});

test('URL 공고 선택은 실행 가능한 선택 상태를 만들고 잘못된 값은 안전하게 무시한다',()=>{
  assert.equal(announcementIdFromSearch('?announcementId=notice-1'),'notice-1');
  assert.equal(adminAnnouncementLookupUrl('notice 1'),'/api/admin/announcements/notice%201');
  assert.equal(publicAnnouncementLookupUrl('notice 1'),'/api/announcements/notice%201');
  assert.equal(announcementIdFromSearch('?announcementId=%20%20'),null);
  assert.equal(adminAnnouncementLookupUrl(null),null);
  assert.equal(publicAnnouncementLookupUrl(null),null);
  assert.deepEqual(announcementSelectionState({id:'notice-1',title:'테스트 공고',announcementDate:'2026-11-13'}),{
    id:'notice-1',query:'테스트 공고',label:'테스트 공고 · 2026-11-13',status:'선택한 공고에 PDF를 연결합니다.'
  });
  assert.equal(announcementSelectionState(null),null);
  assert.equal(selectionFailureMessage(),'선택한 공고를 찾지 못했습니다. 공고를 다시 검색해 주세요.');
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
