import { Button } from '../../components/ui'
import { ReviewField as Field } from './ReviewField'
import type { RecordValue } from '../../api'
import { emptyEligibilityRule as rule } from './draft'

type Props = { draft: RecordValue; add: (path: string, value: unknown) => void; remove: (path: string, index: number) => void }

export function EligibilityEditor({ draft, add, remove }: Props) {
  return <>{(draft.eligibilityRules||[]).map((_rule:RecordValue,i:number)=><section className="repeat" key={i}><div className="editorGrid"><Field label="종류" path={`eligibilityRules[${i}].category`}/><Field label="대상" path={`eligibilityRules[${i}].targetGroup`}/><Field label="순위" path={`eligibilityRules[${i}].rank`} type="number"/><Field label="가구원 수" path={`eligibilityRules[${i}].householdSize`} type="number"/><Field label="연산자" path={`eligibilityRules[${i}].operator`}/><Field label="기준값" path={`eligibilityRules[${i}].thresholdValue`} type="number"/><Field label="단위" path={`eligibilityRules[${i}].thresholdUnit`}/><Field label="설명" path={`eligibilityRules[${i}].description`}/></div><Button type="button" tone="danger" onClick={()=>remove('eligibilityRules',i)}>삭제</Button></section>)}<Button type="button" tone="secondary" onClick={()=>add('eligibilityRules',rule)}>조건 추가</Button></>
}
