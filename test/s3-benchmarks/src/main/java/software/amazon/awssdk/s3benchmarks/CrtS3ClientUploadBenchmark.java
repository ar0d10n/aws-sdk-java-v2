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

package software.amazon.awssdk.s3benchmarks;

import static software.amazon.awssdk.s3benchmarks.BenchmarkUtils.printOutResult;
import static software.amazon.awssdk.transfer.s3.SizeConstant.MB;

import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import software.amazon.awssdk.crt.http.HttpHeader;
import software.amazon.awssdk.crt.http.HttpRequest;
import software.amazon.awssdk.crt.http.HttpRequestBodyStream;
import software.amazon.awssdk.crt.s3.S3MetaRequest;
import software.amazon.awssdk.crt.s3.S3MetaRequestOptions;
import software.amazon.awssdk.crt.s3.S3MetaRequestResponseHandler;
import software.amazon.awssdk.crt.utils.ByteBufferUtils;
import software.amazon.awssdk.utils.Logger;
import software.amazon.awssdk.utils.Validate;

public class CrtS3ClientUploadBenchmark extends BaseCrtClientBenchmark {

    private static final Logger log = Logger.loggerFor(CrtS3ClientUploadBenchmark.class);

    private final long totalContentLength;

    public CrtS3ClientUploadBenchmark(TransferManagerBenchmarkConfig config) {
        super(config);
        this.totalContentLength = Validate.notNull(config.contentLengthInMb() * MB,
                                                   "contentLength is required for Crt Upload Benchmark");
    }

    @Override
    public void sendOneRequest(List<Double> latencies) throws IOException  {
        CompletableFuture<Void> resultFuture = new CompletableFuture<>();
        S3MetaRequestResponseHandler handler = new TestS3MetaRequestResponseHandler(resultFuture);

        ByteBuffer payload = ByteBuffer.wrap(createTestPayload(partSizeInBytes.intValue()));
        HttpRequestBodyStream payloadStream = new PayloadStream(payload);

        String endpoint = bucket + ".s3." + region + ".amazonaws.com";
        HttpHeader[] headers = { new HttpHeader("Host", endpoint),
                                 new HttpHeader("Content-Length", Long.toString(totalContentLength)) };

        String path = key.startsWith("/") ? key : "/" + key;
        HttpRequest httpRequest = new HttpRequest("PUT", path, headers, payloadStream);

        S3MetaRequestOptions metaRequestOptions = new S3MetaRequestOptions()
            .withMetaRequestType(S3MetaRequestOptions.MetaRequestType.PUT_OBJECT)
            .withHttpRequest(httpRequest)
            .withResponseHandler(handler);
        long start = System.currentTimeMillis();
        try (S3MetaRequest metaRequest = crtS3Client.makeMetaRequest(metaRequestOptions)) {
            resultFuture.get(10, TimeUnit.MINUTES);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new RuntimeException(e);
        }
        long end = System.currentTimeMillis();
        latencies.add((end - start) / 1000.0);
    }

    @Override
    protected void onResult(List<Double> metrics) throws IOException {
        printOutResult(metrics, "Uploaded to File", totalContentLength);
    }

    private static final class PayloadStream implements HttpRequestBodyStream {
        private final ByteBuffer payload;

        private PayloadStream(ByteBuffer payload) {
            this.payload = payload;
        }

        @Override
        public boolean sendRequestBody(ByteBuffer outBuffer) {
            ByteBufferUtils.transferData(payload, outBuffer);
            payload.position(0);
            return payload.remaining() == 0;
        }

        @Override
        public boolean resetPosition() {
            return true;
        }

        @Override
        public long getLength() {
            return payload.capacity();
        }
    }

    private static byte[] createTestPayload(int size) {
        String msg = "This is an S3 Java CRT Client Test";
        ByteBuffer payload = ByteBuffer.allocate(size);
        while (true) {
            try {
                payload.put(msg.getBytes(StandardCharsets.UTF_8));
            } catch (BufferOverflowException ex1) {
                while (true) {
                    try {
                        payload.put("#".getBytes(StandardCharsets.UTF_8));
                    } catch (BufferOverflowException ex2) {
                        break;
                    }
                }
                break;
            }
        }
        return payload.array();
    }

}
