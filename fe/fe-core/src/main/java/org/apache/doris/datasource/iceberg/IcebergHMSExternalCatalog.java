// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.datasource.iceberg;

import org.apache.doris.common.Config;
import org.apache.doris.common.security.authentication.AuthenticationConfig;
import org.apache.doris.common.security.authentication.HadoopUGI;
import org.apache.doris.datasource.CatalogProperty;
import org.apache.doris.datasource.hive.HMSCachedClient;
import org.apache.doris.datasource.hive.HiveMetadataOps;
import org.apache.doris.datasource.property.PropertyConverter;
import org.apache.doris.datasource.property.constants.HMSProperties;

import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.hive.HiveCatalog;

import java.util.HashMap;
import java.util.Map;

public class IcebergHMSExternalCatalog extends IcebergExternalCatalog {

    public IcebergHMSExternalCatalog(long catalogId, String name, String resource, Map<String, String> props,
            String comment) {
        super(catalogId, name, comment);
        props = PropertyConverter.convertToMetaProperties(props);
        catalogProperty = new CatalogProperty(resource, props);
    }

    @Override
    protected void initCatalog() {
        icebergCatalogType = ICEBERG_HMS;
        HiveCatalog hiveCatalog = new org.apache.iceberg.hive.HiveCatalog();
        hiveCatalog.setConf(getConfiguration());
        // initialize hive catalog
        Map<String, String> catalogProperties = new HashMap<>();
        String metastoreUris = catalogProperty.getOrDefault(HMSProperties.HIVE_METASTORE_URIS, "");
        catalogProperties.put(CatalogProperties.URI, metastoreUris);
        HiveConf hiveConf = new HiveConf();
        for (Map.Entry<String, String> kv : catalogProperty.getHadoopProperties().entrySet()) {
            hiveConf.set(kv.getKey(), kv.getValue());
        }
        hiveConf.set(HiveConf.ConfVars.METASTORE_CLIENT_SOCKET_TIMEOUT.name(),
                String.valueOf(Config.hive_metastore_client_timeout_second));
        HadoopUGI.tryKrbLogin(this.getName(), AuthenticationConfig.getKerberosConfig(hiveConf,
                AuthenticationConfig.HADOOP_KERBEROS_PRINCIPAL,
                AuthenticationConfig.HADOOP_KERBEROS_KEYTAB));
        initS3Param(hiveConf);
        HMSCachedClient cachedClient = HiveMetadataOps.createCachedClient(hiveConf, 1, null);
        String location = cachedClient.getCatalogLocation("hive");
        catalogProperties.put(CatalogProperties.WAREHOUSE_LOCATION, location);
        hiveCatalog.initialize(icebergCatalogType, catalogProperties);
        catalog = hiveCatalog;
    }
}
