package de.kreuter.hgis.catalog;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
	 * Same lookup as {@link #findById}, but with a {@code SELECT ... FOR UPDATE} --
	 * exactly the three trash state transitions ({@code delete}, {@code restore},
	 * {@code purge} in {@code LayerService}) need it, and only them: each reads the
	 * current state, decides whether the requested transition is legal, and writes the
	 * new state, and without a lock spanning that whole sequence two transitions racing
	 * on the same layer can both read the state as legal before either has written
	 * anything. The concrete failure a review turned up: {@code restore} and {@code
	 * purge} run concurrently, both see the layer as trashed, both proceed -- {@code
	 * restore} answers 200, {@code purge} drops the table and answers 204, and the
	 * layer the caller of {@code restore} was just told is back is gone.
	 *
	 * <p>Holding the lock from the read onward serialises the three operations against
	 * each other for one layer: whichever transaction's {@code SELECT ... FOR UPDATE}
	 * has to wait sees the first transaction's committed result once it finally gets
	 * the row, and its own state check then correctly reports a conflict instead of
	 * acting on a state that has since changed underneath it.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT l FROM Layer l WHERE l.id = :id")
	Optional<Layer> findByIdForUpdate(@Param("id") UUID id);

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
