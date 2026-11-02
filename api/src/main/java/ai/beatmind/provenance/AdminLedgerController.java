package ai.beatmind.provenance;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lets an admin check every provenance chain (API-014, admin role only). */
@RestController
class AdminLedgerController {

    private final ProvenanceLedger ledger;

    AdminLedgerController(ProvenanceLedger ledger) {
        this.ledger = ledger;
    }

    @GetMapping("/v1/admin/ledger/verify")
    ProvenanceLedger.Summary verify() {
        return ledger.verifyAll();
    }
}
