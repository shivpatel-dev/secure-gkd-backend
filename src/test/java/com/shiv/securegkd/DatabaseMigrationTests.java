package com.shiv.securegkd;

import com.shiv.securegkd.allocation.AllocationRepository;
import com.shiv.securegkd.allocation.AllocationRequest;
import com.shiv.securegkd.allocation.AllocationResponse;
import com.shiv.securegkd.allocation.AllocationService;
import com.shiv.securegkd.authentication.AuthenticationIdentityRepository;
import com.shiv.securegkd.authentication.AuthenticationIdentity;
import com.shiv.securegkd.authentication.AuthenticationRole;
import com.shiv.securegkd.game.Game;
import com.shiv.securegkd.game.GameRepository;
import com.shiv.securegkd.gamekey.GameKey;
import com.shiv.securegkd.gamekey.GameKeyRepository;
import com.shiv.securegkd.idempotency.IdempotencyRecordRepository;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DatabaseMigrationTests {

    @Autowired
    private Flyway flyway;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private GameRepository gameRepository;

    @Autowired
    private GameKeyRepository gameKeyRepository;

    @Autowired
    private AllocationRepository allocationRepository;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    private AllocationService allocationService;

    @Autowired
    private AuthenticationIdentityRepository authenticationIdentityRepository;

    @BeforeEach
    void setUp() {
        deleteTestData();
    }

    @AfterEach
    void tearDown() {
        deleteTestData();
    }

    @Test
    void flywayOwnsThePostgresqlSchemaAndRepositoriesUseIt() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }

        assertThat(jdbcTemplate.queryForObject("""
                select count(*)
                from flyway_schema_history
                where version = '1'
                  and description = 'create initial schema'
                  and type = 'SQL'
                  and success
                """, Long.class)).isEqualTo(1L);

        assertThat(jdbcTemplate.queryForObject("""
                select count(*)
                from flyway_schema_history
                where version = '3'
                  and description = 'add authentication identity roles'
                  and type = 'SQL'
                  and success
                """, Long.class)).isEqualTo(1L);

        assertThat(jdbcTemplate.queryForObject("""
                select count(*)
                from flyway_schema_history
                where version = '2'
                  and description = 'create authentication identities'
                  and type = 'SQL'
                  and success
                """, Long.class)).isEqualTo(1L);

        assertThat(jdbcTemplate.queryForList("""
                select table_name
                from information_schema.tables
                where table_schema = current_schema()
                  and table_name in (
                      'games',
                      'game_keys',
                      'allocations',
                      'idempotency_records',
                      'authentication_identities'
                  )
                """, String.class)).containsExactlyInAnyOrder(
                "games",
                "game_keys",
                "allocations",
                "idempotency_records",
                "authentication_identities"
        );

        assertThat(readApplicationColumns()).containsExactlyInAnyOrder(
                column("games", "id", "bigint", false, null, null, true),
                column("games", "code", "character varying", false, 100, null, false),
                column("games", "title", "character varying", false, 255, null, false),
                column("games", "created_at", "timestamp with time zone", false, null, 6, false),
                column("game_keys", "id", "bigint", false, null, null, true),
                column("game_keys", "game_id", "bigint", false, null, null, false),
                column("game_keys", "code", "character varying", false, 100, null, false),
                column("game_keys", "created_at", "timestamp with time zone", false, null, 6, false),
                column("allocations", "id", "bigint", false, null, null, true),
                column("allocations", "game_key_id", "bigint", false, null, null, false),
                column("allocations", "allocated_at", "timestamp with time zone", false, null, 6, false),
                column("idempotency_records", "id", "bigint", false, null, null, true),
                column("idempotency_records", "idempotency_key", "character varying", false, 255, null, false),
                column("idempotency_records", "allocation_id", "bigint", false, null, null, false),
                column("idempotency_records", "created_at", "timestamp with time zone", false, null, 6, false),
                column("authentication_identities", "id", "bigint", false, null, null, true),
                column("authentication_identities", "username", "character varying", false, 100, null, false),
                column("authentication_identities", "password_hash", "character varying", false, 255, null, false),
                column("authentication_identities", "role", "character varying", false, 5, null, false),
                column("authentication_identities", "created_at", "timestamp with time zone", false, null, 6, false)
        );

        assertThat(jdbcTemplate.queryForList("""
                select constraint_name
                from information_schema.table_constraints
                where constraint_schema = current_schema()
                  and table_name in (
                      'games',
                      'game_keys',
                      'allocations',
                      'idempotency_records',
                      'authentication_identities'
                  )
                  and constraint_type in ('PRIMARY KEY', 'UNIQUE', 'FOREIGN KEY')
                """, String.class)).containsExactlyInAnyOrder(
                "games_pkey",
                "games_code_key",
                "game_keys_pkey",
                "game_keys_code_key",
                "game_keys_game_id_fkey",
                "allocations_pkey",
                "allocations_game_key_id_key",
                "allocations_game_key_id_fkey",
                "idempotency_records_pkey",
                "idempotency_records_idempotency_key_key",
                "idempotency_records_allocation_id_key",
                "idempotency_records_allocation_id_fkey",
                "authentication_identities_pkey",
                "authentication_identities_username_key"
        );

        assertThat(jdbcTemplate.queryForList("""
                select constraint_name
                from information_schema.table_constraints
                where constraint_schema = current_schema()
                  and table_name = 'authentication_identities'
                  and constraint_type = 'CHECK'
                """, String.class)).contains("authentication_identities_role_check");

        AuthenticationIdentity administrator = authenticationIdentityRepository.saveAndFlush(
                new AuthenticationIdentity(
                        "migration-admin",
                        "{noop}migration-password",
                        AuthenticationRole.ADMIN
                )
        );
        assertThat(authenticationIdentityRepository.findById(administrator.getId()).orElseThrow().getRole())
                .isEqualTo(AuthenticationRole.ADMIN);

        Game game = gameRepository.saveAndFlush(new Game("MIGRATION-GAME", "Migration Test Game"));
        gameKeyRepository.saveAndFlush(new GameKey(game, "MIGRATION-KEY-001"));

        AllocationResponse firstResponse = allocationService.allocate(
                game.getCode(),
                new AllocationRequest("migration-idempotency-key")
        );
        AllocationResponse replayedResponse = allocationService.allocate(
                game.getCode(),
                new AllocationRequest("migration-idempotency-key")
        );

        assertThat(replayedResponse.gameCode()).isEqualTo(firstResponse.gameCode());
        assertThat(replayedResponse.keyCode()).isEqualTo(firstResponse.keyCode());
        Duration replayTimestampDifference = Duration.between(
                firstResponse.allocatedAt(),
                replayedResponse.allocatedAt()
        ).abs();
        assertThat(replayTimestampDifference)
                .isLessThanOrEqualTo(Duration.ofNanos(1_000));
        assertThat(allocationRepository.count()).isEqualTo(1L);
        assertThat(idempotencyRecordRepository.count()).isEqualTo(1L);

        MigrateResult repeatMigration = flyway.migrate();

        assertThat(repeatMigration.migrationsExecuted).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*)
                from flyway_schema_history
                where version in ('1', '2', '3') and success
                """, Long.class)).isEqualTo(3L);
    }

    @Test
    void roleMigrationBackfillsExistingIdentitiesAndConstrainsApprovedValues() {
        String schema = "role_migration_" + UUID.randomUUID().toString().replace("-", "");
        Flyway versionTwoFlyway = isolatedFlyway(schema, MigrationVersion.fromVersion("2"));
        Flyway currentFlyway = isolatedFlyway(schema, null);

        try {
            versionTwoFlyway.migrate();
            jdbcTemplate.update("""
                    insert into %s.authentication_identities (username, password_hash, created_at)
                    values ('existing-user', '{noop}password', current_timestamp)
                    """.formatted(schema));

            MigrateResult migration = currentFlyway.migrate();

            assertThat(migration.migrationsExecuted).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "select role from %s.authentication_identities where username = 'existing-user'"
                            .formatted(schema),
                    String.class
            )).isEqualTo("USER");
            assertThatThrownBy(() -> jdbcTemplate.update("""
                    insert into %s.authentication_identities
                        (username, password_hash, role, created_at)
                    values ('invalid-role', '{noop}password', 'OWNER', current_timestamp)
                    """.formatted(schema)))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbcTemplate.update("""
                    insert into %s.authentication_identities
                        (username, password_hash, role, created_at)
                    values ('missing-role', '{noop}password', null, current_timestamp)
                    """.formatted(schema)))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            currentFlyway.clean();
        }
    }

    private Flyway isolatedFlyway(String schema, MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .defaultSchema(schema)
                .schemas(schema)
                .locations("classpath:db/migration")
                .cleanDisabled(false);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private List<ColumnMetadata> readApplicationColumns() {
        return jdbcTemplate.query("""
                select table_name,
                       column_name,
                       data_type,
                       is_nullable,
                       character_maximum_length,
                       datetime_precision,
                       is_identity
                from information_schema.columns
                where table_schema = current_schema()
                  and table_name in (
                      'games',
                      'game_keys',
                      'allocations',
                      'idempotency_records',
                      'authentication_identities'
                  )
                """, (resultSet, rowNumber) -> new ColumnMetadata(
                resultSet.getString("table_name"),
                resultSet.getString("column_name"),
                resultSet.getString("data_type"),
                resultSet.getString("is_nullable").equals("YES"),
                resultSet.getObject("character_maximum_length", Integer.class),
                resultSet.getObject("datetime_precision", Integer.class),
                resultSet.getString("is_identity").equals("YES")
        ));
    }

    private ColumnMetadata column(
            String tableName,
            String columnName,
            String dataType,
            boolean nullable,
            Integer maximumLength,
            Integer datetimePrecision,
            boolean identity
    ) {
        return new ColumnMetadata(
                tableName,
                columnName,
                dataType,
                nullable,
                maximumLength,
                datetimePrecision,
                identity
        );
    }

    private void deleteTestData() {
        authenticationIdentityRepository.deleteAllInBatch();
        idempotencyRecordRepository.deleteAllInBatch();
        allocationRepository.deleteAllInBatch();
        gameKeyRepository.deleteAllInBatch();
        gameRepository.deleteAllInBatch();
    }

    private record ColumnMetadata(
            String tableName,
            String columnName,
            String dataType,
            boolean nullable,
            Integer maximumLength,
            Integer datetimePrecision,
            boolean identity
    ) {
    }
}
