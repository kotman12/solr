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

import static org.apache.solr.SolrTestCaseJ4.sdoc;
import static org.hamcrest.CoreMatchers.containsString;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SegmentInfo;
import org.apache.lucene.index.SegmentReader;
import org.apache.lucene.util.Version;
import org.apache.solr.SolrTestCase;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.embedded.EmbeddedSolrServer;
import org.apache.solr.client.solrj.request.CoreAdminRequest;
import org.apache.solr.client.solrj.request.GenericSolrRequest;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.client.solrj.response.CoreAdminResponse;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.CommonAdminParams;
import org.apache.solr.common.params.CoreAdminParams;
import org.apache.solr.common.params.CoreAdminParams.CoreAdminAction;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.SolrCore;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.util.EmbeddedSolrServerTestRule;
import org.apache.solr.util.RefCounted;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

public class UpgradeCoreIndexActionTest extends SolrTestCase {
  private static final int DOCS_PER_SEGMENT = 3;
  private static final String DV_FIELD = "dvonly_i_dvo";
  private static final String COLLECTION = "collection1";

  private static VarHandle segmentInfoMinVersionHandle;

  @ClassRule
  public static final EmbeddedSolrServerTestRule solrTestRule = new EmbeddedSolrServerTestRule();

  @BeforeClass
  public static void beforeClass() throws Exception {
    solrTestRule.startSolr(SolrTestCaseJ4.TEST_HOME());
    SolrTestCaseJ4.newRandomConfig();
    segmentInfoMinVersionHandle =
        MethodHandles.privateLookupIn(SegmentInfo.class, MethodHandles.lookup())
            .findVarHandle(SegmentInfo.class, "minVersion", Version.class);

    solrTestRule
        .newCollection()
        .withConfigSet(SolrTestCaseJ4.TEST_COLL1_CONF())
        .withConfigFile("solrconfig-nomergepolicyfactory.xml")
        .create();
  }

  @Before
  public void resetIndex() throws Exception {
    SolrClient client = solrTestRule.getSolrClient();
    client.deleteByQuery("*:*");
    client.commit();
  }

  @Test
  public void testUpgradeCoreIndexSelectiveReindexDeletesOldSegments() throws Exception {
    SolrClient client = solrTestRule.getSolrClient();
    EmbeddedSolrServer adminClient = solrTestRule.getAdminClient();

    final SegmentLayout layout = buildThreeSegments(client);
    final Version simulatedOldMinVersion = Version.fromBits(Version.LATEST.major - 1, 0, 0);

    // Simulate:
    // - seg1: "pure 9x" (minVersion=9)
    // - seg2: "pure 10x" (minVersion=10)
    // - seg3: "minVersion 9x, version 10x" (merged segment; minVersion=9)
    try (SolrCore core = adminClient.getCoreContainer().getCore(COLLECTION)) {
      setMinVersionForSegments(core, Set.of(layout.seg1, layout.seg3), simulatedOldMinVersion);
    }

    final Set<String> segmentsBeforeUpgrade = listSegmentNames(adminClient);

    CoreAdminRequest req = new CoreAdminRequest();
    req.setAction(CoreAdminAction.UPGRADECOREINDEX);
    req.setCoreName(COLLECTION);
    CoreAdminResponse resp = req.process(adminClient);

    assertEquals(COLLECTION, resp.getResponse().get("core"));
    assertEquals(2, resp.getResponse().get("numSegmentsEligibleForUpgrade"));
    assertEquals(2, resp.getResponse().get("numSegmentsUpgraded"));
    assertEquals("UPGRADE_SUCCESSFUL", resp.getResponse().get("upgradeStatus"));

    // The action commits internally and reopens the searcher; verify segments on disk.
    final Set<String> segmentsAfter = listSegmentNames(adminClient);
    final Set<String> newSegments = new HashSet<>(segmentsAfter);
    newSegments.removeAll(segmentsBeforeUpgrade);
    assertFalse(
        "Expected at least one new segment to be created by reindexing", newSegments.isEmpty());
    assertTrue("Expected seg2 to remain", segmentsAfter.contains(layout.seg2));
    assertFalse("Expected seg1 to be dropped", segmentsAfter.contains(layout.seg1));
    assertFalse("Expected seg3 to be dropped", segmentsAfter.contains(layout.seg3));

    // Verify document count and field values.
    QueryResponse qr = client.query(new SolrQuery("*:*"));
    assertEquals(3 * DOCS_PER_SEGMENT, qr.getResults().getNumFound());

    // Validate docValues-only (non-stored) fields were preserved for reindexed documents.
    // seg1 and seg3 were reindexed; seg2 was not.
    assertDocValuesOnlyFieldPreserved(client);
  }

  @Test
  @SuppressWarnings({"unchecked"})
  public void testUpgradeCoreIndexAsyncRequestStatusContainsOperationResponse() throws Exception {
    SolrClient client = solrTestRule.getSolrClient();
    EmbeddedSolrServer adminClient = solrTestRule.getAdminClient();

    final SegmentLayout layout = buildThreeSegments(client);
    final Version simulatedOldMinVersion = Version.fromBits(Version.LATEST.major - 1, 0, 0);
    try (SolrCore core = adminClient.getCoreContainer().getCore(COLLECTION)) {
      setMinVersionForSegments(core, Set.of(layout.seg1, layout.seg3), simulatedOldMinVersion);
    }

    final Set<String> segmentsBeforeUpgrade = listSegmentNames(adminClient);

    final String requestId = "upgradecoreindex_async_" + System.nanoTime();

    // Submit async upgrade request
    ModifiableSolrParams submitParams = new ModifiableSolrParams();
    submitParams.set(CoreAdminParams.ACTION, CoreAdminAction.UPGRADECOREINDEX.toString());
    submitParams.set(CoreAdminParams.CORE, COLLECTION);
    submitParams.set(CommonAdminParams.ASYNC, requestId);
    adminClient.request(
        new GenericSolrRequest(SolrRequest.METHOD.GET, "/admin/cores", submitParams));

    // Poll for completion
    NamedList<Object> statusResult = null;
    int maxRetries = 60;
    while (maxRetries-- > 0) {
      ModifiableSolrParams statusParams = new ModifiableSolrParams();
      statusParams.set(CoreAdminParams.ACTION, CoreAdminAction.REQUESTSTATUS.toString());
      statusParams.set(CoreAdminParams.REQUESTID, requestId);
      statusResult =
          adminClient.request(
              new GenericSolrRequest(SolrRequest.METHOD.GET, "/admin/cores", statusParams));

      if ("completed".equals(statusResult.get("STATUS"))) {
        break;
      }
      Thread.sleep(250);
    }

    assertNotNull(statusResult);
    assertEquals("completed", statusResult.get("STATUS"));
    Object opResponse = statusResult.get("response");
    assertNotNull(opResponse);
    assertTrue("Expected map response, got: " + opResponse.getClass(), opResponse instanceof Map);

    Map<String, Object> opResponseMap = (Map<String, Object>) opResponse;
    assertEquals(COLLECTION, opResponseMap.get("core"));
    assertEquals(2, ((Number) opResponseMap.get("numSegmentsEligibleForUpgrade")).intValue());
    assertEquals(2, ((Number) opResponseMap.get("numSegmentsUpgraded")).intValue());
    assertEquals("UPGRADE_SUCCESSFUL", opResponseMap.get("upgradeStatus"));

    final Set<String> segmentsAfter = listSegmentNames(adminClient);
    final Set<String> newSegments = new HashSet<>(segmentsAfter);
    newSegments.removeAll(segmentsBeforeUpgrade);
    assertFalse(
        "Expected at least one new segment to be created by reindexing", newSegments.isEmpty());
    assertTrue("Expected seg2 to remain", segmentsAfter.contains(layout.seg2));
    assertFalse("Expected seg1 to be dropped", segmentsAfter.contains(layout.seg1));
    assertFalse("Expected seg3 to be dropped", segmentsAfter.contains(layout.seg3));

    // Validate docValues-only (non-stored) fields were preserved for reindexed documents.
    assertDocValuesOnlyFieldPreserved(client);
  }

  @Test
  public void testNoUpgradeNeededWhenAllSegmentsCurrent() throws Exception {
    SolrClient client = solrTestRule.getSolrClient();
    EmbeddedSolrServer adminClient = solrTestRule.getAdminClient();

    // Index documents and commit - all segments will be at the current Lucene version
    for (int i = 0; i < DOCS_PER_SEGMENT; i++) {
      client.add(sdoc("id", Integer.toString(i)));
    }
    client.commit();

    final Set<String> segmentsBefore = listSegmentNames(adminClient);
    assertFalse("Expected at least one segment", segmentsBefore.isEmpty());

    CoreAdminRequest req = new CoreAdminRequest();
    req.setAction(CoreAdminAction.UPGRADECOREINDEX);
    req.setCoreName(COLLECTION);
    CoreAdminResponse resp = req.process(adminClient);

    assertEquals(COLLECTION, resp.getResponse().get("core"));
    assertEquals(0, resp.getResponse().get("numSegmentsEligibleForUpgrade"));
    assertEquals("NO_UPGRADE_NEEDED", resp.getResponse().get("upgradeStatus"));

    // Verify no segments were modified
    final Set<String> segmentsAfter = listSegmentNames(adminClient);
    assertEquals("Segments should remain unchanged", segmentsBefore, segmentsAfter);

    // Verify documents are still queryable
    QueryResponse qr = client.query(new SolrQuery("*:*"));
    assertEquals(DOCS_PER_SEGMENT, qr.getResults().getNumFound());
  }

  @Test
  public void testUpgradeCoreIndexFailsWithChildDocuments() throws Exception {
    SolrClient client = solrTestRule.getSolrClient();

    // Create a parent document with a child document
    SolrInputDocument parentDoc = new SolrInputDocument();
    parentDoc.addField("id", "100");
    parentDoc.addField("title", "Parent Document");

    SolrInputDocument childDoc = new SolrInputDocument();
    childDoc.addField("id", "101");
    childDoc.addField("title", "Child Document");

    parentDoc.addChildDocument(childDoc);

    client.add(parentDoc);
    client.commit();

    // Verify documents were indexed (parent + child = 2 docs)
    QueryResponse qr = client.query(new SolrQuery("*:*"));
    assertEquals(2, qr.getResults().getNumFound());

    // Attempt to upgrade the index - should fail because of child documents
    assertUpgradeDetectsChildDocs();
  }

  // --- Child docs detection tests ---
  //
  // These tests verify that the child document detection in the upgrade path
  // correctly distinguishes between genuine child docs and non-child docs,
  // even in the presence of updates and deletes that leave deleted documents
  // in segments (since NoMergePolicy prevents segment merges from purging them).

  @Test
  public void testChildDocsDetection_noChildDocsJustAdd() throws Exception {
    SolrClient client = solrTestRule.getSolrClient();
    for (int i = 0; i < 10; i++) {
      client.add(sdoc("id", String.valueOf(i), "title", "doc" + i));
    }
    client.commit();

    assertUpgradeDoesNotDetectChildDocs();
  }

  @Test
  public void testChildDocsDetection_withChildDocsJustAdd() throws Exception {
    SolrClient client = solrTestRule.getSolrClient();
    addChildDoc(client, "100", "101");
    addChildDoc(client, "200", "201");
    client.commit();

    assertUpgradeDetectsChildDocs();
  }

  @Test
  public void testChildDocsDetection_noChildDocsWithWithinCommitUpdates() throws Exception {
    // Add docs and then update some of them BEFORE committing, so both the old
    // (deleted) and new versions end up in the same flushed segment.
    // With NoMergePolicy and a 100MB RAM buffer (from SolrIndexConfig defaults),
    // no flush or merge occurs mid-batch, guaranteeing co-location.
    //
    // In the resulting segment, _root_ Terms stats will show:
    //   Terms.size()     = N  (unique _root_ values, one per unique id)
    //   Terms.getDocCount() = N + updates  (includes deleted doc entries)
    //
    // A naive check (uniqueRootValues < docsWithRoot) may false-positive here
    // because multiple docs share the same _root_ value within the segment.
    SolrClient client = solrTestRule.getSolrClient();
    for (int i = 0; i < 10; i++) {
      client.add(sdoc("id", String.valueOf(i), "title", "doc" + i));
    }
    // Re-add a few docs with the same ids (within-commit updates)
    for (int i = 0; i < 3; i++) {
      client.add(sdoc("id", String.valueOf(i), "title", "updated_doc" + i));
    }
    client.commit();

    // 10 live docs — the updates replaced 3 docs in-place
    QueryResponse qr = client.query(new SolrQuery("*:*"));
    assertEquals(10, qr.getResults().getNumFound());
    assertUpgradeDoesNotDetectChildDocs();
  }

  @Test
  public void testChildDocsDetection_withChildDocsWithWithinCommitUpdates() throws Exception {
    // Same within-commit pattern but with actual child docs present
    SolrClient client = solrTestRule.getSolrClient();
    addChildDoc(client, "100", "101");

    // Add and immediately re-add some non-child docs
    for (int i = 0; i < 5; i++) {
      client.add(sdoc("id", String.valueOf(i), "title", "doc" + i));
    }
    for (int i = 0; i < 3; i++) {
      client.add(sdoc("id", String.valueOf(i), "title", "updated_doc" + i));
    }
    client.commit();

    assertUpgradeDetectsChildDocs();
  }

  @Test
  public void testChildDocsDetection_noChildDocsWithWithinCommitDeletesAndUpdates()
      throws Exception {
    // Add docs, delete some, and update others — all before committing.
    // Deleted and updated docs leave behind deleted entries in the same segment,
    // which can cause false positives in the child docs detection.
    SolrClient client = solrTestRule.getSolrClient();
    for (int i = 0; i < 10; i++) {
      client.add(sdoc("id", String.valueOf(i), "title", "doc" + i));
    }
    // Delete a few
    client.deleteById("3");
    client.deleteById("4");
    client.deleteById("5");
    // Update a few others
    for (int i = 0; i < 3; i++) {
      client.add(sdoc("id", String.valueOf(i), "title", "updated_doc" + i));
    }
    client.commit();

    // 7 live docs: ids 0,1,2 (updated), 6,7,8,9 (untouched); 3,4,5 deleted
    QueryResponse qr = client.query(new SolrQuery("*:*"));
    assertEquals(7, qr.getResults().getNumFound());
    assertUpgradeDoesNotDetectChildDocs();
  }

  @Test
  public void testChildDocsDetection_withChildDocsWithWithinCommitDeletesAndUpdates()
      throws Exception {
    SolrClient client = solrTestRule.getSolrClient();
    addChildDoc(client, "100", "101");

    for (int i = 0; i < 5; i++) {
      client.add(sdoc("id", String.valueOf(i), "title", "doc" + i));
    }
    client.deleteById("3");
    client.deleteById("4");
    client.add(sdoc("id", "0", "title", "updated_doc0"));
    client.commit();

    assertUpgradeDetectsChildDocs();
  }

  /** Index a parent document with a single child. */
  private void addChildDoc(SolrClient client, String parentId, String childId) throws Exception {
    SolrInputDocument parentDoc = new SolrInputDocument();
    parentDoc.addField("id", parentId);
    parentDoc.addField("title", "Parent " + parentId);

    SolrInputDocument childDoc = new SolrInputDocument();
    childDoc.addField("id", childId);
    childDoc.addField("title", "Child " + childId);
    parentDoc.addChildDocument(childDoc);

    client.add(parentDoc);
  }

  private SegmentLayout buildThreeSegments(SolrClient client) throws Exception {
    EmbeddedSolrServer adminClient = solrTestRule.getAdminClient();

    Set<String> segmentsBefore = listSegmentNames(adminClient);
    indexDocs(client, 0);
    client.commit();
    final String seg1 = getNewSegment(adminClient, segmentsBefore);

    segmentsBefore = listSegmentNames(adminClient);
    indexDocs(client, 1000);
    client.commit();
    final String seg2 = getNewSegment(adminClient, segmentsBefore);

    segmentsBefore = listSegmentNames(adminClient);
    indexDocs(client, 2000);
    client.commit();
    final String seg3 = getNewSegment(adminClient, segmentsBefore);

    Set<String> allSegments = listSegmentNames(adminClient);
    assertTrue(allSegments.contains(seg1));
    assertTrue(allSegments.contains(seg2));
    assertTrue(allSegments.contains(seg3));

    return new SegmentLayout(seg1, seg2, seg3);
  }

  private void indexDocs(SolrClient client, int baseId) throws Exception {
    for (int i = 0; i < DOCS_PER_SEGMENT; i++) {
      final String id = Integer.toString(baseId + i);
      client.add(
          sdoc("id", id, DV_FIELD, Integer.toString(baseId + i + 10_000), "title", "t" + id));
    }
  }

  private void assertDocValuesOnlyFieldPreserved(SolrClient client) throws Exception {
    // Assert one doc that must have been reindexed (seg1) and one from seg3.
    assertDocHasDvFieldValue(client, 0, 10_000);
    assertDocHasDvFieldValue(client, 2000, 12_000);

    // Also sanity-check a doc from the untouched segment (seg2) still has its value.
    assertDocHasDvFieldValue(client, 1000, 11_000);
  }

  private void assertDocHasDvFieldValue(SolrClient client, int id, int expected) throws Exception {
    SolrQuery query = new SolrQuery("id:" + id);
    query.setFields("id", DV_FIELD);
    QueryResponse qr = client.query(query);
    assertEquals(1, qr.getResults().getNumFound());
    SolrDocument doc = qr.getResults().get(0);
    assertEquals(expected, ((Number) doc.getFieldValue(DV_FIELD)).intValue());
  }

  private String getNewSegment(EmbeddedSolrServer adminClient, Set<String> segmentsBefore)
      throws Exception {
    Set<String> segmentsAfter = new HashSet<>(listSegmentNames(adminClient));
    segmentsAfter.removeAll(new HashSet<>(segmentsBefore));
    assertEquals("Expected exactly one new segment", 1, segmentsAfter.size());
    return segmentsAfter.iterator().next();
  }

  private Set<String> listSegmentNames(EmbeddedSolrServer adminClient) throws Exception {
    try (SolrCore core = adminClient.getCoreContainer().getCore(COLLECTION)) {
      return core.withSearcher(
          searcher -> {
            final Set<String> segmentNames = new HashSet<>();
            for (LeafReaderContext ctx : searcher.getTopReaderContext().leaves()) {
              SegmentReader segmentReader = (SegmentReader) FilterLeafReader.unwrap(ctx.reader());
              segmentNames.add(segmentReader.getSegmentName());
            }
            return segmentNames;
          });
    }
  }

  private void setMinVersionForSegments(SolrCore core, Set<String> segments, Version minVersion) {
    RefCounted<SolrIndexSearcher> searcherRef = core.getSearcher();
    try {
      final List<LeafReaderContext> leaves = searcherRef.get().getTopReaderContext().leaves();
      for (LeafReaderContext ctx : leaves) {
        SegmentReader segmentReader = (SegmentReader) FilterLeafReader.unwrap(ctx.reader());
        if (!segments.contains(segmentReader.getSegmentName())) {
          continue;
        }
        final SegmentInfo segmentInfo = segmentReader.getSegmentInfo().info;
        segmentInfoMinVersionHandle.set(segmentInfo, minVersion);
      }
    } finally {
      searcherRef.decref();
    }
  }

  /**
   * Assert that the upgrade endpoint does NOT throw the child-documents error. This verifies that
   * {@code indexContainsChildDocs} returns false.
   */
  private void assertUpgradeDoesNotDetectChildDocs() throws Exception {
    EmbeddedSolrServer adminClient = solrTestRule.getAdminClient();
    CoreAdminRequest req = new CoreAdminRequest();
    req.setAction(CoreAdminAction.UPGRADECOREINDEX);
    req.setCoreName(COLLECTION);
    CoreAdminResponse resp = req.process(adminClient);
    assertNull(
        "Unexpected exception: " + resp.getResponse().get("exception"),
        resp.getResponse().get("exception"));
  }

  /**
   * Assert that the upgrade endpoint DOES throw the child-documents error. This verifies that
   * {@code indexContainsChildDocs} returns true.
   */
  private void assertUpgradeDetectsChildDocs() {
    EmbeddedSolrServer adminClient = solrTestRule.getAdminClient();
    CoreAdminRequest req = new CoreAdminRequest();
    req.setAction(CoreAdminAction.UPGRADECOREINDEX);
    req.setCoreName(COLLECTION);
    SolrException thrown = assertThrows(SolrException.class, () -> req.process(adminClient));
    assertThat(
        thrown.getMessage(),
        containsString("does not support indexes containing child documents"));
  }

  private record SegmentLayout(String seg1, String seg2, String seg3) {}
}
