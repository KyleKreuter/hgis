package de.kreuter.hgis.catalog;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

	/**
	 * Feeds the project browser. Layer and feature counts come from an aggregate so the
	 * list needs a single round trip rather than one query per project.
	 *
	 * Ordering mirrors the browser: most recently opened first, never opened last,
	 * newest first among those.
	 */
	@Query(value = """
			SELECT p.id                                AS id,
			       p.name                              AS name,
			       p.description                       AS description,
			       p.srid                              AS srid,
			       p.last_opened_at                    AS lastOpenedAt,
			       p.created_at                        AS createdAt,
			       COUNT(l.id)                         AS layerCount,
			       COALESCE(SUM(l.feature_count), 0)   AS featureCount
			FROM gis_meta.project p
			LEFT JOIN gis_meta.layer l ON l.project_id = p.id
			GROUP BY p.id
			ORDER BY p.last_opened_at DESC NULLS LAST, p.created_at DESC
			""", nativeQuery = true)
	List<ProjectSummaryRow> findAllSummaries();

	@Query(value = """
			SELECT COUNT(l.id)                       AS layerCount,
			       COALESCE(SUM(l.feature_count), 0) AS featureCount
			FROM gis_meta.layer l
			WHERE l.project_id = :projectId
			""", nativeQuery = true)
	ProjectCountsRow countsFor(@Param("projectId") UUID projectId);

	boolean existsByNameIgnoreCase(String name);
}
