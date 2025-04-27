/*
 *
 *  * Licensed to the Apache Software Foundation (ASF) under one or more
 *  * contributor license agreements.  See the NOTICE file distributed with
 *  * this work for additional information regarding copyright ownership.
 *  * The ASF licenses this file to You under the Apache License, Version 2.0
 *  * (the "License"); you may not use this file except in compliance with
 *  * the License.  You may obtain a copy of the License at
 *  *
 *  *     http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package org.apache.solr.cloud;

import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.solr.search.CaffeineCache;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;


public class TestLeaderElectionOptimization extends SolrCloudTestCase {

  private static final String COLLECTION_NAME = "leader-election-test-collection";

  private static final String SHUTDOWN_BLOCKING_CACHE = "  <caches>\n" +
      "    <cache name=\"shutDownBlockingCache\" class=\"solr.cloud.TestLeaderElectionOptimization$ShutdownBlockingCache\"\n" +
      "           size=\"4096\"\n" +
      "           initialSize=\"1024\"\n" +
      "           autowarmCount=\"1024\" />\n" +
      "  </caches>";

  @BeforeClass
  public static void createCluster() throws Exception {
    configureCluster(1)
        .addConfig(
            "conf1", TEST_PATH().resolve("configsets").resolve("cloud-minimal").resolve("conf"))
        .withSolrXml(MiniSolrCloudCluster.DEFAULT_CLOUD_SOLR_XML
            .replace("</solr>", SHUTDOWN_BLOCKING_CACHE + "</solr>"))
        .useOtherCollectionConfigSetExecution()
        .configure();
    CollectionAdminRequest.createCollection(COLLECTION_NAME, "conf1", 1, 1)
        .process(cluster.getSolrClient());
  }


  @Test
  public void testLeaderElectNodeDeletionOptimization() throws Exception {
    var pipe = new LinkedBlockingQueue<>();
    String leaderElectionPath = "/collections/" + COLLECTION_NAME + "/leader_elect/shard1/election";
    try (CuratorFramework curator = CuratorFrameworkFactory.builder()
        .connectString(cluster.getZkServer().getZkAddress())
        .retryPolicy(new ExponentialBackoffRetry(1000, 3))
        .build()){
      curator.start();
      TestLeaderElectionOptimization.ShutdownBlockingCache.setPreCloseHook(() -> {
            for (int i = 0; i < 5; i++) {
              try {
                if (curator.getChildren().forPath(leaderElectionPath).isEmpty()) {
                  pipe.add(true);
                  break;
                }
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
              try {
                Thread.sleep(1000);
              } catch (InterruptedException e) {
                throw new RuntimeException(e);
              }
            }
          }
      );
      cluster.shutdown();
    } finally {
      assertFalse(pipe.isEmpty());
      cluster = null;
    }
  }

  public static class ShutdownBlockingCache extends CaffeineCache<Object, Object> {

    static volatile Runnable preCloseHook;

    public static void setPreCloseHook(Runnable preCloseHook) {
      ShutdownBlockingCache.preCloseHook = preCloseHook;
    }

    @Override
    public void close() throws IOException {
      if (preCloseHook != null) {
        preCloseHook.run();
      }
    }
  }
}
