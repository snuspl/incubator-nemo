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

package edu.snu.nemo.examples.spark;

import edu.snu.nemo.client.JobLauncher;
import edu.snu.nemo.common.test.ArgBuilder;
import edu.snu.nemo.compiler.optimizer.policy.DefaultPolicy;
import edu.snu.nemo.examples.spark.sql.NemoLocalBenchmark;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

/**
 * Test Spark programs with JobLauncher.
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest(JobLauncher.class)
@PowerMockIgnore("javax.management.*")
public final class SparkSQLPerfITCase {
  private static final int TIMEOUT = 120000;
  private static ArgBuilder builder = new ArgBuilder();
  private static final String fileBasePath = System.getProperty("user.dir") + "/../resources/";

  @Before
  public void setUp() {
    builder = new ArgBuilder();
  }

  @Test(timeout = TIMEOUT)
  public void testSparkPi() throws Exception {
    final String numParallelism = "3";

    JobLauncher.main(builder
        .addJobId(SparkPi.class.getSimpleName() + "_test")
        .addUserMain(SparkPi.class.getCanonicalName())
        .addUserArgs(numParallelism)
        .addOptimizationPolicy(DefaultPolicy.class.getCanonicalName())
        .build());
  }

//  @Test(timeout = TIMEOUT)
//  public void testSparkBenchmark() throws Exception {
//    JobLauncher.main(builder
//        .addJobId(SparkBenchmark.class.getSimpleName() + "_test")
//        .addUserMain(SparkBenchmark.class.getCanonicalName())
//        .addUserArgs("")
//        .addOptimizationPolicy(DefaultPolicy.class.getCanonicalName())
//        .build());
//  }
//
//  @Test(timeout = TIMEOUT)
//  public void testNemoBenchmark() throws Exception {
//    JobLauncher.main(builder
//        .addJobId(NemoBenchmark.class.getSimpleName() + "_test")
//        .addUserMain(NemoBenchmark.class.getCanonicalName())
//        .addUserArgs("")
//        .addOptimizationPolicy(DefaultPolicy.class.getCanonicalName())
//        .build());
//  }
//
  @Test(timeout = TIMEOUT)
  public void testNemoLocalBenchmark() throws Exception {
    JobLauncher.main(builder
        .addJobId(NemoLocalBenchmark.class.getSimpleName() + "_test")
        .addUserMain(NemoLocalBenchmark.class.getCanonicalName())
        .addUserArgs("")
        .addOptimizationPolicy(DefaultPolicy.class.getCanonicalName())
        .build());
  }
}
