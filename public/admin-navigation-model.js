export function announcementIdFromSearch(search) {
  const value = new URLSearchParams(search || '').get('announcementId');
  return value && value.trim() ? value.trim() : null;
}

export function adminAnnouncementLookupUrl(announcementId) {
  return announcementId ? `/api/admin/announcements/${encodeURIComponent(announcementId)}` : null;
}

export function publicAnnouncementLookupUrl(announcementId) {
  return announcementId ? `/api/announcements/${encodeURIComponent(announcementId)}` : null;
}

export function announcementSelectionState(announcement) {
  if (!announcement?.id) return null;
  return {
    id: String(announcement.id),
    query: announcement.title || '',
    label: `${announcement.title || '공고명 미기재'} · ${announcement.announcementDate || '날짜 미기재'}`,
    status: '선택한 공고에 PDF를 연결합니다.',
  };
}

export function selectionFailureMessage() {
  return '선택한 공고를 찾지 못했습니다. 공고를 다시 검색해 주세요.';
}
