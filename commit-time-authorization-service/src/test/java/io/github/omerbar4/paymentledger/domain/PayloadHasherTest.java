package io.github.omerbar4.paymentledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PayloadHasherTest {

    @Test
    void matchesPythonPhashFieldOrder() {
        // hashlib.sha256(b"1|2|500|AUTH-1").hexdigest()
        assertThat(PayloadHasher.hash("1", "2", "500", "AUTH-1"))
                .isEqualTo("af4d76615a3f81b1ff6129ed728bd50c839019c0bae6b9cc5a6d36c999d6b187");
    }
}
