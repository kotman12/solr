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
package org.apache.solr.handler.admin;

import static org.apache.solr.common.params.CommonParams.DISTRIB;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.client.solrj.response.LukeResponse;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.cloud.SolrCloudTestCase;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.NamedList;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/** Cloud-specific Luke tests that require SolrCloud features like managed schema and Schema API. */
public class LukeHandlerCloudTest extends SolrCloudTestCase {

  @BeforeClass
  public static void setupCluster() throws Exception {
    System.setProperty("managed.schema.mutable", "true");
    configureCluster(2).addConfig("managed", configset("cloud-managed")).configure();
  }

  @AfterClass
  public static void afterClass() throws Exception {
    shutdownCluster();
  }

  private void requestLuke(String collection, ModifiableSolrParams extra) throws Exception {
    ModifiableSolrParams params = new ModifiableSolrParams();
    params.set("qt", "/admin/luke");
    params.set("numTerms", "0");
    if (extra != null) {
      for (Map.Entry<String, String[]> entry : extra.getMap().entrySet()) {
        params.set(entry.getKey(), entry.getValue());
      }
    }
    QueryRequest req = new QueryRequest(params);
    cluster.getSolrClient().request(req, collection);
  }

  /**
   * Verifies that distributed Luke detects inconsistent index flags across shards. Uses Schema API
   * to change a field's {@code stored} property between indexing on different shards, producing
   * different Lucene FieldInfo (and thus different index flags strings) on each shard.
   */
  @Test
  public void testInconsistentIndexFlagsAcrossShards() throws Exception {
    String collection = "lukeInconsistentFlags";
    CollectionAdminRequest.createCollection(collection, "managed", 2, 1)
        .processAndWait(cluster.getSolrClient(), DEFAULT_TIMEOUT);

    cluster.waitForActiveCollection(collection, 2, 2);

    try {
      // Add a field with stored=true, indexed=true
      Map<String, Object> fieldAttrs = new LinkedHashMap<>();
      fieldAttrs.put("name", "test_flag_s");
      fieldAttrs.put("type", "string");
      fieldAttrs.put("stored", true);
      fieldAttrs.put("indexed", true);
      new SchemaRequest.AddField(fieldAttrs).process(cluster.getSolrClient(), collection);

      // Index a target doc WITH the field, plus seed docs without it
      SolrInputDocument targetDoc = new SolrInputDocument();
      targetDoc.addField("id", "target");
      targetDoc.addField("test_flag_s", "has_indexed");
      cluster.getSolrClient().add(collection, targetDoc);

      List<SolrInputDocument> seedDocs = new ArrayList<>();
      for (int i = 0; i < 20; i++) {
        SolrInputDocument doc = new SolrInputDocument();
        doc.addField("id", "seed_" + i);
        seedDocs.add(doc);
      }
      cluster.getSolrClient().add(collection, seedDocs);
      cluster.getSolrClient().commit(collection);

      // Find which shard has the target doc by querying each replica directly.
      // Must use distrib=false — SolrCloud defaults distrib to true even on direct replica queries.
      DocCollection docColl = getCollectionState(collection);
      String targetSliceName = null;
      for (Slice slice : docColl.getSlices()) {
        Replica leader = slice.getLeader();
        try (SolrClient client = getHttpSolrClient(leader)) {
          SolrQuery q = new SolrQuery("id:target");
          q.set(DISTRIB, "false");
          QueryResponse qr = client.query(q);
          if (qr.getResults().getNumFound() > 0) {
            targetSliceName = slice.getName();
          }
        }
      }
      assertNotNull("target doc should exist on a shard", targetSliceName);

      // Find a seed doc on the other shard
      String otherDocId = null;
      for (Slice slice : docColl.getSlices()) {
        if (!slice.getName().equals(targetSliceName)) {
          Replica leader = slice.getLeader();
          try (SolrClient client = getHttpSolrClient(leader)) {
            SolrQuery q = new SolrQuery("*:*");
            q.setRows(1);
            q.set(DISTRIB, "false");
            QueryResponse qr = client.query(q);
            assertTrue("other shard should have seed docs", qr.getResults().getNumFound() > 0);
            otherDocId = (String) qr.getResults().getFirst().getFieldValue("id");
          }
          break;
        }
      }
      assertNotNull("should find a seed doc on the other shard", otherDocId);

      // Change the field to stored=false via Schema API
      fieldAttrs.put("stored", false);
      new SchemaRequest.ReplaceField(fieldAttrs).process(cluster.getSolrClient(), collection);

      // Reload collection to pick up schema change
      CollectionAdminRequest.reloadCollection(collection).process(cluster.getSolrClient());

      // Update the other-shard doc to include the field (now unstored in the new segment)
      SolrInputDocument updateDoc = new SolrInputDocument();
      updateDoc.addField("id", otherDocId);
      updateDoc.addField("test_flag_s", "not_indexed");
      cluster.getSolrClient().add(collection, updateDoc);
      cluster.getSolrClient().commit(collection);

      // Distributed Luke should detect inconsistent index flags between the two shards.
      // One shard has stored=true segments, the other has stored=false segments for test_flag_s.
      // CloudSolrClient defaults distrib=true when addressing a collection.
      ModifiableSolrParams params = new ModifiableSolrParams();
      params.set("fl", "test_flag_s");

      Exception ex = expectThrows(Exception.class, () -> requestLuke(collection, params));
      String fullMessage = SolrException.getRootCause(ex).getMessage();
      assertTrue(
          "exception chain should mention inconsistent index flags: " + fullMessage,
          fullMessage.contains("inconsistent"));
    } finally {
      CollectionAdminRequest.deleteCollection(collection)
          .processAndWait(cluster.getSolrClient(), DEFAULT_TIMEOUT);
    }
  }

  /**
   * Verifies that distrib mode is determined by how the request is addressed: collection path →
   * distributed, core path → local. Explicit distrib param overrides in both directions.
   */
  @Test
  public void testDistribByAddress() throws Exception {
    String collection = "lukeDistribByAddress";
    CollectionAdminRequest.createCollection(collection, "managed", 2, 1)
        .processAndWait(cluster.getSolrClient(), DEFAULT_TIMEOUT);
    cluster.waitForActiveCollection(collection, 2, 2);

    try {
      // Index a doc so fields appear
      SolrInputDocument doc = new SolrInputDocument();
      doc.addField("id", "1");
      cluster.getSolrClient().add(collection, doc);
      cluster.getSolrClient().commit(collection);

      // Find a replica to address directly by core name
      DocCollection docColl = getCollectionState(collection);
      Replica replica = docColl.getSlices().iterator().next().getLeader();

      // --- Collection path: should be distributed by default ---
      try (SolrClient directClient = getHttpSolrClient(replica)) {
        // Address via collection name → distributed (shards section present)
        ModifiableSolrParams params = new ModifiableSolrParams();
        params.set("qt", "/admin/luke");
        params.set("numTerms", "0");
        QueryRequest req = new QueryRequest(params);
        NamedList<Object> raw = directClient.request(req, collection);
        LukeResponse rsp = new LukeResponse();
        rsp.setResponse(raw);
        assertNotNull(
            "collection-addressed request should be distributed (shards present)",
            rsp.getShardResponses());

        // --- Core path: should be local by default ---
        raw = directClient.request(req, replica.getCoreName());
        rsp = new LukeResponse();
        rsp.setResponse(raw);
        assertNull(
            "core-addressed request should be local (no shards)",
            rsp.getShardResponses());

        // --- Core path + distrib=true: should override to distributed ---
        params.set(DISTRIB, true);
        req = new QueryRequest(params);
        raw = directClient.request(req, replica.getCoreName());
        rsp = new LukeResponse();
        rsp.setResponse(raw);
        assertNotNull(
            "core-addressed with distrib=true should be distributed",
            rsp.getShardResponses());

        // --- Collection path + distrib=false: should override to local ---
        params.set(DISTRIB, false);
        req = new QueryRequest(params);
        raw = directClient.request(req, collection);
        rsp = new LukeResponse();
        rsp.setResponse(raw);
        assertNull(
            "collection-addressed with distrib=false should be local",
            rsp.getShardResponses());
      }
    } finally {
      CollectionAdminRequest.deleteCollection(collection)
          .processAndWait(cluster.getSolrClient(), DEFAULT_TIMEOUT);
    }
  }

  /**
   * Verifies that CloudSolrClient (which routes to core URLs directly) still gets distributed
   * behavior when addressing a collection, thanks to CloudSolrClient defaulting distrib=true.
   */
  @Test
  public void testDistribByAddressCloudSolrClient() throws Exception {
    String collection = "lukeDistribCloudClient";
    CollectionAdminRequest.createCollection(collection, "managed", 2, 1)
        .processAndWait(cluster.getSolrClient(), DEFAULT_TIMEOUT);
    cluster.waitForActiveCollection(collection, 2, 2);

    try {
      SolrInputDocument doc = new SolrInputDocument();
      doc.addField("id", "1");
      cluster.getSolrClient().add(collection, doc);
      cluster.getSolrClient().commit(collection);

      // CloudSolrClient addresses collection — should be distributed
      ModifiableSolrParams params = new ModifiableSolrParams();
      params.set("qt", "/admin/luke");
      params.set("numTerms", "0");
      QueryRequest req = new QueryRequest(params);
      NamedList<Object> raw = cluster.getSolrClient().request(req, collection);
      LukeResponse rsp = new LukeResponse();
      rsp.setResponse(raw);
      assertNotNull(
          "CloudSolrClient collection request should be distributed (shards present)",
          rsp.getShardResponses());

      // CloudSolrClient addresses a specific core — should be local
      DocCollection docColl = getCollectionState(collection);
      String coreName = docColl.getSlices().iterator().next().getLeader().getCoreName();
      params.remove(DISTRIB);
      req = new QueryRequest(params);
      raw = cluster.getSolrClient().request(req, coreName);
      rsp = new LukeResponse();
      rsp.setResponse(raw);
      assertNull(
          "CloudSolrClient core request should be local (no shards)",
          rsp.getShardResponses());
    } finally {
      CollectionAdminRequest.deleteCollection(collection)
          .processAndWait(cluster.getSolrClient(), DEFAULT_TIMEOUT);
    }
  }
}
