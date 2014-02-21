/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ambari.server.upgrade;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Provider;
import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.configuration.Configuration;
import org.apache.ambari.server.orm.DBAccessor;
import org.apache.ambari.server.utils.VersionUtils;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.persistence.EntityManager;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

public abstract class AbstractUpgradeCatalog implements UpgradeCatalog {
  @Inject
  protected DBAccessor dbAccessor;
  @Inject
  protected Configuration configuration;
  @Inject
  protected StackUpgradeUtil stackUpgradeUtil;

  private Injector injector;
  private static final Logger LOG = LoggerFactory.getLogger
    (AbstractUpgradeCatalog.class);
  private static final Map<String, UpgradeCatalog> upgradeCatalogMap =
    new HashMap<String, UpgradeCatalog>();

  @Inject
  public AbstractUpgradeCatalog(Injector injector) {
    this.injector = injector;
    registerCatalog(this);
  }

  /**
   * Every subclass needs to register itself
   */
  protected void registerCatalog(UpgradeCatalog upgradeCatalog) {
    upgradeCatalogMap.put(upgradeCatalog.getTargetVersion(), upgradeCatalog);
  }

  @Override
  public String getSourceVersion() {
    return null;
  }

  protected static UpgradeCatalog getUpgradeCatalog(String version) {
    return upgradeCatalogMap.get(version);
  }

  protected static class VersionComparator implements Comparator<UpgradeCatalog> {

    @Override
    public int compare(UpgradeCatalog upgradeCatalog1,
                       UpgradeCatalog upgradeCatalog2) {
      return VersionUtils.compareVersions(upgradeCatalog1.getTargetVersion(),
        upgradeCatalog2.getTargetVersion(), 3);
    }
  }

  /**
   * Read server version file
   * @return
   */
  protected String getAmbariServerVersion() {
    String versionFilePath = configuration.getServerVersionFilePath();
    try {
      return FileUtils.readFileToString(new File(versionFilePath));
    } catch (IOException e) {
      e.printStackTrace();
    }
    return null;
  }

  /**
   * Update metainfo to new version.
   */
  protected int updateMetaInfoVersion(String version) {
    String ambariServerVersion = getAmbariServerVersion();
    int rows = 0;

    if (ambariServerVersion != null) {
      try {
        dbAccessor.executeQuery("INSERT INTO metainfo ('metainfo_key', " +
          "'metainfo_value') VALUES ('version', '${ambariVersion}')");

        rows = dbAccessor.updateTable("metainfo", "metainfo_value",
          ambariServerVersion, String.format("WHERE metainfo_key = '%s'", version));
      } catch (SQLException e) {
        LOG.error("Failed updating metainfo table.", e);
      }
    }

    return rows;
  }

  protected String getDbType() {
    String dbUrl = configuration.getDatabaseUrl();
    String dbType;

    if (dbUrl.contains(Configuration.POSTGRES_DB_NAME)) {
      dbType = Configuration.POSTGRES_DB_NAME;
    } else if (dbUrl.contains(Configuration.ORACLE_DB_NAME)) {
      dbType = Configuration.ORACLE_DB_NAME;
    } else if (dbUrl.contains(Configuration.MYSQL_DB_NAME)) {
      dbType = Configuration.MYSQL_DB_NAME;
    } else {
      throw new RuntimeException("Unable to determine database type.");
    }

    return dbType;
  }

  protected Provider<EntityManager> getEntityManagerProvider() {
    return injector.getProvider(EntityManager.class);
  }

  protected void executeInTransaction(Runnable func) {
    EntityManager entityManager = getEntityManagerProvider().get();
    if (entityManager.getTransaction().isActive()) { //already started, reuse
      func.run();
    } else {
      entityManager.getTransaction().begin();
      try {
        func.run();
        entityManager.getTransaction().commit();
      } catch (Exception e) {
        entityManager.getTransaction().rollback();
        throw new RuntimeException(e);
      }

    }
  }

  protected void changePostgresSearchPath() throws SQLException {
    String dbUser = configuration.getDatabaseUser();
    String dbName = configuration.getServerDBName();

    dbAccessor.executeQuery(String.format("ALTER SCHEMA %s OWNER TO %s;", dbName, dbUser));

    dbAccessor.executeQuery(String.format("ALTER ROLE %s SET search_path to '%s';", dbUser, dbName));
  }

  protected void grantAllPostgresPrivileges() throws SQLException {
    String query = "GRANT ALL PRIVILEGES ON DATABASE ambari TO " +
      configuration.getDatabaseUser();

    dbAccessor.executeQuery(query);
  }

  @Override
  public void upgradeSchema() throws AmbariException, SQLException {
    if (getDbType().equals(Configuration.POSTGRES_DB_NAME)) {
      changePostgresSearchPath();
    }

    this.executeDDLUpdates();
  }

  protected abstract void executeDDLUpdates() throws AmbariException, SQLException;

  @Override
  public String toString() {
    return "{ " + this.getClass().getCanonicalName() +": " +
      "sourceVersion = " + getSourceVersion() + ", " +
      "targetVersion = " + getTargetVersion() + " }";
  }
}
