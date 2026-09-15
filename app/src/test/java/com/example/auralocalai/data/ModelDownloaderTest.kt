package com.example.auralocalai.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class ModelDownloaderTest {

    private val downloader = ModelDownloader()

    @Test
    fun testPresignedUrlDetection() {
        val s3Url1 = "https://s3.amazonaws.com/models/gemma.bin?AWSAccessKeyId=AKIA123&Signature=xyz123".toHttpUrl()
        val s3Url2 = "https://cdn.huggingface.co/resolve/model.task?X-Amz-Signature=abcd9876".toHttpUrl()
        val plainUrl = "https://huggingface.co/litert-community/Qwen2.5-1.5B/resolve/main/model.litertlm".toHttpUrl()

        assertTrue(downloader.isPresignedUrl(s3Url1))
        assertTrue(downloader.isPresignedUrl(s3Url2))
        assertFalse(downloader.isPresignedUrl(plainUrl))
    }

    @Test
    fun testHuggingFaceDomainScopingPreventsAuthLeakage() {
        // Core HuggingFace domains where Authorization token is safe
        assertTrue(downloader.isHuggingFaceDomain("huggingface.co"))
        assertTrue(downloader.isHuggingFaceDomain("hf.co"))
        assertTrue(downloader.isHuggingFaceDomain("api-face.huggingface.co"))
        assertTrue(downloader.isHuggingFaceDomain("api.huggingface.co"))

        // Third-party CDNs and CloudFront / S3 domains where token MUST be stripped
        assertFalse(downloader.isHuggingFaceDomain("s3.amazonaws.com"))
        assertFalse(downloader.isHuggingFaceDomain("cloudfront.net"))
        assertFalse(downloader.isHuggingFaceDomain("d12345.cloudfront.net"))
        assertFalse(downloader.isHuggingFaceDomain("cdn-lfs.huggingface.co.fake.org"))
        assertFalse(downloader.isHuggingFaceDomain("malicious-site.com"))
    }
}
