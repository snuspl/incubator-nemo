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
package edu.snu.nemo.compiler.optimizer.policy;

import edu.snu.nemo.common.dag.DAG;
import edu.snu.nemo.common.ir.edge.IREdge;
import edu.snu.nemo.common.ir.vertex.IRVertex;
import edu.snu.nemo.compiler.optimizer.pass.compiletime.ConditionalCompileTimePass;
import edu.snu.nemo.compiler.optimizer.pass.compiletime.annotating.ShuffleLocationAssignmentPass;
import edu.snu.nemo.compiler.optimizer.pass.compiletime.composite.PrimitiveCompositePass;

public final class IridiumPolicy {
  private final Policy policy;

  public IridiumPolicy() {
    this.policy = new PolicyBuilder(false)
        .registerCompileTimePass(new PrimitiveCompositePass())
        .registerCompileTimePass(new ConditionalCompileTimePass(dag -> getBandwidthHeterogeneity(dag) > THRESHOLD,
            new ShuffleLocationAssignmentPass()))
        .build();
  }

  private static double THRESHOLD = 0;
  
  private static double getBandwidthHeterogeneity(final DAG<IRVertex, IREdge> irDag) {
    // TODO #??: Implement THIS.
    return 0;
  }
}
