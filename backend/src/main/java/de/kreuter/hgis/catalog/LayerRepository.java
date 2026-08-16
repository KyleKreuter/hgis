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
	 * Layers of a project in drawing order, trashed ones excluded (CONTRACT.md
	 * "Schreibstufe" 1.1: a trashed layer must appear neither in the layer list nor on
	 * the map, and this query backs both).
	 *
	 * Written out as JPQL on purpose. A derived query name would have to carry the
	 * segment "ZIndex", and Spring Data does not decapitalize that back to the entity
	 * property {@code zIndex} -- the Java Beans rule leaves a name unchanged when its
	 * first two characters are uppercase, so {@code getZIndex()} exposes "ZIndex". The
	 * result is {@code ORDER BY l.ZIndex} and a runtime failure.
	 */
	@Query("SELECT l FROM Layer l WHERE l.project.id = :projectId AND l.deletedAt IS NULL "
			+ "ORDER BY l.zIndex ASC, l.createdAt ASC")
	List<Layer> findByProjectOrdered(@Param("projectId") UUID projectId);

	/** The trash: every layer of a project moved to it, most recently deleted first. */
	@Query("SELECT l FROM Layer l WHERE l.project.id = :projectId AND l.deletedAt IS NOT NULL "
			+ "ORDER BY l.deletedAt DESC")
	List<Layer> findTrashedByProject(@Param("projectId") UUID projectId);

	Optional<Layer> findByTableName(String tableName);

	/**
	 * Bumps the tile cache buster without loading the entity. Used after every write to
	 * a payload table, where the surrounding code works with JdbcTemplate anyway.
	 */
	@Modifying
	@Query("UPDATE Layer l SET l.dataVersion = l.dataVersion + 1 WHERE l.id = :layerId")
	void bumpDataVersion(@Param("layerId") UUID layerId);

	/** Trashed layers are excluded -- their z position no longer reserves stacking space. */
	@Query("SELECT COALESCE(MAX(l.zIndex), -1) FROM Layer l WHERE l.project.id = :projectId AND l.deletedAt IS NULL")
	int maxZIndex(@Param("projectId") UUID projectId);

	/**
	 * Bare ids of a project's layers -- enough to tell which references in a stored view
	 * state are still valid, without hydrating full entities for a check that only ever
	 * looks at the id. Trashed layers are excluded: a view state pointing at one is
	 * cleaned up exactly like one pointing at a layer that no longer exists at all.
	 */
	@Query("SELECT l.id FROM Layer l WHERE l.project.id = :projectId AND l.deletedAt IS NULL")
	List<UUID> findIdsByProjectId(@Param("projectId") UUID projectId);

	/**
	 * All masks of the project, bottom-most first -- any number of layers may carry a
	 * {@code clipMode} at once (CONTRACT.md phase 21). Read fresh wherever a tile or a
	 * layer DTO is built rather than cached, so a newly marked, unmarked, edited or
	 * mode-switched mask takes effect immediately. A trashed layer is excluded: it must
	 * stop clipping anything the moment it disappears from the map, or hiding it would
	 * not have gained anything (CONTRACT.md "Schreibstufe" 1.1).
	 */
	@Query("SELECT l FROM Layer l WHERE l.project.id = :projectId AND l.deletedAt IS NULL "
			+ "AND l.clipMode IS NOT NULL ORDER BY l.zIndex ASC")
	List<Layer> findClipMasks(@Param("projectId") UUID projectId);
}
