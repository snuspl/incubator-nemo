/*
 * Copyright (C) 2018 Seoul National University
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nemo.runtime.common.optimizer.pass.runtime;

import com.google.common.annotations.VisibleForTesting;
import org.apache.nemo.common.Pair;
import org.apache.nemo.common.dag.DAG;
import org.apache.nemo.common.eventhandler.RuntimeEventHandler;

import org.apache.nemo.common.ir.edge.executionproperty.ShuffleDistributionProperty;
import org.apache.nemo.common.ir.vertex.executionproperty.ParallelismProperty;
import org.apache.nemo.common.KeyRange;
import org.apache.nemo.common.HashRange;
import org.apache.nemo.runtime.common.eventhandler.DynamicOptimizationEventHandler;
import org.apache.nemo.runtime.common.plan.PhysicalPlan;
import org.apache.nemo.runtime.common.plan.Stage;
import org.apache.nemo.runtime.common.plan.StageEdge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Dynamic optimization pass for handling data skew.
 * Using a map of key to partition size as a metric used for dynamic optimization,
 * this RuntimePass identifies a number of keys with big partition sizes(skewed key)
 * and evenly redistributes data via overwriting incoming edges of destination tasks.
 */
public final class DataSkewRuntimePass extends RuntimePass<Pair<StageEdge, Map<Object, Long>>> {
  private static final Logger LOG = LoggerFactory.getLogger(DataSkewRuntimePass.class.getName());
  private final Set<Class<? extends RuntimeEventHandler>> eventHandlers;
  // Skewed keys denote for top n keys in terms of partition size.
  public static final int DEFAULT_NUM_SKEWED_KEYS = 10;
  public static final int HASH_RANGE_MULTIPLIER = 5;
  private static final String FILE_BASE = "/Users/sanha/tmp/";
  //private static final String FILE_BASE = "/home/ubuntu/int_data_dist/";
  private int numSkewedKeys;

  /**
   * Constructor.
   */
  public DataSkewRuntimePass() {
    this.eventHandlers = Collections.singleton(DynamicOptimizationEventHandler.class);
    this.numSkewedKeys = DEFAULT_NUM_SKEWED_KEYS;
  }

  public DataSkewRuntimePass(final int numOfSkewedKeys) {
    this();
    this.numSkewedKeys = numOfSkewedKeys;
  }

  public DataSkewRuntimePass setNumSkewedKeys(final int numOfSkewedKeys) {
    numSkewedKeys = numOfSkewedKeys;
    return this;
  }

  @Override
  public Set<Class<? extends RuntimeEventHandler>> getEventHandlerClasses() {
    return this.eventHandlers;
  }

  @Override
  public PhysicalPlan apply(final PhysicalPlan originalPlan,
                            final Pair<StageEdge, Map<Object, Long>> metricData) {
    final StageEdge targetEdge = metricData.left();
    // Get number of evaluators of the next stage (number of blocks).
    final Integer dstParallelism = targetEdge.getDst().getPropertyValue(ParallelismProperty.class).
        orElseThrow(() -> new RuntimeException("No parallelism on a vertex"));

    final BigInteger hashRangeBase = new BigInteger(String.valueOf(dstParallelism * HASH_RANGE_MULTIPLIER));
    final int hashRange = hashRangeBase.nextProbablePrime().intValue();

    //LOG.info("Collected metrics:: " + metricData.right());

    // Calculate keyRanges.
    final List<KeyRange> keyRanges = calculateKeyRanges(metricData.right(), dstParallelism, hashRange);

    printUnOpimizedDist(metricData.right(), dstParallelism, targetEdge.getId());
    printOpimizedDist(metricData.right(), hashRange, keyRanges, targetEdge.getId());

    //LOG.info("Optimized key ranges: " + keyRanges);

    final HashMap<Integer, KeyRange> taskIdxToKeyRange = new HashMap<>();
    for (int i = 0; i < dstParallelism; i++) {
      taskIdxToKeyRange.put(i, keyRanges.get(i));
    }

    // Overwrite the previously assigned key range in the physical DAG with the new range.
    final DAG<Stage, StageEdge> stageDAG = originalPlan.getStageDAG();
    for (Stage stage : stageDAG.getVertices()) {
      final List<StageEdge> stageEdges = stageDAG.getOutgoingEdgesOf(stage);
      for (StageEdge edge : stageEdges) {
        if (edge.equals(targetEdge)) {
          edge.getExecutionProperties()
              .put(ShuffleDistributionProperty.of(Pair.of(hashRange, taskIdxToKeyRange)), true);
        }
      }
    }

    return new PhysicalPlan(originalPlan.getPlanId(), stageDAG);
  }

  private List<Long> identifySkewedKeys(final List<Long> partitionSizeList) {
    // Identify skewed keys.
    List<Long> sortedMetricData = partitionSizeList.stream()
        .sorted(Comparator.reverseOrder())
        .collect(Collectors.toList());
    List<Long> skewedSizes = new ArrayList<>();
    int keysToIdentify = Math.min(numSkewedKeys, partitionSizeList.size());
    for (int i = 0; i < keysToIdentify; i++) {
      skewedSizes.add(sortedMetricData.get(i));
      LOG.info("Skewed size: {}", sortedMetricData.get(i));
    }

    return skewedSizes;
  }

  private boolean containsSkewedSize(final List<Long> partitionSizeList,
                                     final List<Long> skewedKeys,
                                     final int startingKey, final int finishingKey) {
    for (int i = startingKey; i < finishingKey; i++) {
      if (skewedKeys.contains(partitionSizeList.get(i))) {
        return true;
      }
    }

    return false;
  }

  public void printUnOpimizedDist(final Map<Object, Long> actualKeyToSizeMap,
                                  final int dstParallelism,
                                  final String targetEdgeId) {
    final List<Long> partitionSizeList = new ArrayList<>(dstParallelism);
    for (int i = 0; i < dstParallelism; i++) {
      partitionSizeList.add(0L);
    }
    actualKeyToSizeMap.forEach((k, v) -> {
      final int partitionKey = Math.abs(k.hashCode() % dstParallelism);
      partitionSizeList.set(partitionKey, partitionSizeList.get(partitionKey) + v);
    });
    partitionSizeList.sort(Long::compareTo);

    /*LOG.info("Un-optimized Dist: ");
    for (int i = 0; i < dstParallelism; i++) {
      LOG.info(String.valueOf(partitionSizeList.get(i)));
    }*/

    try (PrintWriter out = new PrintWriter(
      new BufferedWriter(
        new FileWriter(FILE_BASE + targetEdgeId + "_unopt.txt", true)))) {
      for (int i = dstParallelism - 1; i > 0; i--) {
        out.println(String.valueOf(partitionSizeList.get(i)));
      }
    } catch (final IOException e) {
      throw new RuntimeException(e);
    }
  }

  public void printOpimizedDist(final Map<Object, Long> actualKeyToSizeMap,
                                final int hashRange,
                                final List<KeyRange> ranges,
                                final String targetEdgeId) {
    final List<Long> partitionSizeList = new ArrayList<>(hashRange);
    for (int i = 0; i < hashRange; i++) {
      partitionSizeList.add(0L);
    }

    actualKeyToSizeMap.forEach((k, v) -> {
      final int partitionKey = Math.abs(k.hashCode() % hashRange);
      partitionSizeList.set(partitionKey, partitionSizeList.get(partitionKey) + v);
    });

    final List<Long> sortedSizeList = new ArrayList<>(ranges.size());

    //LOG.info("Optimized Dist: ");
    for (final KeyRange range : ranges) {
      long size = 0;
      for (int i = (int) range.rangeBeginInclusive(); i < (int) range.rangeEndExclusive(); i++) {
        size += partitionSizeList.get(i);
      }
      sortedSizeList.add(size);
    }

    sortedSizeList.sort(Long::compareTo);
    /*
    for (final Long size : sortedSizeList) {
      LOG.info(String.valueOf(size));
    }*/

    try (PrintWriter out = new PrintWriter(
      new BufferedWriter(
        new FileWriter(FILE_BASE + targetEdgeId + "_opt.txt", true)))) {
      for (int i = sortedSizeList.size() - 1; i > 0; i--) {
        out.println(String.valueOf(sortedSizeList.get(i)));
      }
    } catch (final IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Evenly distribute the skewed data to the destination tasks.
   * Partition denotes for a keyed portion of a Task output, whose key is a key.
   * Using a map of key to partition size, this method groups the given partitions
   * to a key range of partitions with approximate size of (total size of partitions / the number of tasks).
   *
   * @param actualKeyToSizeMap a map of (actual element) key to size.
   * @param dstParallelism the number of tasks that receive this data as input.
   * @param hashRange the range of key's hash values.
   * @return the list of key ranges calculated.
   */
  @VisibleForTesting
  public List<KeyRange> calculateKeyRanges(final Map<Object, Long> actualKeyToSizeMap,
                                           final int dstParallelism,
                                           final int hashRange) {
    final List<Long> partitionSizeList = new ArrayList<>(hashRange);
    for (int i = 0; i < hashRange; i++) {
      partitionSizeList.add(0L);
    }

    actualKeyToSizeMap.forEach((k, v) -> {
      final int partitionKey = Math.abs(k.hashCode() % hashRange);
      partitionSizeList.set(partitionKey, partitionSizeList.get(partitionKey) + v);
    });

    // Get the last index.
    final int lastKey = partitionSizeList.size() - 1;

    // Identify skewed sizes, which is top numSkewedKeys number of keys.
    List<Long> skewedSizes = identifySkewedKeys(partitionSizeList);

    // Calculate the ideal size for each destination task.
    final Long totalSize = partitionSizeList.stream().mapToLong(n -> n).sum(); // get total size
    final Long idealSizePerTask = totalSize / dstParallelism; // and derive the ideal size per task
    final List<KeyRange> keyRanges = new ArrayList<>(dstParallelism);

    if (totalSize == 0) {
      LOG.warn("Zero total size!");
      final int meanRange = hashRange / dstParallelism;
      for (int i = 0; i < dstParallelism - 1; i++) {
        keyRanges.add(i, HashRange.of(i * meanRange, (i + 1) * meanRange, false));
      }
      keyRanges.add(dstParallelism - 1, HashRange.of((dstParallelism - 1) * meanRange, hashRange, false));

      return keyRanges;
    }

    int startingKey = 0;
    int finishingKey = 1;
    Long currentAccumulatedSize = partitionSizeList.get(startingKey);
    Long prevAccumulatedSize = 0L;
    for (int i = 1; i <= dstParallelism; i++) {
      if (i != dstParallelism) {
        // Ideal accumulated partition size for this task.
        final Long idealAccumulatedSize = idealSizePerTask * i;

        // By adding partition sizes, find the accumulated size nearest to the given ideal size.
        while (currentAccumulatedSize < idealAccumulatedSize && (lastKey - finishingKey) >= dstParallelism - i) {
          currentAccumulatedSize += partitionSizeList.get(finishingKey);
          finishingKey++;
        }

        final Long oneStepBack =
            currentAccumulatedSize - partitionSizeList.get(finishingKey - 1);
        final Long diffFromIdeal = currentAccumulatedSize - idealAccumulatedSize;
        final Long diffFromIdealOneStepBack = idealAccumulatedSize - oneStepBack;
        // Go one step back if we came too far.
        if (diffFromIdeal > diffFromIdealOneStepBack) {
          finishingKey--;
          currentAccumulatedSize -= partitionSizeList.get(finishingKey);
        }

        boolean isSkewedKey = containsSkewedSize(partitionSizeList, skewedSizes, startingKey, finishingKey);
        keyRanges.add(i - 1, HashRange.of(startingKey, finishingKey, isSkewedKey));
        LOG.debug("KeyRange {}~{}, Size {}", startingKey, finishingKey - 1,
            currentAccumulatedSize - prevAccumulatedSize);

        prevAccumulatedSize = currentAccumulatedSize;
        currentAccumulatedSize += partitionSizeList.get(finishingKey);
        startingKey = finishingKey;
        finishingKey++;
      } else { // last one: we put the range of the rest.
        boolean isSkewedKey = containsSkewedSize(partitionSizeList, skewedSizes, startingKey, lastKey + 1);
        keyRanges.add(i - 1,
            HashRange.of(startingKey, lastKey + 1, isSkewedKey));

        while (finishingKey <= lastKey) {
          currentAccumulatedSize += partitionSizeList.get(finishingKey);
          finishingKey++;
        }
        LOG.debug("KeyRange {}~{}, Size {}", startingKey, lastKey + 1,
            currentAccumulatedSize - prevAccumulatedSize);
      }
    }
    return keyRanges;
  }
}
