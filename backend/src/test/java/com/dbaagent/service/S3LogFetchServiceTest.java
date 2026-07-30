package com.dbaagent.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class S3LogFetchServiceTest {

    @Test
    void parsesS3UriStyle() {
        S3LogFetchService service = new S3LogFetchService();
        S3LogFetchService.S3Location location = service.parseLocation("s3://my-bucket/logs/slow.log", null);

        assertEquals("my-bucket", location.getBucket());
        assertEquals("logs/slow.log", location.getKey());
    }

    @Test
    void parsesVirtualHostedStyle() {
        S3LogFetchService service = new S3LogFetchService();
        S3LogFetchService.S3Location location = service.parseLocation(
            "https://my-bucket.s3.us-west-2.amazonaws.com/logs/slow.log", null);

        assertEquals("my-bucket", location.getBucket());
        assertEquals("logs/slow.log", location.getKey());
        assertEquals("us-west-2", location.getRegion());
    }

    @Test
    void parsesPathStyle() {
        S3LogFetchService service = new S3LogFetchService();
        S3LogFetchService.S3Location location = service.parseLocation(
            "https://s3.us-east-1.amazonaws.com/my-bucket/logs/slow.log", null);

        assertEquals("my-bucket", location.getBucket());
        assertEquals("logs/slow.log", location.getKey());
        assertEquals("us-east-1", location.getRegion());
    }

    @Test
    void detectsPresignedUrls() {
        S3LogFetchService service = new S3LogFetchService();
        String url = "https://bucket.s3.us-east-2.amazonaws.com/slow.log?X-Amz-Signature=abc&X-Amz-Credential=def";

        assertEquals(true, service.isPresignedUrl(url));
    }
}
