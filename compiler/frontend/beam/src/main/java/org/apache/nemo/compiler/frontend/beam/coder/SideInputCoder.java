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
package org.apache.nemo.compiler.frontend.beam.coder;

import org.apache.beam.sdk.coders.AtomicCoder;
import org.apache.beam.sdk.coders.Coder;
import org.apache.nemo.compiler.frontend.beam.SideInputElement;

import java.io.*;

/**
 * EncoderFactory for side inputs.
 * @param <T> type of the side input value.
 */
public final class SideInputCoder<T> extends AtomicCoder<SideInputElement<T>> {
  private final Coder<T> valueCoder;

  /**
   * Private constructor.
   */
  private SideInputCoder(final Coder<T> valueCoder) {
    this.valueCoder = valueCoder;
  }

  /**
   * @return a new coder
   */
  public static SideInputCoder of(final Coder valueCoder) {
    return new SideInputCoder<>(valueCoder);
  }

  @Override
  public void encode(final SideInputElement<T> sideInputElement, final OutputStream outStream) throws IOException {
    final DataOutputStream dataOutputStream = new DataOutputStream(outStream);
    dataOutputStream.writeInt(sideInputElement.getSideInputIndex());
    valueCoder.encode(sideInputElement.getSideInputValue(), dataOutputStream);
  }

  @Override
  public SideInputElement<T> decode(final InputStream inStream) throws IOException {
    final DataInputStream dataInputStream = new DataInputStream(inStream);
    final int index = dataInputStream.readInt();
    final T value = valueCoder.decode(inStream);
    return new SideInputElement<>(index, value);
  }
}
