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

package software.amazon.awssdk.services.s3.internal.multipart;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

// [WIP] Stub
public class DownloaderSubscriber implements Subscriber<AsyncResponseTransformer<GetObjectResponse, GetObjectResponse>> {
    private final S3AsyncClient s3;
    private final GetObjectRequest getObjectRequest;

    private AtomicBoolean totalPartKnown = new AtomicBoolean(false);
    private AtomicInteger totalParts = new AtomicInteger();
    private AtomicInteger completed = new AtomicInteger(0);
    private AtomicInteger currentPart = new AtomicInteger(0);

    private Subscription subscription;

    private CompletableFuture<GetObjectResponse> responseFuture;
    private GetObjectResponse returnResponse;

    public DownloaderSubscriber(S3AsyncClient s3, GetObjectRequest getObjectRequest) {
        this.s3 = s3;
        this.getObjectRequest = getObjectRequest;
        this.responseFuture = new CompletableFuture<>();
    }

    @Override
    public void onSubscribe(Subscription s) {
        if (this.subscription == null) {
            this.subscription = s;
        }
        s.request(1);
    }

    @Override
    public void onNext(AsyncResponseTransformer<GetObjectResponse, GetObjectResponse> asyncResponseTransformer) {
        int part = currentPart.incrementAndGet();
        if (totalPartKnown.get()) {
            System.out.printf("[DownloaderSubscriber] total part: %s, current part: %s%n", totalParts.get(), part);
        }
        if (totalPartKnown.get() && part > totalParts.get()) {
            System.out.printf("[DownloaderSubscriber] no more parts available, stopping%n");
            subscription.cancel();
            return;
        }
        GetObjectRequest actualRequest = this.getObjectRequest.copy(req -> req.partNumber(part));
        CompletableFuture<GetObjectResponse> future = s3.getObject(actualRequest, asyncResponseTransformer);
        future.whenComplete((response, e) -> {
            if (e != null) {
                responseFuture.completeExceptionally(e);
                return;
            }
            completed.incrementAndGet();
            returnResponse = response;
            System.out.printf("[DownloaderSubscriber] received '%s'%n", response.contentRange());
            Integer partCount = response.partsCount();
            if (totalPartKnown.compareAndSet(false, true)) {
                totalParts.set(partCount);
                totalPartKnown.set(true);
            }
            System.out.printf("[DownloaderSubscriber] total parts: %s%n", partCount);
            if (totalParts.get() > 1) {
                subscription.request(1);
            }
        });
    }

    @Override
    public void onError(Throwable t) {
        responseFuture.completeExceptionally(t);
    }

    @Override
    public void onComplete() {
        responseFuture.complete(returnResponse);
    }

    public CompletableFuture<GetObjectResponse> future() {
        return responseFuture;
    }

}
