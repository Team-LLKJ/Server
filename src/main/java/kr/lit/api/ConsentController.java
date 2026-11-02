package kr.lit.api;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import static kr.lit.api.ConsentModels.*;

@RestController
@RequestMapping("/api/users/me/consents")
public class ConsentController {
    private final ConsentService consents;
    public ConsentController(ConsentService consents) {this.consents=consents;}
    @GetMapping public ConsentResponse getConsents() {return consents.getConsents();}
    @PostMapping public ConsentResponse acceptConsents(@Valid @RequestBody ConsentRequest request) {return consents.acceptConsents(request);}
    @GetMapping("/history") public ConsentHistoryResponse getConsentHistory() {return consents.getConsentHistory();}
}
