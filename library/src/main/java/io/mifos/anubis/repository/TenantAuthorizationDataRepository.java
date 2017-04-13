/*
 * Copyright 2017 The Mifos Initiative.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.mifos.anubis.repository;

import com.datastax.driver.core.*;
import com.datastax.driver.core.querybuilder.QueryBuilder;
import com.datastax.driver.core.querybuilder.Select;
import com.datastax.driver.core.querybuilder.Update;
import com.datastax.driver.core.schemabuilder.SchemaBuilder;
import io.mifos.anubis.api.v1.domain.ApplicationSignatureSet;
import io.mifos.anubis.api.v1.domain.Signature;
import io.mifos.anubis.config.AnubisConstants;
import io.mifos.anubis.config.TenantSignatureRepository;
import io.mifos.core.cassandra.core.CassandraSessionProvider;
import io.mifos.core.lang.ApplicationName;
import io.mifos.core.lang.security.RsaKeyPairFactory;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import javax.annotation.Nonnull;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * @author Myrle Krantz
 */
@Component
public class TenantAuthorizationDataRepository implements TenantSignatureRepository {
  private static final String AUTHORIZATION_TABLE_SUFFIX = "_authorization_v1_data";
  private static final String AUTHORIZATION_INDEX_SUFFIX = "_authorization_v1_valid_index";
  private static final String TIMESTAMP_COLUMN = "timestamp";
  private static final String VALID_COLUMN = "valid";
  private static final String IDENTITY_MANAGER_PUBLIC_KEY_MOD_COLUMN = "identity_manager_public_key_mod";
  private static final String IDENTITY_MANAGER_PUBLIC_KEY_EXP_COLUMN = "identity_manager_public_key_exp";
  private static final String APPLICATION_PRIVATE_KEY_MOD_COLUMN = "application_private_key_mod";
  private static final String APPLICATION_PRIVATE_KEY_EXP_COLUMN = "application_private_key_exp";
  private static final String APPLICATION_PUBLIC_KEY_MOD_COLUMN = "application_public_key_mod";
  private static final String APPLICATION_PUBLIC_KEY_EXP_COLUMN = "application_public_key_exp";

  private final String tableName;
  private final String indexName;
  private final CassandraSessionProvider cassandraSessionProvider;

  //So that the query only has to be prepared once and the Cassandra driver stops writing warnings into my logfiles.
  private final Map<String, Select.Where> timestampToSignatureQueryMap = new HashMap<>();
  private final Logger logger;

  @Autowired
  public TenantAuthorizationDataRepository(
      final ApplicationName applicationName,
      final CassandraSessionProvider cassandraSessionProvider,
      final @Qualifier(AnubisConstants.LOGGER_NAME) Logger logger)
  {
    tableName = applicationName.getServiceName() + AUTHORIZATION_TABLE_SUFFIX;
    indexName = applicationName.getServiceName() + AUTHORIZATION_INDEX_SUFFIX;
    this.cassandraSessionProvider = cassandraSessionProvider;
    this.logger = logger;
  }

  /**
   *
   * @param timestamp The timestamp to save the signatures for.  When rotating keys, this will be used to delete keys
   *                  which are being rotated out.
   *
   * @param identityManagerSignature The public keys of the identity manager.  These keys will be used to authenticate
   *                                 the user via the token provided in most requests.
   *
   * @return The signature containing the public keys of the application.  This is *not* the signature passed in
   * for the identity manager.
   */
  public Signature createSignatureSet(final String timestamp, final Signature identityManagerSignature) {
    Assert.notNull(timestamp);
    Assert.notNull(identityManagerSignature);

    //TODO: add validation to make sure this timestamp is more recent than any already stored.
    final RsaKeyPairFactory.KeyPairHolder applicationSignature = RsaKeyPairFactory.createKeyPair();

    final Session session = cassandraSessionProvider.getTenantSession();

    createTable(session);
    createEntry(session,
            timestamp,
            identityManagerSignature.getPublicKeyMod(),
            identityManagerSignature.getPublicKeyExp(),
            applicationSignature.getPrivateKeyMod(),
            applicationSignature.getPrivateKeyExp(),
            applicationSignature.getPublicKeyMod(),
            applicationSignature.getPublicKeyExp());

    return new Signature(applicationSignature.getPublicKeyMod(), applicationSignature.getPublicKeyExp());
  }

  public void deleteSignatureSet(final String timestamp) {
    Assert.notNull(timestamp);
    //Don't actually delete, just invalidate, so that if someone starts coming at me with an older keyset, I'll
    //know what's happening.
    final Session session = cassandraSessionProvider.getTenantSession();
    invalidateEntry(session, timestamp);
  }

  public Optional<Signature> getApplicationSignature(final String timestamp) {
    Assert.notNull(timestamp);

    return getRow(timestamp).map(TenantAuthorizationDataRepository::mapRowToApplicationSignature);
  }

  private void createTable(final @Nonnull Session tenantSession) {

    final String createTenantsTable = SchemaBuilder
            .createTable(tableName)
            .ifNotExists()
            .addPartitionKey(TIMESTAMP_COLUMN, DataType.text())
            .addColumn(VALID_COLUMN, DataType.cboolean())
            .addColumn(IDENTITY_MANAGER_PUBLIC_KEY_MOD_COLUMN, DataType.varint())
            .addColumn(IDENTITY_MANAGER_PUBLIC_KEY_EXP_COLUMN, DataType.varint())
            .addColumn(APPLICATION_PRIVATE_KEY_MOD_COLUMN, DataType.varint())
            .addColumn(APPLICATION_PRIVATE_KEY_EXP_COLUMN, DataType.varint())
            .addColumn(APPLICATION_PUBLIC_KEY_MOD_COLUMN, DataType.varint())
            .addColumn(APPLICATION_PUBLIC_KEY_EXP_COLUMN, DataType.varint())
            .buildInternal();

    tenantSession.execute(createTenantsTable);

    final String createValidIndex = SchemaBuilder.createIndex(indexName)
            .ifNotExists()
            .onTable(tableName)
            .andColumn(VALID_COLUMN)
            .toString();

    tenantSession.execute(createValidIndex);
  }

  private void createEntry(final @Nonnull Session tenantSession,
                           final @Nonnull String timestamp,
                           final @Nonnull BigInteger identityManagerPublicKeyModulus,
                           final @Nonnull BigInteger identityManagerPublicKeyExponent,
                           final @Nonnull BigInteger applicationPrivateKeyModulus,
                           final @Nonnull BigInteger applicationPrivateKeyExponent,
                           final @Nonnull BigInteger applicationPublicKeyModulus,
                           final @Nonnull BigInteger applicationPublicKeyExponent)
  {

    final ResultSet timestampCount =
        tenantSession.execute("SELECT count(*) FROM " + this.tableName + " WHERE " + TIMESTAMP_COLUMN + " = '" + timestamp + "'");
    final Long value = timestampCount.one().get(0, Long.class);
    if (value == 0L) {
      //There will only be one entry in this table per version.
      final BoundStatement tenantCreationStatement =
          tenantSession.prepare("INSERT INTO " + tableName + " ("
                  + TIMESTAMP_COLUMN + ", "
                  + VALID_COLUMN + ", "
                  + IDENTITY_MANAGER_PUBLIC_KEY_MOD_COLUMN + ", "
                  + IDENTITY_MANAGER_PUBLIC_KEY_EXP_COLUMN + ", "
                  + APPLICATION_PRIVATE_KEY_MOD_COLUMN + ", "
                  + APPLICATION_PRIVATE_KEY_EXP_COLUMN + ", "
                  + APPLICATION_PUBLIC_KEY_MOD_COLUMN + ", "
                  + APPLICATION_PUBLIC_KEY_EXP_COLUMN + ")"
                  + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)").bind();
      completeBoundStatement(tenantCreationStatement,
              timestamp,
              true,
              identityManagerPublicKeyModulus,
              identityManagerPublicKeyExponent,
              applicationPrivateKeyModulus,
              applicationPrivateKeyExponent,
              applicationPublicKeyModulus,
              applicationPublicKeyExponent);

      tenantSession.execute(tenantCreationStatement);
    } else {
      //TODO: Make sure existing entry hasn't been invalidated, or just don't allow an update.
      final BoundStatement tenantUpdateStatement =
          tenantSession.prepare("UPDATE " + tableName + " SET "
                  + VALID_COLUMN + " = ?, "
                  + IDENTITY_MANAGER_PUBLIC_KEY_MOD_COLUMN + " = ?, "
                  + IDENTITY_MANAGER_PUBLIC_KEY_EXP_COLUMN + " = ?, "
                  + APPLICATION_PRIVATE_KEY_MOD_COLUMN + " = ?, "
                  + APPLICATION_PRIVATE_KEY_EXP_COLUMN + " = ?, "
                  + APPLICATION_PUBLIC_KEY_MOD_COLUMN + " = ?, "
                  + APPLICATION_PUBLIC_KEY_EXP_COLUMN + " = ? "
                  + "WHERE " + TIMESTAMP_COLUMN + " = ?").bind();
      completeBoundStatement(tenantUpdateStatement,
              timestamp,
              true,
              identityManagerPublicKeyModulus,
              identityManagerPublicKeyExponent,
              applicationPrivateKeyModulus,
              applicationPrivateKeyExponent,
              applicationPublicKeyModulus,
              applicationPublicKeyExponent);

      tenantSession.execute(tenantUpdateStatement);
    }
  }

  private void invalidateEntry(final @Nonnull Session tenantSession, final @Nonnull String timestamp) {
    final Update.Assignments updateQuery = QueryBuilder.update(tableName).where(QueryBuilder.eq(TIMESTAMP_COLUMN, timestamp)).with(QueryBuilder.set(VALID_COLUMN, false));
    tenantSession.execute(updateQuery);
  }

  private void completeBoundStatement(
      final @Nonnull BoundStatement boundStatement,
      final @Nonnull String timestamp,
      final boolean valid,
      final @Nonnull BigInteger identityManagerPublicKeyModulus,
      final @Nonnull BigInteger identityManagerPublicKeyExponent,
      final @Nonnull BigInteger applicationPrivateKeyModulus,
      final @Nonnull BigInteger applicationPrivateKeyExponent,
      final @Nonnull BigInteger applicationPublicKeyModulus,
      final @Nonnull BigInteger applicationPublicKeyExponent) {
    boundStatement.setString(TIMESTAMP_COLUMN, timestamp);
    boundStatement.setBool(VALID_COLUMN, valid);
    boundStatement.setVarint(IDENTITY_MANAGER_PUBLIC_KEY_MOD_COLUMN, identityManagerPublicKeyModulus);
    boundStatement.setVarint(IDENTITY_MANAGER_PUBLIC_KEY_EXP_COLUMN, identityManagerPublicKeyExponent);
    boundStatement.setVarint(APPLICATION_PRIVATE_KEY_MOD_COLUMN, applicationPrivateKeyModulus);
    boundStatement.setVarint(APPLICATION_PRIVATE_KEY_EXP_COLUMN, applicationPrivateKeyExponent);
    boundStatement.setVarint(APPLICATION_PUBLIC_KEY_MOD_COLUMN, applicationPublicKeyModulus);
    boundStatement.setVarint(APPLICATION_PUBLIC_KEY_EXP_COLUMN, applicationPublicKeyExponent);
  }

  @Override
  public Optional<Signature> getIdentityManagerSignature(final String timestamp)
  {
    Assert.notNull(timestamp);
    return getRow(timestamp).map(TenantAuthorizationDataRepository::mapRowToIdentityManagerSignature);
  }

  private Optional<Row> getRow(final @Nonnull String timestamp) {
    final Session tenantSession = cassandraSessionProvider.getTenantSession();
    final Select.Where query = timestampToSignatureQueryMap.computeIfAbsent(timestamp, timestampKey ->
            QueryBuilder.select().from(tableName).where(QueryBuilder.eq(TIMESTAMP_COLUMN, timestampKey)));
    final Row row = tenantSession.execute(query).one();
    final Optional<Row> ret = Optional.ofNullable(row);
    ret.map(TenantAuthorizationDataRepository::mapRowToValid).ifPresent(valid -> {
      if (!valid)
        logger.warn("Invalidated keyset for timestamp '" + timestamp + "' requested. Pretending no keyset exists.");
    });
    return ret.filter(TenantAuthorizationDataRepository::mapRowToValid);
  }

  private static Boolean mapRowToValid(final @Nonnull Row row) {
    return row.get(VALID_COLUMN, Boolean.class);
  }

  private static Signature getSignature(final @Nonnull Row row,
                                        final @Nonnull String publicKeyModColumnName,
                                        final @Nonnull String publicKeyExpColumnName) {
    final BigInteger publicKeyModulus = row.get(publicKeyModColumnName, BigInteger.class);
    final BigInteger publicKeyExponent = row.get(publicKeyExpColumnName, BigInteger.class);

    Assert.notNull(publicKeyModulus);
    Assert.notNull(publicKeyExponent);

    return new Signature(publicKeyModulus, publicKeyExponent);
  }

  private static Signature mapRowToIdentityManagerSignature(final @Nonnull Row row) {
    return getSignature(row, IDENTITY_MANAGER_PUBLIC_KEY_MOD_COLUMN, IDENTITY_MANAGER_PUBLIC_KEY_EXP_COLUMN);
  }

  private static Signature mapRowToApplicationSignature(final @Nonnull Row row) {
    return getSignature(row, APPLICATION_PUBLIC_KEY_MOD_COLUMN, APPLICATION_PUBLIC_KEY_EXP_COLUMN);
  }

  private static ApplicationSignatureSet mapRowToSignatureSet(final @Nonnull Row row) {
    final String timestamp = row.get(TIMESTAMP_COLUMN, String.class);
    final Signature identityManagerSignature = mapRowToIdentityManagerSignature(row);
    final Signature applicationSignature = mapRowToApplicationSignature(row);

    return new ApplicationSignatureSet(timestamp, applicationSignature, identityManagerSignature);
  }

  public List<String> getAllSignatureSetKeyTimestamps() {
    final Select.Where selectValid = QueryBuilder.select(TIMESTAMP_COLUMN).from(tableName).where(QueryBuilder.eq(VALID_COLUMN, true));
    final ResultSet result = cassandraSessionProvider.getTenantSession().execute(selectValid);
    return StreamSupport.stream(result.spliterator(), false)
            .map(x -> x.get(TIMESTAMP_COLUMN, String.class))
            .collect(Collectors.toList());
  }

  public Optional<ApplicationSignatureSet> getSignatureSet(final String timestamp) {
    Assert.notNull(timestamp);
    return getRow(timestamp).map(TenantAuthorizationDataRepository::mapRowToSignatureSet);
  }

  @Override
  public Optional<ApplicationSignatureSet> getLatestSignatureSet() {
    Optional<String> timestamp = getMostRecentTimestamp();
    return timestamp.flatMap(this::getSignatureSet);
  }

  @Override
  public Optional<Signature> getLatestApplicationSignature() {
    Optional<String> timestamp = getMostRecentTimestamp();
    return timestamp.flatMap(this::getApplicationSignature);
  }

  private Optional<String> getMostRecentTimestamp() {
    return getAllSignatureSetKeyTimestamps().stream()
            .max(String::compareTo);
  }
}