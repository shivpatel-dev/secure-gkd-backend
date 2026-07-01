package com.shiv.securegkd.idempotency;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class IdempotencyRecordRepositoryTests {

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Test
    void savesIdempotencyRecord() {
        deleteAll();

        IdempotencyRecord savedIdempotencyRecord = idempotencyRecordRepository.saveAndFlush(
                new IdempotencyRecord("idem-key-001")
        );

        assertThat(savedIdempotencyRecord.getId()).isNotNull();
        assertThat(savedIdempotencyRecord.getCreatedAt()).isNotNull();
        assertThat(savedIdempotencyRecord.getIdempotencyKey()).isEqualTo("idem-key-001");
    }

    @Test
    void findsIdempotencyRecordByIdempotencyKey() {
        deleteAll();
        idempotencyRecordRepository.saveAndFlush(new IdempotencyRecord("idem-key-001"));

        assertThat(idempotencyRecordRepository.findByIdempotencyKey("idem-key-001"))
                .isPresent()
                .get()
                .extracting(IdempotencyRecord::getIdempotencyKey)
                .isEqualTo("idem-key-001");
    }

    @Test
    void checksWhetherIdempotencyRecordExistsByIdempotencyKey() {
        deleteAll();
        idempotencyRecordRepository.saveAndFlush(new IdempotencyRecord("idem-key-001"));

        assertThat(idempotencyRecordRepository.existsByIdempotencyKey("idem-key-001")).isTrue();
        assertThat(idempotencyRecordRepository.existsByIdempotencyKey("idem-key-002")).isFalse();
    }

    @Test
    void rejectsDuplicateIdempotencyKey() {
        deleteAll();
        idempotencyRecordRepository.saveAndFlush(new IdempotencyRecord("idem-key-001"));

        assertThatThrownBy(() ->
                idempotencyRecordRepository.saveAndFlush(new IdempotencyRecord("idem-key-001"))
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    private void deleteAll() {
        idempotencyRecordRepository.deleteAll();
    }
}
