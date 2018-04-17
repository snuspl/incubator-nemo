/*
 * Copyright (C) 2017 Seoul National University
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
package edu.snu.nemo.runtime.executor;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import edu.snu.nemo.common.ContextImpl;
import edu.snu.nemo.common.Pair;
import edu.snu.nemo.common.dag.DAG;
import edu.snu.nemo.common.exception.BlockFetchException;
import edu.snu.nemo.common.exception.BlockWriteException;
import edu.snu.nemo.common.ir.Readable;
import edu.snu.nemo.common.ir.edge.executionproperty.DataCommunicationPatternProperty;
import edu.snu.nemo.common.ir.executionproperty.ExecutionProperty;
import edu.snu.nemo.common.ir.vertex.transform.Transform;
import edu.snu.nemo.common.ir.vertex.OperatorVertex;
import edu.snu.nemo.runtime.common.RuntimeIdGenerator;
import edu.snu.nemo.runtime.common.plan.RuntimeEdge;
import edu.snu.nemo.runtime.common.plan.physical.*;
import edu.snu.nemo.runtime.common.state.TaskGroupState;
import edu.snu.nemo.runtime.executor.data.DataUtil;
import edu.snu.nemo.runtime.executor.datatransfer.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Executes a task group.
 */
public final class TaskGroupExecutor {
  private static final Logger LOG = LoggerFactory.getLogger(TaskGroupExecutor.class.getName());

  private final DAG<Task, RuntimeEdge<Task>> taskGroupDag;
  private final String taskGroupId;
  private final int taskGroupIdx;
  private final TaskGroupStateManager taskGroupStateManager;
  private final List<PhysicalStageEdge> stageIncomingEdges;
  private final List<PhysicalStageEdge> stageOutgoingEdges;
  private final DataTransferFactory channelFactory;
  private final MetricCollector metricCollector;

  private final List<InputReader> inputReaders;
  private final Map<InputReader, List<TaskDataHandler>> inputReaderToDataHandlersMap;
  private final Map<String, Iterator> idToSrcIteratorMap;
  private final Map<String, List<TaskDataHandler>> srcIteratorIdToDataHandlersMap;
  private final Map<String, List<TaskDataHandler>> iteratorIdToDataHandlersMap;
  private final LinkedBlockingQueue<Pair<String, DataUtil.IteratorWithNumBytes>> partitionQueue;
  private List<TaskDataHandler> taskDataHandlers;
  private Map<OutputCollectorImpl, List<TaskDataHandler>> outputToChildrenDataHandlersMap;
  private final Set<String> finishedTaskIds;
  private int numPartitions;
  private Map<String, Readable> logicalTaskIdToReadable;

  // For metrics
  private long serBlockSize;
  private long encodedBlockSize;

  private boolean isExecutionRequested;
  private String logicalTaskIdPutOnHold;

  private static final String ITERATORID_PREFIX = "ITERATOR_";
  private static final AtomicInteger ITERATORID_GENERATOR = new AtomicInteger(0);


  public class BroadcastInputReaderWrapper {
    public final InputReader inputReader;

    public BroadcastInputReaderWrapper(final InputReader inputReader) {
      this.inputReader = inputReader;
    }

    @Override
    public int hashCode() {
      final int hash = inputReader.getRuntimeEdge().getId().hashCode();
      LOG.info("hash: {}", hash);
      return hash;
    }

    @Override
    public boolean equals(Object other) {
      final boolean equality = this.hashCode() == other.hashCode();
      LOG.info("equals: {}", equality);
      return equality;
    }
  }



  private static final LoadingCache<BroadcastInputReaderWrapper, List> broadcastCache = CacheBuilder.newBuilder()
      .expireAfterWrite(60, TimeUnit.SECONDS)
      .build(new CacheLoader<BroadcastInputReaderWrapper, List>() {
        public List load(final BroadcastInputReaderWrapper broadcastInputReaderWrapper) {
          final String edgeId = broadcastInputReaderWrapper.inputReader.getRuntimeEdge().getId();

          try {
            LOG.info("[START-broadcastReader] {}", edgeId);
            final List pieces = new ArrayList();
            final ExecutorService serializationBoy = Executors.newCachedThreadPool();

            broadcastInputReaderWrapper.inputReader.read().forEach(compFuture -> {
              serializationBoy.submit(() -> {
                try {
                  final Iterator iterator = compFuture.get();
                  LOG.info("piece added {}", edgeId);
                  while (iterator.hasNext()) {
                    pieces.add(iterator.next());
                  }
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              });
            });

            serializationBoy.shutdown();
            serializationBoy.awaitTermination(1, TimeUnit.DAYS);

            LOG.info("[FINISH-broadcastReader] {}", edgeId);
            return pieces;
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        }
      });

  /**
   * Constructor.
   * @param scheduledTaskGroup TaskGroup with information needed during execution.
   * @param taskGroupDag TaskGroup expressed as a DAG of Tasks.
   * @param taskGroupStateManager State manager for this TaskGroup.
   * @param channelFactory For reading from/writing to data to other Stages.
   * @param metricMessageSender For sending metric with execution stats to Master.
   */
  public TaskGroupExecutor(final ScheduledTaskGroup scheduledTaskGroup,
                           final DAG<Task, RuntimeEdge<Task>> taskGroupDag,
                           final TaskGroupStateManager taskGroupStateManager,
                           final DataTransferFactory channelFactory,
                           final MetricMessageSender metricMessageSender) {
    this.taskGroupDag = taskGroupDag;
    this.taskGroupId = scheduledTaskGroup.getTaskGroupId();
    this.taskGroupIdx = scheduledTaskGroup.getTaskGroupIdx();
    this.taskGroupStateManager = taskGroupStateManager;
    this.stageIncomingEdges = scheduledTaskGroup.getTaskGroupIncomingEdges();
    this.stageOutgoingEdges = scheduledTaskGroup.getTaskGroupOutgoingEdges();
    this.logicalTaskIdToReadable = scheduledTaskGroup.getLogicalTaskIdToReadable();
    this.channelFactory = channelFactory;
    this.metricCollector = new MetricCollector(metricMessageSender);
    this.logicalTaskIdPutOnHold = null;
    this.isExecutionRequested = false;

    this.inputReaders = new ArrayList<>();
    this.inputReaderToDataHandlersMap = new ConcurrentHashMap<>();
    this.idToSrcIteratorMap = new HashMap<>();
    this.srcIteratorIdToDataHandlersMap = new HashMap<>();
    this.iteratorIdToDataHandlersMap = new ConcurrentHashMap<>();
    this.partitionQueue = new LinkedBlockingQueue<>();
    this.outputToChildrenDataHandlersMap = new HashMap<>();
    this.taskDataHandlers = new ArrayList<>();

    this.finishedTaskIds = new HashSet<>();
    this.numPartitions = 0;

    this.serBlockSize = 0;
    this.encodedBlockSize = 0;


    initialize();
  }

  /**
   * Initializes this TaskGroup before execution.
   * 1) Create and connect reader/writers for both inter-TaskGroup data and intra-TaskGroup data.
   * 2) Prepares Transforms if needed.
   */
  private void initialize() {
    // Initialize data read of SourceVertex.
    taskGroupDag.getTopologicalSort().stream()
        .filter(task -> task instanceof BoundedSourceTask)
        .forEach(boundedSourceTask -> ((BoundedSourceTask) boundedSourceTask).setReadable(
            logicalTaskIdToReadable.get(boundedSourceTask.getId())));

    // Initialize data handlers for each Task.
    taskGroupDag.topologicalDo(task -> taskDataHandlers.add(new TaskDataHandler(task)));

    // Initialize data transfer.
    // Construct a pointer-based DAG of TaskDataHandlers that are used for data transfer.
    // 'Pointer-based' means that it isn't Map/List-based in getting the data structure or parent/children
    // to avoid element-wise extra overhead of calculating hash values(HashMap) or iterating Lists.
    taskGroupDag.topologicalDo(task -> {
      final Set<PhysicalStageEdge> inEdgesFromOtherStages = getInEdgesFromOtherStages(task);
      final Set<PhysicalStageEdge> outEdgesToOtherStages = getOutEdgesToOtherStages(task);
      final TaskDataHandler dataHandler = getTaskDataHandler(task);

      // Set data handlers of children tasks.
      // This forms a pointer-based DAG of TaskDataHandlers.
      final List<TaskDataHandler> childrenDataHandlers = new ArrayList<>();
      taskGroupDag.getChildren(task.getId()).forEach(child ->
          childrenDataHandlers.add(getTaskDataHandler(child)));
      dataHandler.setChildrenDataHandler(childrenDataHandlers);

      // Add InputReaders for inter-stage data transfer
      inEdgesFromOtherStages.forEach(physicalStageEdge -> {
        final InputReader inputReader = channelFactory.createReader(
            taskGroupIdx, physicalStageEdge.getSrcVertex(), physicalStageEdge);

        // For InputReaders that have side input, collect them separately.
        if (DataCommunicationPatternProperty.Value.BroadCast.equals(inputReader.getRuntimeEdge()
            .getExecutionProperties().get(ExecutionProperty.Key.DataCommunicationPattern))) {
          dataHandler.addBroadcastFromOtherStage(inputReader);
        } else {
          inputReaders.add(inputReader);
          inputReaderToDataHandlersMap.putIfAbsent(inputReader, new ArrayList<>());
          inputReaderToDataHandlersMap.get(inputReader).add(dataHandler);
        }
      });

      // Add OutputWriters for inter-stage data transfer
      outEdgesToOtherStages.forEach(physicalStageEdge -> {
        final OutputWriter outputWriter = channelFactory.createWriter(
            task, taskGroupIdx, physicalStageEdge.getDstVertex(), physicalStageEdge);
        dataHandler.addOutputWriters(outputWriter);
      });

      // Add InputPipes for intra-stage data transfer
      addInputFromThisStage(task, dataHandler);

      // Add OutputPipe for intra-stage data transfer
      setOutputCollector(task, dataHandler);
    });

    // Prepare Transforms if needed.
    taskGroupDag.topologicalDo(task -> {
      if (task instanceof OperatorTask) {
        final Transform transform = ((OperatorTask) task).getTransform();
        final Map<Transform, Object> sideInputMap = new HashMap<>();
        final TaskDataHandler dataHandler = getTaskDataHandler(task);
        // Check and collect side inputs.
        if (!dataHandler.getSideInputFromThisStage().isEmpty()) {
          //LOG.info("[1-Side input from this stage] {}", dataHandler.getSideInputFromThisStage());
          sideInputFromThisStage(task, sideInputMap);
          //LOG.info("[2-Side input from this stage] {}", sideInputMap);
        }

        final Transform.Context transformContext = new ContextImpl(sideInputMap);
        final OutputCollectorImpl outputCollector = dataHandler.getOutputCollector();
        transform.prepare(transformContext, outputCollector);

        // TODO: first version -- do not cache
        // cache.get(new Loader() {fetch Data and run task} )
        if (!dataHandler.getBroadcastFromOtherStage().isEmpty()) {
          final InputReader broadcastReader = dataHandler.getBroadcastFromOtherStage().remove(0);
          try {
            for (final Object piece : broadcastCache.get(new BroadcastInputReaderWrapper(broadcastReader))) {
              transform.onData(piece);
            }
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
          transform.close();
          finishedTaskIds.add(task.getId());
        }
      }
    });
  }

  // Helper functions to initializes cross-stage edges.
  private Set<PhysicalStageEdge> getInEdgesFromOtherStages(final Task task) {
    return stageIncomingEdges.stream().filter(
        stageInEdge -> stageInEdge.getDstVertex().getId().equals(task.getIrVertexId()))
        .collect(Collectors.toSet());
  }

  private Set<PhysicalStageEdge> getOutEdgesToOtherStages(final Task task) {
    return stageOutgoingEdges.stream().filter(
        stageInEdge -> stageInEdge.getSrcVertex().getId().equals(task.getIrVertexId()))
        .collect(Collectors.toSet());
  }

  /**
   * Add input OutputCollectors to each {@link Task}.
   * Input OutputCollector denotes all the OutputCollectors of intra-Stage parent tasks of this task.
   *
   * @param task the Task to add input OutputCollectors to.
   */
  private void addInputFromThisStage(final Task task, final TaskDataHandler dataHandler) {
    List<Task> parentTasks = taskGroupDag.getParents(task.getId());
    final String physicalTaskId = getPhysicalTaskId(task.getId());

    if (parentTasks != null) {
      parentTasks.forEach(parent -> {
        final OutputCollectorImpl parentOutputCollector = getTaskDataHandler(parent).getOutputCollector();

        //LOG.info("[addInputFromThisStage] parent Output collector {}: {}", task.getId(), parentOutputCollector.getId());
        if (parentOutputCollector.getSideInputRuntimeEdge() != null) {
          //LOG.info("[addInputFromThisStage] sideInput Output collector {}: {}", task.getId(), parentOutputCollector.getId());
          dataHandler.addSideInputFromThisStage(parentOutputCollector);
        } else {
          dataHandler.addInputFromThisStages(parentOutputCollector);
        }
      });
    }
  }

  /**
   * Add output outputCollectors to each {@link Task}.
   * Output outputCollector denotes the one and only one outputCollector of this task.
   * Check the outgoing edges that will use this outputCollector,
   * and set this outputCollector as side input if any one of the edges uses this outputCollector as side input.
   *
   * @param task the Task to add output outputCollectors to.
   */
  private void setOutputCollector(final Task task, final TaskDataHandler dataHandler) {
    final OutputCollectorImpl outputCollector = new OutputCollectorImpl();
    final String physicalTaskId = getPhysicalTaskId(task.getId());

    taskGroupDag.getOutgoingEdgesOf(task).forEach(outEdge -> {
      if (outEdge.isSideInput()) {
        //LOG.info("[setOutputCollector] sideInput Output collector {}: {}", task.getId(), outputCollector.getId());
        outputCollector.setSideInputRuntimeEdge(outEdge);
      }
    });

    dataHandler.setOutputCollector(outputCollector);
  }

  private boolean hasOutputWriter(final Task task) {
    return !getTaskDataHandler(task).getOutputWriters().isEmpty();
  }

  private void setTaskPutOnHold(final MetricCollectionBarrierTask task) {
    final String physicalTaskId = getPhysicalTaskId(task.getId());
    logicalTaskIdPutOnHold = RuntimeIdGenerator.getLogicalTaskIdIdFromPhysicalTaskId(physicalTaskId);
  }

  private void writeAndCloseOutputWriters(final Task task) {
    final String physicalTaskId = getPhysicalTaskId(task.getId());
    final List<Long> writtenBytesList = new ArrayList<>();
    final Map<String, Object> metric = new HashMap<>();
    metricCollector.beginMeasurement(physicalTaskId, metric);
    final long writeStartTime = System.currentTimeMillis();

    getTaskDataHandler(task).getOutputWriters().forEach(outputWriter -> {
      //LOG.info("Closing outputwriter {}", outputWriter.getId());
      outputWriter.close();
      //LOG.info("Closed outputwriter {}", outputWriter.getId());
      final Optional<Long> writtenBytes = outputWriter.getWrittenBytes();
      writtenBytes.ifPresent(writtenBytesList::add);
      //LOG.info("2-Closed outputwriter {}", outputWriter.getId());
    });

    final long writeEndTime = System.currentTimeMillis();
    //LOG.info("3-Closed outputwriter {}", task.getId());
    metric.put("OutputWriteTime(ms)", writeEndTime - writeStartTime);
    putWrittenBytesMetric(writtenBytesList, metric);
    //LOG.info("4-Closed outputwriter {}", task.getId());
    metricCollector.endMeasurement(physicalTaskId, metric);
    //LOG.info("5-Closed outputwriter {}", task.getId());
  }

  private void prepareInputFromSource() {
    taskGroupDag.topologicalDo(task -> {
      if (task instanceof BoundedSourceTask) {
        try {
          final String iteratorId = generateIteratorId();
          final Iterator iterator = ((BoundedSourceTask) task).getReadable().read().iterator();
          idToSrcIteratorMap.putIfAbsent(iteratorId, iterator);
          srcIteratorIdToDataHandlersMap.putIfAbsent(iteratorId, new ArrayList<>());
          srcIteratorIdToDataHandlersMap.get(iteratorId).add(getTaskDataHandler(task));
        } catch (final BlockFetchException ex) {
          taskGroupStateManager.onTaskGroupStateChanged(TaskGroupState.State.FAILED_RECOVERABLE,
              Optional.empty(), Optional.of(TaskGroupState.RecoverableFailureCause.INPUT_READ_FAILURE));
          LOG.info("{} Execution Failed (Recoverable: input read failure)! Exception: {}", taskGroupId, ex.toString());
        } catch (final Exception e) {
          taskGroupStateManager.onTaskGroupStateChanged(TaskGroupState.State.FAILED_UNRECOVERABLE,
              Optional.empty(), Optional.empty());
          LOG.info("{} Execution Failed! Exception: {}", taskGroupId, e.toString());
          throw new RuntimeException(e);
        }
      }
      // TODO #XXX: Support other types of source tasks, i. e. InitializedSourceTask
    });
  }

  private void prepareInputFromOtherStages() {
    inputReaders.stream().forEach(inputReader -> {
      final List<CompletableFuture<DataUtil.IteratorWithNumBytes>> futures = inputReader.read();
      numPartitions += futures.size();

      // Add consumers which will push iterator when the futures are complete.
      futures.forEach(compFuture -> compFuture.whenComplete((iterator, exception) -> {
        if (exception != null) {
          throw new BlockFetchException(exception);
        }

        final String iteratorId = generateIteratorId();
        if (iteratorIdToDataHandlersMap.containsKey(iteratorId)) {
          throw new RuntimeException("iteratorIdToDataHandlersMap already contains " + iteratorId);
        } else {
          iteratorIdToDataHandlersMap.computeIfAbsent(iteratorId,
              absentIteratorId -> inputReaderToDataHandlersMap.get(inputReader));
          try {
            partitionQueue.put(Pair.of(iteratorId, iterator));
          } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while receiving iterator " + e);
          }
        }
      }));
    });
  }

  private boolean finishedAllTasks() {
    // Total number of Tasks in this TaskGroup
    int taskNum = taskDataHandlers.size();
    int finishedTaskNum = finishedTaskIds.size();

    return finishedTaskNum == taskNum;
  }

  private void initializeOutputToChildrenDataHandlersMap() {
    srcIteratorIdToDataHandlersMap.values().forEach(dataHandlers ->
        dataHandlers.forEach(dataHandler -> {
          outputToChildrenDataHandlersMap.putIfAbsent(dataHandler.getOutputCollector(), dataHandler.getChildren());
        }));
    iteratorIdToDataHandlersMap.values().forEach(dataHandlers ->
        dataHandlers.forEach(dataHandler -> {
          outputToChildrenDataHandlersMap.putIfAbsent(dataHandler.getOutputCollector(), dataHandler.getChildren());
        }));
  }

  private void updateOutputToChildrenDataHandlersMap() {
    Map<OutputCollectorImpl, List<TaskDataHandler>> currentMap = outputToChildrenDataHandlersMap;
    Map<OutputCollectorImpl, List<TaskDataHandler>> updatedMap = new HashMap<>();

    currentMap.values().forEach(dataHandlers ->
        dataHandlers.forEach(dataHandler -> {
          updatedMap.putIfAbsent(dataHandler.getOutputCollector(), dataHandler.getChildren());
        })
    );

    outputToChildrenDataHandlersMap = updatedMap;
  }

  private void closeTransform(final Task task) {
    if (task instanceof OperatorTask) {
      Transform transform = ((OperatorTask) task).getTransform();
      transform.close();
    }
  }

  /*
  private void sideInputFromOtherStages(final Task task, final Map<Transform, Object> sideInputMap) {
    getTaskDataHandler(task).getSideInputFromOtherStages().forEach(sideInputReader -> {
      try {
        final DataUtil.IteratorWithNumBytes sideInputIterator = sideInputReader.read().get(0).get();
        final Object sideInput = getSideInput(sideInputIterator);
        final RuntimeEdge inEdge = sideInputReader.getRuntimeEdge();
        final Transform srcTransform;
        if (inEdge instanceof PhysicalStageEdge) {
          srcTransform = ((OperatorVertex) ((PhysicalStageEdge) inEdge).getSrcVertex()).getTransform();
        } else {
          srcTransform = ((OperatorTask) inEdge.getSrc()).getTransform();
        }
        sideInputMap.put(srcTransform, sideInput);

        // Collect metrics on block size if possible.
        try {
          serBlockSize += sideInputIterator.getNumSerializedBytes();
        } catch (final DataUtil.IteratorWithNumBytes.NumBytesNotSupportedException e) {
          serBlockSize = -1;
        }
        try {
          encodedBlockSize += sideInputIterator.getNumEncodedBytes();
        } catch (final DataUtil.IteratorWithNumBytes.NumBytesNotSupportedException e) {
          encodedBlockSize = -1;
        }
      } catch (final InterruptedException | ExecutionException e) {
        throw new BlockFetchException(e);
      }
    });
  }
  */

  private void sideInputFromThisStage(final Task task, final Map<Transform, Object> sideInputMap) {
    final String physicalTaskId = getPhysicalTaskId(task.getId());
    getTaskDataHandler(task).getSideInputFromThisStage().forEach(input -> {
      // because sideInput is only 1 element in the outputCollector
      Object sideInput = input.remove();
      final RuntimeEdge inEdge = input.getSideInputRuntimeEdge();
      final Transform srcTransform;
      if (inEdge instanceof PhysicalStageEdge) {
        srcTransform = ((OperatorVertex) ((PhysicalStageEdge) inEdge).getSrcVertex()).getTransform();
      } else {
        srcTransform = ((OperatorTask) inEdge.getSrc()).getTransform();
      }
      sideInputMap.put(srcTransform, sideInput);
    });
  }

  /**
   * Executes the task group.
   */
  public void execute() {
    final Map<String, Object> metric = new HashMap<>();
    metricCollector.beginMeasurement(taskGroupId, metric);
    long boundedSrcReadStartTime = 0;
    long boundedSrcReadEndTime = 0;
    long inputReadStartTime = 0;
    long inputReadEndTime = 0;
    if (isExecutionRequested) {
      throw new RuntimeException("TaskGroup {" + taskGroupId + "} execution called again!");
    } else {
      isExecutionRequested = true;
    }
    taskGroupStateManager.onTaskGroupStateChanged(TaskGroupState.State.EXECUTING, Optional.empty(), Optional.empty());
    LOG.info("{} Executing!", taskGroupId);

    // Prepare input data from bounded source.
    boundedSrcReadStartTime = System.currentTimeMillis();
    prepareInputFromSource();
    boundedSrcReadEndTime = System.currentTimeMillis();
    metric.put("BoundedSourceReadTime(ms)", boundedSrcReadEndTime - boundedSrcReadStartTime);

    // Prepare input data from other stages.
    inputReadStartTime = System.currentTimeMillis();
    prepareInputFromOtherStages();

    // Execute the TaskGroup DAG.
    try {
      srcIteratorIdToDataHandlersMap.forEach((srcIteratorId, dataHandlers) -> {
        Iterator iterator = idToSrcIteratorMap.get(srcIteratorId);
        iterator.forEachRemaining(element -> {
          for (final TaskDataHandler dataHandler : dataHandlers) {
            runTask(dataHandler, element);
          }
        });
      });

      // Process data from other stages.
      for (int currPartition = 0; currPartition < numPartitions; currPartition++) {
        Pair<String, DataUtil.IteratorWithNumBytes> idToIteratorPair = partitionQueue.take();
        final String iteratorId = idToIteratorPair.left();
        final DataUtil.IteratorWithNumBytes iterator = idToIteratorPair.right();
        List<TaskDataHandler> dataHandlers = iteratorIdToDataHandlersMap.get(iteratorId);
        iterator.forEachRemaining(element -> {
          for (final TaskDataHandler dataHandler : dataHandlers) {
            runTask(dataHandler, element);
          }
        });

        // Collect metrics on block size if possible.
        try {
          serBlockSize += iterator.getNumSerializedBytes();
        } catch (final DataUtil.IteratorWithNumBytes.NumBytesNotSupportedException e) {
          serBlockSize = -1;
        } catch (final IllegalStateException e) {
          LOG.error("IllegalState ", e);
        }
        try {
          encodedBlockSize += iterator.getNumEncodedBytes();
        } catch (final DataUtil.IteratorWithNumBytes.NumBytesNotSupportedException e) {
          encodedBlockSize = -1;
        } catch (final IllegalStateException e) {
          LOG.error("IllegalState ", e);
        }
      }
      inputReadEndTime = System.currentTimeMillis();
      metric.put("InputReadTime(ms)", inputReadEndTime - inputReadStartTime);

      // Process intra-TaskGroup data.
      // Intra-TaskGroup data comes from outputCollectors of this TaskGroup's Tasks.
      initializeOutputToChildrenDataHandlersMap();
      while (!finishedAllTasks()) {
        // LOG.info("finishedAllTasks()");

        outputToChildrenDataHandlersMap.forEach((outputCollector, childrenDataHandlers) -> {
          // Get the task that has this outputCollector as its output outputCollector
          Task outputCollectorOwnerTask = taskDataHandlers.stream()
              .filter(dataHandler -> dataHandler.getOutputCollector() == outputCollector)
              .findFirst().get().getTask();

          // Before consuming the output of outputCollectorOwnerTask as input,
          // close transform if it is OperatorTransform.
          closeTransform(outputCollectorOwnerTask);

          // Set outputCollectorOwnerTask as finished.
          finishedTaskIds.add(getPhysicalTaskId(outputCollectorOwnerTask.getId()));

          while (!outputCollector.isEmpty()) {
            final Object element = outputCollector.remove();

            // Pass outputCollectorOwnerTask's output to its children tasks recursively.
            if (!childrenDataHandlers.isEmpty()) {
              for (final TaskDataHandler childDataHandler : childrenDataHandlers) {
                runTask(childDataHandler, element);
              }
            }

            // Write element-wise to OutputWriters if any and close the OutputWriters.
            if (hasOutputWriter(outputCollectorOwnerTask)) {
              // If outputCollector isn't empty(if closeTransform produced some output),
              // write them element-wise to OutputWriters.
              List<OutputWriter> outputWritersOfTask =
                  getTaskDataHandler(outputCollectorOwnerTask).getOutputWriters();
              outputWritersOfTask.forEach(outputWriter -> outputWriter.writeElement(element));
            }
          }

          if (hasOutputWriter(outputCollectorOwnerTask)) {
            writeAndCloseOutputWriters(outputCollectorOwnerTask);
          }

          // LOG.info("6-Closed outputwriter {}");
        });
        // LOG.info("7-Closed outputwriter {}");
        updateOutputToChildrenDataHandlersMap();
        // LOG.info("8-Closed outputwriter {}");
      }
    } catch (final BlockWriteException ex2) {
      taskGroupStateManager.onTaskGroupStateChanged(TaskGroupState.State.FAILED_RECOVERABLE,
          Optional.empty(), Optional.of(TaskGroupState.RecoverableFailureCause.OUTPUT_WRITE_FAILURE));
      LOG.info("{} Execution Failed (Recoverable: output write failure)! Exception: {}",
          taskGroupId, ex2.toString());
    } catch (final Exception e) {
      taskGroupStateManager.onTaskGroupStateChanged(TaskGroupState.State.FAILED_UNRECOVERABLE,
          Optional.empty(), Optional.empty());
      LOG.info("{} Execution Failed! Exception: {}",
          taskGroupId, e.toString());
      throw new RuntimeException(e);
    }

    // LOG.info("{} Before metric!", taskGroupId);

    // Put TaskGroup-unit metrics.
    final boolean available = serBlockSize >= 0;
    putReadBytesMetric(available, serBlockSize, encodedBlockSize, metric);
    metricCollector.endMeasurement(taskGroupId, metric);
    if (logicalTaskIdPutOnHold == null) {
      taskGroupStateManager.onTaskGroupStateChanged(TaskGroupState.State.COMPLETE, Optional.empty(), Optional.empty());
    } else {
      taskGroupStateManager.onTaskGroupStateChanged(TaskGroupState.State.ON_HOLD,
          Optional.of(logicalTaskIdPutOnHold),
          Optional.empty());
    }
    LOG.info("{} Complete!", taskGroupId);
  }

  /**
   * Processes an OperatorTask.
   *
   * @param dataHandler TaskDataHandler of a task to execute.
   */
  private void runTask(final TaskDataHandler dataHandler, final Object dataElement) {
    final Task task = dataHandler.getTask();
    final OutputCollectorImpl outputCollector = dataHandler.getOutputCollector();
    final String physicalTaskId = getPhysicalTaskId(task.getId());

    // Process element-wise depending on the Task type
    if (task instanceof BoundedSourceTask) {
      if (dataElement == null) { // null used for Beam VoidCoders
        final List<Object> nullForVoidCoder = Collections.singletonList(dataElement);
        outputCollector.emit(nullForVoidCoder);
      } else {
        outputCollector.emit(dataElement);
      }
    } else if (task instanceof OperatorTask) {
      final Transform transform = ((OperatorTask) task).getTransform();
      transform.onData(dataElement);
    } else if (task instanceof MetricCollectionBarrierTask) {
      if (dataElement == null) { // null used for Beam VoidCoders
        final List<Object> nullForVoidCoder = Collections.singletonList(dataElement);
        outputCollector.emit(nullForVoidCoder);
      } else {
        outputCollector.emit(dataElement);
      }
      setTaskPutOnHold((MetricCollectionBarrierTask) task);
    } else {
      throw new UnsupportedOperationException("This type  of Task is not supported");
    }

    // For the produced output
    while (!outputCollector.isEmpty()) {
      final Object element = outputCollector.remove();

      // Pass output to its children recursively.
      List<TaskDataHandler> childrenDataHandlers = dataHandler.getChildren();
      if (!childrenDataHandlers.isEmpty()) {
        for (final TaskDataHandler childDataHandler : childrenDataHandlers) {
          runTask(childDataHandler, element);
        }
      }

      // Write element-wise to OutputWriters if any
      if (hasOutputWriter(task)) {
        List<OutputWriter> outputWritersOfTask = dataHandler.getOutputWriters();
        outputWritersOfTask.forEach(outputWriter -> outputWriter.writeElement(element));
      }
    }
  }

  /**
   * @param logicalTaskId the logical task id.
   * @return the physical task id.
   */
  private String getPhysicalTaskId(final String logicalTaskId) {
    return RuntimeIdGenerator.generatePhysicalTaskId(taskGroupIdx, logicalTaskId);
  }

  private String generateIteratorId() {
    return ITERATORID_PREFIX + ITERATORID_GENERATOR.getAndIncrement();
  }

  private TaskDataHandler getTaskDataHandler(final Task task) {
    return taskDataHandlers.stream()
        .filter(dataHandler -> dataHandler.getTask() == task)
        .findFirst().get();
  }

  /**
   * Puts read bytes metric if the input data size is known.
   *
   * @param serializedBytes size in serialized (encoded and optionally post-processed (e.g. compressed)) form
   * @param encodedBytes    size in encoded form
   * @param metricMap       the metric map to put written bytes metric.
   */
  private static void putReadBytesMetric(final boolean available,
                                         final long serializedBytes,
                                         final long encodedBytes,
                                         final Map<String, Object> metricMap) {
    if (available) {
      if (serializedBytes != encodedBytes) {
        metricMap.put("ReadBytes(raw)", serializedBytes);
      }
      metricMap.put("ReadBytes", encodedBytes);
    }
  }

  /**
   * Puts written bytes metric if the output data size is known.
   *
   * @param writtenBytesList the list of written bytes.
   * @param metricMap        the metric map to put written bytes metric.
   */
  private static void putWrittenBytesMetric(final List<Long> writtenBytesList,
                                            final Map<String, Object> metricMap) {
    if (!writtenBytesList.isEmpty()) {
      long totalWrittenBytes = 0;
      for (final Long writtenBytes : writtenBytesList) {
        totalWrittenBytes += writtenBytes;
      }
      metricMap.put("WrittenBytes", totalWrittenBytes);
    }
  }

  /**
   * Get sideInput from data from {@link InputReader}.
   *
   * @param iterator data from {@link InputReader#read()}
   * @return The corresponding sideInput
   */
  private static Object getSideInput(final DataUtil.IteratorWithNumBytes iterator) {
    final List copy = new ArrayList();
    iterator.forEachRemaining(copy::add);
    if (copy.size() == 1) {
      return copy.get(0);
    } else {
      if (copy.get(0) instanceof Iterable) {
        final List collect = new ArrayList();
        copy.forEach(element -> ((Iterable) element).iterator().forEachRemaining(collect::add));
        return collect;
      } else if (copy.get(0) instanceof Map) {
        final Map collect = new HashMap();
        copy.forEach(element -> {
          final Set keySet = ((Map) element).keySet();
          keySet.forEach(key -> collect.put(key, ((Map) element).get(key)));
        });
        return collect;
      } else {
        return copy;
      }
    }
  }
}
