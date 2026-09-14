package com.dbaagent.dto;

public record TableFacts(long rowEstimate, long sizeBytes, boolean empty) {}
