package com.opsmx.aws.interceptor

import kotlinx.serialization.json.Json
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentials
import software.amazon.awssdk.core.interceptor.ExecutionAttribute
import software.amazon.awssdk.core.interceptor.ExecutionAttributes
import software.amazon.awssdk.core.interceptor.InterceptorContext
import software.amazon.awssdk.http.SdkHttpMethod
import software.amazon.awssdk.http.SdkHttpRequest
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model.ListBucketsRequest
import java.util.*
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class OpsmxAwsInterceptorTest {
    private val req = SdkHttpRequest.builder()
        .protocol("https")
        .host("original-host.example.com")
        .method(SdkHttpMethod.GET)
        .port(1234)
        .build()

    private val sdkRequest = ListBucketsRequest.builder()
        .build()

    private val modifyRequest = InterceptorContext.builder()
        .httpRequest(req)
        .request(sdkRequest)
        .build()

    fun makeAttributes(creds: AwsCredentials?) : ExecutionAttributes {
        return ExecutionAttributes.builder()
            .put(ExecutionAttribute("SigningRegion"), Region.US_EAST_1)
            .put(ExecutionAttribute("ServiceSigningName"), "s3")
            .put(ExecutionAttribute("AwsCredentials"), creds)
            .build()
    }

    @BeforeTest fun setupEnvironment() {
        System.setProperty("opsmx.controller.aws.hostname", "controller.example.com")
        System.setProperty("opsmx.controller.aws.port", "9876")
    }

    @Test fun handlesNullCredentials() {
        val i = OpsmxAwsInterceptor()
        val ret = i.modifyHttpRequest(modifyRequest, makeAttributes(null))
        assertEquals(req, ret)
    }

    @Test fun rejectsIfNotJWTlike() {
        val i = OpsmxAwsInterceptor()
        val credentials = AwsBasicCredentials.create("accessKey", "secretKey")
        val ret = i.modifyHttpRequest(modifyRequest, makeAttributes(credentials))
        assertEquals(req, ret)
    }

    @Test fun rejectsIfCantDecodeJWTlike() {
        val i = OpsmxAwsInterceptor()
        val credentials = AwsBasicCredentials.create("accessKey", "X.---.X")
        val ret = i.modifyHttpRequest(modifyRequest, makeAttributes(credentials))
        assertEquals(req, ret)
    }

    @Test fun RejectsIfNotIssuedByUs() {
        val i = OpsmxAwsInterceptor()

        val secret = Json.encodeToString(AgentToken.serializer(), AgentToken("not-opsmx"))
        val encodedSecret = Base64.getEncoder().withoutPadding().encodeToString(secret.toByteArray())

        val credentials = AwsBasicCredentials.create("accessKey", "X.${encodedSecret}.X")

        val ret = i.modifyHttpRequest(modifyRequest, makeAttributes(credentials))
        assertEquals(req, ret)
    }


    @Test fun mutates() {
        val i = OpsmxAwsInterceptor()

        val secret = Json.encodeToString(AgentToken.serializer(), AgentToken("opsmx"))
        val encodedSecret = Base64.getEncoder().withoutPadding().encodeToString(secret.toByteArray())

        val credentials = AwsBasicCredentials.create("accessKey", "X.${encodedSecret}.X")

        val ret = i.modifyHttpRequest(modifyRequest, makeAttributes(credentials))

        assertEquals("controller.example.com", ret.host())
        assertEquals(9876, ret.port())

        val headers = ret.headers()
        assertEquals(listOf("original-host.example.com"), headers["x-opsmx-original-host"])
        assertEquals(listOf("1234"), headers["x-opsmx-original-port"])
        assertEquals(listOf("us-east-1"), headers["x-opsmx-signing-region"])
        assertEquals(listOf("s3"), headers["x-opsmx-service-signing-name"])
        assertEquals(listOf(credentials.secretAccessKey()), headers["x-opsmx-token"])
    }
}
