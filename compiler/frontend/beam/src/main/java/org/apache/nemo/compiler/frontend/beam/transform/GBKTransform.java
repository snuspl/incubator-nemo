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
package org.apache.nemo.compiler.frontend.beam.transform;

import org.apache.beam.runners.core.*;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.state.TimeDomain;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.display.DisplayData;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.PaneInfo;
import org.apache.beam.sdk.util.WindowedValue;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.WindowingStrategy;
import org.apache.nemo.common.ir.OutputCollector;
import org.apache.nemo.common.punctuation.Watermark;
import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.beam.sdk.transforms.DoFnSchemaInformation;
import java.util.*;

/**
 * This transform performs GroupByKey or CombinePerKey operation when input data is unbounded or is not in
 * global window.
 * @param <K> key type.
 * @param <InputT> input type.
 * @param <OutputT> output type.
 */
public final class GBKTransform<K, InputT, OutputT>
  extends AbstractDoFnTransform<KV<K, InputT>, KeyedWorkItem<K, InputT>, KV<K, OutputT>> {
  private static final Logger LOG = LoggerFactory.getLogger(GBKTransform.class.getName());
  private final SystemReduceFn reduceFn;
  private transient InMemoryTimerInternalsFactory<K> inMemoryTimerInternalsFactory;
  private transient InMemoryStateInternalsFactory<K> inMemoryStateInternalsFactory;
  private Watermark prevOutputWatermark;
  private Map<K, Watermark> keyOutputWatermarkMap;
  private Watermark inputWatermark;
  private transient OutputCollector originOc;
  private boolean dataReceived = false;

  public GBKTransform(final Coder<K> keyCoder,
                           final Map<TupleTag<?>, Coder<?>> outputCoders,
                           final TupleTag<KV<K, OutputT>> mainOutputTag,
                           final WindowingStrategy<?, ?> windowingStrategy,
                           final PipelineOptions options,
                           final SystemReduceFn reduceFn,
                           final DoFnSchemaInformation doFnSchemaInformation,
                           final DisplayData displayData) {
    super(null,
      null,
      outputCoders,
      mainOutputTag,
      Collections.emptyList(),  /* no additional outputs */
      windowingStrategy,
      Collections.emptyMap(), /* no additional side inputs */
      options,
      displayData,
      doFnSchemaInformation,
      Collections.<String, PCollectionView<?>>emptyMap()); /* does not have side inputs */
    this.reduceFn = reduceFn;
    this.prevOutputWatermark = new Watermark(Long.MIN_VALUE);
    this.inputWatermark = new Watermark(Long.MIN_VALUE);
    this.keyOutputWatermarkMap = new HashMap<>();
  }

  /**
   * This creates a new DoFn that groups elements by key and window.
   * @param doFn original doFn.
   * @return GroupAlsoByWindowViaWindowSetNewDoFn
   */
  @Override
  protected DoFn wrapDoFn(final DoFn doFn) {
    if (inMemoryStateInternalsFactory == null) {
      this.inMemoryStateInternalsFactory = new InMemoryStateInternalsFactory<>();
    } else {
      LOG.info("InMemoryStateInternalFactroy is already set");
    }

    if (inMemoryTimerInternalsFactory == null) {
      this.inMemoryTimerInternalsFactory = new InMemoryTimerInternalsFactory<>();
    } else {
      LOG.info("InMemoryTimerInternalsFactory is already set");
    }

    // This function performs group by key and window operation.
    return
      GroupAlsoByWindowViaWindowSetNewDoFn.create(
        getWindowingStrategy(),
        inMemoryStateInternalsFactory,
        inMemoryTimerInternalsFactory,
        null, // does not have side input.
        reduceFn,
        getOutputManager(),
        getMainOutputTag());
  }

  /** Wrapper function of output collector. */
  @Override
  OutputCollector wrapOutputCollector(final OutputCollector oc) {
    originOc = oc;
    return new GBKOutputCollector(oc);
  }

  /**
   * Every time a single element arrives, this method invokes runner to process a single element.
   * The collected data are emitted at {@link GBKTransform#onWatermark(Watermark)}
   * @param element input data element.
   */
  @Override
  public void onData(final WindowedValue<KV<K, InputT>> element) {
      dataReceived = true;
      try {
        checkAndInvokeBundle();
        final KV<K, InputT> kv = element.getValue();
        final KeyedWorkItem<K, InputT> keyedWorkItem =
          KeyedWorkItems.elementsWorkItem(kv.getKey(),
            Collections.singletonList(element.withValue(kv.getValue())));
        getDoFnRunner().processElement(WindowedValue.valueInGlobalWindow(keyedWorkItem));
        checkAndFinishBundle();
      } catch (final Exception e) {
        e.printStackTrace();
        throw new RuntimeException("exception trigggered element " + element.toString());
      }
  }

  /**
   * Process the collected data, trigger timers, and emit watermark to downstream operators.
   * @param processingTime processing time
   * @param synchronizedTime synchronized time
   * @param triggerWatermark watermark
   */
  private void processElementsAndTriggerTimers(final Instant processingTime,
                                               final Instant synchronizedTime,
                                               final Watermark triggerWatermark) {
    triggerTimers(processingTime, synchronizedTime, triggerWatermark);
    emitOutputWatermark();
  }

  /**
   * Emit watermark to downstream operators.
   * Output watermark = max(prev output watermark, min(input watermark, watermark holds)).
   */
  private void emitOutputWatermark() {
    // Find min watermark hold
    Watermark minWatermarkHold = keyOutputWatermarkMap.isEmpty()
      ? new Watermark(dataReceived ? Long.MIN_VALUE : Long.MAX_VALUE)
      : Collections.min(keyOutputWatermarkMap.values());

    Watermark outputWatermarkCandidate = new Watermark(
      Math.max(prevOutputWatermark.getTimestamp(),
        Math.min(minWatermarkHold.getTimestamp(), inputWatermark.getTimestamp())));

    while (outputWatermarkCandidate.getTimestamp() > prevOutputWatermark.getTimestamp()) {
      // Progress
      prevOutputWatermark = outputWatermarkCandidate;
      // Emit watermark
      getOutputCollector().emitWatermark(outputWatermarkCandidate);
      // Remove minimum watermark holds
      if (minWatermarkHold.getTimestamp() == outputWatermarkCandidate.getTimestamp()) {
        final long minWatermarkTimestamp = minWatermarkHold.getTimestamp();
        keyOutputWatermarkMap.entrySet()
          .removeIf(entry -> entry.getValue().getTimestamp() == minWatermarkTimestamp);
      }

      minWatermarkHold = keyOutputWatermarkMap.isEmpty()
        ? new Watermark(Long.MAX_VALUE) : Collections.min(keyOutputWatermarkMap.values());

      outputWatermarkCandidate = new Watermark(
        Math.max(prevOutputWatermark.getTimestamp(),
          Math.min(minWatermarkHold.getTimestamp(), inputWatermark.getTimestamp())));
    }
  }

  /**
   * Trigger timers that need to be fired.
   * @param watermark watermark
   */
  @Override
  public void onWatermark(final Watermark watermark) throws RuntimeException {
    if (watermark.getTimestamp() <= inputWatermark.getTimestamp()) {
      throw new RuntimeException("Received watermark is before inputWatermark in GBKTransform");
    }
    checkAndInvokeBundle();
    inputWatermark = watermark;
    // Triggering timers
    try {
      processElementsAndTriggerTimers(Instant.now(), Instant.now(), inputWatermark);
    } catch (final Exception e) {
      e.printStackTrace();
      throw new RuntimeException(e);
    }
    // Emit watermark to downstream operators
    checkAndFinishBundle();
  }

  /**
   * This advances the input watermark and processing time to the timestamp max value
   * in order to emit all data.
   */
  @Override
  protected void beforeClose() {
    // Finish any pending windows by advancing the input watermark to infinity.
    inputWatermark = new Watermark(BoundedWindow.TIMESTAMP_MAX_VALUE.getMillis());
    processElementsAndTriggerTimers(
      BoundedWindow.TIMESTAMP_MAX_VALUE, BoundedWindow.TIMESTAMP_MAX_VALUE, inputWatermark);
  }

  /**
   * Trigger timers. When triggering, it emits the windowed data to downstream operators.
   * @param processingTime processing time
   * @param synchronizedTime synchronized time
   * @param watermark watermark
   */
  private void triggerTimers(final Instant processingTime,
                            final Instant synchronizedTime,
                            final Watermark watermark) {

    for (Map.Entry<K, InMemoryTimerInternals> curr : inMemoryTimerInternalsFactory.getTimerInternalsMap().entrySet()) {
      try {
        curr.getValue().advanceInputWatermark(new Instant(watermark.getTimestamp()));
        curr.getValue().advanceProcessingTime(processingTime);
        curr.getValue().advanceSynchronizedProcessingTime(synchronizedTime);
      } catch (final Exception e) {
        e.printStackTrace();
        throw new RuntimeException();
      }
      for (TimeDomain domain : TimeDomain.values()) {
        processTrigger(curr.getKey(), curr.getValue(), domain);
      }
    }
  }

  /**
   * Fetch eligible timers in {@param timedomain} and process them.
   * @param key key
   * @param timerInternal timerInternal to be accessed
   * @param domain timedomain
   */
  private void processTrigger(final K key, final InMemoryTimerInternals timerInternal, final TimeDomain domain) {
    TimerInternals.TimerData timer = inMemoryTimerInternalsFactory.pollTimer(timerInternal, domain);
    while (timer != null) {
      // Trigger timers and emit windowed data
      final KeyedWorkItem<K, InputT> timerWorkItem =
        KeyedWorkItems.timersWorkItem(key, Collections.singletonList(timer));
      getDoFnRunner().processElement(WindowedValue.valueInGlobalWindow(timerWorkItem));
      inMemoryStateInternalsFactory.removeNamespaceForKey(key, timer.getNamespace(), timer.getTimestamp());
      timer = inMemoryTimerInternalsFactory.pollTimer(timerInternal, domain);
    }
  }

  /** Wrapper class for {@link OutputCollector}. */
  public class GBKOutputCollector implements OutputCollector<WindowedValue<KV<K, OutputT>>> {
    private final OutputCollector<WindowedValue<KV<K, OutputT>>> oc;

    public GBKOutputCollector(final OutputCollector oc) {
      this.oc = oc;
    }

    /** Emit output. If an output value is emitted on-time, add output timestamp to watermark hold map. */
    @Override
    public void emit(final WindowedValue<KV<K, OutputT>> output) {
      // The watermark advances only in ON_TIME
      if (output.getPane().getTiming().equals(PaneInfo.Timing.ON_TIME)) {
        KV<K, OutputT> value = output.getValue();
        final K key = value.getKey();
        final InMemoryTimerInternals timerInternals =
          (InMemoryTimerInternals) inMemoryTimerInternalsFactory.timerInternalsForKey(key);
        // Add the output timestamp to the watermark hold of each key.
        // +1 to the output timestamp because if the window is [0-5000), the timestamp is 4999.
          keyOutputWatermarkMap.put(key,
            new Watermark(output.getTimestamp().getMillis() + 1));
          timerInternals.advanceOutputWatermark(new Instant(output.getTimestamp().getMillis() + 1));
      }
      oc.emit(output);
    }

    /** Emit watermark. */
    @Override
    public void emitWatermark(final Watermark watermark) {
      oc.emitWatermark(watermark);
    }

    /** Emit output value to {@param dstVertexId}. */
    @Override
    public <T> void emit(final String dstVertexId, final T output) {
      oc.emit(dstVertexId, output);
    }
  }
}
