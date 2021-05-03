/*
 * Copyright 2021 OpsMx, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.opsmx.aws.interceptor

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
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

@Serializable
data class AgentToken(@SerialName("iss") val issuer: String?)

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
