package com.dbaagent.service;

import java.io.InputStream;

/** Result of a log fetch: the stream plus whether the fetch was size-truncated. */
public record LogFetchResult(InputStream stream, boolean truncated) {}
