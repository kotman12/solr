/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.cloud;

import com.carrotsearch.randomizedtesting.annotations.ThreadLeakLingering;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.embedded.JettySolrRunner;
import org.apache.solr.handler.ReplicationHandler;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reproduces a TLOG leader election that stalls because the outgoing leader froze rather than died.
 *
 * <p>{@link ShardLeaderElectionContext#runLeaderProcess} calls {@link
 * ZkController#stopReplicationFromLeader} inline on the election thread. That tears down the
 * follower's replication process, which blocks in {@code ExecutorUtil.shutdownAndAwaitTermination}
 * until the in-flight index fetch finishes. {@code IndexFetcher.abortFetch} only sets a flag that
 * is polled while streaming file packets, so a fetch parked in the network phase is not cut short:
 * the election parks for the 60s executor wait before {@code shutdownNow()} finally interrupts the
 * poll thread.
 *
 * <p>The old leader has to <em>freeze</em>, not die, so we hold its {@code indexversion} response
 * in a servlet filter and expire its ZooKeeper session to make it lose leadership. The filter
 * doubles as the synchronization point: when it fires we know for certain that the follower's fetch
 * has reached the leader and will not return.
 */
@ThreadLeakLingering(linger = 10)
public class TlogLeaderElectionFrozenLeaderTest extends SolrCloudTestCase {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private static final String COLLECTION = "tlog_frozen_leader";
  private static final String SHARD = "shard1";

  /**
   * An election that only has to replay a tiny tlog should finish well inside this. The bug parks
   * it for the 60s {@code ExecutorUtil.awaitTermination} wait instead.
   */
  private static final long MAX_ACCEPTABLE_ELECTION_MS = 20_000;

  @Before
  public void setupCluster() throws Exception {
    System.setProperty("solr.directoryFactory", "solr.StandardDirectoryFactory");

    // extraFilters are installed ahead of SolrServlet and its filters (JettySolrRunner:336-338), so
    // ours sees the request first. It is installed on every node but only acts on the armed core.
    configureCluster(2)
        .withJettyConfig(b -> b.withFilter(TestFreezeReplicationFilter.class, "/*"))
        .addConfig("conf", configset("cloud-minimal"))
        .configure();
  }

  @After
  public void tearDownCluster() throws Exception {
    TestCoreChannel coreChannel = TestFreezeReplicationFilter.CORE_CHANNEL.getAndSet(null);
    if (coreChannel != null) {
      coreChannel.released().complete(null);
    }
    shutdownCluster();
    System.clearProperty("solr.directoryFactory");
  }

  @Test
  public void testElectionIsNotBlockedByFrozenOldLeader() throws Exception {
    CollectionAdminRequest.createCollection(COLLECTION, "conf", 1, 0, 2, 0)
        .process(cluster.getSolrClient());
    cluster.waitForActiveCollection(COLLECTION, 1, 2);

    // Index something so the follower has a real index to poll against.
    for (int i = 0; i < 10; i++) {
      SolrInputDocument doc = new SolrInputDocument();
      doc.addField("id", String.valueOf(i));
      cluster.getSolrClient(COLLECTION).add(doc);
    }
    cluster.getSolrClient(COLLECTION).commit();

    Slice shard = getCollectionState(COLLECTION).getSlice(SHARD);
    Replica oldLeader = shard.getLeader();
    Replica follower =
        shard.getReplicas().stream()
            .filter(r -> !r.getName().equals(oldLeader.getName()))
            .findFirst()
            .orElseThrow();
    JettySolrRunner leaderJetty = cluster.getReplicaJetty(oldLeader);
    JettySolrRunner followerJetty = cluster.getReplicaJetty(follower);

    // Freeze the leader's replication endpoint. The follower polls every second under
    // jetty.testMode, so its next indexversion call lands in the filter and never returns.
    log.info("Freezing indexversion responses from leader core {}", oldLeader.getCoreName());
    TestCoreChannel channel = new TestCoreChannel(oldLeader.getCoreName());
    assertTrue(
        "the replication filter was already armed; a previous test did not release it",
        TestFreezeReplicationFilter.CORE_CHANNEL.compareAndSet(null, channel));

    // The ordering here is the whole point: once the leader leaves live_nodes, later polls bail out
    // early (LEADER_IS_NOT_ACTIVE) without making an HTTP call. Only a fetch that is *already* in
    // the network phase reproduces the bug.
    try {
      channel.arrived().get(MAX_ACCEPTABLE_ELECTION_MS, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      fail("the follower never issued an indexversion request to the leader");
    }
    log.info("Follower's index fetch is parked in the leader's replication handler");

    log.info("Expiring the ZooKeeper session of the frozen leader {}", leaderJetty.getNodeName());
    long start = System.nanoTime();
    cluster.expireZkSession(leaderJetty);

    waitForState(
        "the surviving TLOG replica never became leader",
        COLLECTION,
        90,
        TimeUnit.SECONDS,
        (liveNodes, collectionState) -> {
          Replica leader = collectionState.getLeader(SHARD);
          return leader != null
              && leader.isActive(liveNodes)
              && leader.getNodeName().equals(followerJetty.getNodeName());
        });
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

    log.info("New leader elected after {}ms", elapsedMs);
    assertTrue(
        "Leader election took "
            + elapsedMs
            + "ms with a frozen old leader; expected under "
            + MAX_ACCEPTABLE_ELECTION_MS
            + "ms. The election thread is parked in stopReplicationFromLeader waiting on an index "
            + "fetch that abortFetch() cannot cancel.",
        elapsedMs < MAX_ACCEPTABLE_ELECTION_MS);
  }

  /**
   * Holds {@code /replication?command=indexversion} requests addressed to one particular core,
   * simulating a leader whose process is alive but which has stopped answering.
   *
   * <p>Coordination is static because {@code JettyConfig.Builder.withFilter} takes a {@link Class}
   * and lets Jetty construct the instance.
   */
  public static class TestFreezeReplicationFilter implements Filter {

    private static final AtomicReference<TestCoreChannel> CORE_CHANNEL = new AtomicReference<>();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException {
      // Capture once: the test nulls CORE_CHANNEL out in release().
      TestCoreChannel coreChannel = CORE_CHANNEL.get();
      String core = coreChannel == null ? null : coreChannel.coreName();
      if (core != null && request instanceof HttpServletRequest http) {
        String uri = http.getRequestURI();
        if (uri != null
            && uri.endsWith("/" + core + ReplicationHandler.PATH)
            && ReplicationHandler.CMD_INDEX_VERSION.equals(http.getParameter("command"))) {
          try {
            // Tell the test the fetch has arrived and cannot complete, then hold the response for
            // longer than the acceptable leader election duration.
            coreChannel.arrived().complete(null);
            coreChannel.released().get(MAX_ACCEPTABLE_ELECTION_MS * 2, TimeUnit.MILLISECONDS);
          } catch (TimeoutException e) {
            // Hold cap reached without a release; answer the request and let teardown proceed.
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServletException(e);
          } catch (ExecutionException e) {
            throw new ServletException(e);
          }
        }
      }
      chain.doFilter(request, response);
    }
  }

  private record TestCoreChannel(
      String coreName, CompletableFuture<Void> arrived, CompletableFuture<Void> released) {
    TestCoreChannel(String coreName) {
      this(coreName, new CompletableFuture<>(), new CompletableFuture<>());
    }
  }
}
