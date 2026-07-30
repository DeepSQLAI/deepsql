package com.dbaagent.controller;

import com.dbaagent.model.InviteCode;
import com.dbaagent.service.InviteCodeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/invite-codes")
public class InviteCodeController {

    @Autowired
    private InviteCodeService inviteCodeService;

    /**
     * Generate a new invite code (admin only).
     */
    @PostMapping
    public ResponseEntity<?> generateCode(@RequestBody Map<String, Object> request) {
        String createdBy = (String) request.get("createdBy");
        String note = (String) request.get("note");
        Integer maxUses = request.get("maxUses") != null
                ? ((Number) request.get("maxUses")).intValue()
                : 1;
        LocalDateTime expiresAt = null;
        if (request.get("expiresAt") != null) {
            expiresAt = LocalDateTime.parse((String) request.get("expiresAt"));
        }

        InviteCode inviteCode = inviteCodeService.generateCode(createdBy, note, maxUses, expiresAt);
        return ResponseEntity.ok(toResponse(inviteCode));
    }

    /**
     * List all invite codes (admin only).
     */
    @GetMapping
    public ResponseEntity<?> listAllCodes() {
        List<InviteCode> codes = inviteCodeService.listAllCodes();
        List<Map<String, Object>> response = codes.stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(response);
    }

    /**
     * List only valid invite codes (admin only).
     */
    @GetMapping("/valid")
    public ResponseEntity<?> listValidCodes() {
        List<InviteCode> codes = inviteCodeService.listValidCodes();
        List<Map<String, Object>> response = codes.stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(response);
    }

    /**
     * Get a specific invite code by ID (admin only).
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getCode(@PathVariable Long id) {
        Optional<InviteCode> inviteCodeOpt = inviteCodeService.getById(id);
        if (inviteCodeOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toResponse(inviteCodeOpt.get()));
    }

    /**
     * Deactivate an invite code (admin only).
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deactivateCode(@PathVariable Long id) {
        Optional<InviteCode> inviteCodeOpt = inviteCodeService.deactivateCode(id);
        if (inviteCodeOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of(
                "message", "Invite code deactivated",
                "code", toResponse(inviteCodeOpt.get())
        ));
    }

    /**
     * Validate an invite code (public endpoint for signup form).
     */
    @GetMapping("/validate/{code}")
    public ResponseEntity<?> validateCode(@PathVariable String code) {
        Optional<InviteCode> inviteCodeOpt = inviteCodeService.validateCode(code);

        Map<String, Object> response = new HashMap<>();
        response.put("valid", inviteCodeOpt.isPresent());
        response.put("code", code.toUpperCase());

        if (inviteCodeOpt.isPresent()) {
            InviteCode inviteCode = inviteCodeOpt.get();
            response.put("remainingUses", inviteCode.getRemainingUses());
            response.put("expiresAt", inviteCode.getExpiresAt());
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Convert InviteCode entity to response map.
     */
    private Map<String, Object> toResponse(InviteCode inviteCode) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", inviteCode.getId());
        map.put("code", inviteCode.getCode());
        map.put("maxUses", inviteCode.getMaxUses());
        map.put("currentUses", inviteCode.getCurrentUses());
        map.put("remainingUses", inviteCode.getRemainingUses());
        map.put("createdBy", inviteCode.getCreatedBy());
        map.put("note", inviteCode.getNote());
        map.put("active", inviteCode.getActive());
        map.put("valid", inviteCode.isValid());
        map.put("expiresAt", inviteCode.getExpiresAt());
        map.put("createdAt", inviteCode.getCreatedAt());
        return map;
    }
}
