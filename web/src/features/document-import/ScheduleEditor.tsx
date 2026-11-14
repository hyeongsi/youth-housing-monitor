import { Button } from '../../components/ui'
import { ReviewField as Field } from './ReviewField'
import type { RecordValue } from '../../api'

type Props = { draft: RecordValue; add: (path: string, value: unknown) => void; remove: (path: string, index: number) => void }

export function ScheduleEditor({ draft, add, remove }: Props) {
  return <>{(draft.events||[]).map((_event:RecordValue,i:number)=><section className="repeat" key={i}><div className="editorGrid"><Field label="종류" path={`events[${i}].eventType`}/><Field label="시작" path={`events[${i}].startsAt`}/><Field label="종료" path={`events[${i}].endsAt`}/><Field label="상태" path={`events[${i}].status`}/><Field label="설명" path={`events[${i}].description`}/></div><Button type="button" tone="danger" onClick={()=>remove('events',i)}>삭제</Button></section>)}<Button type="button" tone="secondary" onClick={()=>add('events',event)}>일정 추가</Button></>
}
