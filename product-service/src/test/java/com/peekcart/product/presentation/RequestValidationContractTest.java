package com.peekcart.product.presentation;

import com.peekcart.global.exception.GlobalExceptionHandler;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RequestValidationContractTest {
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new ValidationController())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void inputConstraintViolationIs400() throws Exception {
        mvc.perform(get("/validation").param("size", "0"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SYS-003"));
    }

    @Test
    void wrongQueryTypeIs400() throws Exception {
        mvc.perform(get("/validation").param("size", "abc"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SYS-003"));
    }

    @Test
    void validInputRemainsSuccessful() throws Exception {
        mvc.perform(get("/validation").param("size", "1"))
                .andExpect(status().isOk()).andExpect(content().string("1"));
    }

    @Test
    void invalidReturnValueRemainsServerFailure() throws Exception {
        mvc.perform(get("/invalid-return"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("SYS-001"));
    }

    @RestController
    static class ValidationController {
        @GetMapping("/validation")
        int input(@RequestParam @Min(1) int size) { return size; }

        @GetMapping("/invalid-return")
        @NotNull String output() { return null; }
    }
}
