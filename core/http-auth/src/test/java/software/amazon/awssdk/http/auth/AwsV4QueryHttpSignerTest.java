/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.http.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static software.amazon.awssdk.http.auth.AwsV4QueryHttpSigner.EXPIRATION_INSTANT;
import static software.amazon.awssdk.http.auth.TestUtils.generateBasicAsyncRequest;
import static software.amazon.awssdk.http.auth.TestUtils.generateBasicRequest;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.spi.AsyncSignRequest;
import software.amazon.awssdk.http.auth.spi.AsyncSignedRequest;
import software.amazon.awssdk.http.auth.spi.SyncSignRequest;
import software.amazon.awssdk.http.auth.spi.SyncSignedRequest;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;

class AwsV4QueryHttpSignerTest {

    private static final AwsV4QueryHttpSigner signer = AwsV4QueryHttpSigner.create();

    @Test
    public void sign_withBasicRequest_shouldSign() {
        final String expectedAmzSignature = "bf7ae1c2f266d347e290a2aee7b126d38b8a695149d003b9fab2ed1eb6d6ebda";
        final String expectedAmzCredentials = "access/19810216/us-east-1/demo/aws4_request";
        final String expectedAmzDateHeader = "19810216T063000Z";
        final String expectedAmzExpires = "604800";

        SyncSignRequest<? extends AwsCredentialsIdentity> request = generateBasicRequest(
            SdkHttpRequest.builder(),
            SyncSignRequest.builder(AwsCredentialsIdentity.create("access", "secret"))
        );

        SyncSignedRequest signedRequest = signer.sign(request);

        assertEquals(expectedAmzSignature, signedRequest.request().rawQueryParameters().get("X-Amz-Signature").get(0));
        assertEquals(expectedAmzCredentials, signedRequest.request().rawQueryParameters().get("X-Amz-Credential").get(0));
        assertEquals(expectedAmzDateHeader, signedRequest.request().rawQueryParameters().get("X-Amz-Date").get(0));
        assertEquals(expectedAmzExpires, signedRequest.request().rawQueryParameters().get("X-Amz-Expires").get(0));
    }

    @Test
    public void sign_withInvalidExpiration_shouldThrow() {
        SyncSignRequest<? extends AwsCredentialsIdentity> request = generateBasicRequest(
            SdkHttpRequest.builder(),
            SyncSignRequest.builder(AwsCredentialsIdentity.create("access", "secret"))
                .putProperty(EXPIRATION_INSTANT, Instant.MAX)
        );

        RuntimeException exception = assertThrows(RuntimeException.class, () -> signer.sign(request));

        assertThat(exception.getMessage()).contains("valid for at most 7 days");
    }

    /**
     * Tests that if passed anonymous credentials, signer will not generate a signature.
     */
    @Test
    public void sign_withAnonymousCredentials_shouldNotSign() {
        SyncSignRequest<? extends AwsCredentialsIdentity> request = generateBasicRequest(
            SdkHttpRequest.builder(),
            SyncSignRequest.builder(new TestUtils.AnonymousCredentialsIdentity())
        );

        SyncSignedRequest signedRequest = signer.sign(request);

        assertEquals(signedRequest.request().rawQueryParameters().size(), 0);
    }

    @Test
    public void signAsync_withBasicRequest_shouldSign() {
        final String expectedAmzSignature = "bf7ae1c2f266d347e290a2aee7b126d38b8a695149d003b9fab2ed1eb6d6ebda";
        final String expectedAmzCredentials = "access/19810216/us-east-1/demo/aws4_request";
        final String expectedAmzDateHeader = "19810216T063000Z";
        final String expectedAmzExpires = "604800";

        AsyncSignRequest<? extends AwsCredentialsIdentity> request = generateBasicAsyncRequest(
            SdkHttpRequest.builder(),
            AsyncSignRequest.builder(AwsCredentialsIdentity.create("access", "secret"))
        );

        AsyncSignedRequest signedRequest = signer.signAsync(request);

        assertEquals(expectedAmzSignature, signedRequest.request().rawQueryParameters().get("X-Amz-Signature").get(0));
        assertEquals(expectedAmzCredentials, signedRequest.request().rawQueryParameters().get("X-Amz-Credential").get(0));
        assertEquals(expectedAmzDateHeader, signedRequest.request().rawQueryParameters().get("X-Amz-Date").get(0));
        assertEquals(expectedAmzExpires, signedRequest.request().rawQueryParameters().get("X-Amz-Expires").get(0));
    }
}