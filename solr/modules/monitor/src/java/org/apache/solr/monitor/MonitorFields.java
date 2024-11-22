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

import java.util.Set;
import org.apache.solr.common.util.NamedList;

public class MonitorFields {

  private static final String QUERY_ID_FIELD_NAME_KEY = "queryIdFieldName";
  private static final String QUERY_ID_FIELD_NAME_DEFAULT = "_query_id_";
  public final String queryIdFieldName;

  private static final String CACHE_ID_FIELD_NAME_KEY = "cacheIdFieldName";
  private static final String CACHE_ID_FIELD_NAME_DEFAULT = "_cache_id_";
  public final String cacheIdFieldName;

  private static final String QUERY_FIELD_NAME_KEY = "queryFieldName";
  private static final String QUERY_FIELD_NAME_DEFAULT = "_mq_";
  public final String queryFieldName;

  private MonitorFields(String queryIdFieldName, String cacheIdFieldName, String queryFieldName) {
    this.queryIdFieldName = queryIdFieldName;
    this.cacheIdFieldName = cacheIdFieldName;
    this.queryFieldName = queryFieldName;
  }

  public Set<String> fieldNames() {
    return Set.of(this.queryIdFieldName, this.cacheIdFieldName, this.queryFieldName);
  }

  public static MonitorFields create(NamedList<?> args) {
    final String queryIdFieldName;
    final Object queryIdFieldNameObj = args.remove(QUERY_ID_FIELD_NAME_KEY);
    if (queryIdFieldNameObj != null) {
      queryIdFieldName = (String) queryIdFieldNameObj;
    } else {
      queryIdFieldName = QUERY_ID_FIELD_NAME_DEFAULT;
    }
    final String cacheIdFieldName;
    final Object cacheIdFieldNameObj = args.remove(CACHE_ID_FIELD_NAME_KEY);
    if (cacheIdFieldNameObj != null) {
      cacheIdFieldName = (String) cacheIdFieldNameObj;
    } else {
      cacheIdFieldName = CACHE_ID_FIELD_NAME_DEFAULT;
    }

    final String queryFieldName;
    final Object queryFieldNameObj = args.remove(QUERY_FIELD_NAME_KEY);
    if (queryFieldNameObj != null) {
      queryFieldName = (String) queryFieldNameObj;
    } else {
      queryFieldName = QUERY_FIELD_NAME_DEFAULT;
    }
    return new MonitorFields(queryIdFieldName, cacheIdFieldName, queryFieldName);
  }
}
