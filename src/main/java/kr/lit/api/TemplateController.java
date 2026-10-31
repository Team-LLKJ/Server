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
    @GetMapping Page list(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {return templates.list(page,size);}
    @GetMapping("/{id}") View detail(@PathVariable String id) {return templates.detail(id);}
    @PutMapping("/{id}") View update(@PathVariable String id,@Valid @RequestBody Input input) {return templates.update(id,input);}
    @DeleteMapping("/{id}") ResponseEntity<Void> delete(@PathVariable String id) {templates.delete(id);return ResponseEntity.noContent().build();}
    @PostMapping("/{id}/classes") @ResponseStatus(HttpStatus.CREATED)
    ClassView add(@PathVariable String id,@Valid @RequestBody ClassInput input) {return templates.addClass(id,input.className());}
    @GetMapping("/{id}/classes") java.util.List<ClassView> classes(@PathVariable String id) {return templates.classes(id);}
    @DeleteMapping("/{id}/classes/{classId}") ResponseEntity<Void> remove(@PathVariable String id,@PathVariable String classId) {
        templates.deleteClass(id,classId);return ResponseEntity.noContent().build();
    }
    @PostMapping("/{id}/classes/{classId}/start") ClassView start(@PathVariable String id,@PathVariable String classId,@Valid @RequestBody StartInput input) {
        return templates.start(id,classId,input.expiresInMinutes());
    }
    @PostMapping("/{id}/classes/{classId}/end") ClassView end(@PathVariable String id,@PathVariable String classId) {return templates.end(id,classId);}
}
