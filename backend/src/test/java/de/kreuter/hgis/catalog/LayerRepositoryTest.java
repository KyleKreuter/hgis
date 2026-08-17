package de.kreuter.hgis.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import de.kreuter.hgis.TestcontainersConfiguration;
import de.kreuter.hgis.common.SqlIdentifier;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * Covers the ordering query behind the layer tree.
 *
 * It exists because the obvious derived-query name would silently break: Spring Data
 * would have to turn the segment "ZIndex" back into the property {@code zIndex}, but
 * the Java Beans rule keeps a name unchanged when its first two characters are
 * uppercase. The generated JPQL said {@code ORDER BY l.ZIndex} and failed at runtime,
 * which is why the repository spells the query out.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class LayerRepositoryTest {

	@Autowired
	private LayerRepository layerRepository;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private jakarta.persistence.EntityManager entityManager;

	@Test
	@DisplayName("returns layers in drawing order")
	void ordersLayersByZIndex() {
		Project project = projectRepository
				.saveAndFlush(new Project("Ordnungstest", null, 25832, "osm"));

		// Inserted out of order on purpose -- the query has to do the sorting.
		layerRepository.save(newLayer(project, "oben", 2));
		layerRepository.save(newLayer(project, "unten", 0));
		layerRepository.save(newLayer(project, "mitte", 1));
		layerRepository.flush();

		List<Layer> ordered = layerRepository.findByProjectOrdered(project.getId());

		assertThat(ordered).extracting(Layer::getName)
				.containsExactly("unten", "mitte", "oben");
	}

	@Test
	@DisplayName("a trashed layer drops out of the ordinary order but shows up in the trash")
	void excludesTrashedLayersFromTheOrdinaryOrderButListsThemInTheTrash() {
		Project project = projectRepository
				.saveAndFlush(new Project("Papierkorbtest", null, 25832, "osm"));

		Layer kept = layerRepository.save(newLayer(project, "bleibt", 0));
		Layer trashed = layerRepository.save(newLayer(project, "geloescht", 1));
		trashed.moveToTrash("tester");
		layerRepository.flush();

		assertThat(layerRepository.findByProjectOrdered(project.getId()))
				.extracting(Layer::getId)
				.containsExactly(kept.getId());

		assertThat(layerRepository.findTrashedByProject(project.getId()))
				.extracting(Layer::getId)
				.containsExactly(trashed.getId());
	}

	@Test
	@DisplayName("bumps the tile cache buster without loading the entity")
	void bumpsDataVersion() {
		Project project = projectRepository
				.saveAndFlush(new Project("Versionstest", null, 25832, "osm"));
		Layer layer = layerRepository.saveAndFlush(newLayer(project, "Gebäude", 0));
		assertThat(layer.getDataVersion()).isEqualTo(1);

		layerRepository.bumpDataVersion(layer.getId());
		layerRepository.flush();
		// The bump ran as bulk SQL and bypassed the persistence context, so the cached
		// instance is stale by design. Clearing forces a real read.
		entityManager.clear();

		assertThat(layerRepository.findById(layer.getId()))
				.get()
				.extracting(Layer::getDataVersion)
				.isEqualTo(2L);
	}

	private static Layer newLayer(Project project, String name, int zIndex) {
		UUID id = UUID.randomUUID();
		Layer layer = new Layer(id, project, name, SqlIdentifier.tableName(id),
				"MULTIPOLYGON", project.getSrid());
		layer.setZIndex(zIndex);
		return layer;
	}
}
