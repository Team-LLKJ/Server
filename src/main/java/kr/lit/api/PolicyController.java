package kr.lit.api;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/policies")
class PolicyController {
    private final ConsentService consents;
    PolicyController(ConsentService consents){this.consents=consents;}
    @GetMapping("/current") ConsentService.CurrentPolicies getCurrentPolicies(){return consents.getCurrentPolicies();}
}
