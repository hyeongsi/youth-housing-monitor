import { readJson } from './shared.mjs';

const [profile, notices, programs, state, discovered, allAnnouncements] = await Promise.all([
  readJson('profile.json', {}),
  readJson('notices.json', []),
  readJson('programs.json', []),
  readJson('monitor-state.json', null),
  readJson('auto-discovered.json', []),
  readJson('all-announcements.json', { itemCount: 0, mode: null }),
]);
console.log(JSON.stringify({
  profile: {
    age: profile.age,
    residenceCity: profile.residenceCity,
    targetMoveBy: profile.targetMoveBy,
    unconfirmed: Object.entries(profile).filter(([, value]) => value === '미확인').map(([key]) => key),
  },
  records: {
    programs: programs.length,
    curatedNotices: notices.length,
    allAnnouncements: allAnnouncements.itemCount || 0,
    allAnnouncementsMode: allAnnouncements.mode || '아직 수집하지 않음',
    unreviewedDiscoveries: discovered.length,
  },
  monitorLastRun: state?.lastRunAt || '아직 실행하지 않음',
}, null, 2));
