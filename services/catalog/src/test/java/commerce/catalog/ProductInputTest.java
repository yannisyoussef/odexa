package commerce.catalog;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProductInputTest {
    @Test
    void validatesRequiredFieldsAndMoneyBoundaries() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertTrue(validator.validate(new ProductInput("Tote", "", 0L, "USD", true)).isEmpty());
            assertFalse(validator.validate(new ProductInput(" ", "", 25L, "USD", true)).isEmpty());
            assertFalse(validator.validate(new ProductInput("Tote", "", -1L, "USD", true)).isEmpty());
            assertFalse(validator.validate(new ProductInput("Tote", "", 25L, "EUR", true)).isEmpty());
            assertEquals(5, validator.validate(new ProductInput(null, null, null, null, null)).size());
            assertFalse(validator.validate(new ProductInput("x".repeat(201), "", 25L, "USD", true)).isEmpty());
            assertFalse(validator.validate(new ProductInput("Tote", "x".repeat(2001), 25L, "USD", true)).isEmpty());
        }
    }
}
