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

package software.amazon.awssdk.auth.signer;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.core.SelectedAuthScheme;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.SdkInternalExecutionAttribute;
import software.amazon.awssdk.http.auth.aws.AwsV4FamilyHttpSigner;
import software.amazon.awssdk.http.auth.aws.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.aws.AwsV4aHttpSigner;
import software.amazon.awssdk.http.auth.spi.AuthSchemeOption;
import software.amazon.awssdk.http.auth.spi.HttpSigner;
import software.amazon.awssdk.http.auth.spi.SignerProperty;
import software.amazon.awssdk.identity.spi.Identity;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.RegionScope;

class AwsSignerExecutionAttributeTest {
    private static final SelectedAuthScheme<Identity> EMPTY_SELECTED_AUTH_SCHEME =
        new SelectedAuthScheme<>(CompletableFuture.completedFuture(Mockito.mock(Identity.class)),
                                 (HttpSigner<Identity>) Mockito.mock(HttpSigner.class),
                                 AuthSchemeOption.builder().schemeId("mock").build());

    private ExecutionAttributes attributes;
    private Clock testClock;

    @BeforeEach
    public void setup() {
        this.attributes = new ExecutionAttributes();
        this.testClock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
        AwsSignerExecutionAttribute.presignerExpirationClock(testClock);
    }

    @Test
    public void awsCredentials_oldAndNewAttributeAreMirrored() {
        AwsCredentials creds = Mockito.mock(AwsCredentials.class);

        // If selected auth scheme is null, writing non-null old property can be read with new property
        attributes.putAttribute(SdkInternalExecutionAttribute.SELECTED_AUTH_SCHEME, null);
        attributes.putAttribute(AwsSignerExecutionAttribute.AWS_CREDENTIALS, creds);
        assertThat(attributes.getAttribute(SdkInternalExecutionAttribute.SELECTED_AUTH_SCHEME).identity().join()).isSameAs(creds);

        // If selected auth scheme is null, writing null to old property can be read with new property
        attributes.putAttribute(SdkInternalExecutionAttribute.SELECTED_AUTH_SCHEME, null);
        attributes.putAttribute(AwsSignerExecutionAttribute.AWS_CREDENTIALS, null);
        assertThat(attributes.getAttribute(SdkInternalExecutionAttribute.SELECTED_AUTH_SCHEME).identity().join()).isNull();

        // If selected auth scheme is non-null, writing non-null to old property can be read with new property
        attributes.putAttribute(SdkInternalExecutionAttribute.SELECTED_AUTH_SCHEME, EMPTY_SELECTED_AUTH_SCHEME);
        attributes.putAttribute(AwsSignerExecutionAttribute.AWS_CREDENTIALS, creds);
        assertThat(attributes.getAttribute(SdkInternalExecutionAttribute.SELECTED_AUTH_SCHEME).identity().join()).isSameAs(creds);

        // If selected auth scheme is non-null, writing null to old property can be read with new property
        attributes.putAttribute(SdkInternalExecutionAttribute.SELECTED_AUTH_SCHEME, EMPTY_SELECTED_AUTH_SCHEME);
        attributes.putAttribute(AwsSignerExecutionAttribute.AWS_CREDENTIALS, null);
        assertThat(attributes.getAttribute(SdkInternalExecutionAttribute.SELECTED_AUTH_SCHEME).identity().join()).isNull();

        // Writing non-null new property can be read with old property
        attributes.putAttribute(SdkInternalExecutionAttribute.SELECTED_AUTH_SCHEME,
                                new SelectedAuthScheme<>(CompletableFuture.completedFuture(creds),
                                                         EMPTY_SELECTED_AUTH_SCHEME.signer(),
                                                         EMPTY_SELECTED_AUTH_SCHEME.authSchemeOption()));
        assertThat(attributes.getAttribute(AwsSignerExecutionAttribute.AWS_CREDENTIALS)).isSameAs(creds);

        // Writing null new property can be read with old property
        attributes.putAttribute(SdkInternalExecutionAttribute.SELECTED_AUTH_SCHEME,
                                new SelectedAuthScheme<>(CompletableFuture.completedFuture(null),
                                                         EMPTY_SELECTED_AUTH_SCHEME.signer(),
                                                         EMPTY_SELECTED_AUTH_SCHEME.authSchemeOption()));
        assertThat(attributes.getAttribute(AwsSignerExecutionAttribute.AWS_CREDENTIALS)).isNull();

        // Null selected auth scheme can be read with old property
        attributes.putAttribute(SdkInternalExecutionAttribute.SELECTED_AUTH_SCHEME, null);
        assertThat(attributes.getAttribute(AwsSignerExecutionAttribute.AWS_CREDENTIALS)).isNull();
    }

    @Test
    public void signingRegion_oldAndNewAttributeAreMirrored() {
        assertOldAndNewAttributesAreMirrored(AwsSignerExecutionAttribute.SIGNING_REGION,
                                             AwsV4HttpSigner.REGION_NAME,
                                             Region.US_EAST_1,
                                             "us-east-1");
    }

    @Test
    public void signingRegionScope_oldAndNewAttributeAreMirrored() {
        assertOldAndNewAttributesAreMirrored(AwsSignerExecutionAttribute.SIGNING_REGION_SCOPE,
                                             AwsV4aHttpSigner.REGION_NAME,
                                             RegionScope.create("foo"),
                                             "foo");
    }

    @Test
    public void signingName_oldAndNewAttributeAreMirrored() {
        assertOldAndNewAttributesAreMirrored(AwsSignerExecutionAttribute.SERVICE_SIGNING_NAME,
                                             AwsV4FamilyHttpSigner.SERVICE_SIGNING_NAME,
                                             "ServiceName");
    }

    @Test
    public void doubleUrlEncode_oldAndNewAttributeAreMirrored() {
        assertOldAndNewBooleanAttributesAreMirrored(AwsSignerExecutionAttribute.SIGNER_DOUBLE_URL_ENCODE,
                                                    AwsV4FamilyHttpSigner.DOUBLE_URL_ENCODE);
    }

    @Test
    public void signerNormalizePath_oldAndNewAttributeAreMirrored() {
        assertOldAndNewBooleanAttributesAreMirrored(AwsSignerExecutionAttribute.SIGNER_NORMALIZE_PATH,
                                                    AwsV4FamilyHttpSigner.NORMALIZE_PATH);
    }

    @Test
    public void signingClock_oldAndNewAttributeAreMirrored() {
        assertOldAndNewAttributesAreMirrored(AwsSignerExecutionAttribute.SIGNING_CLOCK,
                                             HttpSigner.SIGNING_CLOCK,
                                             Mockito.mock(Clock.class));
    }

    @Test
    public void signingExpiration_oldAndNewAttributeAreMirrored() {
        assertOldAndNewAttributesAreMirrored(AwsSignerExecutionAttribute.PRESIGNER_EXPIRATION,
                                             AwsV4FamilyHttpSigner.EXPIRATION_DURATION,
                                             testClock.instant().plusSeconds(10),
                                             Duration.ofSeconds(10));
    }

    private void assertOldAndNewBooleanAttributesAreMirrored(ExecutionAttribute<Boolean> attribute,
                                                             SignerProperty<Boolean> property) {
        assertOldAndNewAttributesAreMirrored(attribute, property, true);
        assertOldAndNewAttributesAreMirrored(attribute, property, false);
    }

    private <T> void assertOldAndNewAttributesAreMirrored(ExecutionAttribute<T> attributeToWrite,
                                                          SignerProperty<T> propertyToRead,
                                                          T valueToWrite) {
        assertOldAndNewAttributesAreMirrored(attributeToWrite, propertyToRead, valueToWrite, valueToWrite);
    }

    private <T, U> void assertOldAndNewAttributesAreMirrored(ExecutionAttribute<T> oldAttribute,
                                                             SignerProperty<U> newProperty,
                                                             T oldPropertyValue,
                                                             U newPropertyValue) {
        // If selected auth scheme is null, writing non-null old property can be read with new property
        attributes.putAttribute(SdkInternalExecutionAttribute.SELECTED_AUTH_SCHEME, null);
        assertOldAttributeWrite_canBeReadFromNewAttribute(oldAttribute, newProperty, oldPropertyValue, newPropertyValue);

        // If selected auth scheme is null, writing null to old property can be read with new property
        attributes.putAttribute(SdkInternalExecutionAttribute.SELECTED_AUTH_SCHEME, null);
        assertOldAttributeWrite_canBeReadFromNewAttribute(oldAttribute, newProperty, null, null);

        // If selected auth scheme is non-null, writing non-null to old property can be read with new property
        attributes.putAttribute(SdkInternalExecutionAttribute.SELECTED_AUTH_SCHEME, EMPTY_SELECTED_AUTH_SCHEME);
        assertOldAttributeWrite_canBeReadFromNewAttribute(oldAttribute, newProperty, oldPropertyValue, newPropertyValue);

        // If selected auth scheme is non-null, writing null to old property can be read with new property
        attributes.putAttribute(SdkInternalExecutionAttribute.SELECTED_AUTH_SCHEME, EMPTY_SELECTED_AUTH_SCHEME);
        assertOldAttributeWrite_canBeReadFromNewAttribute(oldAttribute, newProperty, null, null);

        // Writing non-null new property can be read with old property
        assertNewPropertyWrite_canBeReadFromNewAttribute(oldAttribute, newProperty, oldPropertyValue, newPropertyValue);

        // Writing null new property can be read with old property
        assertNewPropertyWrite_canBeReadFromNewAttribute(oldAttribute, newProperty, null, null);

        // Null selected auth scheme can be read with old property
        attributes.putAttribute(SdkInternalExecutionAttribute.SELECTED_AUTH_SCHEME, null);
        assertThat(attributes.getAttribute(oldAttribute)).isNull();
    }

    private <T, U> void assertNewPropertyWrite_canBeReadFromNewAttribute(ExecutionAttribute<T> oldAttribute,
                                                                         SignerProperty<U> newProperty,
                                                                         T oldPropertyValue,
                                                                         U newPropertyValue) {
        AuthSchemeOption newOption =
            EMPTY_SELECTED_AUTH_SCHEME.authSchemeOption().copy(o -> o.putSignerProperty(newProperty, newPropertyValue));
        attributes.putAttribute(SdkInternalExecutionAttribute.SELECTED_AUTH_SCHEME,
                                new SelectedAuthScheme<>(EMPTY_SELECTED_AUTH_SCHEME.identity(),
                                                         EMPTY_SELECTED_AUTH_SCHEME.signer(),
                                                         newOption));
        assertThat(attributes.getAttribute(oldAttribute)).isEqualTo(oldPropertyValue);
    }

    private <T, U> void assertOldAttributeWrite_canBeReadFromNewAttribute(ExecutionAttribute<T> attributeToWrite,
                                                                          SignerProperty<U> propertyToRead,
                                                                          T valueToWrite,
                                                                          U propertyToExpect) {
        attributes.putAttribute(attributeToWrite, valueToWrite);
        assertThat(attributes.getAttribute(SdkInternalExecutionAttribute.SELECTED_AUTH_SCHEME)
                             .authSchemeOption()
                             .signerProperty(propertyToRead)).isEqualTo(propertyToExpect);
    }
}