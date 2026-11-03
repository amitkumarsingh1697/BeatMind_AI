package ai.beatmind.billing;

import ai.beatmind.identity.CurrentUser;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** API-009 checkout, API-010 webhooks, the billing summary and cancellation. */
@RestController
class BillingController {

    record CheckoutRequest(@Size(max = 10) String plan, @Size(max = 2) String billingCountry, @Size(max = 1) String variant) {}

    record SimulateRequest(@Size(max = 20) String outcome, @Size(max = 20) String method) {}

    private final Billing billing;

    BillingController(Billing billing) {
        this.billing = billing;
    }

    @GetMapping("/v1/billing")
    Billing.Summary summary() {
        return billing.summary(CurrentUser.get().orElseThrow().id());
    }

    /** API-009. The price comes from the billing country the user confirms, not from where they browse. */
    @PostMapping("/v1/checkout")
    ResponseEntity<?> checkout(@RequestBody CheckoutRequest req) {
        CurrentUser me = CurrentUser.get().orElseThrow();
        if (!me.profileComplete()) {
            return problem(HttpStatus.FORBIDDEN, "profile_incomplete", "Finish setting up your account first.");
        }
        try {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(billing.checkout(me.id(), req.plan(), req.billingCountry(), req.variant()));
        } catch (Billing.Refusal r) {
            return problem(HttpStatus.valueOf(r.status), r.code, r.getMessage());
        }
    }

    /** US-037: the last of three steps (Your data, Cancel plan, Confirm). */
    @PostMapping("/v1/billing/cancel")
    ResponseEntity<?> cancel() {
        try {
            return ResponseEntity.ok(billing.cancel(CurrentUser.get().orElseThrow().id()));
        } catch (Billing.Refusal r) {
            return problem(HttpStatus.valueOf(r.status), r.code, r.getMessage());
        }
    }

    /** Stub mode only: plays the Razorpay events for an outcome through the webhook handler. */
    @PostMapping("/v1/billing/simulate")
    ResponseEntity<?> simulate(@RequestBody SimulateRequest req) {
        try {
            List<String> outcomes = billing.simulate(CurrentUser.get().orElseThrow().id(), req.outcome(), req.method());
            return ResponseEntity.ok(Map.of("outcomes", outcomes));
        } catch (Billing.Refusal r) {
            return problem(HttpStatus.valueOf(r.status), r.code, r.getMessage());
        }
    }

    /**
     * API-010. The raw body is read as bytes so the signature is checked on exactly what Razorpay
     * sent. A bad signature gets 401 and changes nothing; a repeat gets 200 so Razorpay stops.
     */
    @PostMapping("/v1/webhooks/razorpay")
    ResponseEntity<?> razorpay(@RequestBody byte[] raw,
                               @RequestHeader(name = "X-Razorpay-Signature", required = false) String signature,
                               @RequestHeader(name = "X-Razorpay-Event-Id", required = false) String eventId) {
        String body = new String(raw, StandardCharsets.UTF_8);
        if (!billing.signatureValid(body, signature)) {
            return problem(HttpStatus.UNAUTHORIZED, "bad_signature", "Signature does not match.");
        }
        return ResponseEntity.ok(Map.of("outcome", billing.handle(body, eventId)));
    }

    static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setProperty("code", code);
        return ResponseEntity.status(status).body(body);
    }
}
