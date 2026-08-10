package de.kreuter.hgis.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LayerRepository extends JpaRepository<Layer, UUID> {

	List<Layer> findByProjectIdOrderByZIndexAscCreatedAtAsc(UUID projectId);

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
}
