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

package org.apache.solr.monitor;

import org.apache.solr.schema.IndexSchema;
import org.apache.solr.schema.SchemaField;

public class MonitorSchemaFields {

  private final SchemaField cacheId;
  private final SchemaField queryId;
  private final SchemaField monitorQuery;

  public MonitorSchemaFields(IndexSchema indexSchema, MonitorFields monitorFields) {
    this.cacheId = indexSchema.getField(monitorFields.cacheIdFieldName);
    this.queryId = indexSchema.getField(monitorFields.queryIdFieldName);
    this.monitorQuery = indexSchema.getField(monitorFields.queryFieldName);
  }

  public SchemaField getCacheId() {
    return cacheId;
  }

  public SchemaField getQueryId() {
    return queryId;
  }

  public SchemaField getMonitorQuery() {
    return monitorQuery;
  }
}
