import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { ROOT, readJson } from './shared.mjs';

function toIcsDate(value) {
  return value.replace(/-/g, '').slice(0, 8);
}

function escapeIcs(value) {
  return String(value).replace(/\\/g, '\\\\').replace(/;/g, '\\;').replace(/,/g, '\\,').replace(/\n/g, '\\n');
}

const notices = await readJson('notices.json', []);
const created = new Date().toISOString().replace(/[-:]/g, '').replace(/\.\d{3}/, '');
const events = notices
  .filter((item) => item.applicationEnd)
  .flatMap((item) => {
    const deadline = item.applicationEnd.slice(0, 10);
    const reminder = new Date(`${deadline}T00:00:00+09:00`);
    reminder.setDate(reminder.getDate() - 3);
    return [
      { date: deadline, title: `마감: ${item.name}`, description: `${item.cost}\n${item.officialUrl}` },
      { date: reminder.toISOString().slice(0, 10), title: `3일 전 확인: ${item.name}`, description: `공고 원문·제출서류·신청 상태를 확인하세요.\n${item.officialUrl}` },
    ];
  });
const body = [
  'BEGIN:VCALENDAR',
  'VERSION:2.0',
  'PRODID:-//Seoul Youth Housing Monitor//KO',
  'CALSCALE:GREGORIAN',
  ...events.flatMap((event, index) => [
    'BEGIN:VEVENT',
    `UID:housing-${index}-${toIcsDate(event.date)}@local`,
    `DTSTAMP:${created}`,
    `DTSTART;VALUE=DATE:${toIcsDate(event.date)}`,
    `SUMMARY:${escapeIcs(event.title)}`,
    `DESCRIPTION:${escapeIcs(event.description)}`,
    'END:VEVENT',
  ]),
  'END:VCALENDAR',
  '',
].join('\r\n');
const outputDir = path.join(ROOT, 'output');
await mkdir(outputDir, { recursive: true });
const output = path.join(outputDir, 'housing-deadlines.ics');
await writeFile(output, body, 'utf8');
console.log(`Created ${output} with ${events.length} events.`);
