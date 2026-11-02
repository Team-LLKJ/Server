package kr.lit.api;

import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static kr.lit.api.TemplateModels.*;

@RestController
@RequestMapping("/api/teacher/assessments")
class TemplateController {
    private final TemplateService templates;
    TemplateController(TemplateService templates) {this.templates=templates;}
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    View create(@Valid @RequestBody Input input) {return templates.create(input);}
    @GetMapping Page list(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="mine") String scope) {return templates.list(page,size,scope);}
    @GetMapping("/{id}") View detail(@PathVariable String id) {return templates.detail(id);}
    @PutMapping("/{id}") View update(@PathVariable String id,@Valid @RequestBody Input input) {return templates.update(id,input);}
    @DeleteMapping("/{id}") ResponseEntity<Void> delete(@PathVariable String id) {templates.delete(id);return ResponseEntity.noContent().build();}
    @PostMapping("/{id}/classes") @ResponseStatus(HttpStatus.CREATED)
    ClassView add(@PathVariable String id,@Valid @RequestBody ClassInput input) {return templates.addClass(id,input.className());}
    @GetMapping("/{id}/classes") java.util.List<ClassView> classes(@PathVariable String id) {return templates.classes(id);}
    @DeleteMapping("/{id}/classes/{classId}") ResponseEntity<Void> remove(@PathVariable String id,@PathVariable String classId) {
        templates.deleteClass(id,classId);return ResponseEntity.noContent().build();
    }
    @PatchMapping("/{id}/classes/{classId}") ClassView rename(@PathVariable String id,@PathVariable String classId,@Valid @RequestBody ClassInput input) {
        return templates.renameClass(id,classId,input.className());
    }
    @PostMapping("/{id}/collaborators") @ResponseStatus(HttpStatus.CREATED)
    Collaborator addCollaborator(@PathVariable String id,@Valid @RequestBody ShareInput input) {return templates.addCollaborator(id,input.email(),input.role());}
    @GetMapping("/{id}/collaborators") java.util.List<Collaborator> collaborators(@PathVariable String id) {return templates.collaborators(id);}
    @DeleteMapping("/{id}/collaborators/{teacherId}") ResponseEntity<Void> unshare(@PathVariable String id,@PathVariable String teacherId) {
        templates.unshare(id,teacherId);return ResponseEntity.noContent().build();
    }
    @PostMapping("/{id}/classes/{classId}/start") ClassView start(@PathVariable String id,@PathVariable String classId,@Valid @RequestBody StartInput input) {
        return templates.start(id,classId,input.expiresInMinutes());
    }
    @PostMapping("/{id}/classes/{classId}/end") ClassView end(@PathVariable String id,@PathVariable String classId) {return templates.end(id,classId);}
}
