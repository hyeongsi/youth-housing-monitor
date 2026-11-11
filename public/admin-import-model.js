export function pathParts(path){return [...path.matchAll(/([^.\[\]]+)|\[(\d+)\]/g)].map(match=>match[1]??Number(match[2]));}
export function getAtPath(root,path){return pathParts(path).reduce((value,key)=>value?.[key],root);}
export function setAtPath(root,path,value){const parts=pathParts(path);let target=root;for(let index=0;index<parts.length-1;index++)target=target[parts[index]];target[parts.at(-1)]=value;return root;}
export function addAtPath(root,path,value){getAtPath(root,path).push(structuredClone(value));return root;}
export function removeAtPath(root,path,index){getAtPath(root,path).splice(index,1);return root;}
export function patchPayload(current){return {draft:structuredClone(current.draft),evidenceReviews:(current.evidence||[]).map(item=>({id:item.id,fieldPath:item.fieldPath,pageNumber:item.pageNumber,rawText:item.rawText,reviewStatus:item.reviewStatus,deleted:Boolean(item.deleted)}))};}
