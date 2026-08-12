package de.kreuter.hgis.catalog;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

	/**
	 * Feeds the project browser: one page, newest/most-recently-opened first, optionally
	 * restricted to projects whose name or description matches {@code pattern}.
	 *
	 * <p>The {@code page} CTE picks the row set and applies {@code LIMIT} <em>before</em>
	 * the aggregation below it -- a {@code LIMIT} tacked onto the old single-query,
	 * {@code GROUP BY}-over-everything shape would still aggregate the whole table and
	 * only cut the result down afterwards. Layer and feature counts still come from one
	 * aggregate, so a page needs a single round trip rather than one query per project.
	 *
	 * <p>Ordering and the keyset condition both fold a null {@code last_opened_at} to
	 * {@code -infinity} with the same {@code COALESCE}, in the row and in the cursor
	 * alike -- {@code NULLS LAST} cannot be written as a row-value comparison, and
	 * without a real value there is nothing to build a keyset condition from.
	 * {@code created_at} then {@code id} break every tie, which is what keeps a page
	 * boundary from ever skipping or repeating a row; see {@link ProjectCursor}.
	 *
	 * <p>Caller passes {@code cursorId == null} for the first page; the three cursor
	 * parameters travel together; see {@link ProjectCursor}.
	 *
	 * @param pattern ILIKE pattern (already wildcard-escaped, wrapped in {@code %...%}),
	 *                or null for no search restriction
	 * @param fetchLimit the caller's page size plus one, so an extra row surviving the
	 *                    trip answers "is there a next page" without a second query
	 */
	@Query(value = """
			WITH page AS (
			    SELECT p.id, p.name, p.description, p.srid, p.last_opened_at, p.created_at,
			           p.center, p.zoom, p.extent, p.basemap
			    FROM gis_meta.project p
			    WHERE (:pattern IS NULL
			           OR p.name ILIKE :pattern ESCAPE '\\'
			           OR p.description ILIKE :pattern ESCAPE '\\')
			      AND (:cursorId IS NULL
			           OR (COALESCE(p.last_opened_at, TIMESTAMPTZ '-infinity'), p.created_at, p.id)
			              < (COALESCE(:cursorOpened, TIMESTAMPTZ '-infinity'), :cursorCreated, :cursorId))
			    ORDER BY COALESCE(p.last_opened_at, TIMESTAMPTZ '-infinity') DESC,
			             p.created_at DESC, p.id DESC
			    LIMIT :fetchLimit
			)
			SELECT page.id                              AS id,
			       page.name                             AS name,
			       page.description                      AS description,
			       page.srid                             AS srid,
			       page.last_opened_at                   AS lastOpenedAt,
			       page.created_at                       AS createdAt,
			       ST_X(page.center)                     AS centerLng,
			       ST_Y(page.center)                     AS centerLat,
			       page.zoom                             AS zoom,
			       ST_XMin(page.extent)                  AS extentMinLng,
			       ST_YMin(page.extent)                  AS extentMinLat,
			       ST_XMax(page.extent)                  AS extentMaxLng,
			       ST_YMax(page.extent)                  AS extentMaxLat,
			       page.basemap                          AS basemap,
			       COUNT(l.id)                           AS layerCount,
			       COALESCE(SUM(l.feature_count), 0)     AS featureCount
			FROM page LEFT JOIN gis_meta.layer l ON l.project_id = page.id
			GROUP BY page.id, page.name, page.description, page.srid, page.last_opened_at,
			         page.created_at, page.center, page.zoom, page.extent, page.basemap
			ORDER BY COALESCE(page.last_opened_at, TIMESTAMPTZ '-infinity') DESC,
			         page.created_at DESC, page.id DESC
			""", nativeQuery = true)
	List<ProjectSummaryRow> findPage(@Param("pattern") String pattern,
			@Param("cursorOpened") Instant cursorOpened,
			@Param("cursorCreated") Instant cursorCreated,
			@Param("cursorId") UUID cursorId,
			@Param("fetchLimit") int fetchLimit);

	@Query(value = """
			SELECT COUNT(l.id)                       AS layerCount,
			       COALESCE(SUM(l.feature_count), 0) AS featureCount
			FROM gis_meta.layer l
			WHERE l.project_id = :projectId
			""", nativeQuery = true)
	ProjectCountsRow countsFor(@Param("projectId") UUID projectId);

	boolean existsByNameIgnoreCase(String name);
}
