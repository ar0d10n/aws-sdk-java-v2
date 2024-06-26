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

package software.amazon.awssdk.core.internal.http.pipeline.stages;

import static software.amazon.awssdk.core.interceptor.SdkInternalExecutionAttribute.SDK_HTTP_EXECUTION_ATTRIBUTES;
import static software.amazon.awssdk.core.internal.http.timers.TimerUtils.resolveTimeoutInMillis;
import static software.amazon.awssdk.http.Header.CONTENT_LENGTH;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.core.Response;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.client.config.SdkAdvancedAsyncClientOption;
import software.amazon.awssdk.core.client.config.SdkClientOption;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.SdkInternalExecutionAttribute;
import software.amazon.awssdk.core.internal.http.HttpClientDependencies;
import software.amazon.awssdk.core.internal.http.RequestExecutionContext;
import software.amazon.awssdk.core.internal.http.TransformingAsyncResponseHandler;
import software.amazon.awssdk.core.internal.http.async.FilterTransformingAsyncHttpResponseHandler;
import software.amazon.awssdk.core.internal.http.async.SimpleHttpContentPublisher;
import software.amazon.awssdk.core.internal.http.pipeline.RequestPipeline;
import software.amazon.awssdk.core.internal.http.timers.TimeoutTracker;
import software.amazon.awssdk.core.internal.http.timers.TimerUtils;
import software.amazon.awssdk.core.internal.metrics.BytesReadTrackingPublisher;
import software.amazon.awssdk.core.internal.util.MetricUtils;
import software.amazon.awssdk.core.metrics.CoreMetric;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.http.async.AsyncExecuteRequest;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.http.async.SdkHttpContentPublisher;
import software.amazon.awssdk.metrics.MetricCollector;
import software.amazon.awssdk.utils.CompletableFutureUtils;
import software.amazon.awssdk.utils.Logger;

/**
 * Delegate to the HTTP implementation to make an HTTP request and receive the response.
 */
@SdkInternalApi
public final class MakeAsyncHttpRequestStage<OutputT>
        implements RequestPipeline<CompletableFuture<SdkHttpFullRequest>, CompletableFuture<Response<OutputT>>> {

    private static final Logger log = Logger.loggerFor(MakeAsyncHttpRequestStage.class);

    private final SdkAsyncHttpClient sdkAsyncHttpClient;
    private final TransformingAsyncResponseHandler<Response<OutputT>> responseHandler;
    private final Executor futureCompletionExecutor;
    private final ScheduledExecutorService timeoutExecutor;
    private final Duration apiCallAttemptTimeout;

    public MakeAsyncHttpRequestStage(TransformingAsyncResponseHandler<Response<OutputT>> responseHandler,
                                     HttpClientDependencies dependencies) {
        this.responseHandler = responseHandler;
        this.futureCompletionExecutor =
                dependencies.clientConfiguration().option(SdkAdvancedAsyncClientOption.FUTURE_COMPLETION_EXECUTOR);
        this.sdkAsyncHttpClient = dependencies.clientConfiguration().option(SdkClientOption.ASYNC_HTTP_CLIENT);
        this.apiCallAttemptTimeout = dependencies.clientConfiguration().option(SdkClientOption.API_CALL_ATTEMPT_TIMEOUT);
        this.timeoutExecutor = dependencies.clientConfiguration().option(SdkClientOption.SCHEDULED_EXECUTOR_SERVICE);
    }

    @Override
    public CompletableFuture<Response<OutputT>> execute(CompletableFuture<SdkHttpFullRequest> requestFuture,
                                                        RequestExecutionContext context) {
        CompletableFuture<Response<OutputT>> toReturn = new CompletableFuture<>();

        // Setup the cancellations. If the caller fails to provide a request, forward the exception to the future we
        // return
        CompletableFutureUtils.forwardExceptionTo(requestFuture, toReturn);

        // On the other hand, if the future we return is completed exceptionally, throw the exception back up to the
        // request future
        CompletableFutureUtils.forwardExceptionTo(toReturn, requestFuture);

        requestFuture.thenAccept(request -> {
            // At this point, we have a request that we're ready to execute; we do everything in a try-catch in case the
            // method call to executeHttpRequest throws directly
            try {
                CompletableFuture<Response<OutputT>> executeFuture = executeHttpRequest(request, context);

                executeFuture.whenComplete((r, t) -> {
                    if (t != null) {
                        toReturn.completeExceptionally(t);
                    } else {
                        toReturn.complete(r);
                    }
                });

                // Similar to cancelling the request future, but we've now started the request execution, so if our
                // returned future gets an exception, forward to the HTTP execution future
                CompletableFutureUtils.forwardExceptionTo(toReturn, executeFuture);
            } catch (Throwable t) {
                toReturn.completeExceptionally(t);
            }
        });

        return toReturn;
    }

    private CompletableFuture<Response<OutputT>> executeHttpRequest(SdkHttpFullRequest request,
                                                                    RequestExecutionContext context) {

        CompletableFuture<Response<OutputT>> responseFuture = new CompletableFuture<>();

        CompletableFuture<Response<OutputT>> responseHandlerFuture = responseHandler.prepare();

        SdkHttpContentPublisher requestProvider = context.requestProvider() == null
                                                  ? new SimpleHttpContentPublisher(request)
                                                  : new SdkHttpContentPublisherAdapter(context.requestProvider());
        // Set content length if it hasn't been set already.
        SdkHttpFullRequest requestWithContentLength = getRequestWithContentLength(request, requestProvider);

        MetricCollector httpMetricCollector = MetricUtils.createHttpMetricsCollector(context);

        AsyncExecuteRequest.Builder executeRequestBuilder = AsyncExecuteRequest.builder()
                                                                .request(requestWithContentLength)
                                                                .requestContentPublisher(requestProvider)
                                                                .fullDuplex(isFullDuplex(context.executionAttributes()))
                                                                .metricCollector(httpMetricCollector);
        if (context.executionAttributes().getAttribute(SDK_HTTP_EXECUTION_ATTRIBUTES) != null) {
            executeRequestBuilder.httpExecutionAttributes(
                context.executionAttributes()
                       .getAttribute(SDK_HTTP_EXECUTION_ATTRIBUTES));
        }

        CompletableFuture<Void> httpClientFuture = doExecuteHttpRequest(context, executeRequestBuilder, responseHandler);

        TimeoutTracker timeoutTracker = setupAttemptTimer(responseFuture, context);
        context.apiCallAttemptTimeoutTracker(timeoutTracker);

        // Forward the cancellation
        responseFuture.whenComplete((r, t) -> {
            if (t != null) {
                httpClientFuture.completeExceptionally(t);
            }
        });

        // Attempt to offload the completion of the future returned from this
        // stage onto the future completion executor
        CompletableFuture<Void> asyncComplete =
            responseHandlerFuture.handleAsync((r, t) -> {
                completeResponseFuture(responseFuture, r, t);
                return null;
            },
                                              futureCompletionExecutor);

        // It's possible the async execution above fails. If so, log a warning,
        // and just complete it synchronously.
        asyncComplete.whenComplete((ignored, asyncCompleteError) -> {
            if (asyncCompleteError != null) {
                log.debug(() -> String.format("Could not complete the service call future on the provided "
                                              + "FUTURE_COMPLETION_EXECUTOR. The future will be completed synchronously by thread"
                                              + " %s. This may be an indication that the executor is being overwhelmed by too"
                                              + " many requests, and it may negatively affect performance. Consider changing "
                                              + "the configuration of the executor to accommodate the load through the client.",
                                              Thread.currentThread().getName()),
                          asyncCompleteError);
                responseHandlerFuture.whenComplete((r, t) -> {
                    completeResponseFuture(responseFuture, r, t);

                });
            }
        });

        return responseFuture;
    }

    private CompletableFuture<Void> doExecuteHttpRequest(RequestExecutionContext context,
                                                         AsyncExecuteRequest.Builder executeRequestBuilder,
                                                         TransformingAsyncResponseHandler<Response<OutputT>> responseHandler) {
        MetricCollector metricCollector = context.attemptMetricCollector();
        ReadMetricsTrackingResponseHandler<Response<OutputT>> wrappedResponseHandler =
            new ReadMetricsTrackingResponseHandler<>(responseHandler, context);

        AsyncExecuteRequest executeRequest = executeRequestBuilder.responseHandler(wrappedResponseHandler)
                                                                  .build();

        long startTime = MetricUtils.resetApiCallAttemptStartNanoTime(context);
        CompletableFuture<Void> httpClientFuture = sdkAsyncHttpClient.execute(executeRequest);

        CompletableFuture<Void> result = httpClientFuture.whenComplete((r, t) -> {
            long d = System.nanoTime() - startTime;
            metricCollector.reportMetric(CoreMetric.SERVICE_CALL_DURATION, Duration.ofNanos(d));
        });

        // Make sure failures on the result future are forwarded to the http client future.
        CompletableFutureUtils.forwardExceptionTo(result, httpClientFuture);

        return result;
    }

    private boolean isFullDuplex(ExecutionAttributes executionAttributes) {
        return executionAttributes.getAttribute(SdkInternalExecutionAttribute.IS_FULL_DUPLEX) != null &&
               executionAttributes.getAttribute(SdkInternalExecutionAttribute.IS_FULL_DUPLEX);
    }

    private SdkHttpFullRequest getRequestWithContentLength(SdkHttpFullRequest request, SdkHttpContentPublisher requestProvider) {
        if (shouldSetContentLength(request, requestProvider)) {
            return request.toBuilder()
                          .putHeader(CONTENT_LENGTH, String.valueOf(requestProvider.contentLength().get()))
                          .build();
        }
        return request;
    }

    private boolean shouldSetContentLength(SdkHttpFullRequest request, SdkHttpContentPublisher requestProvider) {

        if (request.method() == SdkHttpMethod.GET || request.method() == SdkHttpMethod.HEAD ||
            request.firstMatchingHeader(CONTENT_LENGTH).isPresent()) {
            return false;
        }

        return Optional.ofNullable(requestProvider).flatMap(SdkHttpContentPublisher::contentLength).isPresent();
    }

    private TimeoutTracker setupAttemptTimer(CompletableFuture<Response<OutputT>> executeFuture, RequestExecutionContext ctx) {
        long timeoutMillis = resolveTimeoutInMillis(ctx.requestConfig()::apiCallAttemptTimeout, apiCallAttemptTimeout);
        Supplier<SdkClientException> exceptionSupplier = () -> ApiCallAttemptTimeoutException.create(timeoutMillis);

        return TimerUtils.timeAsyncTaskIfNeeded(executeFuture,
                                                timeoutExecutor,
                                                exceptionSupplier,
                                                timeoutMillis);
    }

    private void completeResponseFuture(CompletableFuture<Response<OutputT>> responseFuture, Response<OutputT> r, Throwable t) {
        if (t == null) {
            responseFuture.complete(r);
        } else {
            responseFuture.completeExceptionally(t);
        }
    }

    /**
     * When an operation has a streaming input, the customer must supply an {@link AsyncRequestBody} to
     * provide the request content in a non-blocking manner. This adapts that interface to the
     * {@link SdkHttpContentPublisher} which the HTTP client SPI expects.
     */
    private static final class SdkHttpContentPublisherAdapter implements SdkHttpContentPublisher {

        private final AsyncRequestBody asyncRequestBody;

        private SdkHttpContentPublisherAdapter(AsyncRequestBody asyncRequestBody) {
            this.asyncRequestBody = asyncRequestBody;
        }

        @Override
        public Optional<Long> contentLength() {
            return asyncRequestBody.contentLength();
        }

        @Override
        public void subscribe(Subscriber<? super ByteBuffer> s) {
            asyncRequestBody.subscribe(s);
        }
    }

    /**
     * Decorator response handler that records response read metrics as well as records other data for computing other read
     * metrics at later points.
     */
    private static final class ReadMetricsTrackingResponseHandler<ResultT>
        extends FilterTransformingAsyncHttpResponseHandler<ResultT> {
        private final RequestExecutionContext context;

        private ReadMetricsTrackingResponseHandler(TransformingAsyncResponseHandler<ResultT> delegate,
                                                   RequestExecutionContext context) {
            super(delegate);
            this.context = context;
        }

        @Override
        public void onHeaders(SdkHttpResponse headers) {
            long startTime = MetricUtils.apiCallAttemptStartNanoTime(context).getAsLong();
            long now = System.nanoTime();
            context.executionAttributes().putAttribute(SdkInternalExecutionAttribute.HEADERS_READ_END_NANO_TIME, now);

            long d = now - startTime;
            context.attemptMetricCollector().reportMetric(CoreMetric.TIME_TO_FIRST_BYTE, Duration.ofNanos(d));
            super.onHeaders(headers);
        }

        @Override
        public void onStream(Publisher<ByteBuffer> stream) {
            AtomicLong bytesReadCounter = context.executionAttributes()
                                                 .getAttribute(SdkInternalExecutionAttribute.RESPONSE_BYTES_READ);
            BytesReadTrackingPublisher bytesReadTrackingPublisher = new BytesReadTrackingPublisher(stream, bytesReadCounter);
            super.onStream(bytesReadTrackingPublisher);
        }
    }
}
