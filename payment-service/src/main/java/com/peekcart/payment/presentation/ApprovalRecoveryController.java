package com.peekcart.payment.presentation;

import com.peekcart.payment.application.ApprovalRecoveryService;
import com.peekcart.global.response.ApiResponse;
import com.peekcart.payment.domain.model.ApprovalRecovery;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 운영 principal로만 접근한다. 증빙 원문/paymentKey는 응답하지 않는다. */
@RestController
@RequestMapping("/api/v1/payments/approval-recoveries")
@RequiredArgsConstructor
public class ApprovalRecoveryController {
    private final ApprovalRecoveryService service;
    @GetMapping("/{id}")
    public ApiResponse<RecoveryResponse> read(@PathVariable String id) {
        return ApiResponse.of(RecoveryResponse.from(service.read(id)));
    }
    @PostMapping("/orders/{orderId}")
    public ApiResponse<RecoveryResponse> request(@PathVariable Long orderId, @Valid @RequestBody Reason request) {
        return ApiResponse.of(RecoveryResponse.from(service.request(orderId, request.reason())));
    }
    @PostMapping("/{id}/approve")
    public ApiResponse<RecoveryResponse> approve(@PathVariable String id, @Valid @RequestBody Reason request) {
        return ApiResponse.of(RecoveryResponse.from(service.approve(id, request.reason())));
    }
    @PostMapping("/{id}/execute")
    public ApiResponse<RecoveryResponse> execute(@PathVariable String id, @Valid @RequestBody Reason request) {
        return ApiResponse.of(RecoveryResponse.from(service.execute(id, request.reason())));
    }
    @PostMapping("/{id}/resume")
    public ApiResponse<RecoveryResponse> resume(@PathVariable String id, @Valid @RequestBody Reason request) {
        return ApiResponse.of(RecoveryResponse.from(service.resume(id, request.reason())));
    }
    @PostMapping("/{id}/retry")
    public ApiResponse<RecoveryResponse> retry(@PathVariable String id, @Valid @RequestBody Reason request) {
        return ApiResponse.of(RecoveryResponse.from(service.retry(id, request.reason())));
    }
    public record Reason(@NotBlank @Size(max = 500) String reason) {}
    public record RecoveryResponse(String id, Long orderId, String status, long generation) {
        static RecoveryResponse from(ApprovalRecovery recovery) {
            return new RecoveryResponse(recovery.getId(), recovery.getOrderId(), recovery.getStatus(), recovery.getGeneration());
        }
    }
}
