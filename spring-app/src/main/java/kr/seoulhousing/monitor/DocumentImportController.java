package kr.seoulhousing.monitor;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
class DocumentImportController {
    private final DocumentImportService service;
    DocumentImportController(DocumentImportService service){this.service=service;}

    @GetMapping("/announcements") List<Map<String,Object>> announcements(@RequestParam(defaultValue="") String query){return service.announcements(query);}
    @PostMapping(value="/document-imports",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<Map<String,Object>> create(@RequestParam String siteCode,@RequestParam String documentType,@RequestParam String announcementId,@RequestPart("file") MultipartFile file){Map<String,Object> result=service.create(siteCode,documentType,announcementId,file);return ResponseEntity.status(Boolean.TRUE.equals(result.get("duplicate"))?HttpStatus.OK:HttpStatus.CREATED).body(result);}
    @GetMapping("/announcements/{announcementId}") Map<String,Object> announcement(@PathVariable String announcementId){return service.announcement(announcementId);}
    @GetMapping("/document-parser-options") List<Map<String,Object>> parserOptions(@RequestParam String announcementId){return service.parserOptions(announcementId);}
    @GetMapping("/document-imports/{id}") Map<String,Object> get(@PathVariable String id){return service.get(id);}
    @GetMapping("/document-imports") Map<String,Object> list(@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="") String query,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){if(page<1||size<1||size>50)throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,"페이지 조건이 올바르지 않습니다.");return service.list(status,query,page,size);}
    @GetMapping("/announcement-work-items") Map<String,Object> workItems(@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="") String query,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){if(page<1||size<1||size>50)throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,"페이지 조건이 올바르지 않습니다.");return service.workItems(status,query,page,size);}
    @PatchMapping("/document-imports/{id}") Map<String,Object> update(@PathVariable String id,@RequestBody Map<String,Object> draft){return service.update(id,draft);}
    @PostMapping("/document-imports/{id}/validate") Map<String,Object> validate(@PathVariable String id){return service.validate(id);}
    @PostMapping("/document-imports/{id}/commit") Map<String,Object> commit(@PathVariable String id){return service.commit(id);}
    @GetMapping("/announcements/{announcementId}/detail-revisions") List<Map<String,Object>> revisions(@PathVariable String announcementId){return service.revisions(announcementId);}
    @GetMapping("/announcements/{announcementId}/detail-revisions/{revisionId}/diff") Map<String,Object> revisionDiff(@PathVariable String announcementId,@PathVariable String revisionId){return service.revisionDiff(announcementId,revisionId);}
    @PostMapping("/announcements/{announcementId}/detail-revisions/{revisionId}/restore") ResponseEntity<Void> restore(@PathVariable String announcementId,@PathVariable String revisionId){service.restore(announcementId,revisionId);return ResponseEntity.noContent().build();}
    @GetMapping(value="/document-imports/{id}/document",produces=MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<FileSystemResource> document(@PathVariable String id){FileSystemResource resource=new FileSystemResource(service.document(id));return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).header(HttpHeaders.CONTENT_DISPOSITION,"inline; filename=review-document.pdf").body(resource);}
}
