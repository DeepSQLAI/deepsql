package com.dbaagent.model;

public enum AuthChallengeState {
    CREATED,
    OTP_SENT,
    OTP_VERIFIED,
    PENDING_MFA,
    CONSUMED,
    EXPIRED,
    FAILED
}
