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
package org.apache.solr.common.cloud;

import static org.apache.solr.SolrTestCaseJ4.assumeWorkingMockito;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carrotsearch.randomizedtesting.annotations.Seed;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.solr.SolrTestCase;
import org.apache.solr.common.util.ExecutorUtil;
import org.apache.solr.common.util.SolrNamedThreadFactory;
import org.apache.solr.common.util.SuppressForbidden;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooKeeper;
import org.junit.BeforeClass;
import org.junit.Test;

/** Deterministic state-ordering and replacement-session recovery tests. */
@Seed("5301C0DE")
@SuppressForbidden(reason = "Pinned seed reproduces SOLR-18623 reconnect hang")
public class ConnectionManagerOrderingTest extends SolrTestCase {
  private static final int TIMEOUT_MS = 60_000;

  @BeforeClass
  public static void beforeClass() {
    assumeWorkingMockito();
  }

  @Test
  public void testStaleDisconnectedDoesNotUndoConnectedState() {
    ZkClientConnectionStrategy strategy = mock(ZkClientConnectionStrategy.class);
    ConnectionManager manager = newManager(mock(SolrZkClient.class), strategy, null);
    manager.process(event(KeeperState.SyncConnected));

    long disconnected = manager.nextEventSequence();
    long connected = manager.nextEventSequence();
    manager.process(event(KeeperState.SyncConnected), connected);
    manager.process(event(KeeperState.Disconnected), disconnected);

    assertTrue(manager.isConnectedAndNotClosed());
    assertFalse(manager.isLikelyExpired());
    verify(strategy, never()).disconnected();
    verify(strategy, times(2)).connected();
  }

  @Test
  public void testStaleConnectedDoesNotUndoDisconnectedState() {
    ZkClientConnectionStrategy strategy = mock(ZkClientConnectionStrategy.class);
    ConnectionManager manager = newManager(mock(SolrZkClient.class), strategy, null);
    manager.process(event(KeeperState.SyncConnected));

    long firstDisconnect = manager.nextEventSequence();
    long connected = manager.nextEventSequence();
    long lastDisconnect = manager.nextEventSequence();
    manager.process(event(KeeperState.Disconnected), lastDisconnect);
    manager.process(event(KeeperState.SyncConnected), connected);
    assertFalse(manager.isConnectedAndNotClosed());
    assertFalse(manager.isLikelyExpired());
    manager.process(event(KeeperState.Disconnected), firstDisconnect);

    assertFalse(manager.isConnectedAndNotClosed());
    assertFalse(manager.isLikelyExpired());
    verify(strategy).disconnected();
    verify(strategy).connected();
  }

  @Test
  public void testStaleConnectedDoesNotClearExpiredState() {
    ZkClientConnectionStrategy strategy = mock(ZkClientConnectionStrategy.class);
    ConnectionManager manager = newManager(mock(SolrZkClient.class), strategy, null);
    manager.process(event(KeeperState.SyncConnected));

    long connected = manager.nextEventSequence();
    long expired = manager.nextEventSequence();
    manager.process(event(KeeperState.Expired), expired);
    manager.process(event(KeeperState.SyncConnected), connected);

    assertFalse(manager.isConnectedAndNotClosed());
    assertTrue(manager.isLikelyExpired());
    verify(strategy).connected();
  }

  @Test
  public void testAppliesEventsInDeliveryOrder() {
    ZkClientConnectionStrategy strategy = mock(ZkClientConnectionStrategy.class);
    ConnectionManager manager = newManager(mock(SolrZkClient.class), strategy, null);

    manager.process(event(KeeperState.SyncConnected));
    assertTrue(manager.isConnectedAndNotClosed());
    assertFalse(manager.isLikelyExpired());
    manager.process(event(KeeperState.Disconnected));
    assertFalse(manager.isConnectedAndNotClosed());
    assertFalse(manager.isLikelyExpired());
    manager.process(event(KeeperState.SyncConnected));
    assertTrue(manager.isConnectedAndNotClosed());
    assertFalse(manager.isLikelyExpired());

    verify(strategy).disconnected();
    verify(strategy, times(2)).connected();
  }

  @Test
  public void testExpiredReplacementDoesNotBlockRecovery() throws Exception {
    SolrZkClient client = mock(SolrZkClient.class);
    ZooKeeper expiredKeeper = mock(ZooKeeper.class);
    ZooKeeper healthyKeeper = mock(ZooKeeper.class);
    AtomicReference<ZooKeeper.States> replacementState =
        new AtomicReference<>(ZooKeeper.States.CONNECTING);
    when(expiredKeeper.getState()).thenAnswer(invocation -> replacementState.get());
    when(healthyKeeper.getState()).thenReturn(ZooKeeper.States.CONNECTED);

    AtomicReference<ConnectionManager> managerRef = new AtomicReference<>();
    AtomicInteger attempts = new AtomicInteger();
    AtomicLong delayedConnectedSequence = new AtomicLong();
    CountDownLatch replacementCreated = new CountDownLatch(1);
    CountDownLatch reconnected = new CountDownLatch(1);
    DefaultConnectionStrategy strategy =
        new DefaultConnectionStrategy() {
          @Override
          protected ZooKeeper newZooKeeperInstance(
              String serverAddress, int zkClientTimeout, Watcher watcher) {
            ConnectionManager manager = managerRef.get();
            if (attempts.incrementAndGet() == 1) {
              // Reserve delivery order, but apply the disconnect before the connected callback.
              delayedConnectedSequence.set(manager.nextEventSequence());
              manager.process(event(KeeperState.Disconnected), manager.nextEventSequence());
              replacementCreated.countDown();
              return expiredKeeper;
            }
            assertEquals("Unexpected additional reconnect attempt", 2, attempts.get());
            manager.process(event(KeeperState.SyncConnected), manager.nextEventSequence());
            return healthyKeeper;
          }
        };
    strategy.setZkCredentialsToAddAutomatically(new DefaultZkCredentialsProvider());
    ConnectionManager manager = newManager(client, strategy, reconnected::countDown);
    managerRef.set(manager);
    manager.process(event(KeeperState.SyncConnected));

    ExecutorService reconnectExecutor =
        ExecutorUtil.newMDCAwareSingleThreadExecutor(
            new SolrNamedThreadFactory("connectionManagerOrdering"));
    try {
      Future<?> firstExpiration =
          reconnectExecutor.submit(() -> manager.process(event(KeeperState.Expired)));
      assertTrue(
          "Replacement session was not created", replacementCreated.await(10, TimeUnit.SECONDS));

      Future<?> replacementExpiration;
      synchronized (manager) {
        // Publish the terminal event before the waiter can start a healthy replacement.
        replacementState.set(ZooKeeper.States.CLOSED);
        long expiredSequence = manager.nextEventSequence();
        replacementExpiration =
            reconnectExecutor.submit(
                () -> manager.process(event(KeeperState.Expired), expiredSequence));
        manager.process(event(KeeperState.SyncConnected), delayedConnectedSequence.get());
      }

      assertTrue(
          "An expired replacement must not block the next reconnect",
          reconnected.await(10, TimeUnit.SECONDS));
      firstExpiration.get(10, TimeUnit.SECONDS);
      replacementExpiration.get(10, TimeUnit.SECONDS);

      assertEquals(2, attempts.get());
      assertTrue(manager.isConnectedAndNotClosed());
      assertFalse(manager.isLikelyExpired());
      verify(client, never()).updateKeeper(expiredKeeper);
      verify(client).updateKeeper(healthyKeeper);
      verify(expiredKeeper, atLeastOnce()).close();
    } finally {
      manager.close();
      reconnectExecutor.shutdownNow();
      assertTrue(reconnectExecutor.awaitTermination(10, TimeUnit.SECONDS));
    }
  }

  private ConnectionManager newManager(
      SolrZkClient client, ZkClientConnectionStrategy strategy, OnReconnect onReconnect) {
    when(client.getZkClientTimeout()).thenReturn(TIMEOUT_MS);
    when(client.wrapWatcher(any(Watcher.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    return new ConnectionManager(
        "ordering test", client, "unused", strategy, onReconnect, null, () -> false);
  }

  private static WatchedEvent event(KeeperState state) {
    return new WatchedEvent(EventType.None, state, null);
  }
}
