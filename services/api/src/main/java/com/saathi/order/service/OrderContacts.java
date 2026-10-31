package com.saathi.order.service;

import com.saathi.identity.domain.UserAccount;
import com.saathi.identity.repository.UserAccountRepository;
import com.saathi.order.domain.Order;
import com.saathi.shared.contact.PhoneNormaliser;
import com.saathi.shared.security.ContactCipher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * §11.4's progressive disclosure of the customer's contact details, decided in one place.
 *
 * <p>The phone is stored encrypted and leaves here as plaintext only when the viewer is entitled to
 * it, so no endpoint has to remember the rule: a customer reading their own order and a shop reading
 * an order it is fulfilling both call this and get different answers. The shop's window is the
 * design's — 30 days after {@code delivered_at} the number re-masks by itself, because a shop that
 * fulfilled an order in March has no reason to reach that person in June.
 *
 * <p>No {@code audit_event} is written here. §11.4's "every reveal is audited" attaches to
 * {@code PII_REVEAL}, which is the operator's permission to see past a masked default; the merchant
 * row of that table grants full contact by role and time window, and auditing it would put a row in
 * {@code audit_event} for every queue refresh, which is how an audit trail becomes noise.
 */
@Component
@RequiredArgsConstructor
public class OrderContacts {

    private static final Duration SHOP_WINDOW = Duration.ofDays(30);

    private final UserAccountRepository accounts;
    private final ContactCipher cipher;
    private final ObjectMapper json;
    private final Clock clock;

    /** The three things §11.4's table decides, already resolved for this viewer. */
    public record Viewed(String name, String phone, String address) {
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Viewed forViewer(Order order, OrderViews.Viewer viewer) {
        String phone = cipher.decrypt(order.getContactPhoneCipher());
        boolean masked = viewer == OrderViews.Viewer.SHOP && pastShopWindow(order);
        return new Viewed(nameOf(order), masked ? PhoneNormaliser.mask(phone) : phone,
            addressOf(order));
    }

    /** §11.4 re-masks a shop's read 30 days after the order was delivered. */
    private boolean pastShopWindow(Order order) {
        Instant delivered = order.getDeliveredAt();
        return delivered != null && clock.instant().isAfter(delivered.plus(SHOP_WINDOW));
    }

    private String nameOf(Order order) {
        UserAccount customer = accounts.findById(order.getCustomerId()).orElse(null);
        if (customer == null || customer.getDisplayName() == null || customer.getDisplayName().isBlank()) {
            return "Customer";
        }
        return customer.getDisplayName();
    }

    /**
     * The address is a snapshot, not a live reference (§5.5): what the customer typed at placement,
     * kept even if they later edit their saved address. Written as an object by the order writer and
     * checked as one by the schema, so a malformed row here is a bug in this kernel, not bad data.
     */
    private String addressOf(Order order) {
        Map<String, Object> address = json.readValue(order.getAddressSnapshot(),
            new TypeReference<Map<String, Object>>() { });
        List<String> parts = new ArrayList<>(3);
        for (String key : List.of("line1", "landmark", "pincode")) {
            Object value = address.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                parts.add(String.valueOf(value));
            }
        }
        return String.join(", ", parts);
    }
}
