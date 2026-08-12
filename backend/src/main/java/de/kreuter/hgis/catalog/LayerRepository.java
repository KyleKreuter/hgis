package de.kreuter.hgis.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LayerRepository extends JpaRepository<Layer, UUID> {

	/**
	 * Layers of a project in drawing order.
	 *
	 * Written out as JPQL on purpose. A derived query name would have to carry the
	 * segment "ZIndex", and Spring Data does not decapitalize that back to the entity
	 * property {@code zIndex} -- the Java Beans rule leaves a name unchanged when its
	 * first two characters are uppercase, so {@code getZIndex()} exposes "ZIndex". The
	 * result is {@code ORDER BY l.ZIndex} and a runtime failure.
	 */
	@Query("SELECT l FROM Layer l WHERE l.project.id = :projectId ORDER BY l.zIndex ASC, l.createdAt ASC")
	List<Layer> findByProjectOrdered(@Param("projectId") UUID projectId);

	Optional<Layer> findByTableName(String tableName);

	/**
	 * Bumps the tile cache buster without loading the entity. Used after every write to
	 * a payload table, where the surrounding code works with JdbcTemplate anyway.
	 */
	@Modifying
	@Query("UPDATE Layer l SET l.dataVersion = l.dataVersion + 1 WHERE l.id = :layerId")
	void bumpDataVersion(@Param("layerId") UUID layerId);

	@Query("SELECT COALESCE(MAX(l.zIndex), -1) FROM Layer l WHERE l.project.id = :projectId")
	int maxZIndex(@Param("projectId") UUID projectId);

	/**
	 * Bare ids of a project's layers -- enough to tell which references in a stored view
	 * state are still valid, without hydrating full entities for a check that only ever
	 * looks at the id.
	 */
	@Query("SELECT l.id FROM Layer l WHERE l.project.id = :projectId")
	List<UUID> findIdsByProjectId(@Param("projectId") UUID projectId);
}
