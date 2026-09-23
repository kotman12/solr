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
import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.util.TimeSource;
import org.apache.solr.embedded.JettySolrRunner;
import org.apache.solr.util.SocketProxy;
import org.apache.solr.util.TimeOut;
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
 * follower's {@link org.apache.solr.handler.ReplicationHandler}, which blocks in {@code
 * ExecutorUtil.shutdownAndAwaitTermination} until the in-flight index fetch finishes. {@link
 * org.apache.solr.handler.IndexFetcher#abortFetch} only sets a flag that is polled while streaming
 * file packets, so a fetch parked in the network phase is not cut short: the election parks for the
 * 60s executor wait before {@code shutdownNow()} finally interrupts the poll thread.
 *
 * <p>The old leader has to <em>freeze</em>, not die. Stopping its Jetty is not enough: {@link
 * SocketProxy}'s pump breaks out of its loop on EOF <em>before</em> the pause latch and then closes
 * both streams, which would unblock the follower's socket and destroy the repro. So we pause the
 * proxy in front of the leader (process and TCP connections stay alive) and expire its ZooKeeper
 * session to make it lose leadership.
 */
@ThreadLeakLingering(linger = 10)
public class TlogLeaderElectionFrozenLeaderTest extends SolrCloudTestCase {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private static final String COLLECTION = "tlog_frozen_leader";
  private static final String SHARD = "shard1";

  /**
   * An election that only has to replay a tiny tlog should finish well inside this. The bug parks it
   * for the 60s {@code ExecutorUtil.awaitTermination} wait instead.
   */
  private static final long MAX_ACCEPTABLE_ELECTION_MS = 20_000;

  private Map<JettySolrRunner, SocketProxy> proxies;

  @Before
  public void setupCluster() throws Exception {
    System.setProperty("solr.directoryFactory", "solr.StandardDirectoryFactory");

    configureCluster(2).addConfig("conf", configset("cloud-minimal")).configure();

    // Put a SocketProxy in front of every node. The stop/start is required: hostPort is only read
    // at context init, and replicas must register under the proxy port so that the follower's
    // IndexFetcher dials the leader through the proxy.
    proxies = new HashMap<>(cluster.getJettySolrRunners().size());
    for (JettySolrRunner jetty : cluster.getJettySolrRunners()) {
      SocketProxy proxy = new SocketProxy();
      jetty.setProxyPort(proxy.getListenPort());
      cluster.stopJettySolrRunner(jetty);
      cluster.startJettySolrRunner(jetty);
      proxy.open(jetty.getBaseUrl().toURI());
      if (log.isInfoEnabled()) {
        log.info("Added proxy {} in front of {}", proxy.getUrl(), jetty.getBaseUrl());
      }
      proxies.put(jetty, proxy);
    }
  }

  @After
  public void tearDownCluster() throws Exception {
    if (proxies != null) {
      for (SocketProxy proxy : proxies.values()) {
        // Un-pause before closing, otherwise cluster shutdown inherits the very stall this test is
        // about (60s per core).
        proxy.goOn();
        proxy.close();
      }
      proxies = null;
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

    Replica oldLeader = getCollectionState(COLLECTION).getSlice(SHARD).getLeader();
    JettySolrRunner leaderJetty = cluster.getReplicaJetty(oldLeader);
    SocketProxy leaderProxy = proxies.get(leaderJetty);
    assertNotNull("no proxy found for the leader " + oldLeader, leaderProxy);
    assertTrue(
        "leader did not register behind its proxy: " + oldLeader.getCoreUrl(),
        oldLeader.getCoreUrl().contains(String.valueOf(leaderProxy.getListenPort())));

    JettySolrRunner followerJetty =
        cluster.getJettySolrRunners().stream()
            .filter(j -> j != leaderJetty)
            .findFirst()
            .orElseThrow();

    // Freeze the leader: the Acceptor and every existing Bridge pump stop, so any request to it
    // hangs -- including a bare /replication?command=indexversion with nothing to download.
    log.info("Pausing proxy in front of leader {}", oldLeader.getCoreUrl());
    leaderProxy.pause();

    // The ordering here is the whole point: once the leader leaves live_nodes, later polls bail out
    // early (LEADER_IS_NOT_ACTIVE) without making an HTTP call. Only a fetch that is *already* in
    // the network phase reproduces the bug, so wait until we can see one parked there.
    awaitParkedIndexFetch();

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
   * Blocks until the follower's {@code indexFetcher} poll thread is parked inside the HTTP call to
   * the leader. The follower polls every second under {@code jetty.testMode}, so this normally
   * returns within a couple of seconds.
   *
   * <p>Only the follower runs a {@link ReplicateFromLeader}, so there is no ambiguity even though
   * both nodes share this JVM. This stack sniffing is deliberately crude; it keeps the reproducer
   * free of production-code test hooks.
   */
  private static void awaitParkedIndexFetch() throws InterruptedException {
    TimeOut timeout = new TimeOut(30, TimeUnit.SECONDS, TimeSource.NANO_TIME);
    StackTraceElement[] lastSeen = null;
    while (!timeout.hasTimedOut()) {
      for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
        if (!entry.getKey().getName().startsWith("indexFetcher-")) {
          continue;
        }
        lastSeen = entry.getValue();
        for (StackTraceElement frame : lastSeen) {
          if (frame.getClassName().endsWith("HttpJettySolrClient")
              && "request".equals(frame.getMethodName())) {
            log.info("Index fetch is parked in the network phase on {}", entry.getKey().getName());
            return;
          }
        }
      }
      Thread.sleep(100);
    }
    fail(
        "No indexFetcher thread parked in an HTTP request to the frozen leader within 30s."
            + (lastSeen == null
                ? " No indexFetcher thread was found at all."
                : " Last stack seen: " + Arrays.toString(lastSeen)));
  }
}
