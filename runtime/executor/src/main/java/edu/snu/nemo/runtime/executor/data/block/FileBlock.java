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
package edu.snu.nemo.runtime.executor.data.block;

import edu.snu.nemo.common.exception.BlockFetchException;
import edu.snu.nemo.common.exception.BlockWriteException;
import edu.snu.nemo.runtime.common.data.KeyRange;
import edu.snu.nemo.runtime.executor.data.*;
import edu.snu.nemo.runtime.executor.data.partition.NonSerializedPartition;
import edu.snu.nemo.runtime.executor.data.partition.Partition;
import edu.snu.nemo.runtime.executor.data.partition.SerializedPartition;
import edu.snu.nemo.runtime.executor.data.streamchainer.Serializer;
import edu.snu.nemo.runtime.executor.data.metadata.PartitionMetadata;
import edu.snu.nemo.runtime.executor.data.metadata.FileMetadata;

import javax.annotation.concurrent.NotThreadSafe;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * This class represents a block which is stored in (local or remote) file.
 * Concurrent read is supported, but concurrent write is not supported.
 *
 * @param <K> the key type of its partitions.
 */
@NotThreadSafe
public final class FileBlock<K extends Serializable> extends AbstractBlock<K> {

  private final String filePath;
  private final FileMetadata<K> metadata;

  /**
   * Constructor.
   * If write (or read) as bytes is enabled, data written to (read from) the block does not (de)serialized.
   *
   * @param blockId    the ID of this block.
   * @param serializer the {@link Serializer}.
   * @param filePath   the path of the file that this block will be stored.
   * @param metadata   the metadata for this block.\
   */
  public FileBlock(final String blockId,
                   final Serializer serializer,
                   final String filePath,
                   final FileMetadata<K> metadata) {
    super(blockId, serializer);
    this.filePath = filePath;
    this.metadata = metadata;
  }

  /**
   * Writes the serialized data of this block having a specific key value as a partition to the file
   * where this block resides.
   * Invariant: This method does not support concurrent write.
   *
   * @param serializedPartitions the iterable of the serialized partitions to write.
   * @throws IOException if fail to write.
   */
  private void writeToFile(final Iterable<SerializedPartition<K>> serializedPartitions)
      throws IOException {
    try (final FileOutputStream fileOutputStream = new FileOutputStream(filePath, true)) {
      for (final SerializedPartition<K> serializedPartition : serializedPartitions) {
        // Reserve a partition write and get the metadata.
        metadata.writePartitionMetadata(
            serializedPartition.getKey(), serializedPartition.getLength(), serializedPartition.getElementsCount());
        fileOutputStream.write(serializedPartition.getData(), 0, serializedPartition.getLength());
      }
    }
  }

  /**
   * Writes an element to non-committed block.
   * Invariant: This should not be invoked after this block is committed.
   * Invariant: This method does not support concurrent write.
   *
   * @param key     the key.
   * @param element the element to write.
   * @throws BlockWriteException for any error occurred while trying to write a block.
   */
  @Override
  public void write(final K key,
                    final Object element) throws BlockWriteException {
    if (metadata.isCommitted()) {
      throw new BlockWriteException(new Throwable("The block is already committed!"));
    } else {
      final Serializer serializerToUse = metadata.isWriteAsBytes()
          ? SerializerManager.getAsBytesSerializer() : getSerializer();
      try {
        final Map nonCommittedPartitionsMap = getNonCommittedPartitionsMap();
        SerializedPartition<K> partition = (SerializedPartition<K>) nonCommittedPartitionsMap.get(key);
        if (partition == null) {
          partition = new SerializedPartition<>(key, serializerToUse);
          nonCommittedPartitionsMap.put(key, partition);
        }
        partition.write(element);
      } catch (final IOException e) {
        throw new BlockWriteException(e);
      }
    }
  }

  /**
   * Writes {@link NonSerializedPartition}s to this block.
   * Invariant: This method does not support concurrent write.
   *
   * @param partitions the {@link NonSerializedPartition}s to write.
   * @throws BlockWriteException for any error occurred while trying to write a block.
   */
  @Override
  public void writePartitions(final Iterable<NonSerializedPartition<K>> partitions) throws BlockWriteException {
    if (metadata.isCommitted()) {
      throw new BlockWriteException(new Throwable("The partition is already committed!"));
    } else {
      try {
        final Serializer serializerToUse = metadata.isWriteAsBytes()
            ? SerializerManager.getAsBytesSerializer() : getSerializer();
        final Iterable<SerializedPartition<K>> convertedPartitions =
            DataUtil.convertToSerPartitions(serializerToUse, partitions);
        writeSerializedPartitions(convertedPartitions);
      } catch (final IOException e) {
        throw new BlockWriteException(e);
      }
    }
  }

  /**
   * Writes {@link SerializedPartition}s to this block.
   * Invariant: This method does not support concurrent write.
   *
   * @param partitions the {@link SerializedPartition}s to store.
   * @throws BlockWriteException for any error occurred while trying to write a block.
   */
  @Override
  public void writeSerializedPartitions(final Iterable<SerializedPartition<K>> partitions)
      throws BlockWriteException {
    if (metadata.isCommitted()) {
      throw new BlockWriteException(new Throwable("The partition is already committed!"));
    } else {
      try {
        writeToFile(partitions);
      } catch (final IOException e) {
        throw new BlockWriteException(e);
      }
    }
  }

  /**
   * WOW.
   */
  private final class LazyIterator implements Iterator, AutoCloseable, DataUtil.IteratorWithNumBytes {

    private final KeyRange keyRange;
    private final FileInputStream fileInputStream;
    private final Iterator<PartitionMetadata<K>> partitionMetadataItr;
    private final Serializer serializerToUse;
    private int readablePartitions;
    private Iterator currentIterator;
    private long numSerializedBytes;

    private LazyIterator(final KeyRange keyRange) throws BlockFetchException {
      try {
        this.keyRange = keyRange;
        this.fileInputStream = new FileInputStream(filePath);
        this.partitionMetadataItr = metadata.getPartitionMetadataList().iterator();
        this.readablePartitions = metadata.getPartitionMetadataList().size();
        this.serializerToUse = metadata.isReadAsBytes()
            ? SerializerManager.getAsBytesSerializer() : getSerializer();
        this.currentIterator = null;
        this.numSerializedBytes = 0;
      } catch (final IOException e) {
        throw new BlockFetchException(e);
      }
    }

    @Override
    public boolean hasNext() {
      try {
        if (currentIterator == null) {
          while (readablePartitions > 0) {
            readablePartitions--;
            final PartitionMetadata<K> pMetadata = partitionMetadataItr.next();
            final K key = pMetadata.getKey();
            if (keyRange.includes(key)) {
              // The key value of this partition is in the range.
              final long availableBefore = fileInputStream.available();
              // We need to limit read bytes on this FileStream, which could be over-read by wrapped
              // compression stream. We recommend to wrap with LimitedInputStream once more when
              // reading input from chained compression InputStream.
              // Plus, this stream must be not closed to prevent to close the filtered file partition.
              final int length = pMetadata.getPartitionSize();
              this.numSerializedBytes += length;
              final LimitedInputStream limitedInputStream = new LimitedInputStream(fileInputStream, length);
              final NonSerializedPartition<K> deserializePartition =
                  DataUtil.deserializePartition(length, serializerToUse, key, limitedInputStream);
              // rearrange file pointer
              final long toSkip = pMetadata.getPartitionSize() - availableBefore + fileInputStream.available();
              if (toSkip > 0) {
                skipBytes(fileInputStream, toSkip);
              } else if (toSkip < 0) {
                throw new IOException("file stream has been overread");
              }
              currentIterator = deserializePartition.getData().iterator();
              if (currentIterator.hasNext()) {
                return true;
              } else {
                currentIterator = null;
              }
            } else {
              // Have to skip this partition.
              skipBytes(fileInputStream, pMetadata.getPartitionSize());
            }
          }
          return false;
        } else {
          return true;
        }
      } catch (final IOException e) {
        throw new BlockFetchException(e);
      }
    }

    @Override
    public Object next() {
      if (hasNext()) {
        final Object value = currentIterator.next();
        if (!currentIterator.hasNext()) {
          currentIterator = null;
        }
        return value;
      } else {
        throw new NoSuchElementException();
      }
    }

    @Override
    public void close() throws IOException {
      fileInputStream.close();
    }

    @Override
    public long getNumSerializedBytes() throws NumBytesNotSupportedException {
      return numSerializedBytes;
    }

    @Override
    public long getNumEncodedBytes() throws NumBytesNotSupportedException {
      throw new NumBytesNotSupportedException();
    }

  }

  public DataUtil.IteratorWithNumBytes readLazily(final KeyRange keyRange) throws BlockFetchException {
    if (!metadata.isCommitted()) {
      throw new BlockFetchException(new Throwable("Cannot retrieve elements before a block is committed"));
    } else {
      return new LazyIterator(keyRange);
    }
  }

  /**
   * Retrieves the partitions of this block from the file in a specific key range and deserializes it.
   *
   * @param keyRange the key range.
   * @return an iterable of {@link NonSerializedPartition}s.
   * @throws BlockFetchException for any error occurred while trying to fetch a block.
   */
  @Override
  public Iterable<NonSerializedPartition<K>> readPartitions(final KeyRange keyRange) throws BlockFetchException {
    if (!metadata.isCommitted()) {
      throw new BlockFetchException(new Throwable("Cannot retrieve elements before a block is committed"));
    } else {
      // Deserialize the data
      final List<NonSerializedPartition<K>> deserializedPartitions = new ArrayList<>();
      final Serializer serializerToUse = metadata.isReadAsBytes()
          ? SerializerManager.getAsBytesSerializer() : getSerializer();
      try {
        try (final FileInputStream fileStream = new FileInputStream(filePath)) {
          for (final PartitionMetadata<K> partitionMetadata : metadata.getPartitionMetadataList()) {
            final K key = partitionMetadata.getKey();
            if (keyRange.includes(key)) {
              // The key value of this partition is in the range.
              final long availableBefore = fileStream.available();
              // We need to limit read bytes on this FileStream, which could be over-read by wrapped
              // compression stream. We recommend to wrap with LimitedInputStream once more when
              // reading input from chained compression InputStream.
              // Plus, this stream must be not closed to prevent to close the filtered file partition.
              final int length = partitionMetadata.getPartitionSize();
              final LimitedInputStream limitedInputStream = new LimitedInputStream(fileStream, length);
              final NonSerializedPartition<K> deserializePartition =
                  DataUtil.deserializePartition(length, serializerToUse, key, limitedInputStream);
              deserializedPartitions.add(deserializePartition);
              // rearrange file pointer
              final long toSkip = partitionMetadata.getPartitionSize() - availableBefore + fileStream.available();
              if (toSkip > 0) {
                skipBytes(fileStream, toSkip);
              } else if (toSkip < 0) {
                throw new IOException("file stream has been overread");
              }
            } else {
              // Have to skip this partition.
              skipBytes(fileStream, partitionMetadata.getPartitionSize());
            }
          }
        }
      } catch (final IOException e) {
        throw new BlockFetchException(e);
      }

      return deserializedPartitions;
    }
  }

  /**
   * Retrieves the {@link SerializedPartition}s in a specific key range.
   * Invariant: This should not be invoked before this block is committed.
   *
   * @param keyRange the key range to retrieve.
   * @return an iterable of {@link SerializedPartition}s.
   * @throws BlockFetchException for any error occurred while trying to fetch a block.
   */
  @Override
  public Iterable<SerializedPartition<K>> readSerializedPartitions(final KeyRange keyRange) throws BlockFetchException {
    if (!metadata.isCommitted()) {
      throw new BlockFetchException(new Throwable("Cannot retrieve elements before a block is committed"));
    } else {
      // Deserialize the data
      final List<SerializedPartition<K>> partitionsInRange = new ArrayList<>();
      try {
        try (final FileInputStream fileStream = new FileInputStream(filePath)) {
          for (final PartitionMetadata<K> partitionmetadata : metadata.getPartitionMetadataList()) {
            final K key = partitionmetadata.getKey();
            if (keyRange.includes(key)) {
              // The hash value of this partition is in the range.
              final byte[] serializedData = new byte[partitionmetadata.getPartitionSize()];
              final int readBytes = fileStream.read(serializedData);
              if (readBytes != serializedData.length) {
                throw new IOException("The read data size does not match with the partition size.");
              }
              partitionsInRange.add(new SerializedPartition<>(
                  key, partitionmetadata.getElementsTotal(), serializedData, serializedData.length));
            } else {
              // Have to skip this partition.
              skipBytes(fileStream, partitionmetadata.getPartitionSize());
            }
          }
        }
      } catch (final IOException e) {
        throw new BlockFetchException(e);
      }

      return partitionsInRange;
    }
  }

  /**
   * Skips some bytes in a input stream.
   *
   * @param inputStream the stream to skip.
   * @param bytesToSkip the number of bytes to skip.
   * @throws IOException if fail to skip.
   */
  private void skipBytes(final InputStream inputStream,
                         final long bytesToSkip) throws IOException {
    long remainingBytesToSkip = bytesToSkip;
    while (remainingBytesToSkip > 0) {
      final long skippedBytes = inputStream.skip(bytesToSkip);
      remainingBytesToSkip -= skippedBytes;
      if (skippedBytes <= 0) {
        throw new IOException("The file stream failed to skip to the next block.");
      }
    }
  }

  /**
   * Retrieves the list of {@link FileArea}s for the specified {@link KeyRange}.
   *
   * @param keyRange the key range
   * @return list of the file areas
   * @throws IOException if failed to open a file channel
   */
  public List<FileArea> asFileAreas(final KeyRange keyRange) throws IOException {
    if (!metadata.isCommitted()) {
      throw new IOException("Cannot retrieve elements before a block is committed");
    } else {
      final List<FileArea> fileAreas = new ArrayList<>();
      for (final PartitionMetadata<K> partitionMetadata : metadata.getPartitionMetadataList()) {
        if (keyRange.includes(partitionMetadata.getKey())) {
          fileAreas.add(new FileArea(filePath, partitionMetadata.getOffset(), partitionMetadata.getPartitionSize()));
        }
      }
      return fileAreas;
    }
  }

  /**
   * Deletes the file that contains this block data.
   * This method have to be called after all read is completed (or failed).
   *
   * @throws IOException if failed to delete.
   */
  public void deleteFile() throws IOException {
    metadata.deleteMetadata();
    if (new File(filePath).exists()) {
      Files.delete(Paths.get(filePath));
    }
  }

  /**
   * Commits this block to prevent further write.
   *
   * @return the size of each partition.
   * @throws BlockWriteException for any error occurred while trying to write a block.
   */
  @Override
  public synchronized Optional<Map<K, Long>> commit() throws BlockWriteException {
    try {
      if (!metadata.isCommitted()) {
        commitPartitions();
        metadata.commitBlock();
      }
      final List<PartitionMetadata<K>> partitionMetadataList = metadata.getPartitionMetadataList();
      final Map<K, Long> partitionSizes = new HashMap<>(partitionMetadataList.size());
      for (final PartitionMetadata<K> partitionMetadata : partitionMetadataList) {
        final K key = partitionMetadata.getKey();
        final long partitionSize = partitionMetadata.getPartitionSize();
        if (partitionSizes.containsKey(key)) {
          partitionSizes.compute(key,
              (existingKey, existingValue) -> existingValue + partitionSize);
        } else {
          partitionSizes.put(key, partitionSize);
        }
      }
      return Optional.of(partitionSizes);
    } catch (final IOException e) {
      throw new BlockWriteException(e);
    }
  }

  @Override
  public void commitPartitions() throws BlockWriteException {
    final List<SerializedPartition<K>> partitions = new ArrayList<>();
    try {
      for (final Partition<?, K> partition : getNonCommittedPartitionsMap().values()) {
        partition.commit();
        partitions.add((SerializedPartition<K>) partition);
      }
      writeToFile(partitions);
      getNonCommittedPartitionsMap().clear();
    } catch (final IOException e) {
      throw new BlockWriteException(e);
    }
  }

  /**
   * @return whether this block is committed or not.
   */
  @Override
  public boolean isCommitted() {
    return metadata.isCommitted();
  }
}
