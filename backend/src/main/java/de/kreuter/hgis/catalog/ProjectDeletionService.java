package de.kreuter.hgis.catalog;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes a project including the physical tables of all its layers.
 *
 * ON DELETE CASCADE inside gis_meta is not enough. It removes catalog rows and leaves
 * the payload tables in gis_data behind as orphans that nothing can attribute any more.
 * So the tables have to be dropped explicitly, and before the catalog rows disappear --
 * afterwards their names would be unknown.
 *
 * Everything runs in one transaction. DDL is transactional in PostgreSQL, so a failure
 * halfway through rolls back the drops that already succeeded; the project is either
 * gone completely or still fully intact.
 */
@Service
public class ProjectDeletionService {

	private static final Logger log = LoggerFactory.getLogger(ProjectDeletionService.class);

	private final JdbcClient jdbc;

	ProjectDeletionService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional
	public void deleteProject(UUID projectId) {
		// table_name is NULL for a map image layer (kind WMS) -- it has no payload table
		// to drop, so it is excluded here rather than passed on to the check below, which
		// exists for a name, not for the absence of one.
		List<String> tableNames = jdbc
				.sql("SELECT table_name FROM gis_meta.layer WHERE project_id = :projectId AND table_name IS NOT NULL")
				.param("projectId", projectId)
				.query(String.class)
				.list();

		for (String tableName : tableNames) {
			// table_name is generated as 'layer_' + hex(uuid) and additionally guarded by a
			// CHECK constraint in the schema, so it can never carry user input. Verified
			// again here because this is the one place that interpolates into DDL.
			if (!tableName.matches("^layer_[0-9a-f]{32}$")) {
				throw new IllegalStateException(
						"Refusing to drop table with unexpected name: " + tableName);
			}
			jdbc.sql("DROP TABLE IF EXISTS gis_data.\"" + tableName + "\"").update();
		}

		int removed = jdbc.sql("DELETE FROM gis_meta.project WHERE id = :projectId")
				.param("projectId", projectId)
				.update();

		log.info("Deleted project {} together with {} layer table(s), {} catalog row(s)",
				projectId, tableNames.size(), removed);
	}
}
