package com.peekcart.global.deadletter;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Statement;

/** 같은 적재 트랜잭션에서 ID를 예약한다. gap은 허용하며 counter를 reset하지 않는다(ADR-0043). */
@Component
@RequiredArgsConstructor
public class DeadLetterIdAllocator {

    private final JdbcTemplate jdbcTemplate;

    @Transactional(propagation = Propagation.MANDATORY)
    public long allocate() {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> connection.prepareStatement(
                "INSERT INTO dead_letter_record_ids () VALUES ()", Statement.RETURN_GENERATED_KEYS), keys);
        Number generated = keys.getKey();
        if (generated == null || generated.longValue() <= 0 || generated.longValue() == Long.MAX_VALUE) {
            throw new IllegalStateException("DLQ ID 예약 범위가 유효하지 않거나 고갈됐다");
        }
        long id = generated.longValue();
        if (jdbcTemplate.update("DELETE FROM dead_letter_record_ids WHERE id = ?", id) != 1) {
            throw new IllegalStateException("방금 예약한 DLQ ID 행을 삭제하지 못했다");
        }
        return id;
    }
}
