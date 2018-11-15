/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.nemo.runtime.executor.datatransfer;

import com.amazonaws.services.lambda.AWSLambda;
import com.amazonaws.services.lambda.AWSLambdaAsync;
import com.amazonaws.services.lambda.AWSLambdaAsyncClientBuilder;
import com.amazonaws.services.lambda.model.InvokeRequest;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.ListObjectsV2Request;
import com.amazonaws.services.s3.model.ListObjectsV2Result;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.util.WindowedValue;
import org.apache.nemo.common.coder.EncoderFactory;
import org.apache.nemo.common.ir.OutputCollector;
import org.apache.nemo.common.ir.vertex.IRVertex;
import org.apache.nemo.common.punctuation.Watermark;
import org.apache.nemo.runtime.common.plan.StageEdge;
import org.apache.nemo.runtime.executor.data.SerializerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

import static org.apache.nemo.runtime.executor.datatransfer.AWSUtils.S3_BUCKET_NAME;

public final class SideInputLambdaCollector<O> implements OutputCollector<O> {
  private static final Logger LOG = LoggerFactory.getLogger(SideInputLambdaCollector.class.getName());

  private final IRVertex irVertex;

  // TODO: remove
  private final AmazonS3 amazonS3;
  private final AWSLambdaAsync awsLambdaAsync;
  private final AWSLambda awsLambda;
  private EncoderFactory<O> encoderFactory;
  private EncoderFactory.Encoder<O> encoder;
  private OutputStream outputStream;

  int cnt = 0;


  /**
   * Constructor of the output collector.
   * @param irVertex the ir vertex that emits the output
   */
  public SideInputLambdaCollector(
    final IRVertex irVertex,
    final List<StageEdge> outgoingEdges,
    final SerializerManager serializerManager) {
    this.irVertex = irVertex;
    this.encoderFactory = ((NemoEventEncoderFactory) serializerManager.getSerializer(outgoingEdges.get(0).getId())
      .getEncoderFactory()).getValueEncoderFactory();
    this.amazonS3 = AmazonS3ClientBuilder.standard().build();
    this.awsLambda = AWSUtils.AWS_LAMBDA;
    this.awsLambdaAsync = AWSLambdaAsyncClientBuilder.standard().build();
  }

  private EncoderFactory.Encoder createEncoder(final String fileName) {
    try {
      outputStream = new FileOutputStream(fileName);
      return encoderFactory.create(outputStream);
    } catch (IOException e) {
      e.printStackTrace();
      throw new RuntimeException(e);
    }
  }

  private void closeEncoder() {
    try {
      outputStream.close();
      encoder = null;
    } catch (IOException e) {
      e.printStackTrace();
      throw new RuntimeException();
    }
  }

  @Override
  public void emit(final O output) {
        // CreateViewTransform (side input)
    // send to serverless
    final WindowedValue wv = (WindowedValue) output;
    final BoundedWindow window = (BoundedWindow) wv.getWindows().iterator().next();
    LOG.info("Vertex20 Window: {} ********** {}", window, window.maxTimestamp().toString());

    final String fileName = window.toString();
    try {
      if (encoder == null) {
        encoder = createEncoder(fileName);
      }
      encoder.encode((O) wv);

      closeEncoder();

      final File file = new File(fileName);

      LOG.info("Start to send sideinput data to S3");
      final PutObjectRequest putObjectRequest =
        new PutObjectRequest(S3_BUCKET_NAME + "/sideinput", file.getName(), file);
      amazonS3.putObject(putObjectRequest);
      file.delete();
      LOG.info("End of send sideinput to S3");

      // Get main input list
      LOG.info("Request sideinput lambda");
      final ListObjectsV2Request req =
        new ListObjectsV2Request().withBucketName(S3_BUCKET_NAME).withPrefix("maininput/" + file.getName());
      final ListObjectsV2Result result = amazonS3.listObjectsV2(req);
      for (final S3ObjectSummary objectSummary : result.getObjectSummaries()) {
        System.out.printf(" - %s (size: %d)\n", objectSummary.getKey(), objectSummary.getSize());

        // Trigger lambdas
        final InvokeRequest request = new InvokeRequest()
          .withFunctionName(AWSUtils.SIDEINPUT_LAMBDA_NAME)
          .withPayload(String.format("{\"input\":\"%s\"}", objectSummary.getKey()));

        awsLambdaAsync.invokeAsync(request);
        LOG.info("End of Request sideinput lambda");
      }
    } catch (IOException e) {
      e.printStackTrace();
      throw new RuntimeException(e);
    }
    return;
  }

  @Override
  public <T> void emit(final String dstVertexId, final T output) {
    // do nothing
  }

  @Override
  public void emitWatermark(final Watermark watermark) {
    // do nothing
  }
}
