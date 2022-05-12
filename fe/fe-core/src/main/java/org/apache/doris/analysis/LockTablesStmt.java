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

package org.apache.doris.analysis;

import org.apache.doris.catalog.Catalog;
import org.apache.doris.catalog.Database;
import org.apache.doris.catalog.Table;
import org.apache.doris.cluster.ClusterNamespace;
import org.apache.doris.common.ErrorCode;
import org.apache.doris.common.ErrorReport;
import org.apache.doris.common.UserException;
import org.apache.doris.mysql.privilege.PrivPredicate;
import org.apache.doris.qe.ConnectContext;

import com.google.common.base.Strings;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

public class LockTablesStmt extends StatementBase {
    private static final Logger LOG = LogManager.getLogger(LockTablesStmt.class);

    private List<LockTable> lockTables;

    public LockTablesStmt(ArrayList<LockTable> lockTables) {
        this.lockTables = lockTables;
    }

    @Override
    public void analyze(Analyzer analyzer) throws UserException {
        super.analyze(analyzer);
        for (LockTable lockTable : lockTables) {
            String dbName = lockTable.getTableName().getDb();
            String tableName = lockTable.getTableName().getTbl();
            if (Strings.isNullOrEmpty(dbName)) {
                dbName = analyzer.getDefaultDb();
            } else {
                dbName = ClusterNamespace.getFullName(analyzer.getClusterName(), dbName);
            }
            if (Strings.isNullOrEmpty(dbName)) {
                ErrorReport.reportAnalysisException(ErrorCode.ERR_NO_DB_ERROR);
            }
            if (Strings.isNullOrEmpty(tableName)) {
                ErrorReport.reportAnalysisException(ErrorCode.ERR_UNKNOWN_TABLE, tableName, dbName);
            }
            Database db = analyzer.getCatalog().getDbOrAnalysisException(dbName);
            Table table = db.getTableOrAnalysisException(tableName);

            // check auth
            if (!Catalog.getCurrentCatalog().getAuth().checkTblPriv(ConnectContext.get(), dbName,
                    tableName,
                    PrivPredicate.SELECT)) {
                ErrorReport.reportAnalysisException(ErrorCode.ERR_TABLEACCESS_DENIED_ERROR, "SELECT",
                        ConnectContext.get().getQualifiedUser(),
                        ConnectContext.get().getRemoteIP(),
                        dbName + ": " + tableName);
            }
        }
    }

    @Override
    public String toSql() {
        StringBuilder sb = new StringBuilder();
        sb.append("LOCK TABLES ");
        for (int i = 0; i < lockTables.size(); i++) {
            if (i != 0) {
                sb.append(", ");
            }
            sb.append(lockTables.get(i).getTableName().toSql());
            if (lockTables.get(i).getAlias() != null) {
                sb.append(" AS ").append(lockTables.get(i).getAlias());
            }
            sb.append(" ").append(lockTables.get(i).getLockType().toString());
        }
        return sb.toString();
    }

    @Override
    public RedirectStatus getRedirectStatus() {
        return RedirectStatus.NO_FORWARD;
    }
}
